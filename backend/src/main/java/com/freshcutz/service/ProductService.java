package com.freshcutz.service;

import com.freshcutz.dto.ProductListRow;
import com.freshcutz.dto.ProductRequest;
import com.freshcutz.dto.ProductResponse;
import com.freshcutz.entity.Product;
import com.freshcutz.exception.ResourceNotFoundException;
import com.freshcutz.repository.ProductRepository;
import com.freshcutz.util.ProductImageProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Product reads and edits.
 *
 * <p>Edits are small plain-SQL statements (a flag, a price, a position) instead of loading a product with its
 * photo bytes and writing the whole row back; and placing a product only looks at the products of ITS category
 * (see ProductOrdering), never at the whole catalog. A product's {@code updated_at} is only changed when its
 * photo changes — it is the photo's cache-buster in the image link.
 */
@Service
public class ProductService {

    /** A preview bigger than this is not stored (it only happens for a photo that could not be shrunk).
     * (Previews are normally ProductImageProcessor.THUMB_MAX_BYTES or less.) */
    private static final int MAX_STORED_THUMB_BYTES = 4096;

    private final ProductRepository productRepository;
    private final JdbcTemplate jdbc;
    private final ProductImageProcessor imageProcessor;

    public ProductService(ProductRepository productRepository, JdbcTemplate jdbc, ProductImageProcessor imageProcessor) {
        this.productRepository = productRepository;
        this.jdbc = jdbc;
        this.imageProcessor = imageProcessor;
    }

    /** Deliberately uses listViewForCustomers() (a lighter row that never
     * selects imageData), not findByEnabledTrueOrderByDisplayOrderAscIdAsc()
     * — see ProductListRow for why. Every customer page load (and every live
     * connection) asks for this, so the answer is kept in memory
     * (StorefrontCache) and only re-read after an admin changes a product.
     * No @Transactional on purpose: a cache hit must not open a database
     * connection, which would wake the Neon database. The query itself runs
     * fine without one. */
    public List<ProductResponse> listForCustomers() {
        return StorefrontCache.products(() -> productRepository.listViewForCustomers().stream()
                .map(ProductResponse::from)
                .collect(Collectors.toList()));
    }

