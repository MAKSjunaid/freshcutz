package com.freshcutz.service;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Remembers the "new orders" number so the admin panel's 15-second poll does
 * not have to hit the database every time.
 *
 * Why: Neon's free plan bills by the hours its database is awake, and any
 * query keeps it awake for 5 more minutes. A poll every 15 s would keep it
 * awake all day. The number only changes when an order is created or its
 * status changes, so we keep it in memory and throw it away the moment an
 * order changes (see OrderCountListener). The admin still sees changes
 * within one poll, and no feature is removed.
 *
 * Safe against races: the version is read BEFORE the query runs. If an order
 * changes while the query is running, the stored entry carries the old
 * version and is treated as stale on the next read.
 * The 30-minute expiry is only a safety net (e.g. someone edits the table by
 * hand in the Neon console).
 */
public final class NewOrdersCountCache {

    private static final long TTL_MS = 30L * 60 * 1000;

    private static final AtomicLong VERSION = new AtomicLong();
    private static volatile Entry entry;

    private record Entry(long count, long version, long at) {}

    private NewOrdersCountCache() {}

    /** Cached count, or runs {@code loader} (one COUNT query) when stale. */
    public static long get(LongSupplier loader) {
        long v = VERSION.get();
        long now = System.currentTimeMillis();
        Entry e = entry;
        if (e != null && e.version == v && now - e.at < TTL_MS) {
            return e.count;
        }
        long count = loader.getAsLong();
        entry = new Entry(count, v, now);
        return count;
    }

    /** Mark the cached number stale. If a transaction is open, wait until it
     * has committed so a reader can't cache the pre-commit value. */
    public static void invalidateAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    VERSION.incrementAndGet();
                }
            });
        } else {
            VERSION.incrementAndGet();
        }
    }
}
