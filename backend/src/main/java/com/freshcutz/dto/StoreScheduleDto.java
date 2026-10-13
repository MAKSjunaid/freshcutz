package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/** The automatic open/close schedule — used both as the request body of
 * PUT /api/admin/store/schedule and inside AdminStoreResponse. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoreScheduleDto {
    /** true = open and close by the clock. */
    private boolean autoEnabled;
    /** "HH:mm", 24-hour, in {@link #timezone}. */
    private String openTime;
    /** "HH:mm", 24-hour. A time earlier than openTime means closing after midnight. */
    private String closeTime;
    /** Days the store opens: 1 = Monday ... 7 = Sunday. */
    private List<Integer> days;
    /** IANA zone id, e.g. "Asia/Kolkata". */
    private String timezone;
}
