package com.freshcutz.entity;

import com.freshcutz.service.NewOrdersCountCache;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;

/** Tells the new-orders count cache that an order was created, changed or
 * removed, so the admin badge refreshes on its next poll. */
public class OrderCountListener {

    @PostPersist
    @PostUpdate
    @PostRemove
    public void orderChanged(Object order) {
        NewOrdersCountCache.invalidateAfterCommit();
    }
}
