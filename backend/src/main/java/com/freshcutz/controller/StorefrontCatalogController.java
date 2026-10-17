package com.freshcutz.controller;

import com.freshcutz.dto.StoreItem;
import com.freshcutz.service.SseBroadcastService;
import com.freshcutz.service.StorefrontCatalogService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

/**
 * The customer website's catalog, in small pieces (public, no login). Every answer comes from memory.
 *
 * <p>The answers carry an ETag (a "version" of the catalog), so a browser that already has the current one gets a
 * tiny "304 not modified" instead of the data again. {@code Cache-Control: no-cache} means "always ask, but the
 * answer is almost free" — a changed price or hidden product is never served stale.
 */
@RestController
@RequestMapping("/api/products")
public class StorefrontCatalogController {

    private final StorefrontCatalogService catalog;
    private final SseBroadcastService broadcastService;

    public StorefrontCatalogController(StorefrontCatalogService catalog, SseBroadcastService broadcastService) {
        this.catalog = catalog;
        this.broadcastService = broadcastService;
    }

    /** First screen in ONE call: category tabs + the first products of the default tab. */
    @GetMapping("/start")
    public Object start(@RequestParam(value = "limit", defaultValue = "12") int limit,
                        WebRequest request, HttpServletResponse response) {
        StorefrontCatalogService.Start start = catalog.start(limit);
        // the default tab is part of this answer, so it is part of its version too
        String etag = etag(start.version() + "-" + Integer.toString(start.category().hashCode(), 36) + "-" + limit);
        if (notModified(request, response, etag)) return null;
        return start;
    }

    /** The next products of a tab: after the product with rank {@code after} (-1 = from the start). {@code category}
     * empty = the "All" tab. */
    @GetMapping("/page")
    public Object page(@RequestParam(value = "category", defaultValue = "") String category,
                       @RequestParam(value = "after", defaultValue = "-1") int after,
                       @RequestParam(value = "limit", defaultValue = "12") int limit,
                       @RequestParam(value = "v", required = false) String v,
                       WebRequest request, HttpServletResponse response) {
        if (v == null || v.isBlank()) { // older callers: always ask, "304" when unchanged
            if (notModified(request, response, etag(catalog.version()))) return null;
            return catalog.page(category, after, limit);
        }
        StorefrontCatalogService.Page page = catalog.page(category, after, limit);
        cacheByVersion(response, page.version(), v);
        return page;
    }

    /** What changed since the version a page holds (a page that was hidden / offline / missed a live message).
     * Never stored: the answer depends on the moment it is asked. */
    @GetMapping("/delta")
    public StorefrontCatalogService.Delta delta(@RequestParam("since") String since, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return catalog.since(since);
    }

    /** Category tabs only (used when the catalog changed while a customer is browsing). */
    @GetMapping("/tabs")
    public Object tabs(WebRequest request, HttpServletResponse response) {
        if (notModified(request, response, etag(catalog.version()))) return null;
        return catalog.tabs();
    }

    /** Every product. The page asks for this only the first time the search box is used. */
    @GetMapping("/all")
    public Object all(@RequestParam(value = "v", required = false) String v,
                      WebRequest request, HttpServletResponse response) {
        if (v == null || v.isBlank()) {
            if (notModified(request, response, etag(catalog.version()))) return null;
            return catalog.all();
        }
        StorefrontCatalogService.All all = catalog.all();
        cacheByVersion(response, all.version(), v);
        return all;
    }

    /** Current details of a few products (the cart, a past order being reordered). Products that are hidden or
     * deleted are not in the answer. */
    @GetMapping("/by-ids")
    public List<StoreItem> byIds(@RequestParam(value = "ids", defaultValue = "") String ids, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        List<Long> parsed = new ArrayList<>();
        for (String part : ids.split(",")) {
            if (parsed.size() >= StorefrontCatalogService.MAX_IDS) break;
            try {
                parsed.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
                // a stray value is skipped
            }
        }
        return catalog.byIds(parsed);
    }

    /** The live stream: after connecting, and after every admin edit, a tiny message says what changed (a small change
     * carries the changed products themselves). {@code since} = the version the page already holds. */
    @GetMapping(path = "/changes", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter changes(@RequestParam(value = "since", required = false) String since) {
        return broadcastService.subscribeCatalogChanges(since);
    }

    /** The page asks with the catalog version it knows (?v=...). When that is still the current version the answer can
     * never change, so the browser keeps it and never asks again (reloading the page, going back to a tab). When the
     * page's version is old the answer is not stored (the page hears about the new version from the live stream). */
    private static void cacheByVersion(HttpServletResponse response, String currentVersion, String askedVersion) {
        response.setHeader("Cache-Control",
                currentVersion.equals(askedVersion) ? "public, max-age=31536000, immutable" : "no-store");
    }

    private static String etag(String version) {
        return "\"" + version + "\"";
    }

    /** True (and the 304 answer is ready) when the browser already has this version. */
    private static boolean notModified(WebRequest request, HttpServletResponse response, String etag) {
        response.setHeader("Cache-Control", "no-cache");
        return request.checkNotModified(etag);
    }
}
