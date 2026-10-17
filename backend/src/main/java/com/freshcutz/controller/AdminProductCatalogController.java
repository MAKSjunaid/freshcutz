package com.freshcutz.controller;

import com.freshcutz.dto.ProductResponse;
import com.freshcutz.service.AdminCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The two small reads behind the admin Products page. Protected by AdminAuthFilter (it is under /api/admin). */
@RestController
@RequestMapping("/api/admin/products")
public class AdminProductCatalogController {

    private final AdminCatalogService catalog;

    public AdminProductCatalogController(AdminCatalogService catalog) {
        this.catalog = catalog;
    }

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
}
