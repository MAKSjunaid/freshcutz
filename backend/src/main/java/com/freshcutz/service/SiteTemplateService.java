package com.freshcutz.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freshcutz.dto.SiteConfig;
import com.freshcutz.dto.SiteResponse;
import com.freshcutz.dto.TemplateDetail;
import com.freshcutz.dto.TemplateInfo;
import com.freshcutz.entity.SiteTemplate;
import com.freshcutz.exception.BadRequestException;
import com.freshcutz.repository.SiteTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Up to four saved designs of the customer website. Editing or saving a
 * template never touches the live website — unless that template is the
 * primary one, or the admin makes it primary. The live website itself stays
 * in SiteCustomizationService exactly as before, so the storefront needs no
 * changes. */
@Service
public class SiteTemplateService {

    public static final int SLOTS = 4;
    private static final int MAX_NAME = 30;

    private final SiteTemplateRepository repository;
    private final SiteCustomizationService site;
    private final ObjectMapper mapper;

    public SiteTemplateService(SiteTemplateRepository repository, SiteCustomizationService site, ObjectMapper mapper) {
        this.repository = repository;
        this.site = site;
        this.mapper = mapper;
    }

    /** What a save / make-primary did: the template, and the new live website
     * if it changed (so the controller can push it to open storefronts). */
    public static class Result {
        public final TemplateDetail template;
        public final SiteResponse live;

        Result(TemplateDetail template, SiteResponse live) {
            this.template = template;
            this.live = live;
        }
    }

    @Transactional
    public List<TemplateInfo> list() {
        seedIfEmpty();
        Map<Integer, SiteTemplate> bySlot = new HashMap<>();
        for (SiteTemplate t : repository.findAll()) bySlot.put(t.getSlot(), t);
        List<TemplateInfo> out = new ArrayList<>();
        for (int slot = 1; slot <= SLOTS; slot++) {
            SiteTemplate t = bySlot.get(slot);
            if (t == null) {
                out.add(new TemplateInfo(slot, defaultName(slot), false, false, null));
            } else {
                out.add(new TemplateInfo(slot, t.getName(), true, t.isPrimary(), t.getUpdatedAt() == null ? null : t.getUpdatedAt().toString()));
            }
        }
        return out;
    }

    @Transactional
    public TemplateDetail get(int slot) {
        checkSlot(slot);
        seedIfEmpty();
        return detail(slot, repository.findById(slot).orElse(null));
    }

    /** The original look, as a starting point for "reset this template". */
    public SiteConfig defaults() {
        return site.normalize(new SiteConfig());
    }

    @Transactional
    public Result save(int slot, String name, SiteConfig config) {
        checkSlot(slot);
        seedIfEmpty();
        SiteConfig clean = site.normalize(config == null ? new SiteConfig() : config);
        SiteTemplate t = repository.findById(slot).orElseGet(() -> {
            SiteTemplate created = new SiteTemplate();
            created.setSlot(slot);
            return created;
        });
        t.setName(cleanName(name, slot));
        t.setConfigJson(write(clean));
        t.setUpdatedAt(Instant.now());
        repository.save(t);
        // the primary template IS the live website, so saving it publishes it
        SiteResponse live = t.isPrimary() ? site.save(clean) : null;
        return new Result(detail(slot, t), live);
    }

    @Transactional
    public Result makePrimary(int slot) {
        checkSlot(slot);
        seedIfEmpty();
        SiteTemplate chosen = repository.findById(slot).orElseThrow(() -> new BadRequestException("Save this template first, then make it live"));
        for (SiteTemplate t : repository.findAll()) {
            boolean should = t.getSlot() == slot;
            if (t.isPrimary() != should) {
                t.setPrimary(should);
                repository.save(t);
            }
        }
        SiteResponse live = site.save(read(chosen.getConfigJson()));
        return new Result(detail(slot, chosen), live);
    }

    @Transactional
    public List<TemplateInfo> clear(int slot) {
        checkSlot(slot);
        seedIfEmpty();
        SiteTemplate t = repository.findById(slot).orElse(null);
        if (t != null) {
            if (t.isPrimary()) {
                throw new BadRequestException("This is the design customers see — make another template live first");
            }
            repository.delete(t);
        }
        return list();
    }

    // ---- internals -----------------------------------------------------

    /** First time ever: today's live design becomes Template 1 (and primary), so nothing changes for customers. */
    private void seedIfEmpty() {
        if (repository.count() > 0) return;
        SiteTemplate first = new SiteTemplate();
        first.setSlot(1);
        first.setName(defaultName(1));
        first.setPrimary(true);
        first.setConfigJson(write(site.get().getConfig()));
        first.setUpdatedAt(Instant.now());
        repository.save(first);
    }

    private TemplateDetail detail(int slot, SiteTemplate t) {
        SiteResponse live = site.get();
        if (t == null) {
            return new TemplateDetail(slot, defaultName(slot), false, false, live.getConfig(), live.getLogoUrl());
        }
        return new TemplateDetail(slot, t.getName(), true, t.isPrimary(), read(t.getConfigJson()), live.getLogoUrl());
    }

    private void checkSlot(int slot) {
        if (slot < 1 || slot > SLOTS) throw new BadRequestException("Templates are numbered 1 to " + SLOTS);
    }

    private static String defaultName(int slot) {
        return "Template " + slot;
    }

    private static String cleanName(String name, int slot) {
        String n = name == null ? "" : name.replaceAll("[\\p{Cntrl}]", " ").trim().replaceAll("\\s+", " ");
        if (n.length() > MAX_NAME) n = n.substring(0, MAX_NAME).trim();
        return n.isEmpty() ? defaultName(slot) : n;
    }

    private String write(SiteConfig config) {
        try {
            return mapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new BadRequestException("Could not save that template");
        }
    }

    private SiteConfig read(String json) {
        try {
            return mapper.readValue(json, SiteConfig.class);
        } catch (Exception e) {
            return new SiteConfig(); // unreadable copy: fall back to the original look
        }
    }
}
