package com.freshcutz.service;

import com.freshcutz.dto.ProductRequest;
import com.freshcutz.dto.ProductResponse;
import com.freshcutz.entity.Product;
import com.freshcutz.exception.ResourceNotFoundException;
import com.freshcutz.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
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

    /** Same reasoning as listForCustomers() above — the admin Products tab
     * shows the same images/prices and hits the same query shape. */
    @Transactional(readOnly = true)
    public List<ProductResponse> listForAdmin() {
        return productRepository.listViewForAdmin().stream()
                .map(ProductResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public ProductResponse create(ProductRequest request) {
        Product product = new Product();
        applyRequest(product, request);
        product.setEnabled(true);
        Product saved = productRepository.save(product);
        reposition(saved, request.getDisplayOrder());
        return ProductResponse.from(saved);
    }

    @Transactional
    public ProductResponse update(Long id, ProductRequest request) {
        Product product = getEntity(id);
        applyRequest(product, request);
        Product saved = productRepository.save(product);
        reposition(saved, request.getDisplayOrder());
        return ProductResponse.from(saved);
    }

    /** Implements "position 1 = shows first" semantics: the saved product is
     * inserted at the requested 1-based position among every other product,
     * and everyone else's displayOrder shifts to make room — instead of the
     * confusing raw-sort-key-with-ties behaviour of just storing the number
     * exactly as typed. */
    private void reposition(Product moved, Integer requestedPosition) {
        List<Product> others = productRepository.findAllByOrderByDisplayOrderAscIdAsc().stream()
                .filter(p -> !p.getId().equals(moved.getId()))
                .collect(Collectors.toList());

        int position = requestedPosition == null ? others.size() + 1 : requestedPosition;
        int index = position - 1;
        if (index < 0) index = 0;
        if (index > others.size()) index = others.size();

        List<Product> ordered = new ArrayList<>(others);
        ordered.add(index, moved);

        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setDisplayOrder(i);
        }
        productRepository.saveAll(ordered);
    }

    @Transactional
    public ProductResponse setEnabled(Long id, boolean enabled) {
        Product product = getEntity(id);
        product.setEnabled(enabled);
        return ProductResponse.from(productRepository.save(product));
    }

    @Transactional
    public ProductResponse setOutOfStock(Long id, boolean outOfStock) {
        Product product = getEntity(id);
        product.setOutOfStock(outOfStock);
        return ProductResponse.from(productRepository.save(product));
    }

    @Transactional
    public ProductResponse updateImage(Long id, byte[] imageData, String contentType) {
        Product product = getEntity(id);
        product.setImageData(imageData);
        product.setImageContentType(contentType);
        return ProductResponse.from(productRepository.save(product));
    }

    /** A product's photo for ProductController's image endpoint, served from
     * memory when it was viewed recently (see StorefrontCache). Returns null
     * when the product has no photo. No @Transactional on purpose, same
     * reason as listForCustomers(): a cache hit must not touch the database. */
    public StorefrontCache.ProductImage getImage(Long id) {
        return StorefrontCache.image(id, () -> {
            Product product = getEntity(id);
            if (product.getImageData() == null) return null;
            return new StorefrontCache.ProductImage(product.getImageData(), product.getImageContentType());
        });
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
        Product product = getEntity(id);
        productRepository.delete(product);
    }

    Product getEntity(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product " + id + " not found"));
    }

    private void applyRequest(Product product, ProductRequest request) {
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setCategory(request.getCategory());
        product.setVariant1Label(request.getVariant1Label());
        product.setVariant1Price(request.getVariant1Price());
        product.setVariant1Mrp(request.getVariant1Mrp());
        product.setVariant2Label(request.getVariant2Label());
        product.setVariant2Price(request.getVariant2Price());
        product.setVariant2Mrp(request.getVariant2Mrp());
        product.setEnabled(request.isEnabled());
        product.setOutOfStock(request.isOutOfStock());
    }
}