package com.freshcutz.controller;

import com.freshcutz.dto.StoreStatusResponse;
import com.freshcutz.service.SseBroadcastService;
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
    private final SseBroadcastService broadcastService;

    public AdminStoreController(StoreSettingsService storeSettingsService, SseBroadcastService broadcastService) {
        this.storeSettingsService = storeSettingsService;
        this.broadcastService = broadcastService;
    }

    @GetMapping
    public StoreStatusResponse status() {
        return StoreStatusResponse.builder().online(storeSettingsService.isOnline()).build();
    }

    @PatchMapping("/online")
    public StoreStatusResponse setOnline(@RequestBody Map<String, Boolean> body) {
        boolean online = Boolean.TRUE.equals(body.get("online"));
        StoreStatusResponse response =
                StoreStatusResponse.builder().online(storeSettingsService.setOnline(online)).build();
        // Pushes to every storefront tab subscribed to
        // /api/store/status/stream, so the "we're closed" banner (and the
        // checkout gate that reads it) updates within about a second
        // instead of waiting for their next 15-second poll — which no
        // longer exists.
        broadcastService.broadcastStatus(response);
        return response;
    }
}
