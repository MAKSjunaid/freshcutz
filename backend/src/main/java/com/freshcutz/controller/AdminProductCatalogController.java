package com.freshcutz.controller;

import com.freshcutz.dto.ProductResponse;
import com.freshcutz.service.AdminCatalogService;
import com.freshcutz.service.ProductService;
import com.freshcutz.service.ShopSettingsService;
import com.freshcutz.service.SseBroadcastService;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** The two small reads behind the admin Products page. Protected by AdminAuthFilter (it is under /api/admin). */
@RestController
@RequestMapping("/api/admin/products")
public class AdminProductCatalogController {

    private final AdminCatalogService catalog;
    private final ProductService productService;
    private final SseBroadcastService broadcastService;
    private final ShopSettingsService shopSettings;

    public AdminProductCatalogController(AdminCatalogService catalog, ProductService productService,
                                         SseBroadcastService broadcastService, ShopSettingsService shopSettings) {
        this.catalog = catalog;
        this.productService = productService;
        this.broadcastService = broadcastService;
        this.shopSettings = shopSettings;
    }

    /** category "" = open the shop on "All". */
    public record DefaultCategory(String category) {}

    /** name "" = products without a category; position 1 = first category (right after "All" on the shop). */
    public record CategoryMove(String name, int position) {}

    /** Every category with its product count. */
    @GetMapping("/categories")
    public List<AdminCatalogService.CategoryInfo> categories() {
        return catalog.categories();
    }

    /** The products of one category. {@code name} empty = products without a category. */
    @GetMapping("/by-category")
    public List<ProductResponse> byCategory(@RequestParam(value = "name", defaultValue = "") String name) {
        return catalog.productsIn(name);
    }

    /** Puts a category at a position in the category order, in the admin list and on the customer site. */
    @PutMapping("/categories/position")
    public Map<String, Object> moveCategory(@RequestBody CategoryMove move) {
        boolean changed = productService.moveCategory(move.name(), move.position());
        if (changed) {
            broadcastService.broadcastProductsIfAny(productService::listForCustomers); // open shop tabs re-order at once
        }
        return Map.of("changed", changed);
    }

    /** Chooses the category tab the customer website opens on (it only applies the next time a customer opens the page). */
    @PutMapping("/default-category")
    public Map<String, String> setDefaultCategory(@RequestBody DefaultCategory body) {
        return Map.of("category", shopSettings.setDefaultCategory(body.category()));
    }
}
