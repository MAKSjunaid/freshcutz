package com.freshcutz.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** A customer's current profile — name + delivery address as of their most
 * recent update, keyed by phone. Deliberately separate from Order: an
 * Order is a historical snapshot that should never change once placed
 * (see OrderService.placeOrder's own javadoc), while this is the one
 * record "My Account" reads and writes. Kept in sync two ways: upserted
 * from the order's details every time an order is placed
 * (CustomerService.upsertFromOrder, called by OrderService), and upserted
 * directly when the customer edits their address from the profile screen
 * (CustomerService.updateAddress) — either way, past orders are never
 * touched. */
@Entity
@Table(name = "customers", indexes = {
        // Backs the admin Customers tab's main list/search query
        // (CustomerRepository.findCustomerSummariesPage), which sorts by
        // order_count DESC, last_order_at DESC, phone DESC by default —
        // without this, that sort (and its keyset cursor's "< this value"
        // comparisons) falls back to scanning and sorting the whole
        // customers table on every page, same reasoning as the Orders
        // tab's own indexes.
        @Index(name = "idx_customers_order_count_last_order_at", columnList = "order_count, last_order_at"),
        // Backs the Sales tab's "By Customer" ranking for the All-time
        // period (CustomerRepository.findTopCustomersAllTime), which sorts
        // by total_spent DESC, phone DESC straight off this table — same
        // reasoning as the index above, for the other sort order.
        @Index(name = "idx_customers_total_spent_phone", columnList = "total_spent, phone")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer {

    @Id
    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "flat_no", nullable = false, length = 60)
    private String flatNo;

    @Column(name = "address_line", nullable = false, length = 300)
    private String addressLine;

    @Column(name = "landmark", length = 150)
    private String landmark;

    // Running aggregates kept up to date incrementally — at order
    // placement (orderCount, lastOrderAt), delivery (totalSpent added),
    // and reopen-from-Delivered (totalSpent subtracted back out) — see
    // CustomerRepository's upsertOnOrderPlaced/addToTotalSpent/
    // subtractFromTotalSpent and CustomerService's own methods that call
    // them. This is what lets the admin Customers tab's list query be a
    // plain indexed SELECT straight off this table instead of
    // recomputing these three numbers from the entire orders table on
    // every single page load and search keystroke — see
    // CustomerRepository.findCustomerSummariesPage's own comment for the
    // full before/after story. Nullable here (unlike the fields above)
    // because every pre-existing row gets these added as NULL by
    // Hibernate's schema update — CustomerStatsBackfillRunner fills them
    // in once at startup, after which they're never null for a row this
    // app itself writes.
    @Column(name = "order_count")
    private Integer orderCount;

    @Column(name = "total_spent", precision = 12, scale = 2)
    private BigDecimal totalSpent;

    @Column(name = "last_order_at")
    private Instant lastOrderAt;

    // How many of this customer's orders are currently Delivered — the
    // "orders" figure beside totalSpent on the Sales tab's By Customer
    // view for the All-time period, so that view can report delivered
    // orders (matching what totalSpent actually sums) straight off this
    // row instead of counting orders at read time. Kept current at the
    // same two moments totalSpent is: +1 on delivery, -1 on reopen from
    // Delivered (see CustomerRepository.addToTotalSpent/
    // subtractFromTotalSpent), and recomputed by backfillCustomerStats.
    @Column(name = "delivered_count")
    private Integer deliveredCount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void onSave() {
        updatedAt = Instant.now();
    }
}
