package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** A template opened for editing. For a slot that was never saved, `config`
 * is a copy of the live design to start from. */
@Getter
@AllArgsConstructor
public class TemplateDetail {
    private int slot;
    private String name;
    private boolean saved;
    private boolean primary;
    private SiteConfig config;
    /** The shared logo (same for every template), or null for the built-in one. */
    private String logoUrl;
}
