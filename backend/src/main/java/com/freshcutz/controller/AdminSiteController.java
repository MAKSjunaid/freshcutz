package com.freshcutz.controller;

import com.freshcutz.dto.SiteConfig;
import com.freshcutz.dto.SiteResponse;
import com.freshcutz.exception.BadRequestException;
import com.freshcutz.service.SiteCustomizationService;
import com.freshcutz.service.SseBroadcastService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Set;

/** The admin's Customize tab: edit the customer website's brand, texts,
 * badges, header buttons and colors, and upload the logo. Protected by
 * AdminAuthFilter. Every change is pushed straight to open storefront tabs
 * (see SseBroadcastService.broadcastSite). */
@RestController
@RequestMapping("/api/admin/site")
public class AdminSiteController {

    private static final long MAX_LOGO_BYTES = 1024 * 1024; // 1 MB
    private static final Set<String> LOGO_TYPES = Set.of("image/png", "image/jpeg", "image/jpg", "image/webp");

    private final SiteCustomizationService service;
    private final SseBroadcastService broadcastService;

    public AdminSiteController(SiteCustomizationService service, SseBroadcastService broadcastService) {
        this.service = service;
        this.broadcastService = broadcastService;
    }

    @GetMapping
    public SiteResponse get() {
        return service.get();
    }

    @PutMapping
    public SiteResponse save(@RequestBody SiteConfig config) {
        return publish(service.save(config));
    }

    @PostMapping(value = "/logo", consumes = "multipart/form-data")
    public SiteResponse uploadLogo(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("No image file received");
        }
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!LOGO_TYPES.contains(type)) {
            throw new BadRequestException("The logo must be a PNG, JPG or WEBP image");
        }
        if (file.getSize() > MAX_LOGO_BYTES) {
            throw new BadRequestException("The logo is too large (max 1 MB) — use a smaller image");
        }
        return publish(service.saveLogo(file.getBytes(), type));
    }

    @DeleteMapping("/logo")
    public SiteResponse removeLogo() {
        return publish(service.removeLogo());
    }

    /** Everything back to the original look, logo included. */
    @PostMapping("/reset")
    public SiteResponse reset() {
        return publish(service.reset());
    }

    private SiteResponse publish(SiteResponse response) {
        broadcastService.broadcastSite(response);
        return response;
    }
}
