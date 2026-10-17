package com.freshcutz.controller;

import com.freshcutz.exception.BadRequestException;
import com.freshcutz.service.ProductImportService;
import com.freshcutz.service.ProductService;
import com.freshcutz.service.SseBroadcastService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** "Import products" in the admin Products tab: many products from one Excel sheet.
 * Protected by AdminAuthFilter like everything under /api/admin. */
@RestController
@RequestMapping("/api/admin/products/import")
public class AdminProductImportController {

    private final ProductImportService importService;
    private final ProductService productService;
    private final SseBroadcastService broadcastService;

    public AdminProductImportController(ProductImportService importService, ProductService productService,
                                        SseBroadcastService broadcastService) {
        this.importService = importService;
        this.productService = productService;
        this.broadcastService = broadcastService;
    }

    @PostMapping(consumes = "multipart/form-data")
    public Map<String, Object> importProducts(@RequestParam("file") MultipartFile file,
                                              @RequestParam(value = "show", defaultValue = "true") boolean show) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("No file received");
        }
        ProductImportService.Result result;
        try (InputStream in = file.getInputStream()) {
            result = importService.importProducts(in, show);
        } catch (IOException e) {
            throw new BadRequestException("Could not read the file: " + e.getMessage());
        }
        if (result.created > 0) {
            // one catalog push to open storefront tabs, after everything is saved
            broadcastService.broadcastProductsIfAny(productService::listForCustomers);
        }
        return result.toMap();
    }
}
