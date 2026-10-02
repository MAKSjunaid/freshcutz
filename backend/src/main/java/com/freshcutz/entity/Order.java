package com.freshcutz.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A customer order placed from the storefront. Customer details and the
 * items are snapshotted at order time so the order stays correct even if
 * the customer's cart or the product catalog changes later.
 */
@Entity
@Table(name = "orders", indexes = {
        @Index(name = "idx_orders_status_delivered_at", columnList = "status, delivered_at"),
        // Backs every Orders-tab list query added by this session's
        // pagination/infinite-scroll work: all of them filter by status
        // (New/Delivered/Cancelled) and sort by createdAt DESC, or just
        // sort by createdAt DESC with no filter (the All tab). Without
        // these, every one of those queries — at every page, not just
        // deep ones — falls back to a full sequential scan + in-memory
        // sort of the whole orders table on every single request, which
        // only gets worse as the table grows. A standard ascending btree
        // index like this is still used efficiently for DESC ordering
        // (Postgres can scan it backward), so there's no need for a
        // separate descending-specific index.
        @Index(name = "idx_orders_status_created_at", columnList = "status, created_at"),
        @Index(name = "idx_orders_created_at", columnList = "created_at"),
        // Backs the Delivered/Cancelled/All tabs' sort order, which keys
        // off statusUpdatedAt (when the order last changed status) rather
        // than createdAt (when it was first placed) — see statusUpdatedAt's
        // own comment below for why. Same shape as idx_orders_status_created_at
        // above, just for the new column.
        @Index(name = "idx_orders_status_status_updated_at", columnList = "status, status_updated_at"),
        // Backs the admin Customers tab's backfill/recompute query
        // (CustomerRepository.backfillCustomerStats), which groups every
        // order by phone and also picks each phone's single latest order
        // (DISTINCT ON) — without this, both of those fall back to a full
        // sequential scan of the whole orders table. The Customers tab's
        // own list/search query no longer touches this table at all (see
        // the customers table's own order_count/total_spent/last_order_at
        // columns) — this index is purely for that occasional recompute,
        // plus CustomerService.ordersForCustomer's per-customer history
        // fetch (findByPhoneOrderByCreatedAtDesc).
        @Index(name = "idx_orders_phone_created_at", columnList = "phone, created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_code", nullable = false, unique = true, length = 30)
    private String orderCode;

    @Column(name = "customer_name", nullable = false, length = 120)
    private String customerName;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "flat_no", nullable = false, length = 60)
    private String flatNo;

    @Column(name = "address_line", nullable = false, length = 300)
    private String addressLine;

    @Column(name = "landmark", length = 150)
    private String landmark;

    @Column(name = "notes", length = 300)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private OrderStatus status = OrderStatus.NEW;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    /** When this order last changed status (placed as NEW, then marked
     * Delivered/Cancelled, then possibly reopened back to NEW) — distinct
     * from createdAt, which never changes after the order is placed. The
     * admin Orders tab's Delivered/Cancelled/All lists sort by this field
     * (most recently actioned first) instead of createdAt, so an order
     * placed days ago that's only just now been marked delivered shows up
     * at the top where the admin is looking, not buried under today's
     * orders by its original placement date. It also backs the "Reopen as
     * New" 24-hour window (see OrderService.reopen) — that window is
     * measured from when the order was marked Delivered/Cancelled, not
     * from when it was originally placed, so an old order marked delivered
     * today can still be reopened today.
     *
     * Defaults to createdAt for a brand-new order (see onCreate below), so
     * the New tab — which never reads this column, only createdAt — stays
     * completely unaffected. */
    @Column(name = "status_updated_at")
    private Instant statusUpdatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = OrderStatus.NEW;
        }
        if (statusUpdatedAt == null) {
            statusUpdatedAt = createdAt;
        }
    }

    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }
}