    /** Every product (no photo bytes). The Products page no longer uses this — it asks AdminCatalogService for
     * one category at a time — but "Add photos" still needs the whole list to match photos to products. */
    @Transactional(readOnly = true)
    public List<ProductResponse> listForAdmin() {
        return productRepository.listViewForAdmin().stream()
                .map(ProductResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public Long create(ProductRequest request) {
        Product product = new Product();
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setCategory(clean(request.getCategory()));
        product.setVariant1Label(request.getVariant1Label());
        product.setVariant1Price(request.getVariant1Price());
        product.setVariant1Mrp(request.getVariant1Mrp());
        product.setVariant2Label(request.getVariant2Label());
        product.setVariant2Price(request.getVariant2Price());
        product.setVariant2Mrp(request.getVariant2Mrp());
        product.setEnabled(true);
        product.setOutOfStock(request.isOutOfStock());
        product.setDisplayOrder(nextOrder()); // goes last first; reposition() below moves it if a position was asked for
        Product saved = productRepository.saveAndFlush(product);
        // it was just put last (above); only a requested position needs the category's other products looked at
        if (request.getOverallPosition() > 0) repositionOverall(saved.getId(), request.getOverallPosition());
        else if (request.getDisplayOrder() > 0) reposition(saved.getId(), saved.getCategory(), request.getDisplayOrder());
        return saved.getId();
    }

    @Transactional
    public void update(Long id, ProductRequest request) {
        String category = clean(request.getCategory());
        int rows = jdbc.update("UPDATE products SET name = ?, description = ?, category = ?, "
                        + "variant1_label = ?, variant1_price = ?, variant1_mrp = ?, "
                        + "variant2_label = ?, variant2_price = ?, variant2_mrp = ?, "
                        + "enabled = ?, out_of_stock = ?, "
                        // moved to another category -> goes to the end of the whole list first (the old value of
                        // "category" is what the right-hand side sees); reposition() then places it
                        + "display_order = CASE WHEN COALESCE(TRIM(category), '') = ? THEN display_order "
                        + "ELSE (SELECT COALESCE(MAX(display_order), -1) + 1 FROM products) END "
                        + "WHERE id = ?",
                ps -> {
                    int i = 1;
                    i = str(ps, i, request.getName());
                    i = str(ps, i, request.getDescription());
                    i = str(ps, i, category);
                    i = str(ps, i, request.getVariant1Label());
                    i = num(ps, i, request.getVariant1Price());
                    i = num(ps, i, request.getVariant1Mrp());
                    i = str(ps, i, request.getVariant2Label());
                    i = num(ps, i, request.getVariant2Price());
                    i = num(ps, i, request.getVariant2Mrp());
                    ps.setBoolean(i++, request.isEnabled());
                    ps.setBoolean(i++, request.isOutOfStock());
                    ps.setString(i++, category == null ? "" : category);
                    ps.setLong(i, id);
                });
        if (rows == 0) throw new ResourceNotFoundException("Product " + id + " not found");

        // an overall position wins over the position inside the category (the form only lets one of them be filled)
        if (request.getOverallPosition() > 0) repositionOverall(id, request.getOverallPosition());
        else reposition(id, category, request.getDisplayOrder());
        StorefrontCache.productChanged(null); // list changed; the photo did not
    }

    /** Places the product at {@code position} (1 = first, 0 / empty = last) among the products of its category.
     * Only that category's rows are read, and only rows whose number really changes are written. */
    private void reposition(Long id, String category, Integer position) {
        List<ProductOrdering.Member> members = jdbc.query(
                "SELECT id, display_order FROM products WHERE COALESCE(TRIM(category), '') = ? ORDER BY display_order, id",
                (rs, i) -> new ProductOrdering.Member(rs.getLong(1), rs.getInt(2)),
                category == null ? "" : category);
        apply(ProductOrdering.plan(members, id, position));
    }

    /** Places the product at {@code position} (1 = first of ALL products) in the whole list, whatever its category.
     * The shop shows products (and orders its category chips) by this number, so position 1 puts the product first
     * on the customer site even when its category used to be last. Everything else keeps its relative order. */
    private void repositionOverall(Long id, int position) {
        List<ProductOrdering.Member> members = jdbc.query(
                "SELECT id, display_order FROM products ORDER BY display_order, id",
                (rs, i) -> new ProductOrdering.Member(rs.getLong(1), rs.getInt(2)));
        apply(ProductOrdering.plan(members, id, position));
    }

    private void apply(ProductOrdering.Plan plan) {
        write(plan.changes());
    }

    private void write(Map<Long, Integer> changes) {
        if (changes.isEmpty()) return;
        List<Object[]> batch = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : changes.entrySet()) {
            batch.add(new Object[]{e.getValue(), e.getKey()});
        }
        jdbc.batchUpdate("UPDATE products SET display_order = ? WHERE id = ?", batch);
    }

    /** Moves a whole category to {@code position} (1 = first, right after "All" on the shop). Returns false when
     * nothing needed to change. Categories are ordered by their earliest product, so this renumbers the products. */
    @Transactional
    public boolean moveCategory(String name, int position) {
        String key = name == null ? "" : name.trim();
        List<ProductOrdering.Row> rows = jdbc.query(
                "SELECT id, COALESCE(TRIM(category), ''), display_order FROM products ORDER BY display_order, id",
                (rs, i) -> new ProductOrdering.Row(rs.getLong(1), rs.getString(2), rs.getInt(3)));
        Map<Long, Integer> changes = ProductOrdering.planCategories(rows, key, position);
        if (changes.isEmpty()) return false;
        write(changes);
        StorefrontCache.productChanged(null); // list order changed; no photo did
        return true;
    }

    @Transactional
    public void setEnabled(Long id, boolean enabled) {
        setFlag("enabled", id, enabled);
    }

    @Transactional
    public void setOutOfStock(Long id, boolean outOfStock) {
        setFlag("out_of_stock", id, outOfStock);
    }

    /** {@code column} is always one of two fixed names from the callers above, never user input. */
    private void setFlag(String column, Long id, boolean value) {
        int rows = jdbc.update("UPDATE products SET " + column + " = ? WHERE id = ?", value, id);
        if (rows == 0) throw new ResourceNotFoundException("Product " + id + " not found");
        StorefrontCache.productChanged(null);
    }

    /** Saves a new photo together with its tiny admin preview (about 1 KB), in one statement. */
    @Transactional
    public ProductResponse updateImage(Long id, byte[] imageData, String contentType) {
        byte[] thumb = makeThumb(imageData, contentType);
        int rows = jdbc.update("UPDATE products SET image_data = ?, image_content_type = ?, preview_data = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                ps -> {
                    ps.setBytes(1, imageData);
                    ps.setString(2, contentType);
                    if (thumb == null) ps.setNull(3, Types.BINARY); else ps.setBytes(3, thumb);
                    ps.setLong(4, id);
                });
        if (rows == 0) throw new ResourceNotFoundException("Product " + id + " not found");
        StorefrontCache.productChanged(id); // photo changed: drop the cached photo + thumbnail too
        return fetchRow(id);
    }

