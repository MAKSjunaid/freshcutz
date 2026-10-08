package com.freshcutz.repository;

/** SQL fragments shared by every native query that lists orders of a mix
 * of statuses for the Sales tab (the "All / New / Delivered / Cancelled"
 * filter — see SalesStatusFilter). They are compile-time constants so
 * they can be concatenated straight into @Query annotations.
 *
 * PREDICATE: an order is in the window when, for each status the caller
 * switched on, that status's own date falls in [:from, :to) — New by
 * created_at, Delivered by delivered_at, Cancelled by status_updated_at
 * (when it was cancelled). Written as three separate AND-ed branches
 * rather than one CASE expression so each branch can use its own index
 * (idx_orders_status_created_at, idx_orders_status_delivered_at,
 * idx_orders_status_status_updated_at). Needs the :incNew,
 * :incDelivered, :incCancelled, :from and :to parameters, and the orders
 * table aliased as o.
 *
 * PREDICATE_ALL: the same three branches with every status switched on
 * and no flag parameters — for the overview query, which needs every
 * status's numbers for the period at once (header boxes) and picks the
 * included ones afterwards. Needs only :from and :to.
 *
 * Why the three statuses use three different dates: Delivered orders
 * count on the day they were delivered so the boxes agree with the
 * revenue figures (which are keyed by delivery date); Cancelled by when
 * they were cancelled; but New by created_at, NOT status_updated_at,
 * because OrderService.reopen sets status back to NEW and bumps
 * status_updated_at to now on an order that may have been placed days
 * ago — keyed by status_updated_at, every order reopened today would
 * count as "New today". created_at never changes.
 *
 * EFFECTIVE_AT: the same per-status date as a single expression, for
 * grouping by day and sorting. */
final class OrderMixSql {

    private OrderMixSql() {}

    static final String PREDICATE =
            "( (CAST(:incNew AS boolean) AND o.status = 'NEW' " +
            "     AND o.created_at >= CAST(:from AS timestamptz) AND o.created_at < CAST(:to AS timestamptz)) " +
            "  OR (CAST(:incDelivered AS boolean) AND o.status = 'DELIVERED' " +
            "     AND o.delivered_at >= CAST(:from AS timestamptz) AND o.delivered_at < CAST(:to AS timestamptz)) " +
            "  OR (CAST(:incCancelled AS boolean) AND o.status = 'CANCELLED' " +
            "     AND o.status_updated_at >= CAST(:from AS timestamptz) AND o.status_updated_at < CAST(:to AS timestamptz)) ) ";

    static final String PREDICATE_ALL =
            "( (o.status = 'NEW' " +
            "     AND o.created_at >= CAST(:from AS timestamptz) AND o.created_at < CAST(:to AS timestamptz)) " +
            "  OR (o.status = 'DELIVERED' " +
            "     AND o.delivered_at >= CAST(:from AS timestamptz) AND o.delivered_at < CAST(:to AS timestamptz)) " +
            "  OR (o.status = 'CANCELLED' " +
            "     AND o.status_updated_at >= CAST(:from AS timestamptz) AND o.status_updated_at < CAST(:to AS timestamptz)) ) ";

    static final String EFFECTIVE_AT =
            "CASE o.status WHEN 'NEW' THEN o.created_at WHEN 'DELIVERED' THEN o.delivered_at ELSE o.status_updated_at END";
}
