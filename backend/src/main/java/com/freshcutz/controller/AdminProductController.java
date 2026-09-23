package com.freshcutz.controller;

import com.freshcutz.dto.ProductRequest;
import com.freshcutz.dto.ProductResponse;
import com.freshcutz.exception.BadRequestException;
import com.freshcutz.service.ProductService;
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

    public AdminProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public List<ProductResponse> list() {
        return productService.listForAdmin();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse create(@Valid @RequestBody ProductRequest request) {
        return productService.create(request);
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        return productService.update(id, request);
    }

    @PatchMapping("/{id}/enabled")
    public ProductResponse setEnabled(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        return productService.setEnabled(id, enabled);
    }

    @PatchMapping("/{id}/out-of-stock")
    public ProductResponse setOutOfStock(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        boolean outOfStock = Boolean.TRUE.equals(body.get("outOfStock"));
        return productService.setOutOfStock(id, outOfStock);
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
            // Stored directly in the database (Product.imageData) — this
            // REPLACES whatever bytes were there before on the same row,
            // never inserting a separate file/record, which is what kept
            // the old disk-based version quietly filling up with orphaned
            // files every time a photo was changed.
            return productService.updateImage(id, file.getBytes(), contentType);
        } catch (IOException e) {
            throw new BadRequestException("Could not read the uploaded image: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        productService.delete(id);
    }
}
