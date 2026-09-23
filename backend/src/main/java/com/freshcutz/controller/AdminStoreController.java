package com.freshcutz.controller;

import com.freshcutz.dto.StoreStatusResponse;
import com.freshcutz.service.StoreSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Lets the admin flip the shop between accepting and not accepting new
 * orders (e.g. closed for the night, out of stock storewide). Protected
 * by AdminAuthFilter. */
@RestController
@RequestMapping("/api/admin/store")
public class AdminStoreController {

    private final StoreSettingsService storeSettingsService;

    public AdminStoreController(StoreSettingsService storeSettingsService) {
        this.storeSettingsService = storeSettingsService;
    }

    @GetMapping
    public StoreStatusResponse status() {
        return StoreStatusResponse.builder().online(storeSettingsService.isOnline()).build();
    }

    @PatchMapping("/online")
    public StoreStatusResponse setOnline(@RequestBody Map<String, Boolean> body) {
        boolean online = Boolean.TRUE.equals(body.get("online"));
        return StoreStatusResponse.builder().online(storeSettingsService.setOnline(online)).build();
    }
}
