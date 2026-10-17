package com.freshcutz.service;

import com.freshcutz.dto.ProductListRow;
import com.freshcutz.exception.BadRequestException;
import com.freshcutz.repository.ProductRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The admin Products tab's "Bulk actions": the Excel template, the Excel export of the catalog, and
 * changing many products at once (show / hide, out of stock / back in stock, category, delete).
 *
 * <p>Kept light on purpose:
 * <ul>
 *   <li>Plain SQL, a handful of statements for ANY number of products (ids go in chunks of 500) —
 *       never one load-and-save per product, and the photo bytes are never read.</li>
 *   <li>Only rows that would really change are touched (hiding an already hidden product writes nothing).</li>
 *   <li>{@code updated_at} is left alone: it is the photo's cache-buster in the shop's image links, so
 *       bumping it for 150 products would make every customer download every photo again.</li>
 *   <li>One transaction; the shop's in-memory product cache is told once, after the commit.</li>
 * </ul>
 */
@Service
public class ProductBulkService {

    static final int MAX_IDS = 2000;
    private static final int CHUNK = 500;

    private final JdbcTemplate jdbc;
    private final ProductRepository productRepository;

    public ProductBulkService(JdbcTemplate jdbc, ProductRepository productRepository) {
        this.jdbc = jdbc;
        this.productRepository = productRepository;
    }

    public enum Action { ENABLE, DISABLE, OUT_OF_STOCK, IN_STOCK, CATEGORY, DELETE }

