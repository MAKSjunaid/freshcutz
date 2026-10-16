package com.freshcutz.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freshcutz.dto.SiteConfig;
import com.freshcutz.dto.SiteResponse;
import com.freshcutz.entity.SiteCustomization;
import com.freshcutz.exception.BadRequestException;
import com.freshcutz.repository.SiteCustomizationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Reads and writes the customer website's look (see SiteConfig). The
 * storefront asks for this on every page load, so the finished response is
 * kept in memory and only rebuilt when the admin saves something. */
@Service
public class SiteCustomizationService {

    private static final Long ROW_ID = 1L;
    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final int MAX_BADGES = 8;

    private final SiteCustomizationRepository repository;
    private final ObjectMapper mapper;

    private volatile SiteResponse cached;

    public SiteCustomizationService(SiteCustomizationRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    // No @Transactional on get()/getForLogo() on purpose: a cache hit must not
    // open a database connection (that would wake the Neon database). The
    // repository calls inside open their own short read-only transaction.
    public SiteResponse get() {
        SiteResponse hit = cached;
        if (hit != null) return hit;
        SiteResponse built = build(repository.findById(ROW_ID).orElse(null));
        cached = built;
        return built;
    }

    @Transactional
    public SiteResponse save(SiteConfig incoming) {
        SiteConfig clean = normalize(incoming);
        SiteCustomization row = getOrCreate();
        try {
            row.setConfigJson(mapper.writeValueAsString(clean));
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Could not save those settings");
        }
        row.setUpdatedAt(Instant.now());
        return remember(repository.save(row));
    }

    @Transactional
    public SiteResponse saveLogo(byte[] data, String contentType) {
        SiteCustomization row = getOrCreate();
        row.setLogoData(data);
        row.setLogoContentType(contentType);
        row.setLogoUpdatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        return remember(repository.save(row));
    }

    @Transactional
    public SiteResponse removeLogo() {
        SiteCustomization row = getOrCreate();
        row.setLogoData(null);
        row.setLogoContentType(null);
        row.setLogoUpdatedAt(null);
        row.setUpdatedAt(Instant.now());
        return remember(repository.save(row));
    }

    /** Back to the original look: default settings and the built-in logo. */
    @Transactional
    public SiteResponse reset() {
        SiteCustomization row = getOrCreate();
        row.setConfigJson(null);
        row.setLogoData(null);
        row.setLogoContentType(null);
        row.setLogoUpdatedAt(null);
        row.setUpdatedAt(Instant.now());
        return remember(repository.save(row));
    }

    public SiteCustomization getForLogo() {
        return StorefrontCache.logo(() -> repository.findById(ROW_ID).orElse(null));
    }

    // ---- internals -----------------------------------------------------

    private SiteCustomization getOrCreate() {
        return repository.findById(ROW_ID).orElseGet(() -> {
            SiteCustomization row = new SiteCustomization();
            row.setId(ROW_ID);
            return row;
        });
    }

    private SiteResponse remember(SiteCustomization saved) {
        StorefrontCache.logoChanged(); // the saved logo/row replaces the cached copy once committed
        SiteResponse built = build(saved);
        cached = built;
        return built;
    }

    private SiteResponse build(SiteCustomization row) {
        SiteConfig config = new SiteConfig();
        String logoUrl = null;
        if (row != null) {
            if (row.getConfigJson() != null && !row.getConfigJson().isBlank()) {
                try {
                    config = mapper.readValue(row.getConfigJson(), SiteConfig.class);
                } catch (Exception e) {
                    config = new SiteConfig(); // unreadable copy: show the original look rather than fail
                }
            }
            if (row.getLogoData() != null && row.getLogoUpdatedAt() != null) {
                logoUrl = "/api/store/site/logo?v=" + row.getLogoUpdatedAt().toEpochMilli();
            }
        }
        return new SiteResponse(config, logoUrl);
    }

    /** Trims and length-limits the text, checks every colour is #rrggbb,
     * and caps the badge list — so nothing odd can be saved and then break
     * the customer website. */
    public SiteConfig normalize(SiteConfig in) {
        if (in == null) throw new BadRequestException("No settings received");
        in.setBrandName(text(in.getBrandName(), "Brand name", 40, false));
        in.setTagline(text(in.getTagline(), "Tagline", 80, true));
        in.setHeroHeading(rawText(in.getHeroHeading(), "Hero heading", 60));
        in.setHeroHighlight(text(in.getHeroHighlight(), "Hero highlighted text", 40, true));
        in.setHeroText(text(in.getHeroText(), "Hero line", 200, true));
        in.setWhatsappNumber(whatsapp(in.getWhatsappNumber()));

        in.setPageTitle(text(in.getPageTitle(), "Browser tab title", 80, true));
        in.setAnnouncementText(text(in.getAnnouncementText(), "Announcement", 200, true));
        in.setHeroButtonText(text(in.getHeroButtonText(), "Hero button text", 30, true));
        in.setHeroAlign(choice(in.getHeroAlign(), "Hero alignment", "center", "left"));
        in.setSearchPlaceholder(text(in.getSearchPlaceholder(), "Search placeholder", 60, true));
        in.setAddToCartText(text(in.getAddToCartText(), "Add button text", 24, false));
        in.setOutOfStockText(text(in.getOutOfStockText(), "Out of stock text", 24, false));
        in.setEmptyProductsText(text(in.getEmptyProductsText(), "Empty catalog message", 160, true));
        in.setNoSearchResultsText(text(in.getNoSearchResultsText(), "No search results message", 160, true));
        in.setViewCartText(text(in.getViewCartText(), "View cart button text", 30, false));
        in.setCartTitle(text(in.getCartTitle(), "Cart title", 40, false));
        in.setCartEmptyText(text(in.getCartEmptyText(), "Empty cart message", 160, true));
        in.setCheckoutButtonText(text(in.getCheckoutButtonText(), "Checkout button text", 40, false));
        in.setPlaceOrderText(text(in.getPlaceOrderText(), "Place order button text", 30, false));
        in.setNotesLabel(text(in.getNotesLabel(), "Notes field label", 60, false));
        in.setSuccessTitle(text(in.getSuccessTitle(), "Order success title", 60, false));
        in.setSuccessMessage(text(in.getSuccessMessage(), "Order success message", 200, true));
        in.setOfflineBannerText(text(in.getOfflineBannerText(), "Offline banner text", 200, true));
        in.setOfflineSheetText(text(in.getOfflineSheetText(), "Offline message", 200, true));
        in.setFooterAbout(text(in.getFooterAbout(), "Footer about text", 300, true));
        in.setFooterAddress(text(in.getFooterAddress(), "Footer address", 200, true));
        in.setFooterPhone(phoneLine(in.getFooterPhone(), "Footer phone"));
        in.setFooterEmail(email(in.getFooterEmail()));
        in.setFooterHours(text(in.getFooterHours(), "Footer opening hours", 120, true));
        in.setInstagramUrl(url(in.getInstagramUrl(), "Instagram link"));
        in.setFacebookUrl(url(in.getFacebookUrl(), "Facebook link"));
        in.setFooterCopyright(text(in.getFooterCopyright(), "Footer copyright line", 120, true));
        in.setSizeLogo(size(in.getSizeLogo()));
        in.setSizeHeaderIcons(size(in.getSizeHeaderIcons()));
        in.setSizeBrandName(size(in.getSizeBrandName()));
        in.setSizeTagline(size(in.getSizeTagline()));
        in.setSizeHeroHeading(size(in.getSizeHeroHeading()));
        in.setSizeHeroText(size(in.getSizeHeroText()));
        in.setSizeBadges(size(in.getSizeBadges()));
        in.setSizeAnnouncement(size(in.getSizeAnnouncement()));
        in.setSizeCategories(size(in.getSizeCategories()));
        in.setSizeSearch(size(in.getSizeSearch()));
        in.setSizeProductImage(size(in.getSizeProductImage()));
        in.setSizeProductName(size(in.getSizeProductName()));
        in.setSizeProductDesc(size(in.getSizeProductDesc()));
        in.setSizePrice(size(in.getSizePrice()));
        in.setSizePhotoBadge(size(in.getSizePhotoBadge()));
        in.setSizeButtons(size(in.getSizeButtons()));
        in.setSizeCartBar(size(in.getSizeCartBar()));
        in.setSizeFooter(size(in.getSizeFooter()));
        in.setStyles(cleanStyles(in.getStyles()));
        in.setToastSeconds(Math.max(0.5, Math.min(60, in.getToastSeconds())));
        in.setToastPosition(choice(in.getToastPosition(), "Pop-up position", "auto", "top-left", "top-center", "top-right",
                "middle-center", "bottom-left", "bottom-center", "bottom-right"));
        in.setToastOffsetX(Math.max(-500, Math.min(500, in.getToastOffsetX())));
        in.setToastOffsetY(Math.max(-500, Math.min(500, in.getToastOffsetY())));
        in.setToastMax(Math.max(1, Math.min(10, in.getToastMax())));
        in.setMessages(cleanMessages(in.getMessages()));
        in.setFontStyle(choice(in.getFontStyle(), "Font style", "classic", "modern", "elegant"));
        in.setCornerStyle(choice(in.getCornerStyle(), "Corner style", "default", "sharp", "round"));

        List<SiteConfig.Badge> badges = new ArrayList<>();
        if (in.getBadges() != null) {
            for (SiteConfig.Badge b : in.getBadges()) {
                if (b == null) continue;
                String label = text(b.getText(), "Badge text", 40, true);
                if (label.isEmpty()) continue; // an empty badge is just dropped
                String icon = text(b.getIcon(), "Badge icon", 12, true);
                badges.add(new SiteConfig.Badge(icon, label, b.isEnabled()));
            }
        }
        if (badges.size() > MAX_BADGES) {
            throw new BadRequestException("At most " + MAX_BADGES + " badges are allowed");
        }
        in.setBadges(badges);

        in.setHeaderBg(color(in.getHeaderBg(), "Header background"));
        in.setBrandNameColor(color(in.getBrandNameColor(), "Brand name color"));
        in.setTaglineColor(color(in.getTaglineColor(), "Tagline color"));
        in.setHeroBgStart(color(in.getHeroBgStart(), "Hero background (start)"));
        in.setHeroBgEnd(color(in.getHeroBgEnd(), "Hero background (end)"));
        in.setHeroHeadingColor(color(in.getHeroHeadingColor(), "Hero heading color"));
        in.setHeroHighlightColor(color(in.getHeroHighlightColor(), "Hero highlight color"));
        in.setHeroTextColor(color(in.getHeroTextColor(), "Hero text color"));
        in.setBadgeBg(color(in.getBadgeBg(), "Badge background"));
        in.setBadgeText(color(in.getBadgeText(), "Badge text color"));
        in.setAccentColor(color(in.getAccentColor(), "Accent color"));
        in.setPageBg(color(in.getPageBg(), "Page background"));
        in.setTextColor(color(in.getTextColor(), "Text color"));
        in.setCardBg(color(in.getCardBg(), "Card background"));
        in.setPriceColor(color(in.getPriceColor(), "Price color"));
        in.setCategoryActiveBg(color(in.getCategoryActiveBg(), "Selected category color"));
        in.setCartBarBg(color(in.getCartBarBg(), "Cart bar color"));
        in.setAnnouncementBg(color(in.getAnnouncementBg(), "Announcement background"));
        in.setAnnouncementColor(color(in.getAnnouncementColor(), "Announcement text color"));
        in.setFooterBg(color(in.getFooterBg(), "Footer background"));
        in.setFooterTextColor(color(in.getFooterTextColor(), "Footer text color"));
        return in;
    }

    private String text(String value, String label, int max, boolean allowBlank) {
        String v = value == null ? "" : value.trim();
        if (!allowBlank && v.isEmpty()) throw new BadRequestException(label + " can't be empty");
        if (v.length() > max) throw new BadRequestException(label + " is too long (max " + max + " characters)");
        return v;
    }

    /** Same as text() but keeps surrounding spaces — the hero heading's
     * trailing space is what separates it from the highlighted part. */
    private String rawText(String value, String label, int max) {
        String v = value == null ? "" : value;
        if (v.length() > max) throw new BadRequestException(label + " is too long (max " + max + " characters)");
        return v;
    }

    /** Blank is allowed (the site then falls back to its built-in number).
     * Otherwise it must be a phone number with country code: only digits,
     * spaces, dashes, brackets and an optional leading +, 8 to 15 digits. */
    private String whatsapp(String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) return "";
        if (!v.matches("^\\+?[0-9 ()\\-]{6,24}$")) {
            throw new BadRequestException("WhatsApp number can only contain digits, spaces and an optional leading +");
        }
        int digits = v.replaceAll("\\D", "").length();
        if (digits < 8 || digits > 15) {
            throw new BadRequestException("WhatsApp number must include the country code, e.g. +91 98765 43210");
        }
        return v;
    }

