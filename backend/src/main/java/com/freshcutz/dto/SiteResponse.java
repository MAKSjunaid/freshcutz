package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** What both the storefront and the Customize tab receive: the saved
 * settings plus, when a custom logo has been uploaded, the URL to load it
 * from (null = keep using the built-in logo). The URL carries a ?v=
 * cache-buster so a replaced logo is never served stale. */
@Getter
@AllArgsConstructor
public class SiteResponse {
    private SiteConfig config;
    private String logoUrl;
}
