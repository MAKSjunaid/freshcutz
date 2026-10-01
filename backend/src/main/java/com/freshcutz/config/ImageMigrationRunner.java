package com.freshcutz.config;

import com.freshcutz.entity.Product;
import com.freshcutz.repository.ProductRepository;
import com.freshcutz.util.ProductImageProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** One-time cutover: FreshCutz used to store product photos as files on
 * disk (Product.imageUrl pointing at /uploads/products/...), which doesn't
 * survive a redeploy on a free host like Render. Every photo now lives
 * directly in the database instead (see Product.imageData).
 *
 * This copies each such product's file into the database, once — after
 * that imageData is set and this product is skipped forever. IMPORTANT:
 * run the backend locally, pointed at your real (e.g. Neon) database, at
 * least once BEFORE your first deploy — the old files only exist on your
 * own disk right now, not wherever you deploy to.
 *
 * It doesn't assume one fixed working directory for where the old files
 * live: run this app from different places (a terminal in backend/, an
 * IDE run button whose working directory is the project root, ...) and
 * freshcutz.uploads.dir's own "./uploads/products" resolves differently
 * each time. So for every legacy photo, this checks a short list of
 * likely folders — wherever it's configured to look, plus one level up
 * and one level down — and uses whichever one actually has the file. */
@Slf4j
@Component
public class ImageMigrationRunner implements CommandLineRunner {

    private final ProductRepository productRepository;
    private final ProductImageProcessor imageProcessor;
    private final List<Path> candidateDirs;

    public ImageMigrationRunner(ProductRepository productRepository,
                                 ProductImageProcessor imageProcessor,
                                 @Value("${freshcutz.uploads.dir}") String uploadsDir) {
        this.productRepository = productRepository;
        this.imageProcessor = imageProcessor;
        Path configured = Paths.get(uploadsDir).toAbsolutePath().normalize();
        Set<Path> candidates = new LinkedHashSet<>();
        candidates.add(configured);
        candidates.add(configured.resolveSibling(Paths.get("..", "uploads", "products")).normalize());
        candidates.add(Paths.get("uploads", "products").toAbsolutePath().normalize());
        candidates.add(Paths.get("backend", "uploads", "products").toAbsolutePath().normalize());
        this.candidateDirs = candidates.stream().toList();
    }

    @Override
    public void run(String... args) {
        List<Product> legacy = productRepository.findAll().stream()
                .filter(p -> p.getImageData() == null)
                .filter(p -> p.getImageUrl() != null && p.getImageUrl().startsWith("/uploads/products/"))
                .toList();
        if (legacy.isEmpty()) return;

        log.info("Looking for {} legacy product photo(s) in: {}", legacy.size(), candidateDirs);

        int migrated = 0;
        for (Product product : legacy) {
            String filename = product.getImageUrl().substring("/uploads/products/".length());
            Path file = candidateDirs.stream()
                    .map(dir -> dir.resolve(filename))
                    .filter(Files::exists)
                    .findFirst()
                    .orElse(null);
            if (file == null) {
                log.warn("Skipping product {} ('{}') — couldn't find its old photo '{}' in any of: {}",
                        product.getId(), product.getName(), filename, candidateDirs);
                continue;
            }
            try {
                byte[] raw = Files.readAllBytes(file);
                ProductImageProcessor.ProcessedImage processed = imageProcessor.compress(raw, contentTypeFor(filename));
                product.setImageData(processed.data());
                product.setImageContentType(processed.contentType());
                productRepository.save(product);
                migrated++;
                log.info("Migrated photo for product {} ('{}') from {}", product.getId(), product.getName(), file);
            } catch (IOException e) {
                log.warn("Couldn't migrate image for product {}: {}", product.getId(), e.getMessage());
            }
        }
        if (migrated > 0) {
            log.info("Migrated {} of {} product photo(s) from disk into the database", migrated, legacy.size());
        }
    }

    private String contentTypeFor(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        return "image/jpeg";
    }
}
