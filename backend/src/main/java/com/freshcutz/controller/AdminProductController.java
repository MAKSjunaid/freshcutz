package com.freshcutz.controller;

import com.freshcutz.dto.ProductRequest;
import com.freshcutz.dto.ProductResponse;
import com.freshcutz.exception.BadRequestException;
import com.freshcutz.service.ProductService;
import com.freshcutz.service.SseBroadcastService;
import com.freshcutz.util.ProductImageProcessor;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Everything the admin panel needs to manage the product catalog. Protected by AdminAuthFilter. */
@RestController
@RequestMapping("/api/admin/products")
public class AdminProductController {

    private static final Set<String> ALLOWED_IMAGE_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp", "image/jpg");

    private final ProductService productService;
    private final SseBroadcastService broadcastService;
    private final ProductImageProcessor imageProcessor;

    public AdminProductController(ProductService productService, SseBroadcastService broadcastService,
                                   ProductImageProcessor imageProcessor) {
        this.productService = productService;
        this.broadcastService = broadcastService;
        this.imageProcessor = imageProcessor;
    }

    @GetMapping
    public List<ProductResponse> list() {
        return productService.listForAdmin();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse create(@Valid @RequestBody ProductRequest request) {
        ProductResponse response = productService.create(request);
        broadcastCatalog();
        return response;
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        ProductResponse response = productService.update(id, request);
        broadcastCatalog();
        return response;
    }

    @PatchMapping("/{id}/enabled")
    public ProductResponse setEnabled(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        ProductResponse response = productService.setEnabled(id, enabled);
        broadcastCatalog();
        return response;
    }

    @PatchMapping("/{id}/out-of-stock")
    public ProductResponse setOutOfStock(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        boolean outOfStock = Boolean.TRUE.equals(body.get("outOfStock"));
        ProductResponse response = productService.setOutOfStock(id, outOfStock);
        broadcastCatalog();
        return response;
    }

    @PostMapping(value = "/{id}/image", consumes = "multipart/form-data")
    public ProductResponse uploadImage(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("No image file received");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType.toLowerCase())) {
            throw new BadRequestException("Only JPG, PNG or WEBP images are allowed");
        }
        try {
            // Resized/re-compressed before it ever reaches the database —
            // see ProductImageProcessor's own javadoc for why (a raw phone
            // photo can be several MB; nothing downstream needs more than
            // a web-sized JPEG). Stored directly in Product.imageData,
            // REPLACING whatever bytes were there before on the same row,
            // never inserting a separate file/record, which is what kept
            // the old disk-based version quietly filling up with orphaned
            // files every time a photo was changed.
            ProductImageProcessor.ProcessedImage processed = imageProcessor.compress(file.getBytes(), contentType);
            ProductResponse response = productService.updateImage(id, processed.data(), processed.contentType());
            broadcastCatalog();
            return response;
        } catch (IOException e) {
            throw new BadRequestException("Could not read the uploaded image: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        productService.delete(id);
        broadcastCatalog();
    }

    /** Pushes the current customer-facing catalog to every storefront tab
     * subscribed to /api/products/stream. Called after every mutation
     * above — reposition() inside ProductService.create()/update() can
     * also shift OTHER products' displayOrder, so this always re-reads
     * the full list rather than trying to patch just the one product
     * that was directly touched. */
    private void broadcastCatalog() {
        broadcastService.broadcastProducts(productService.listForCustomers());
    }
}
