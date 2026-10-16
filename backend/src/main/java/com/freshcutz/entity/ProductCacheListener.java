package com.freshcutz.entity;

import com.freshcutz.service.StorefrontCache;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;

/** Tells the storefront cache that a product (or its photo) was created,
 * changed or removed, so customers see the change on their next request. */
public class ProductCacheListener {

    @PostPersist
    @PostUpdate
    @PostRemove
    public void productChanged(Product product) {
        StorefrontCache.productChanged(product.getId());
    }
}
