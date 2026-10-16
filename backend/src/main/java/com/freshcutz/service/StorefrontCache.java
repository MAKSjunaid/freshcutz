package com.freshcutz.service;

import com.freshcutz.dto.ProductResponse;
import com.freshcutz.entity.SiteCustomization;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * In-memory copies of the things every customer page load asks for: the
 * product list, product photos and the shop logo.
 *
 * Why: Neon's free plan bills by the hours its database is awake, and any
 * query keeps it awake for 5 more minutes. These values only change when an
 * admin edits something, so we keep them in memory and throw the copy away
 * the moment that edit is saved (see ProductCacheListener and
 * SiteCustomizationService). Customers browsing then cost no database time.
 *
 * Safe against races: each copy remembers a version number read BEFORE the
 * database query ran. If an admin edit commits while a query is running, the
 * copy carries the old version and is treated as stale on the next read, so
 * an old value can never be kept after a newer one was saved.
 * Invalidation always waits for the edit to COMMIT, so nobody can cache a
 * half-saved value.
 *
 * The long expiry is only a safety net (e.g. someone edits a table by hand
 * in the Neon console). Assumes one backend instance, which is what Render's
 * free plan runs.
 */
public final class StorefrontCache {

    private static final long TTL_MS = 6L * 60 * 60 * 1000; // 6 hours
    /** Total size of cached photos. Render's free plan has little memory (the JVM
     * gets roughly a quarter of 512 MB), so keep this small. Least-recently
     * viewed photos are dropped first. */
    private static final long IMAGE_CACHE_MAX_BYTES = 16L * 1024 * 1024;
    /** A single photo bigger than this is served but never kept. */
    private static final int IMAGE_MAX_ENTRY_BYTES = 1024 * 1024;

    /** A product photo as stored in the database. */
    public record ProductImage(byte[] data, String contentType) {}

    private record Entry<T>(T value, long version, long at) {}

    /** One cached value + its version. Only one thread reloads at a time, so a
     * burst of customers right after an edit costs a single query, not many. */
    private static final class Slot<T> {
        private final AtomicLong version = new AtomicLong();
        private volatile Entry<T> entry;

        T get(Supplier<T> loader) {
            Entry<T> e = fresh();
            if (e != null) return e.value();
            synchronized (this) {
                e = fresh();
                if (e != null) return e.value();
                long v = version.get(); // read BEFORE the query
                T value = loader.get();
                entry = new Entry<>(value, v, System.currentTimeMillis());
                return value;
            }
        }

        private Entry<T> fresh() {
            Entry<T> e = entry;
            if (e != null && e.version() == version.get()
                    && System.currentTimeMillis() - e.at() < TTL_MS) {
                return e;
            }
            return null;
        }

        void bump() {
            version.incrementAndGet();
        }
    }

    private static final Slot<List<ProductResponse>> PRODUCTS = new Slot<>();
    private static final Slot<SiteCustomization> LOGO = new Slot<>();

    private static final AtomicLong IMAGE_VERSION = new AtomicLong();
    private static final Map<Long, ProductImage> IMAGES = new LinkedHashMap<>(16, 0.75f, true); // LRU
    private static long imageBytes; // guarded by IMAGES

    private StorefrontCache() {}

    // ---- reads ---------------------------------------------------------

    /** The customer-facing product list, or runs {@code loader} when stale. */
    public static List<ProductResponse> products(Supplier<List<ProductResponse>> loader) {
        return PRODUCTS.get(() -> List.copyOf(loader.get()));
    }

    /** The shop-logo row (may be null when no logo is set), or runs {@code loader} when stale. */
    public static SiteCustomization logo(Supplier<SiteCustomization> loader) {
        return LOGO.get(loader);
    }

    /** One product photo, or runs {@code loader} on a miss. A photo is only kept
     * if nothing changed while it was being read. */
    public static ProductImage image(Long id, Supplier<ProductImage> loader) {
        synchronized (IMAGES) {
            ProductImage hit = IMAGES.get(id);
            if (hit != null) return hit;
        }
        long v = IMAGE_VERSION.get(); // read BEFORE the query
        ProductImage loaded = loader.get();
        if (loaded != null && loaded.data() != null && loaded.data().length <= IMAGE_MAX_ENTRY_BYTES) {
            synchronized (IMAGES) {
                if (IMAGE_VERSION.get() == v) {
                    ProductImage old = IMAGES.put(id, loaded);
                    if (old != null) imageBytes -= old.data().length;
                    imageBytes += loaded.data().length;
                    var it = IMAGES.entrySet().iterator();
                    while (imageBytes > IMAGE_CACHE_MAX_BYTES && it.hasNext()) {
                        var eldest = it.next();
                        imageBytes -= eldest.getValue().data().length;
                        it.remove();
                    }
                }
            }
        }
        return loaded;
    }

    // ---- invalidation (waits for the edit to commit) --------------------

    /** A product was created, changed or deleted. */
    public static void productChanged(Long id) {
        afterCompletion(() -> {
            PRODUCTS.bump();
            IMAGE_VERSION.incrementAndGet(); // before the remove, so an in-flight read can't re-add it
            if (id != null) {
                synchronized (IMAGES) {
                    ProductImage old = IMAGES.remove(id);
                    if (old != null) imageBytes -= old.data().length;
                }
            }
        });
    }

    /** The logo or the site settings row was saved. */
    public static void logoChanged() {
        afterCompletion(LOGO::bump);
    }

    private static void afterCompletion(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
