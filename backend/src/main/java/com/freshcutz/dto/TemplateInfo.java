package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** One line of the template picker in the Customize tab. */
@Getter
@AllArgsConstructor
public class TemplateInfo {
    private int slot;
    private String name;
    /** false = this slot has never been saved. */
    private boolean saved;
    /** true = this is the design customers currently see. */
    private boolean primary;
    private String updatedAt;
}
