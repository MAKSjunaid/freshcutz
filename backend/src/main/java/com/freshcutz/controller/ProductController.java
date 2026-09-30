package com.freshcutz.controller;

import com.freshcutz.dto.ProductResponse;
import com.freshcutz.entity.Product;
import com.freshcutz.service.ProductService;
import com.freshcutz.service.SseBroadcastService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Public, read-only catalog for the storefront. No auth required. */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;
    private final SseBroadcastService broadcastService;

    public ProductController(ProductService productService, SseBroadcastService broadcastService) {
        this.productService = productService;
        this.broadcastService = broadcastService;
    }

    @GetMapping
    public List<ProductResponse> list() {
        return productService.listForCustomers();
    }

    /** Replaces the storefront's old 15-second poll of the endpoint above:
     * the browser opens this once and gets pushed a fresh catalog
     * whenever an admin actually changes something (see
     * AdminProductController), instead of asking again on a timer. The
     * current catalog is sent immediately on connecting, same as the
     * plain GET above would return. */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return broadcastService.subscribeProducts(productService.listForCustomers());
    }

    /** Streams a product's photo straight out of the database. The URL
     * always carries a `?v=` cache-buster (see ProductResponse), so it's
     * safe to tell browsers to cache this response for a long time —
     * replacing the photo changes the URL, it never serves stale bytes. */
    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable Long id) {
        Product product = productService.getEntityForImage(id);
        if (product.getImageData() == null) {
            return ResponseEntity.notFound().build();
        }
        MediaType type = MediaType.parseMediaType(
                product.getImageContentType() != null ? product.getImageContentType() : "image/jpeg");
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic())
                .body(product.getImageData());
    }
}
