package com.freshcutz.service;

import com.freshcutz.dto.ProductResponse;
import com.freshcutz.entity.SiteCustomization;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * In-memory copies of the things every customer page load asks for: the
 * product list, product photos and the shop logo. (Plus, for the admin
 * Products page: the category list, each category's products and small
 * photo thumbnails.)
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
    /** Admin thumbnails are tiny (a few KB each), so a small budget holds hundreds of them. */
    private static final long THUMB_CACHE_MAX_BYTES = 6L * 1024 * 1024;
    private static final int THUMB_MAX_ENTRY_BYTES = 200 * 1024;
    /** Customer-card photos (about 20-30 KB each): room for several hundred of the most recently viewed ones. */
    private static final long MEDIUM_CACHE_MAX_BYTES = 12L * 1024 * 1024;
    private static final int MEDIUM_MAX_ENTRY_BYTES = 512 * 1024;
    /** Safety cap on cached admin answers (category list + one list per category). */
    private static final int ADMIN_MAX_ENTRIES = 200;

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

    /** A size-limited, least-recently-used set of photos. */
    private static final class ImageLru {
        private final long maxBytes;
        private final int maxEntryBytes;
        private final Map<Long, ProductImage> map = new LinkedHashMap<>(16, 0.75f, true);
        private long bytes; // guarded by this

        ImageLru(long maxBytes, int maxEntryBytes) {
            this.maxBytes = maxBytes;
            this.maxEntryBytes = maxEntryBytes;
        }

        synchronized ProductImage get(Long id) {
            return map.get(id);
        }

        /** Keeps the photo only if nothing changed since {@code seenVersion} was read. */
        synchronized void put(Long id, ProductImage image, AtomicLong version, long seenVersion) {
            if (image == null || image.data() == null || image.data().length > maxEntryBytes) return;
            if (version.get() != seenVersion) return;
            ProductImage old = map.put(id, image);
            if (old != null) bytes -= old.data().length;
            bytes += image.data().length;
            var it = map.entrySet().iterator();
            while (bytes > maxBytes && it.hasNext()) {
                var eldest = it.next();
                bytes -= eldest.getValue().data().length;
                it.remove();
            }
        }

        synchronized void remove(Long id) {
            ProductImage old = map.remove(id);
            if (old != null) bytes -= old.data().length;
        }
    }

    private static final Slot<List<ProductResponse>> PRODUCTS = new Slot<>();
    private static final Slot<SiteCustomization> LOGO = new Slot<>();

    private static final AtomicLong IMAGE_VERSION = new AtomicLong();
    private static final ImageLru IMAGES = new ImageLru(IMAGE_CACHE_MAX_BYTES, IMAGE_MAX_ENTRY_BYTES);
    private static final ImageLru THUMBS = new ImageLru(THUMB_CACHE_MAX_BYTES, THUMB_MAX_ENTRY_BYTES);
    private static final ImageLru MEDIUMS = new ImageLru(MEDIUM_CACHE_MAX_BYTES, MEDIUM_MAX_ENTRY_BYTES);

    private static final AtomicLong ADMIN_VERSION = new AtomicLong();
    private static final Map<String, Entry<Object>> ADMIN = new HashMap<>();

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
        ProductImage hit = IMAGES.get(id);
        if (hit != null) return hit;
        long v = IMAGE_VERSION.get(); // read BEFORE the query
        ProductImage loaded = loader.get();
        IMAGES.put(id, loaded, IMAGE_VERSION, v);
        return loaded;
    }

    /** The full-size photo if it happens to be in memory already — never loads anything. */
    public static ProductImage peekImage(Long id) {
        return IMAGES.get(id);
    }

    /** One small admin thumbnail, or runs {@code loader} on a miss. */
    public static ProductImage thumb(Long id, Supplier<ProductImage> loader) {
        ProductImage hit = THUMBS.get(id);
        if (hit != null) return hit;
        long v = IMAGE_VERSION.get(); // read BEFORE the query
        ProductImage loaded = loader.get();
        THUMBS.put(id, loaded, IMAGE_VERSION, v);
        return loaded;
    }

    /** One customer-card photo (about 480 px), or runs {@code loader} on a miss. */
    public static ProductImage medium(Long id, Supplier<ProductImage> loader) {
        ProductImage hit = MEDIUMS.get(id);
        if (hit != null) return hit;
        long v = IMAGE_VERSION.get(); // read BEFORE the query
        ProductImage loaded = loader.get();
        MEDIUMS.put(id, loaded, IMAGE_VERSION, v);
        return loaded;
    }

    /** An admin Products-page answer (category list or one category's products), or runs {@code loader} when stale.
     * Dropped as soon as any product changes. */
    @SuppressWarnings("unchecked")
    public static <T> T admin(String key, Supplier<T> loader) {
        synchronized (ADMIN) {
            Entry<Object> e = ADMIN.get(key);
            if (e != null && e.version() == ADMIN_VERSION.get()
                    && System.currentTimeMillis() - e.at() < TTL_MS) {
                return (T) e.value();
            }
        }
        long v = ADMIN_VERSION.get(); // read BEFORE the query
        T value = loader.get();
        synchronized (ADMIN) {
            if (ADMIN.size() >= ADMIN_MAX_ENTRIES) ADMIN.clear();
            ADMIN.put(key, new Entry<Object>(value, v, System.currentTimeMillis()));
        }
        return value;
    }

    // ---- invalidation (waits for the edit to commit) --------------------

    /** A product was created, changed or deleted. {@code id} = its photo may have changed too; null = only the list did. */
    public static void productChanged(Long id) {
        afterCompletion(() -> {
            PRODUCTS.bump();
            ADMIN_VERSION.incrementAndGet();
            if (id != null) {
                IMAGE_VERSION.incrementAndGet(); // before the remove, so an in-flight read can't re-add it
                IMAGES.remove(id);
                THUMBS.remove(id);
                MEDIUMS.remove(id);
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
