package com.freshcutz.service;

import com.freshcutz.entity.StoreSettings;
import com.freshcutz.repository.StoreSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Whether the shop is currently accepting orders. Backed by a single-row
 * table (created on first use) rather than an in-memory flag, so the
 * setting survives a backend restart. */
@Service
public class StoreSettingsService {

    private static final Long SETTINGS_ID = 1L;

    private final StoreSettingsRepository repository;

    public StoreSettingsService(StoreSettingsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public boolean isOnline() {
        return getOrCreate().isOnline();
    }

    @Transactional
    public boolean setOnline(boolean online) {
        StoreSettings settings = getOrCreate();
        settings.setOnline(online);
        return repository.save(settings).isOnline();
    }

    private StoreSettings getOrCreate() {
        return repository.findById(SETTINGS_ID).orElseGet(() -> {
            StoreSettings settings = new StoreSettings();
            settings.setId(SETTINGS_ID);
            settings.setOnline(true);
            return repository.save(settings);
        });
    }
}
