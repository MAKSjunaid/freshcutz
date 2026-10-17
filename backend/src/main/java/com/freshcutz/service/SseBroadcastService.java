package com.freshcutz.service;

import com.freshcutz.dto.ProductResponse;
import com.freshcutz.dto.SiteResponse;
import com.freshcutz.dto.StoreStatusResponse;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Pushes product-catalog and store-status changes to subscribed storefront
 * tabs over Server-Sent Events, replacing what used to be a 15-second
 * setInterval poll of /api/products and /api/store/status from every open
 * tab. A tab now opens one long-lived connection (ProductController's
 * /api/products/stream, StoreStatusController's /api/store/status/stream)
 * and simply waits — the backend only does any work, and only touches the
 * database, when an admin actually changes a product or the store's
 * online/offline status, via broadcastProducts()/broadcastStatus() below
 * (called from AdminProductController / AdminStoreController right after
 * a change is saved).
 */
@Service
public class SseBroadcastService {

    // A bounded lifetime per connection rather than none at all: if
    // Render's own proxy (or anything else in between) ever silently
    // drops a very long-lived idle connection, capping it here means the
    // browser's EventSource just reconnects on schedule instead of the
    // tab quietly going stale until the page is reloaded.
    private static final long EMITTER_TIMEOUT_MS = 25 * 60 * 1000L; // 25 minutes

    private final List<SseEmitter> productEmitters = new CopyOnWriteArrayList<>();
    private final List<SseEmitter> statusEmitters = new CopyOnWriteArrayList<>();
    private final List<SseEmitter> siteEmitters = new CopyOnWriteArrayList<>();
    /** Storefront tabs that only want to hear "the catalog changed" (no data is pushed; they ask for what they show). */
    private final List<SseEmitter> catalogEmitters = new CopyOnWriteArrayList<>();
    /** What a tab is told after an edit: the catalog version it brings the tab up to, and the message itself. */
    public record CatalogUpdate(String version, Object payload) {}

    /** The catalog version each connected tab was last told about, so every tab gets exactly "what changed since the
     * version YOU have" (even when the catalog was rebuilt a few times in between). */
    private final Map<SseEmitter, String> catalogKnown = new ConcurrentHashMap<>();
    private volatile Supplier<String> catalogVersion;
    private volatile Function<String, CatalogUpdate> catalogUpdate;

    /** Set by StorefrontCatalogService: the current version, and "what changed since version X" (null = nothing). */
    public void setCatalogProviders(Supplier<String> version, Function<String, CatalogUpdate> update) {
        this.catalogVersion = version;
        this.catalogUpdate = update;
    }

    /** Registers a new subscriber and immediately sends it the current
     * catalog, so a tab that just connected does not have to wait for the
     * next admin edit to see anything. */
    public SseEmitter subscribeProducts(List<ProductResponse> initial) {
        SseEmitter emitter = register(productEmitters);
        send(emitter, "products", initial, productEmitters);
        return emitter;
    }

    /** A customer tab that loads its products in small pieces. It is told the catalog's version when it connects (so
     * it never has to ask "did anything change?") and, after every edit, exactly WHAT changed (a few ids — a
     * handful of bytes, the same for everybody). It then fetches only those products, and only if it holds them.
     *
     * <p>{@code since} = the catalog version the tab already holds (a tab coming back after being hidden, or
     * reconnecting). It is brought up to date right here on the stream — it is sent just what changed since that
     * version — so it needs no separate request. */
    public SseEmitter subscribeCatalogChanges(String since) {
        SseEmitter emitter = register(catalogEmitters);
        String version = null;
        try {
            Supplier<String> provider = catalogVersion;
            if (provider != null) version = provider.get();
        } catch (Exception ignored) {
            // the tab then checks the version itself
        }
        if (version != null) {
            boolean behind = since != null && !since.isBlank() && !since.equals(version);
            catalogKnown.put(emitter, behind ? since : version);
            if (!behind) send(emitter, "state", Map.of("version", version), catalogEmitters);
            // behind (or an edit happened a moment ago): send what changed since the version the tab holds
            notifyCatalogChanged();
        } else {
            sendComment(emitter, catalogEmitters); // opens the stream right away
        }
        return emitter;
    }

    /** Same idea as subscribeProducts(), for the store's online/offline
     * status. */
    public SseEmitter subscribeStatus(StoreStatusResponse initial) {
        SseEmitter emitter = register(statusEmitters);
        send(emitter, "status", initial, statusEmitters);
        return emitter;
    }

    /** Same idea again, for the customer website's look (the admin's
     * Customize tab): a tab gets the current settings on connect and a
     * fresh copy whenever an admin saves a change. */
    public SseEmitter subscribeSite(SiteResponse initial) {
        SseEmitter emitter = register(siteEmitters);
        send(emitter, "site", initial, siteEmitters);
        return emitter;
    }

    public void broadcastSite(SiteResponse site) {
        for (SseEmitter emitter : siteEmitters) {
            send(emitter, "site", site, siteEmitters);
        }
    }

    /** Same as broadcastProducts(), but the catalog is only read (from the database) when at least one storefront
     * tab is actually listening — with nobody connected, an admin edit costs no extra query. */
    public void broadcastProductsIfAny(Supplier<List<ProductResponse>> catalog) {
        notifyCatalogChanged(); // tabs that load in pieces just hear "changed" (a few bytes, no catalog read)
        if (productEmitters.isEmpty()) return; // older open pages still get the whole catalog pushed
        broadcastProducts(catalog.get());
    }

    /** Tells every tab that loads its products in small pieces that the catalog changed. */
    public synchronized void notifyCatalogChanged() { // one at a time, so a tab's messages never overlap or cross
        Function<String, CatalogUpdate> provider = catalogUpdate;
        if (catalogEmitters.isEmpty() || provider == null) return; // nobody listening: nothing to work out
        Map<String, CatalogUpdate> bySince = new HashMap<>(); // tabs on the same version share one message
        for (SseEmitter emitter : catalogEmitters) {
            String since = catalogKnown.get(emitter);
            if (since == null) continue;
            CatalogUpdate update;
            try {
                if (!bySince.containsKey(since)) bySince.put(since, provider.apply(since));
                update = bySince.get(since);
            } catch (Exception e) {
                continue; // that tab will ask for itself when it next notices
            }
            if (update == null) continue; // this tab is already up to date
            catalogKnown.put(emitter, update.version());
            send(emitter, "changed", update.payload(), catalogEmitters);
        }
    }

    public void broadcastProducts(List<ProductResponse> products) {
        for (SseEmitter emitter : productEmitters) {
            send(emitter, "products", products, productEmitters);
        }
    }

    public void broadcastStatus(StoreStatusResponse status) {
        for (SseEmitter emitter : statusEmitters) {
            send(emitter, "status", status, statusEmitters);
        }
    }

    /** Keeps intermediate proxies (Render's included) from treating a
     * quiet-but-healthy connection as idle and closing it, and doubles as
     * a cheap way to notice and drop connections the browser side has
     * already gone away on (a failed send here removes the emitter). */
    @Scheduled(fixedRate = 20_000)
    public void heartbeat() {
        for (SseEmitter emitter : productEmitters) {
            sendComment(emitter, productEmitters);
        }
        for (SseEmitter emitter : statusEmitters) {
            sendComment(emitter, statusEmitters);
        }
        for (SseEmitter emitter : siteEmitters) {
            sendComment(emitter, siteEmitters);
        }
        for (SseEmitter emitter : catalogEmitters) {
            sendComment(emitter, catalogEmitters);
        }
    }

    private SseEmitter register(List<SseEmitter> emitters) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitter.onCompletion(() -> {
            emitters.remove(emitter);
            catalogKnown.remove(emitter);
        });
        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> {
            emitters.remove(emitter);
            catalogKnown.remove(emitter);
        });
        emitters.add(emitter);
        return emitter;
    }

    private void send(SseEmitter emitter, String eventName, Object data, List<SseEmitter> emitters) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception e) {
            // The tab navigated away / lost its connection between us
            // iterating the list and writing to it — just stop tracking
            // it. Deliberately NOT calling emitter.complete() here: by
            // the time send() throws, the servlet container may already
            // have run its own AsyncListener.onError() for this exact
            // connection on ITS OWN thread (that's what onError() below,
            // in register(), is for) — calling complete() again from
            // here, a plain application thread, after that has already
            // happened is precisely what Tomcat's "a non-container
            // thread attempted to use the AsyncContext after an error"
            // guard exists to stop, and it threw straight out of this
            // method, which meant one dead connection could kill the
            // broadcast loop for every tab after it in the list. A
            // broad `catch (Exception e)` here (rather than just
            // IOException/IllegalStateException) is deliberate too, so
            // that whatever a given container throws for "this
            // connection is gone" can never again take the whole
            // broadcast down.
            emitters.remove(emitter);
            catalogKnown.remove(emitter);
        }
    }

    private void sendComment(SseEmitter emitter, List<SseEmitter> emitters) {
        try {
            emitter.send(SseEmitter.event().comment("ping"));
        } catch (Exception e) {
            emitters.remove(emitter);
        }
    }
}
