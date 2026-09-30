package com.freshcutz.controller;

import com.freshcutz.dto.StoreStatusResponse;
import com.freshcutz.service.SseBroadcastService;
import com.freshcutz.service.StoreSettingsService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Public, read-only "is the shop open for orders right now" check — the
 * storefront used to poll this every 15 seconds to show a banner and
 * block checkout; it now opens /status/stream once instead and gets
 * pushed a fresh value whenever an admin actually flips the toggle (see
 * AdminStoreController). No auth required (mirrors ProductController). */
@RestController
@RequestMapping("/api/store")
public class StoreStatusController {

    private final StoreSettingsService storeSettingsService;
    private final SseBroadcastService broadcastService;

    public StoreStatusController(StoreSettingsService storeSettingsService, SseBroadcastService broadcastService) {
        this.storeSettingsService = storeSettingsService;
        this.broadcastService = broadcastService;
    }

    @GetMapping("/status")
    public StoreStatusResponse status() {
        return StoreStatusResponse.builder().online(storeSettingsService.isOnline()).build();
    }

    @GetMapping(path = "/status/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        StoreStatusResponse current = StoreStatusResponse.builder().online(storeSettingsService.isOnline()).build();
        return broadcastService.subscribeStatus(current);
    }
}
