package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/** Summary shown to the admin after uploading an edited orders .xlsx. */
@Getter
@Builder
@AllArgsConstructor
public class ImportResultResponse {
    private int updated;
    private int skipped;
    private List<String> errors;
}
