package com.freshcutz.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
@Table(name = "customers")
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

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void onSave() {
        updatedAt = Instant.now();
    }
}
