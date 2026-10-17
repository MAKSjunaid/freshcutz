package com.freshcutz.controller;

import com.freshcutz.service.ProductBulkService;
import com.freshcutz.service.ProductService;
import com.freshcutz.service.SseBroadcastService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** "Bulk actions" in the admin Products tab: Excel template, Excel export, and changing many products at once.
 * Protected by AdminAuthFilter like everything under /api/admin. */
@RestController
@RequestMapping("/api/admin/products")
public class AdminProductBulkController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ProductBulkService bulkService;
    private final ProductService productService;
    private final SseBroadcastService broadcastService;

    public AdminProductBulkController(ProductBulkService bulkService, ProductService productService,
                                      SseBroadcastService broadcastService) {
        this.bulkService = bulkService;
        this.productService = productService;
        this.broadcastService = broadcastService;
    }

    /** action: ENABLE, DISABLE, OUT_OF_STOCK, IN_STOCK, CATEGORY (value = name), DELETE. */
    public record BulkRequest(String action, List<Long> ids, String value) {}

    @PostMapping("/bulk")
    public Map<String, Object> bulk(@RequestBody BulkRequest request) {
        Map<String, Object> result = bulkService.apply(request.action(), request.ids(), request.value());
        if (((Number) result.get("changed")).intValue() > 0) {
            // one catalog push to open storefront tabs, after everything is saved
            broadcastService.broadcastProductsIfAny(productService::listForCustomers);
        }
        return result;
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> template() {
        return xlsx(bulkService.templateXlsx(), "FreshCutz-products-template.xlsx");
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export() {
        return xlsx(bulkService.exportXlsx(), "FreshCutz-products.xlsx");
    }

    private static ResponseEntity<byte[]> xlsx(byte[] body, String filename) {
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }
}
