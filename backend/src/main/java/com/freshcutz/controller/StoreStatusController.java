package com.freshcutz.controller;

import com.freshcutz.dto.StoreStatusResponse;
import com.freshcutz.service.StoreSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, read-only "is the shop open for orders right now" check — the
 * storefront polls this to show a banner and block checkout. No auth
 * required (mirrors ProductController). */
@RestController
@RequestMapping("/api/store")
public class StoreStatusController {

    private final StoreSettingsService storeSettingsService;

    public StoreStatusController(StoreSettingsService storeSettingsService) {
        this.storeSettingsService = storeSettingsService;
    }

    @GetMapping("/status")
    public StoreStatusResponse status() {
        return StoreStatusResponse.builder().online(storeSettingsService.isOnline()).build();
    }
}