    /** Sizes are percentages of the original; out-of-range values are pulled
     * back into 10 to 1000 instead of failing the save. */
    private int size(int value) {
        return Math.max(10, Math.min(1000, value));
    }

    // ---- per-element fine-tuning ------------------------------------------------------
    private static final int MAX_STYLED_ELEMENTS = 150;
    private static final Pattern ELEMENT_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9]{0,39}$");
    private static final Pattern LENGTH_TOKEN = Pattern.compile("^(auto|0|-?\\d{1,4}(\\.\\d{1,2})?(px|%|rem|em|vw|vh)?)$");
    private static final Pattern DECIMAL = Pattern.compile("^\\d{1,3}(\\.\\d{1,2})?$");
    private static final Set<String> COLOR_PROPS = Set.of("color", "background", "borderColor");
    private static final Set<String> LENGTH_PROPS = Set.of("fontSize", "letterSpacing", "width", "height", "minHeight",
            "maxWidth", "borderRadius", "borderWidth", "gap", "moveX", "moveY");
    private static final Set<String> BOX_PROPS = Set.of("padding", "margin");
    private static final Map<String, Set<String>> CHOICE_PROPS = Map.of(
            "fontWeight", Set.of("400", "500", "600", "700", "800", "900"),
            "textAlign", Set.of("left", "center", "right"),
            "boxShadow", Set.of("none", "soft", "medium", "strong"),
            "objectFit", Set.of("cover", "contain", "fill"),
            "animation", Set.of("none", "fadeIn", "slideUp", "slideDown", "zoomIn", "pulse", "bounce", "shake", "glow", "float"),
            "scope", Set.of("all", "phone", "desktop"),
            "justify", Set.of("start", "center", "end", "between", "around"),
            "hidden", Set.of("1"));

    /** Keeps only well-formed settings. Anything unknown or malformed is dropped
     * (never turned into CSS), so a saved value can't carry markup or stray CSS. */
    private Map<String, Map<String, String>> cleanStyles(Map<String, Map<String, String>> in) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (in == null) return out;
        for (Map.Entry<String, Map<String, String>> e : in.entrySet()) {
            if (out.size() >= MAX_STYLED_ELEMENTS) break;
            if (e.getKey() == null || !ELEMENT_KEY.matcher(e.getKey()).matches() || e.getValue() == null) continue;
            Map<String, String> props = new LinkedHashMap<>();
            for (Map.Entry<String, String> p : e.getValue().entrySet()) {
                String v = styleValue(p.getKey(), p.getValue());
                if (v != null) props.put(p.getKey(), v);
            }
            if (!props.isEmpty()) out.put(e.getKey(), props);
        }
        return out;
    }

    // ---- pop-up messages and editable texts ---------------------------------------------------
    private static final int MAX_MESSAGES = 120;
    private static final Set<String> TONES = Set.of("success", "error", "info");

    /** Per-message overrides: text, enabled (only "0" is kept), seconds, tone. */
    private Map<String, Map<String, String>> cleanMessages(Map<String, Map<String, String>> in) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (in == null) return out;
        for (Map.Entry<String, Map<String, String>> e : in.entrySet()) {
            if (out.size() >= MAX_MESSAGES) break;
            if (e.getKey() == null || !ELEMENT_KEY.matcher(e.getKey()).matches() || e.getValue() == null) continue;
            Map<String, String> m = e.getValue();
            Map<String, String> clean = new LinkedHashMap<>();
            String text = m.get("text") == null ? "" : m.get("text").trim();
            if (!text.isEmpty()) {
                if (text.length() > 200) throw new BadRequestException("A message is too long (max 200 characters)");
                clean.put("text", text);
            }
            if ("0".equals(m.get("enabled"))) clean.put("enabled", "0");
            String secs = m.get("seconds") == null ? "" : m.get("seconds").trim();
            if (DECIMAL.matcher(secs).matches()) {
                double d = Double.parseDouble(secs);
                if (d >= 0.5 && d <= 60) clean.put("seconds", secs);
            }
            String tone = m.get("tone") == null ? "" : m.get("tone").trim();
            if (TONES.contains(tone)) clean.put("tone", tone);
            if (!clean.isEmpty()) out.put(e.getKey(), clean);
        }
        return out;
    }

    /** The cleaned value, or null when it should be dropped. */
    private String styleValue(String prop, String raw) {
        if (prop == null || raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty() || v.length() > 60) return null;
        if (COLOR_PROPS.contains(prop)) {
            if (v.equalsIgnoreCase("transparent")) return "transparent";
            return HEX_COLOR.matcher(v).matches() ? v.toLowerCase() : null;
        }
        if (LENGTH_PROPS.contains(prop)) {
            return LENGTH_TOKEN.matcher(v).matches() ? v : null;
        }
        if (BOX_PROPS.contains(prop)) {
            String[] parts = v.split("\\s+");
            if (parts.length > 4) return null;
            for (String part : parts) {
                if (!LENGTH_TOKEN.matcher(part).matches()) return null;
            }
            return String.join(" ", parts);
        }
        switch (prop) {
            case "lineHeight":
            case "animationSpeed":
                return DECIMAL.matcher(v).matches() ? v : null;
            case "zoom":
                return DECIMAL.matcher(v).matches() && Double.parseDouble(v) >= 10 && Double.parseDouble(v) <= 500 ? v : null;
            case "opacity":
                return v.matches("^\\d{1,3}$") && Integer.parseInt(v) <= 100 ? v : null;
            default:
                Set<String> allowed = CHOICE_PROPS.get(prop);
                return allowed != null && allowed.contains(v) ? v : null;
        }
    }

    /** One of the allowed keywords; anything else falls back to the first
     * (original) one rather than failing the whole save. */
    private String choice(String value, String label, String... allowed) {
        String v = value == null ? "" : value.trim().toLowerCase();
        for (String a : allowed) {
            if (a.equals(v)) return a;
        }
        return allowed[0];
    }

    /** Only http(s) links, so a saved value can never be a javascript: URL. */
    private String url(String value, String label) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) return "";
        if (v.length() > 200) throw new BadRequestException(label + " is too long (max 200 characters)");
        String lower = v.toLowerCase();
        if (!(lower.startsWith("https://") || lower.startsWith("http://")) || v.matches(".*[\\s<>\"].*")) {
            throw new BadRequestException(label + " must be a full web address starting with https://");
        }
        return v;
    }

    private String phoneLine(String value, String label) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) return "";
        if (!v.matches("^\\+?[0-9 ()\\-]{6,24}$")) {
            throw new BadRequestException(label + " can only contain digits, spaces and an optional leading +");
        }
        return v;
    }

    private String email(String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) return "";
        if (v.length() > 100 || !v.matches("^[^@\\s<>\"]+@[^@\\s<>\"]+\\.[^@\\s<>\"]+$")) {
            throw new BadRequestException("Footer email doesn't look like an email address");
        }
        return v;
    }

    private String color(String value, String label) {
        if (value == null || !HEX_COLOR.matcher(value.trim()).matches()) {
            throw new BadRequestException(label + " must be a color like #c9a13b");
        }
        return value.trim().toLowerCase();
    }
}
