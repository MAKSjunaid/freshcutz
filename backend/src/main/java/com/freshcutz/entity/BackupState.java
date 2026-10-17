package com.freshcutz.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Single-row table (always id=1): remembers when the last backup e-mail was
 * sent successfully, so a restart of the server doesn't send another one.
 * Written only after a successful send (about once per backup interval). */
@Entity
@Table(name = "backup_state")
public class BackupState {

    @Id
    private Long id;

    /** Epoch millis of the last successful backup e-mail. null = never. */
    @Column(name = "last_success_at")
    private Long lastSuccessAt;

    @Column(name = "last_size_bytes")
    private Long lastSizeBytes;

    @Column(name = "last_note", length = 300)
    private String lastNote;

    /** The admin's on/off switch for automatic backups. null = not chosen yet (follow BACKUP_INTERVAL_HOURS). */
    @Column(name = "auto_enabled")
    private Boolean autoEnabled;

    /** The admin's chosen interval in hours. null = not chosen yet. */
    @Column(name = "interval_hours")
    private Integer intervalHours;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getLastSuccessAt() { return lastSuccessAt; }
    public void setLastSuccessAt(Long lastSuccessAt) { this.lastSuccessAt = lastSuccessAt; }
    public Long getLastSizeBytes() { return lastSizeBytes; }
    public void setLastSizeBytes(Long lastSizeBytes) { this.lastSizeBytes = lastSizeBytes; }
    /** How many days of orders to include in the backup. null/0 = everything. */
    @Column(name = "range_days")
    private Integer rangeDays;

    /** Epoch millis of the last AUTOMATIC backup (the manual button does not move the schedule). */
    @Column(name = "last_scheduled_at")
    private Long lastScheduledAt;

    public Long getLastScheduledAt() { return lastScheduledAt; }
    public void setLastScheduledAt(Long lastScheduledAt) { this.lastScheduledAt = lastScheduledAt; }
    public Integer getRangeDays() { return rangeDays; }
    public void setRangeDays(Integer rangeDays) { this.rangeDays = rangeDays; }
    public Boolean getAutoEnabled() { return autoEnabled; }
    public void setAutoEnabled(Boolean autoEnabled) { this.autoEnabled = autoEnabled; }
    public Integer getIntervalHours() { return intervalHours; }
    public void setIntervalHours(Integer intervalHours) { this.intervalHours = intervalHours; }
    public String getLastNote() { return lastNote; }
    public void setLastNote(String lastNote) { this.lastNote = lastNote; }
}
