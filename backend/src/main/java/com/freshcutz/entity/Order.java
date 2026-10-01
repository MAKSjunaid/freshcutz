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
        @Index(name = "idx_orders_created_at", columnList = "created_at")
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
    }

    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }
}
