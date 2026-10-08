package com.freshcutz.service;

/** Which orders the Sales tab's By Day / By Customer / By Item lists
 * include — chosen by tapping the All / New / Delivered / Cancelled boxes
 * under the period header. Each flag says whether orders of that status
 * are part of the list; ALL is simply all three at once.
 *
 * Every status is dated by the moment that status happened, the same rule
 * the header's status counts use (see OrderMixSql): New by when it was placed, Delivered by when
 * it was delivered, Cancelled by when it was cancelled. */
public enum SalesStatusFilter {
    ALL(true, true, true),
    NEW(true, false, false),
    DELIVERED(false, true, false),
    CANCELLED(false, false, true);

    private final boolean includeNew;
    private final boolean includeDelivered;
    private final boolean includeCancelled;

    SalesStatusFilter(boolean includeNew, boolean includeDelivered, boolean includeCancelled) {
        this.includeNew = includeNew;
        this.includeDelivered = includeDelivered;
        this.includeCancelled = includeCancelled;
    }

    public boolean includeNew() { return includeNew; }
    public boolean includeDelivered() { return includeDelivered; }
    public boolean includeCancelled() { return includeCancelled; }

    /** Blank/missing means ALL (what the Sales tab opens on); an
     * unrecognized value is an error rather than silently falling back. */
    public static SalesStatusFilter parse(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        return valueOf(value.trim().toUpperCase());
    }
}
