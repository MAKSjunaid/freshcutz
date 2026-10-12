package com.freshcutz.controller;

import com.freshcutz.dto.SiteConfig;
import com.freshcutz.dto.TemplateDetail;
import com.freshcutz.dto.TemplateInfo;
import com.freshcutz.dto.TemplateSaveRequest;
import com.freshcutz.service.SiteTemplateService;
import com.freshcutz.service.SseBroadcastService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** The template picker in the admin's Customize tab: up to four saved designs,
 * one of which is the primary (live) one. Protected by AdminAuthFilter
 * (everything under /api/admin). */
@RestController
@RequestMapping("/api/admin/site/templates")
public class AdminTemplateController {

    private final SiteTemplateService service;
    private final SseBroadcastService broadcastService;

    public AdminTemplateController(SiteTemplateService service, SseBroadcastService broadcastService) {
        this.service = service;
        this.broadcastService = broadcastService;
    }

    @GetMapping
    public List<TemplateInfo> list() {
        return service.list();
    }

    @GetMapping("/defaults")
    public SiteConfig defaults() {
        return service.defaults();
    }

    @GetMapping("/{slot}")
    public TemplateDetail get(@PathVariable("slot") int slot) {
        return service.get(slot);
    }

    @PutMapping("/{slot}")
    public TemplateDetail save(@PathVariable("slot") int slot, @RequestBody TemplateSaveRequest request) {
        return push(service.save(slot, request.getName(), request.getConfig()));
    }

    /** Make this template the one customers see. */
    @PostMapping("/{slot}/primary")
    public TemplateDetail makePrimary(@PathVariable("slot") int slot) {
        return push(service.makePrimary(slot));
    }

    @DeleteMapping("/{slot}")
    public List<TemplateInfo> clear(@PathVariable("slot") int slot) {
        return service.clear(slot);
    }

    private TemplateDetail push(SiteTemplateService.Result result) {
        if (result.live != null) broadcastService.broadcastSite(result.live);
        return result.template;
    }
}