    /** Runs one bulk action. Returns {action, requested, changed}. */
    @Transactional
    public Map<String, Object> apply(String actionName, List<Long> rawIds, String value) {
        Action action;
        try {
            action = Action.valueOf(actionName == null ? "" : actionName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown bulk action.");
        }
        if (rawIds == null || rawIds.isEmpty()) throw new BadRequestException("Select at least one product first.");
        Set<Long> unique = new LinkedHashSet<>();
        for (Long id : rawIds) if (id != null) unique.add(id);
        if (unique.isEmpty()) throw new BadRequestException("Select at least one product first.");
        if (unique.size() > MAX_IDS) throw new BadRequestException("Too many products at once (limit " + MAX_IDS + ").");
        List<Long> ids = new ArrayList<>(unique);

        int changed = 0;
        switch (action) {
            case ENABLE -> changed = flag("enabled", true, ids);
            case DISABLE -> changed = flag("enabled", false, ids);
            case OUT_OF_STOCK -> changed = flag("out_of_stock", true, ids);
            case IN_STOCK -> changed = flag("out_of_stock", false, ids);
            case CATEGORY -> changed = category(ids, value);
            case DELETE -> changed = delete(ids);
        }

        if (changed > 0 && action != Action.DELETE) {
            StorefrontCache.productChanged(null); // product list changed; photos did not
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", action.name());
        out.put("requested", ids.size());
        out.put("changed", changed);
        return out;
    }

    // ---- the actions ---------------------------------------------------

    /** Sets a true/false column; only rows not already in that state are written. Column name is fixed in code. */
    private int flag(String column, boolean on, List<Long> ids) {
        int n = 0;
        for (List<Long> part : chunks(ids)) {
            n += jdbc.update("UPDATE products SET " + column + " = ? WHERE id IN (" + marks(part.size()) + ") AND " + column + " <> ?",
                    args(on, part, on));
        }
        return n;
    }

    private int category(List<Long> ids, String value) {
        String c = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (c.isEmpty()) throw new BadRequestException("Type the category name.");
        if (c.length() > 60) throw new BadRequestException("The category name can be at most 60 characters.");
        int n = 0;
        for (List<Long> part : chunks(ids)) {
            n += jdbc.update("UPDATE products SET category = ? WHERE id IN (" + marks(part.size()) + ") AND (category IS NULL OR category <> ?)",
                    args(c, part, c));
        }
        return n;
    }

    private int delete(List<Long> ids) {
        int n = 0;
        for (List<Long> part : chunks(ids)) {
            n += jdbc.update("DELETE FROM products WHERE id IN (" + marks(part.size()) + ")", part.toArray());
        }
        // the deleted products' photos must also leave the shop's photo memory
        for (Long id : ids) StorefrontCache.productChanged(id);
        return n;
    }

    // ---- Excel files ---------------------------------------------------

    static final List<String> SHEET_HEADERS = List.of("Product Name", "Description", "Category", "Size 1", "Price 1 (₹)",
            "MRP 1 (₹)", "Size 2", "Price 2 (₹)", "MRP 2 (₹)", "Show on shop");

    /** The empty sheet for "Import products": the right column names and one sample row (not imported). */
    public byte[] templateXlsx() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(8 * 1024);
            try (MiniXlsxWriter xlsx = new MiniXlsxWriter(bytes)) {
                MiniXlsxWriter.Sheet s = xlsx.startSheet("Products", SHEET_HEADERS, false);
                s.row("EXAMPLE - Chicken Curry Cut", "Fresh skinless curry cut, cleaned and ready to cook.", "Chicken Cuts",
                        "500 g", 150, 165, "1 kg", 290, 320, "Yes");
                s.end();
                MiniXlsxWriter.Sheet r = xlsx.startSheet("Read me", List.of("Column", "What to put there"), false);
                r.row("Product Name", "Required. Up to 120 characters. Products whose name is already in your shop are skipped.");
                r.row("Description", "Optional. Up to 500 characters.");
                r.row("Category", "Optional but recommended, for example Chicken Cuts. Up to 60 characters.");
                r.row("Size 1 / Price 1 / MRP 1", "Required: the first size (for example 500 g), its selling price and (optional) the crossed-out MRP.");
                r.row("Size 2 / Price 2 / MRP 2", "Optional second size (for example 1 kg). Leave all three empty if there is only one size.");
                r.row("Show on shop", "Yes or No. Empty = Yes (the Import screen's tick box can still hide everything).");
                r.row("The EXAMPLE row", "The row starting with EXAMPLE is only a sample and is never imported. Replace it or delete it.");
                r.row("Photos", "Photos are added afterwards with Bulk actions > Add photos. Name each photo like the product, e.g. chicken-curry-cut.jpg.");
                r.end();
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the template", e);
        }
    }

    /** Every product in the same column layout as the template, plus stock and photo status. Never reads photo bytes. */
    @Transactional(readOnly = true)
    public byte[] exportXlsx() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
            List<String> headers = new ArrayList<>(SHEET_HEADERS);
            headers.add("Out of stock");
            headers.add("Has photo");
            try (MiniXlsxWriter xlsx = new MiniXlsxWriter(bytes)) {
                MiniXlsxWriter.Sheet s = xlsx.startSheet("Products", headers, false);
                for (ProductListRow p : productRepository.listViewForAdmin()) {
                    s.row(p.getName(), p.getDescription(), p.getCategory(),
                            p.getVariant1Label(), p.getVariant1Price(), p.getVariant1Mrp(),
                            p.getVariant2Label(), p.getVariant2Price(), p.getVariant2Mrp(),
                            p.isEnabled(), p.isOutOfStock(), hasPhoto(p));
                }
                s.end();
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the export", e);
        }
    }

    private static boolean hasPhoto(ProductListRow p) {
        return p.isHasImage() || (p.getImageUrl() != null && !p.getImageUrl().isBlank());
    }

    // ---- small helpers -------------------------------------------------

    private static List<List<Long>> chunks(List<Long> ids) {
        List<List<Long>> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += CHUNK) out.add(ids.subList(i, Math.min(ids.size(), i + CHUNK)));
        return out;
    }

    private static String marks(int n) {
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "?" : ",?");
        return sb.toString();
    }

    /** value first, then the ids, then an optional trailing value (for the "<> ?" part). */
    private static Object[] args(Object first, List<Long> ids, Object last) {
        Object[] a = new Object[ids.size() + 2];
        a[0] = first;
        for (int i = 0; i < ids.size(); i++) a[i + 1] = ids.get(i);
        a[a.length - 1] = last;
        return a;
    }
}
