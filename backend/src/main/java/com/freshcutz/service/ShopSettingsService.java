package com.freshcutz.service;

import com.freshcutz.entity.ShopSetting;
import com.freshcutz.exception.BadRequestException;
import com.freshcutz.repository.ShopSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The category tab the customer website opens on ("" = the normal "All" tab).
 *
 * <p>Read on every page load of the shop, so the value is kept in memory after the first read and only
 * replaced when the admin saves a new one. No @Transactional on the read: a cache hit must not open a database
 * connection (that would wake Neon).
 */
@Service
public class ShopSettingsService {

    private static final String DEFAULT_CATEGORY = "default_category";
    private static final int MAX_LENGTH = 100;

    private final ShopSettingRepository repository;
    private volatile String defaultCategory; // null = not read yet

    public ShopSettingsService(ShopSettingRepository repository) {
        this.repository = repository;
    }

    /** "" when no default is set (the shop then opens on "All"). */
    public String getDefaultCategory() {
        String hit = defaultCategory;
        if (hit != null) return hit;
        String loaded = repository.findById(DEFAULT_CATEGORY).map(ShopSetting::getValue).orElse("");
        loaded = loaded == null ? "" : loaded;
        defaultCategory = loaded;
        return loaded;
    }

    @Transactional
    public String setDefaultCategory(String category) {
        String clean = category == null ? "" : category.trim();
        if (clean.length() > MAX_LENGTH) throw new BadRequestException("Category name is too long");
        repository.save(new ShopSetting(DEFAULT_CATEGORY, clean));
        defaultCategory = clean;
        return clean;
    }
}
