package com.freshcutz.service;

import com.freshcutz.dto.ProductListRow;
import org.springframework.jdbc.core.RowMapper;

/**
 * The plain-SQL way of reading product rows for the admin panel: the columns to select and how to turn
 * one result row into a ProductListRow. The photo bytes are never selected — only "has a photo" is.
 */
final class ProductRows {

    private ProductRows() {}

    static final String COLUMNS =
            "id, name, description, category, image_url, (image_data IS NOT NULL) AS has_image, updated_at, "
                    + "variant1_label, variant1_price, variant1_mrp, variant2_label, variant2_price, variant2_mrp, "
                    + "enabled, out_of_stock, display_order";

    static final RowMapper<ProductListRow> MAPPER = (rs, i) -> new ProductListRow(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("category"),
            rs.getString("image_url"),
            rs.getBoolean("has_image"),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getString("variant1_label"),
            rs.getBigDecimal("variant1_price"),
            rs.getBigDecimal("variant1_mrp"),
            rs.getString("variant2_label"),
            rs.getBigDecimal("variant2_price"),
            rs.getBigDecimal("variant2_mrp"),
            rs.getBoolean("enabled"),
            rs.getBoolean("out_of_stock"),
            rs.getInt("display_order"));
}
