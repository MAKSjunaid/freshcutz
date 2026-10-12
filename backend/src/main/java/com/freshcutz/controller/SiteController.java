package com.freshcutz.controller;

import com.freshcutz.dto.SiteResponse;
import com.freshcutz.entity.SiteCustomization;
import com.freshcutz.service.SiteCustomizationService;
import com.freshcutz.service.SseBroadcastService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.TimeUnit;

/** Public, read-only: how the customer website should look right now
 * (set from the admin's Customize tab). The storefront loads /site once and
 * then keeps /site/stream open, so a change an admin saves reaches open tabs
 * within about a second. No auth, same as the other /api/store endpoints. */
@RestController
@RequestMapping("/api/store/site")
public class SiteController {

    private final SiteCustomizationService service;
    private final SseBroadcastService broadcastService;

    public SiteController(SiteCustomizationService service, SseBroadcastService broadcastService) {
        this.service = service;
        this.broadcastService = broadcastService;
    }

    @GetMapping
    public SiteResponse site() {
        return service.get();
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return broadcastService.subscribeSite(service.get());
    }

    /** The uploaded logo. Its URL always carries ?v=<upload time>, so it is
     * safe to cache hard: a new logo is a new URL. */
    @GetMapping("/logo")
    public ResponseEntity<byte[]> logo() {
        SiteCustomization row = service.getForLogo();
        if (row == null || row.getLogoData() == null) {
            return ResponseEntity.notFound().build();
        }
        MediaType type = MediaType.parseMediaType(
                row.getLogoContentType() != null ? row.getLogoContentType() : "image/png");
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic())
                .body(row.getLogoData());
    }
}
