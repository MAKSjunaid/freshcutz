package com.freshcutz.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Single-row table for shop-wide settings: whether the store is open for
 * orders, and the optional automatic open/close schedule. Always read/written
 * at id=1 (see StoreSettingsService); there's only ever one row.
 *
 * The schedule columns are deliberately nullable wrapper types: adding a
 * NOT NULL column to a table that already has its row would fail on Postgres
 * with ddl-auto=update, so "null" simply means "default" (see the service). */
@Entity
@Table(name = "store_settings")
@Getter
@Setter
public class StoreSettings {

    @Id
    private Long id;

    /** The manual switch. With the schedule OFF this is the whole story; with
     * it ON it only matters while a manual override is active. */
    @Column(nullable = false)
    private boolean online = true;

    /** Open and close automatically by the clock. null = off. */
    private Boolean autoEnabled;

    /** "HH:mm" in {@link #timezone}. null = 09:00. */
    @Column(length = 5)
    private String openTime;

    /** "HH:mm" in {@link #timezone}. null = 21:00. Earlier than openTime means "after midnight". */
    @Column(length = 5)
    private String closeTime;

    /** Bit 0 = Monday ... bit 6 = Sunday. null = every day. */
    private Integer openDays;

    /** IANA zone id, e.g. Asia/Kolkata. null = Asia/Kolkata. */
    @Column(length = 64)
    private String timezone;

    /** While the clock is before this (epoch millis) the manual switch beats the
     * schedule — set when the admin flips the toggle against the schedule. null = no override. */
    private Long overrideUntil;
}
