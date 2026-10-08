package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/** The Sales tab's header AND one page of its By Day list in a single
 * response — see SalesService.getOverview. `summary` is the period header
 * (money + status boxes); `days` is one page of day rows for the selected
 * status filter, newest first; `hasMore` says whether older days remain
 * (the client then asks again with cursorDay = the last day it holds).
 * A header-only request (size 0) returns an empty `days`. */
@Getter
@Builder
@AllArgsConstructor
public class DailyOverviewResponse {
    private PeriodSummaryResponse summary;
    private List<DailySalesGroup> days;
    private boolean hasMore;
}
