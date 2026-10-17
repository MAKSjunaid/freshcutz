package com.freshcutz.service;

import com.freshcutz.dto.ProductResponse;
import com.freshcutz.dto.StoreItem;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the customer website reads, in small pieces instead of "everything at once":
 * the category tabs, one page of products (for "All" or for one category), and a few products by id.
 *
 * <p>Everything here is answered from memory. It is built from the product list that StorefrontCache already
 * keeps ({@link ProductService#listForCustomers()}), and is rebuilt (cheaply, no extra query) only when that list
 * is replaced after an admin change. So customers browsing cost the database nothing; the database is read
 * once after an admin change (or when the server wakes up), no matter how many customers are online.
 *
 * <p>No @Transactional on purpose: a request that is answered from memory must not open a database connection.
 */
@Service
public class StorefrontCatalogService {

    public static final int DEFAULT_LIMIT = 12;
    public static final int MAX_LIMIT = 100;
    public static final int MAX_IDS = 100;

    /** A category tab with the number of products it has (hidden products are not counted). */
    public record Tab(String name, int count) {}

    /** {@code items} in shop order; {@code hasMore} = there are more after the last one; {@code version} = the
     * catalog version this page belongs to (a page and the tabs must come from the same version). */
    public record Page(String version, List<StoreItem> items, boolean hasMore) {}

    /** The category tabs with the catalog version and the total number of products. */
    public record Tabs(String version, int total, List<Tab> categories) {}

    /** Every product, with the catalog version. */
    public record All(String version, List<StoreItem> items) {}

    /** Everything the page needs for its first screen, in one answer. */
    public record Start(String version, String defaultCategory, String category, int total,
                        List<Tab> categories, List<StoreItem> items, boolean hasMore) {}

    /** Just the version (what a page is told when it connects: it compares and normally has nothing more to do). */
    public record Version(String version) {}

    /**
     * What changed between two catalog versions, so a page can update itself from the message alone.
     * <ul>
     *   <li>{@code ids} — the products that changed (price, name, photo, stock...) or are new on the shop;</li>
     *   <li>{@code items} — those products in full (when there are only a few);</li>
     *   <li>{@code structural} — products were added / hidden / deleted / moved / changed category, which shifts the
     *       places of the others. Then the message also carries the NEW ORDER of the whole shop in a compact form:
     *       {@code order} (product ids, shop order), {@code names} (the categories, first appearance order, "" =
     *       none) and {@code cats} (for each id in {@code order}, its category's index in {@code names}). A page
     *       works out every place and every tab count from that, without asking for anything;</li>
     *   <li>{@code total}/{@code categories} — only in the rare "history too old" answer, where the page re-reads.</li>
     * </ul>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delta(String version, String prev, boolean structural, List<Long> ids, List<StoreItem> items,
                        List<Long> order, List<String> names, List<Integer> cats,
                        Integer total, List<Tab> categories) {}

    /** A change to a few products carries those products with it (so the page needs no request at all); a bigger
     * change only lists the ids and the page fetches them. */
    private static final int MAX_ITEMS_WITH_CHANGE = 20;

    /** {@code changed} = products present before and after whose shown details differ; {@code added} = products
     * that were not on the shop before. */
    private record Change(String prev, String version, boolean structural, List<Long> changed, List<Long> added) {}

    private static final int KEPT_CHANGES = 40;

    private static final class Built {
        final List<ProductResponse> source; // identity is the "has the list been replaced?" check
        final String version;
        final List<StoreItem> all;
        final Map<String, List<StoreItem>> byCategory;
        final List<Tab> tabs;
        final Map<Long, StoreItem> byId;
        final List<Long> order; // product ids in shop order
        final List<String> names; // categories in first-appearance order ("" = no category)
        final List<Integer> cats; // for each id in order: its category's index in names

        Built(List<ProductResponse> source, String version, List<StoreItem> all,
              Map<String, List<StoreItem>> byCategory, List<Tab> tabs, Map<Long, StoreItem> byId) {
            List<Long> ord = new ArrayList<>(all.size());
            List<Integer> cs = new ArrayList<>(all.size());
            Map<String, Integer> nameIndex = new LinkedHashMap<>();
            for (StoreItem item : all) {
                ord.add(item.getProduct().getId());
                cs.add(nameIndex.computeIfAbsent(cat(item.getProduct()), k -> nameIndex.size()));
            }
            this.order = List.copyOf(ord);
            this.cats = List.copyOf(cs);
            this.names = List.copyOf(nameIndex.keySet());
            this.source = source;
            this.version = version;
            this.all = all;
            this.byCategory = byCategory;
            this.tabs = tabs;
            this.byId = byId;
        }
    }

    private final ProductService productService;
    private final ShopSettingsService shopSettings;
    private final AtomicLong lastVersion = new AtomicLong();
    private volatile Built built;

    private final SseBroadcastService broadcastService;

    public StorefrontCatalogService(ProductService productService, ShopSettingsService shopSettings,
                                    SseBroadcastService broadcastService) {
        this.productService = productService;
        this.shopSettings = shopSettings;
        this.broadcastService = broadcastService;
    }

    /** The live-update stream tells every open shop tab the version when it connects and what changed after each edit. */
    @PostConstruct
    void announceToLiveUpdates() {
        broadcastService.setCatalogProviders(this::version, since -> {
            Delta d = since(since);
            return d.version().equals(since) ? null : new SseBroadcastService.CatalogUpdate(d.version(), d);
        });
    }

    private final Deque<Change> changes = new ArrayDeque<>(); // newest last; guarded by "this"

    // ---- reads --------------------------------------------------------------

    /** Changes whenever the catalog does (also after a server restart), so a browser can ask "still the same?". */
    public String version() {
        return built().version;
    }

    /** The first screen: tabs + the first products of the default tab (or of "All" when none is set / it is empty). */
    public Start start(int limit) {
        Built b = built();
        String def = shopSettings.getDefaultCategory();
        String shown = !def.isEmpty() && b.byCategory.containsKey(def) ? def : "";
        Page first = page(b, shown, -1, limit);
        return new Start(b.version, shown, shown, b.all.size(), b.tabs, first.items(), first.hasMore());
    }

    /** Up to {@code limit} products after the one with rank {@code afterRank} (-1 = from the start).
     * {@code category} blank = "All". A category that does not exist gives an empty page. */
    public Page page(String category, int afterRank, int limit) {
        return page(built(), category, afterRank, limit);
    }

    /** The category tabs (name + product count), in shop order. */
    public Tabs tabs() {
        Built b = built();
        return new Tabs(b.version, b.all.size(), b.tabs);
    }

    /** Every product (used by the search box, the first time it is used). */
    public All all() {
        Built b = built();
        return new All(b.version, b.all);
    }

    /** Everything that changed since the version a page holds (a page that was hidden, offline or missed a message).
     * When that version is too old to know, the answer has no {@code order}: the page re-reads what it shows. */
    public Delta since(String since) {
        Built b = built();
        if (b.version.equals(since)) return new Delta(b.version, since, false, List.of(), null, null, null, null, null, null);
        synchronized (this) {
            boolean structural = false;
            Set<Long> ids = new LinkedHashSet<>();
            String at = null;
            for (Change c : changes) {
                if (at == null) {
                    if (!c.prev().equals(since)) continue;
                } else if (!c.prev().equals(at)) {
                    at = null; // a gap in the history: start looking again
                    structural = false;
                    ids.clear();
                    if (!c.prev().equals(since)) continue;
                }
                at = c.version();
                structural |= c.structural();
                ids.addAll(c.changed());
                ids.addAll(c.added());
            }
            if (at != null && at.equals(b.version)) return delta(b, since, structural, ids);
        }
        // history too old (or from before a restart): the page must re-read what it shows
        return new Delta(b.version, since, true, List.of(), null, null, null, null, b.all.size(), b.tabs);
    }

    private static Delta delta(Built b, String since, boolean structural, Set<Long> ids) {
        List<Long> present = new ArrayList<>();
        for (Long id : ids) if (b.byId.containsKey(id)) present.add(id); // (one that was hidden again is gone)
        List<StoreItem> items = null;
        if (present.size() <= MAX_ITEMS_WITH_CHANGE) {
            items = new ArrayList<>();
            for (Long id : present) items.add(b.byId.get(id));
        }
        return structural
                ? new Delta(b.version, since, true, present, items, b.order, b.names, b.cats, null, null)
                : new Delta(b.version, since, false, present, items, null, null, null, null, null);
    }

    /** These products if they are still on sale; ids that are hidden or gone are simply missing from the answer. */
    public List<StoreItem> byIds(List<Long> ids) {
        Built b = built();
        List<StoreItem> out = new ArrayList<>();
        for (Long id : ids) {
            StoreItem item = b.byId.get(id);
            if (item != null) out.add(item);
        }
        return out;
    }

    // ---- internals ------------------------------------------------------------

    private static Page page(Built b, String category, int afterRank, int limit) {
        int lim = Math.max(1, Math.min(limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT));
        String key = category == null ? "" : category.trim();
        List<StoreItem> source = key.isEmpty() ? b.all : b.byCategory.getOrDefault(key, List.of());
        int from = firstAfter(source, afterRank);
        int to = Math.min(source.size(), from + lim);
        return new Page(b.version, List.copyOf(source.subList(from, to)), to < source.size());
    }

    /** Index of the first item whose rank is bigger than {@code rank} (ranks only ever go up in a list). */
    private static int firstAfter(List<StoreItem> items, int rank) {
        int lo = 0;
        int hi = items.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (items.get(mid).getRank() > rank) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    private Built built() {
        List<ProductResponse> list = productService.listForCustomers(); // memory, unless it was just replaced
        Built b = built;
        if (b != null && b.source == list) return b;
        synchronized (this) {
            b = built;
            if (b != null && b.source == list) return b;
            Built old = b;
            b = build(list);
            if (old != null) {
                changes.addLast(diff(old, b));
                while (changes.size() > KEPT_CHANGES) changes.removeFirst();
            }
            built = b;
            return b;
        }
    }

    /** Works out what is different between two versions of the catalog. */
    private static Change diff(Built old, Built now) {
        boolean structural = old.all.size() != now.all.size();
        for (int i = 0; !structural && i < now.all.size(); i++) {
            ProductResponse a = old.all.get(i).getProduct();
            ProductResponse c = now.all.get(i).getProduct();
            structural = !Objects.equals(a.getId(), c.getId()) || !Objects.equals(cat(a), cat(c));
        }
        List<Long> changed = new ArrayList<>();
        List<Long> added = new ArrayList<>();
        for (StoreItem item : now.all) {
            ProductResponse c = item.getProduct();
            StoreItem before = old.byId.get(c.getId());
            if (before == null) added.add(c.getId());
            else if (!sameShown(before.getProduct(), c) || !Objects.equals(cat(before.getProduct()), cat(c))) changed.add(c.getId());
        }
        if (structural) {
            // products that JUMPED to another place (as opposed to merely shifting by one because another product was
            // hidden / added) ride along too, so a page can fill the new place without asking
            for (Long id : jumped(old, now)) if (!changed.contains(id) && !added.contains(id)) changed.add(id);
        }
        return new Change(old.version, now.version, structural, List.copyOf(changed), List.copyOf(added));
    }

    /** The products that moved relative to the others: everything outside the longest run of products that kept
     * their relative order. (Hiding one product shifts the rest but moves none of them; moving one product to another
     * place gives exactly that product.) */
    private static List<Long> jumped(Built old, Built now) {
        Map<Long, Integer> oldIndex = new HashMap<>();
        for (int i = 0; i < old.order.size(); i++) oldIndex.put(old.order.get(i), i);
        List<Long> ids = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        for (Long id : now.order) {
            Integer before = oldIndex.get(id);
            if (before != null) {
                ids.add(id);
                indexes.add(before);
            }
        }
        int n = indexes.size();
        int[] tails = new int[n]; // tails[k] = smallest old index ending a run of length k + 1
        int[] tailAt = new int[n]; // where in the new order that tail is
        int[] previous = new int[n];
        int length = 0;
        for (int i = 0; i < n; i++) {
            int x = indexes.get(i);
            int lo = 0;
            int hi = length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (tails[mid] < x) lo = mid + 1;
                else hi = mid;
            }
            tails[lo] = x;
            tailAt[lo] = i;
            previous[i] = lo > 0 ? tailAt[lo - 1] : -1;
            if (lo == length) length++;
        }
        boolean[] keeps = new boolean[n];
        for (int k = length > 0 ? tailAt[length - 1] : -1; k >= 0; k = previous[k]) keeps[k] = true;
        List<Long> moved = new ArrayList<>();
        for (int i = 0; i < n; i++) if (!keeps[i]) moved.add(ids.get(i));
        return moved;
    }

    private static String cat(ProductResponse p) {
        return p.getCategory() == null ? "" : p.getCategory().trim();
    }

    /** Is everything the customer sees about this product the same? */
    private static boolean sameShown(ProductResponse a, ProductResponse b) {
        return Objects.equals(a.getName(), b.getName())
                && Objects.equals(a.getDescription(), b.getDescription())
                && Objects.equals(a.getImageUrl(), b.getImageUrl())
                && Objects.equals(a.getVariant1Label(), b.getVariant1Label())
                && sameMoney(a.getVariant1Price(), b.getVariant1Price())
                && sameMoney(a.getVariant1Mrp(), b.getVariant1Mrp())
                && Objects.equals(a.getVariant2Label(), b.getVariant2Label())
                && sameMoney(a.getVariant2Price(), b.getVariant2Price())
                && sameMoney(a.getVariant2Mrp(), b.getVariant2Mrp())
                && a.isEnabled() == b.isEnabled()
                && a.isOutOfStock() == b.isOutOfStock();
    }

    private static boolean sameMoney(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? a == b : a.compareTo(b) == 0;
    }

    private Built build(List<ProductResponse> source) {
        List<StoreItem> all = new ArrayList<>(source.size());
        Map<String, List<StoreItem>> byCategory = new LinkedHashMap<>(); // first appearance = shop order
        Map<Long, StoreItem> byId = new HashMap<>();
        for (int i = 0; i < source.size(); i++) {
            ProductResponse p = source.get(i);
            String cat = p.getCategory() == null ? "" : p.getCategory().trim();
            List<StoreItem> inCat = byCategory.computeIfAbsent(cat, k -> new ArrayList<>());
            StoreItem item = new StoreItem(p, i, inCat.size());
            inCat.add(item);
            all.add(item);
            byId.put(p.getId(), item);
        }
        List<Tab> tabs = new ArrayList<>();
        byCategory.forEach((name, items) -> {
            if (!name.isEmpty()) tabs.add(new Tab(name, items.size()));
        });
        long now = System.currentTimeMillis();
        long v = lastVersion.updateAndGet(prev -> Math.max(prev + 1, now));
        return new Built(source, Long.toString(v, 36), List.copyOf(all), byCategory, List.copyOf(tabs), byId);
    }
}
