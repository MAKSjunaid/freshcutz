package com.freshcutz.config;

import com.freshcutz.entity.Product;
import com.freshcutz.repository.ProductRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** ONE-TIME tool: copies your real local products (prices, descriptions,
 * every edit you made, and your actual uploaded photos) out of the local
 * H2 file and into whatever database this app is currently pointed at
 * (e.g. Neon), REPLACING every product already there.
 *
 * Why this exists: a brand new Neon database starts empty, so the normal
 * first-run behaviour (DataSeeder) fills it with generic starter products
 * and placeholder photos — not the real catalog you already built and
 * tested locally. This runner does the real copy instead, once.
 *
 * Off by default — does nothing unless you explicitly turn it on for one
 * run: set freshcutz.sync-local-to-remote=true as an environment variable
 * (do NOT put it in application.properties, and turn it back off / unset
 * it after this one run — running it again later would wipe out any real
 * orders or product changes made directly on the live database since). */
@Slf4j
@Component
@Order(-10)
public class LocalToRemoteSyncRunner implements CommandLineRunner {

    private final ProductRepository productRepository;
    private final boolean enabled;
    private final String datasourceUrl;
    private final List<Path> dbCandidates;
    private final List<Path> uploadsCandidates;

    public LocalToRemoteSyncRunner(ProductRepository productRepository,
                                    @Value("${freshcutz.sync-local-to-remote:false}") boolean enabled,
                                    @Value("${spring.datasource.url}") String datasourceUrl,
                                    @Value("${freshcutz.uploads.dir}") String uploadsDir) {
        this.productRepository = productRepository;
        this.enabled = enabled;
        this.datasourceUrl = datasourceUrl;

        Set<Path> dbDirs = new LinkedHashSet<>();
        dbDirs.add(Paths.get("data").toAbsolutePath().normalize());
        dbDirs.add(Paths.get("..", "data").toAbsolutePath().normalize());
        dbDirs.add(Paths.get("backend", "data").toAbsolutePath().normalize());
        this.dbCandidates = dbDirs.stream().toList();

        Path configuredUploads = Paths.get(uploadsDir).toAbsolutePath().normalize();
        Set<Path> uploadDirs = new LinkedHashSet<>();
        uploadDirs.add(configuredUploads);
        uploadDirs.add(configuredUploads.resolveSibling(Paths.get("..", "uploads", "products")).normalize());
        uploadDirs.add(Paths.get("uploads", "products").toAbsolutePath().normalize());
        uploadDirs.add(Paths.get("backend", "uploads", "products").toAbsolutePath().normalize());
        this.uploadsCandidates = uploadDirs.stream().toList();
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!enabled) return;
        if (datasourceUrl.startsWith("jdbc:h2:")) {
            log.warn("freshcutz.sync-local-to-remote is on, but this app is currently pointed at H2 " +
                    "itself — refusing to run (there'd be nothing meaningful to copy). Point this app at " +
                    "your real target database (e.g. Neon) first.");
            return;
        }

        Path h2Dir = dbCandidates.stream().filter(Files::isDirectory).findFirst().orElse(null);
        if (h2Dir == null) {
            log.warn("Couldn't find your local H2 database folder in any of: {} — nothing synced.", dbCandidates);
            return;
        }
        String h2Url = "jdbc:h2:file:" + h2Dir.resolve("freshcutz") + ";AUTO_SERVER=TRUE";

        List<Product> localProducts = new ArrayList<>();
        try (Connection h2 = DriverManager.getConnection(h2Url, "sa", "");
             Statement st = h2.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM products ORDER BY display_order, id")) {

            while (rs.next()) {
                Product p = new Product();
                p.setName(rs.getString("name"));
                p.setDescription(rs.getString("description"));
                p.setCategory(rs.getString("category"));
                p.setVariant1Label(rs.getString("variant1_label"));
                p.setVariant1Price(bd(rs, "variant1_price"));
                p.setVariant1Mrp(bd(rs, "variant1_mrp"));
                p.setVariant2Label(rs.getString("variant2_label"));
                p.setVariant2Price(bd(rs, "variant2_price"));
                p.setVariant2Mrp(bd(rs, "variant2_mrp"));
                p.setEnabled(rs.getBoolean("enabled"));
                p.setOutOfStock(rs.getBoolean("out_of_stock"));
                p.setDisplayOrder(rs.getInt("display_order"));

                byte[] existingImageData = rs.getBytes("image_data");
                String imageUrl = rs.getString("image_url");
                if (existingImageData != null && existingImageData.length > 0) {
                    p.setImageData(existingImageData);
                    p.setImageContentType(rs.getString("image_content_type"));
                } else if (imageUrl != null && imageUrl.startsWith("/uploads/products/")) {
                    loadPhotoFromDisk(p, imageUrl.substring("/uploads/products/".length()));
                }
                localProducts.add(p);
            }
        } catch (Exception e) {
            log.error("Couldn't read your local H2 database at {} — nothing synced: {}", h2Url, e.getMessage());
            return;
        }

        if (localProducts.isEmpty()) {
            log.warn("Your local H2 database has no products — nothing synced.");
            return;
        }

        long existing = productRepository.count();
        productRepository.deleteAll();
        Instant now = Instant.now();
        for (Product p : localProducts) {
            p.setCreatedAt(now);
            p.setUpdatedAt(now);
            productRepository.save(p);
        }
        log.info("Synced {} product(s) from your local database into this database, replacing {} that were there.",
                localProducts.size(), existing);
    }

    private void loadPhotoFromDisk(Product p, String filename) {
        Path file = uploadsCandidates.stream()
                .map(dir -> dir.resolve(filename))
                .filter(Files::exists)
                .findFirst()
                .orElse(null);
        if (file == null) {
            log.warn("Couldn't find photo '{}' for product '{}' in any of: {} — it'll have no photo.",
                    filename, p.getName(), uploadsCandidates);
            return;
        }
        try {
            p.setImageData(Files.readAllBytes(file));
            String lower = filename.toLowerCase();
            p.setImageContentType(lower.endsWith(".svg") ? "image/svg+xml"
                    : lower.endsWith(".png") ? "image/png" : "image/jpeg");
        } catch (IOException e) {
            log.warn("Couldn't read photo '{}' for product '{}': {}", filename, p.getName(), e.getMessage());
        }
    }

    private BigDecimal bd(ResultSet rs, String column) throws java.sql.SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value; // null is fine — Product's variant2* fields are already nullable
    }
}
