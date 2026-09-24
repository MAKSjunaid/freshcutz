package com.freshcutz.repository;

import com.freshcutz.dto.ProductListRow;
import com.freshcutz.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /** Full entities — still used wherever the actual row is needed
     * (repositioning on save, editing, streaming a single photo). Not
     * used by the LIST endpoints any more; see below. */
    List<Product> findByEnabledTrueOrderByDisplayOrderAscIdAsc();

    List<Product> findAllByOrderByDisplayOrderAscIdAsc();

    /** Same rows as the two finders above, but built as a JPQL constructor
     * expression that deliberately never selects imageData — see
     * ProductListRow for why that matters. Standard JPQL (no native SQL),
     * so it runs identically against local H2 and production Postgres. */
    @Query("SELECT NEW com.freshcutz.dto.ProductListRow(" +
            "p.id, p.name, p.description, p.category, p.imageUrl, " +
            "CASE WHEN p.imageData IS NOT NULL THEN true ELSE false END, p.updatedAt, " +
            "p.variant1Label, p.variant1Price, p.variant1Mrp, " +
            "p.variant2Label, p.variant2Price, p.variant2Mrp, " +
            "p.enabled, p.outOfStock, p.displayOrder) " +
            "FROM Product p WHERE p.enabled = true ORDER BY p.displayOrder ASC, p.id ASC")
    List<ProductListRow> listViewForCustomers();

    @Query("SELECT NEW com.freshcutz.dto.ProductListRow(" +
            "p.id, p.name, p.description, p.category, p.imageUrl, " +
            "CASE WHEN p.imageData IS NOT NULL THEN true ELSE false END, p.updatedAt, " +
            "p.variant1Label, p.variant1Price, p.variant1Mrp, " +
            "p.variant2Label, p.variant2Price, p.variant2Mrp, " +
            "p.enabled, p.outOfStock, p.displayOrder) " +
            "FROM Product p ORDER BY p.displayOrder ASC, p.id ASC")
    List<ProductListRow> listViewForAdmin();
}
