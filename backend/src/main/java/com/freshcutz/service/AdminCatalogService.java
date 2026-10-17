package com.freshcutz.service;

import com.freshcutz.dto.ProductResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * What the admin Products page reads. Two small questions instead of "give me everything":
 * <ol>
 *   <li>{@link #categories()} — one GROUP BY query: each category with how many products it has.</li>
 *   <li>{@link #productsIn(String)} — the products of ONE category.</li>
 * </ol>
 * Both answers are kept in memory (StorefrontCache) and thrown away the moment any product is saved, so
 * opening the page, going into a category and coming back costs the database nothing unless something
 * changed. No @Transactional on purpose: a cache hit must not open a database connection (that would wake
 * Neon).
 */
@Service
public class AdminCatalogService {

    /** One category tile: {@code name} is "" for products that have no category. */
    public record CategoryInfo(String name, long total, long hidden, long outOfStock) {}

    private final JdbcTemplate jdbc;

    public AdminCatalogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** In the order the shop shows them (by the first product of each category). */
    public List<CategoryInfo> categories() {
        return StorefrontCache.admin("cats", () -> List.copyOf(jdbc.query(
                "SELECT COALESCE(TRIM(category), '') AS cat, COUNT(*) AS total, "
                        + "SUM(CASE WHEN enabled THEN 0 ELSE 1 END) AS hidden, "
                        + "SUM(CASE WHEN out_of_stock THEN 1 ELSE 0 END) AS oos "
                        + "FROM products GROUP BY COALESCE(TRIM(category), '') "
                        + "ORDER BY MIN(display_order), COALESCE(TRIM(category), '')",
                (rs, i) -> new CategoryInfo(rs.getString("cat"), rs.getLong("total"),
                        rs.getLong("hidden"), rs.getLong("oos")))));
    }

    /** The products of one category (use "" for products without a category), in display order. */
    public List<ProductResponse> productsIn(String category) {
        String name = category == null ? "" : category.trim();
        return StorefrontCache.admin("cat:" + name, () -> jdbc.query(
                        "SELECT " + ProductRows.COLUMNS + " FROM products "
                                + "WHERE COALESCE(TRIM(category), '') = ? ORDER BY display_order, id",
                        ProductRows.MAPPER, name)
                .stream().map(ProductResponse::from).collect(Collectors.toUnmodifiableList()));
    }
}
