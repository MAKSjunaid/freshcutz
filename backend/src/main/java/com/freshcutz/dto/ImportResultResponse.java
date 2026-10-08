package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/** Summary shown to the admin after uploading an edited orders .xlsx:
 * how many orders really changed, how many rows matched an order but had
 * nothing different, and which rows were rejected (with the reason). */
@Getter
@Builder
@AllArgsConstructor
public class ImportResultResponse {
    private int updated;
    private int unchanged;
    private int skipped;
    private List<String> errors;
}
