package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** What the admin "Store hours" panel needs in one response. */
@Getter
@Builder
@AllArgsConstructor
public class AdminStoreResponse {
    /** Whether customers can order right now (schedule and manual switch combined). */
    private boolean online;
    /** true while the toggle is overriding the schedule (until the next open/close time). */
    private boolean manualOverride;
    private StoreScheduleDto schedule;
    /** When the schedule next flips the store (epoch millis), or null if it never will. */
    private Long nextChangeAt;
    /** true if that next change opens the store, false if it closes it. */
    private boolean nextChangeOpens;
    /** The next change as text in the store's time zone, e.g. "Tue 9:00 PM". */
    private String nextChangeText;
}
