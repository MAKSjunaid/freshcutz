package com.freshcutz.config;

import com.freshcutz.entity.Product;
import com.freshcutz.repository.ProductRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * Loads FreshCutz's current price list as starting products, but only the
 * very first time the app runs against an empty database. Feel free to
 * edit everything from the admin panel afterwards - this never runs again
 * once at least one product exists.
 *
 * Seed photos are bundled inside the jar itself (src/main/resources/
 * seed-images) and loaded straight into the database — not written to
 * disk — so they work identically on a fresh Render deploy with an empty
 * Neon database as they do locally, with no separate upload step needed.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private final ProductRepository productRepository;
    private final byte[] chickenImg;
    private final byte[] eggsImg;
    private final byte[] curryCutImg;
    private final byte[] breastImg;
    private final byte[] drumsticksImg;

    public DataSeeder(ProductRepository productRepository) throws IOException {
        this.productRepository = productRepository;
        this.chickenImg = readSeedImage("placeholder-chicken.svg");
        this.eggsImg = readSeedImage("placeholder-eggs.svg");
        this.curryCutImg = readSeedImage("seed-chicken-curry-cut.jpg");
        this.breastImg = readSeedImage("seed-chicken-breast.jpg");
        this.drumsticksImg = readSeedImage("seed-chicken-drumsticks.jpg");
    }

    private byte[] readSeedImage(String filename) throws IOException {
        return new ClassPathResource("seed-images/" + filename).getContentAsByteArray();
    }

    private String contentTypeFor(String filename) {
        return filename.endsWith(".svg") ? "image/svg+xml" : "image/jpeg";
    }

    @Override
    public void run(String... args) {
        if (productRepository.count() > 0) {
            return;
        }

        int order = 0;
        productRepository.save(chicken("Chicken Curry Cut",
                "Bone-in curry cut pieces, freshly cut to order.", "Chicken Cuts",
                curryCutImg, "seed-chicken-curry-cut.jpg",
                "500g", 150, 170, "1kg", 280, 340, order++));

        productRepository.save(chicken("Chicken Boneless Tikka",
                "Cubed boneless pieces, perfect for tikka and kebabs.", "Chicken Cuts",
                chickenImg, "placeholder-chicken.svg", "500g", 210, 230, "1kg", 400, 470, order++));

        productRepository.save(chicken("Chicken Breast",
                "Skinless boneless breast fillets.", "Chicken Cuts",
                breastImg, "seed-chicken-breast.jpg",
                "500g", 210, 230, "1kg", 400, 470, order++));

        productRepository.save(chicken("Chicken Drumsticks",
                "Juicy drumsticks, skin on.", "Chicken Cuts",
                drumsticksImg, "seed-chicken-drumsticks.jpg",
                "500g", 170, 190, "1kg", 320, 360, order++));

        productRepository.save(chicken("Chicken Whole Leg",
                "Whole leg piece, skin on.", "Chicken Cuts",
                chickenImg, "placeholder-chicken.svg", "500g", 170, 200, "1kg", 320, 360, order++));

        productRepository.save(chicken("Chicken Wings Lollipop",
                "Frenched wings, ready to fry or grill.", "Chicken Cuts",
                chickenImg, "placeholder-chicken.svg", "500g", 170, 200, "1kg", 320, 360, order++));

        productRepository.save(chicken("Chicken Liver",
                "Fresh chicken liver.", "Chicken Cuts",
                chickenImg, "placeholder-chicken.svg", "1kg", 150, 150, null, 0, 0, order++));

        productRepository.save(chicken("Chicken Keema",
                "Freshly minced chicken.", "Chicken Cuts",
                chickenImg, "placeholder-chicken.svg", "500g", 210, 230, "1kg", 400, 470, order++));

        productRepository.save(chicken("Chicken Thigh Boneless",
                "Boneless thigh, great for curries and grilling.", "Chicken Cuts",
                chickenImg, "placeholder-chicken.svg", "500g", 220, 240, "1kg", 420, 470, order++));

        productRepository.save(chicken("Farm Broiler Eggs",
                "Tray of 30 farm eggs.", "Eggs",
                eggsImg, "placeholder-eggs.svg", "Tray of 30", 230, 280, null, 0, 0, order++));

        productRepository.save(chicken("Farm Desi Eggs",
                "Tray of 30 desi eggs.", "Eggs",
                eggsImg, "placeholder-eggs.svg", "Tray of 30", 480, 600, null, 0, 0, order++));
    }

    /**
     * Builds one seed product. Pass v2Label = null (v2Price/v2Mrp ignored)
     * for a single-size item like eggs or liver.
     */
    private Product chicken(String name, String description, String category,
                             byte[] imageData, String imageFilename,
                             String v1Label, double v1Price, double v1Mrp,
                             String v2Label, double v2Price, double v2Mrp, int order) {
        Product.ProductBuilder builder = Product.builder()
                .name(name)
                .description(description)
                .category(category)
                .imageData(imageData)
                .imageContentType(contentTypeFor(imageFilename))
                .variant1Label(v1Label)
                .variant1Price(BigDecimal.valueOf(v1Price))
                .variant1Mrp(BigDecimal.valueOf(v1Mrp))
                .enabled(true)
                .displayOrder(order);

        if (v2Label != null) {
            builder.variant2Label(v2Label)
                    .variant2Price(BigDecimal.valueOf(v2Price))
                    .variant2Mrp(BigDecimal.valueOf(v2Mrp));
        }

        return builder.build();
    }
}
