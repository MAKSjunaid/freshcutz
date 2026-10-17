package com.freshcutz.controller;

import com.freshcutz.service.ShopSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Public: which category tab the customer website should open on. Answered from memory (no database). */
@RestController
@RequestMapping("/api/store")
public class StoreDefaultCategoryController {

    private final ShopSettingsService settings;

    public StoreDefaultCategoryController(ShopSettingsService settings) {
        this.settings = settings;
    }

    /** {"category": "Chicken"}, or {"category": ""} for the normal "All" tab. */
    @GetMapping("/default-category")
    public Map<String, String> defaultCategory() {
        return Map.of("category", settings.getDefaultCategory());
    }
}