    /** The tiny preview for a photo, or null when the photo can't be decoded (then the admin page just shows the photo itself). */
    private byte[] makeThumb(byte[] photo, String contentType) {
        ProductImageProcessor.ProcessedImage small = imageProcessor.thumbnail(photo, contentType);
        return small.data().length <= MAX_STORED_THUMB_BYTES ? small.data() : null;
    }

    /** A product's photo for ProductController's image endpoint, served from
     * memory when it was viewed recently (see StorefrontCache). Returns null
     * when the product has no photo. No @Transactional on purpose, same
     * reason as listForCustomers(): a cache hit must not touch the database. */
    public StorefrontCache.ProductImage getImage(Long id) {
        return StorefrontCache.image(id, () -> readImage(id));
    }

    /** The tiny admin preview of a product's photo (about 1 KB). Read from its own database column, so the
     * full photo is not touched; kept in memory afterwards. A photo uploaded before this existed — or one whose
     * preview is bigger than the current limit — gets a fresh preview made once from the photo and saved, never
     * again. Returns null when the product has no photo. */
    public StorefrontCache.ProductImage getThumb(Long id) {
        return StorefrontCache.thumb(id, () -> {
            List<byte[]> stored = jdbc.query("SELECT preview_data FROM products WHERE id = ?", (rs, i) -> rs.getBytes(1), id);
            if (!stored.isEmpty() && stored.get(0) != null && stored.get(0).length <= ProductImageProcessor.THUMB_MAX_BYTES) {
                return new StorefrontCache.ProductImage(stored.get(0), "image/jpeg");
            }
            StorefrontCache.ProductImage full = StorefrontCache.peekImage(id);
            if (full == null) full = readImage(id);
            if (full == null) return null;
            byte[] thumb = makeThumb(full.data(), full.contentType());
            if (thumb == null) return full; // can't be shrunk (e.g. WEBP): show the photo itself
            // never overwrites a small preview that a photo upload saved in the meantime
            jdbc.update("UPDATE products SET preview_data = ? WHERE id = ? AND (preview_data IS NULL OR OCTET_LENGTH(preview_data) > ?)",
                    thumb, id, ProductImageProcessor.THUMB_MAX_BYTES);
            return new StorefrontCache.ProductImage(thumb, "image/jpeg");
        });
    }

    private StorefrontCache.ProductImage readImage(Long id) {
        List<StorefrontCache.ProductImage> rows = jdbc.query(
                "SELECT image_data, image_content_type FROM products WHERE id = ?",
                (rs, i) -> {
                    byte[] data = rs.getBytes(1);
                    return data == null ? null : new StorefrontCache.ProductImage(data, rs.getString(2));
                }, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Public accessor for ProductController's image-streaming endpoint —
     * getEntity() below is package-private on purpose (everywhere else
     * that needs a raw Product entity is inside this package). */
    @Transactional(readOnly = true)
    public Product getEntityForImage(Long id) {
        return getEntity(id);
    }

    @Transactional
    public void delete(Long id) {
        int rows = jdbc.update("DELETE FROM products WHERE id = ?", id);
        if (rows == 0) throw new ResourceNotFoundException("Product " + id + " not found");
        StorefrontCache.productChanged(id);
    }

    Product getEntity(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product " + id + " not found"));
    }

    // ---- helpers -------------------------------------------------------

    private ProductResponse fetchRow(Long id) {
        List<ProductListRow> rows = jdbc.query("SELECT " + ProductRows.COLUMNS + " FROM products WHERE id = ?",
                ProductRows.MAPPER, id);
        if (rows.isEmpty()) throw new ResourceNotFoundException("Product " + id + " not found");
        return ProductResponse.from(rows.get(0));
    }

    private int nextOrder() {
        Integer next = jdbc.queryForObject("SELECT COALESCE(MAX(display_order), -1) + 1 FROM products", Integer.class);
        return next == null ? 0 : next;
    }

    /** Trimmed; blank becomes null (= "no category"). */
    private static String clean(String category) {
        if (category == null) return null;
        String t = category.trim();
        return t.isEmpty() ? null : t;
    }

    private static int str(PreparedStatement ps, int i, String v) throws SQLException {
        if (v == null) ps.setNull(i, Types.VARCHAR); else ps.setString(i, v);
        return i + 1;
    }

    private static int num(PreparedStatement ps, int i, BigDecimal v) throws SQLException {
        if (v == null) ps.setNull(i, Types.NUMERIC); else ps.setBigDecimal(i, v);
        return i + 1;
    }
}
