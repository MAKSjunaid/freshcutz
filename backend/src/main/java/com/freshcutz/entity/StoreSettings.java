package com.freshcutz.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Single-row table for shop-wide settings — currently just whether the
 * store is open for orders. Always read/written at id=1 (see
 * StoreSettingsService); there's only ever one row. */
@Entity
@Table(name = "store_settings")
@Getter
@Setter
public class StoreSettings {

    @Id
    private Long id;

    @Column(nullable = false)
    private boolean online = true;
}
