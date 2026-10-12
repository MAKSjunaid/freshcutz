package com.freshcutz.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything the admin's Customize tab controls on the customer website.
 * The field initialisers ARE the shop's original look (what index.html used
 * to hard-code), so a brand-new database — or a field missing from an older
 * saved copy — falls back to exactly the site as it was before the Customize
 * tab existed. Stored as JSON (see SiteCustomization) and sent as-is to both
 * the storefront and the admin form. */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SiteConfig {

    // ---- Brand ---------------------------------------------------------
    private String brandName = "FRESHCUTZ";
    private String tagline = "Freshly Cut. Naturally Juicy.";
    private boolean showTagline = true;
    private boolean showLogo = true;

    // ---- Hero banner ---------------------------------------------------
    private boolean showHero = true;
    private String heroHeading = "Fresh Chicken, ";
    private String heroHighlight = "Cut to Order";
    private String heroText = "No frozen stock, no shortcuts — order before 10 PM for next morning delivery.";
    private boolean showHeroText = true;

    // ---- Badges under the hero text -------------------------------------
    private boolean showBadges = true;
    private List<Badge> badges = defaultBadges();

    // ---- Header buttons --------------------------------------------------
    private boolean showWhatsapp = true;
    /** Number the WhatsApp button opens, with country code. Blank = site default. */
    private String whatsappNumber = "+91 97048 90039";
    private boolean showAccount = true;

    // ---- Colors (#rrggbb) -----------------------------------------------
    private String headerBg = "#17140f";
    private String brandNameColor = "#f0d98a";
    private String taglineColor = "#c9bfa3";
    private String heroBgStart = "#2a2114";
    private String heroBgEnd = "#17140f";
    private String heroHeadingColor = "#ffffff";
    private String heroHighlightColor = "#f0d98a";
    private String heroTextColor = "#c9bfa3";
    private String badgeBg = "#c9a13b";
    private String badgeText = "#f0d98a";
    private String accentColor = "#c9a13b";
    private String pageBg = "#faf7f0";
    private String textColor = "#201c14";
    private String cardBg = "#ffffff";
    private String priceColor = "#17140f";
    private String categoryActiveBg = "#17140f";
    private String cartBarBg = "#17140f";
    private String announcementBg = "#c9a13b";
    private String announcementColor = "#17140f";
    private String footerBg = "#17140f";
    private String footerTextColor = "#c9bfa3";

    // ---- Browser tab ------------------------------------------------------
    /** Blank = built from the brand name and tagline. */
    private String pageTitle = "";

    // ---- Announcement bar (a thin strip under the header) ------------------
    private boolean showAnnouncement = false;
    private String announcementText = "";

    // ---- Hero extras --------------------------------------------------------
    private boolean showHeroButton = false;
    private String heroButtonText = "Order now";
    /** "center" (original) or "left". */
    private String heroAlign = "center";

    // ---- Search & categories -----------------------------------------------
    private boolean showSearch = true;
    private String searchPlaceholder = "Search chicken, eggs…";
    private boolean showCategories = true;

    // ---- Product cards -------------------------------------------------------
    private String addToCartText = "Add to cart";
    private String outOfStockText = "Out of Stock";
    private boolean showDiscountBadge = true;
    private boolean showMrp = true;
    private boolean showProductDescription = true;
    private String emptyProductsText = "No products available right now. Please check back soon.";
    private String noSearchResultsText = "We checked the whole farm. Nothing matched!";

    // ---- Cart & checkout ---------------------------------------------------
    private String viewCartText = "View Cart →";
    private String cartTitle = "Your Cart";
    private String cartEmptyText = "Your cart is empty. Add something delicious!";
    private String checkoutButtonText = "Proceed to Checkout →";
    private String placeOrderText = "Place Order";
    private boolean showLandmarkField = true;
    private boolean showNotesField = true;
    private String notesLabel = "Special instructions (optional)";
    private String successTitle = "Order Placed!";
    private boolean showSuccessMessage = true;
    private String successMessage = "We'll call you to confirm delivery timing.";
    private boolean showScreenshotHint = true;

    // ---- Offline notices ------------------------------------------------------
    private String offlineBannerText = "🔴 We're currently offline and not accepting new orders right now.";
    private String offlineSheetText = "We're currently offline and not accepting new orders. Please check back soon!";

    // ---- Footer ------------------------------------------------------------------
    private boolean showFooter = false;
    private String footerAbout = "";
    private String footerAddress = "";
    private String footerPhone = "";
    private String footerEmail = "";
    private String footerHours = "";
    private String instagramUrl = "";
    private String facebookUrl = "";
    /** {year} and {brand} are filled in by the storefront. Blank hides the line. */
    private String footerCopyright = "© {year} {brand}. All rights reserved.";

    // ---- Sizes (percent of the original: 100 = unchanged, 60 to 160) --------------
    private int sizeLogo = 100;
    private int sizeHeaderIcons = 100;
    private int sizeBrandName = 100;
    private int sizeTagline = 100;
    private int sizeHeroHeading = 100;
    private int sizeHeroText = 100;
    private int sizeBadges = 100;
    private int sizeAnnouncement = 100;
    private int sizeCategories = 100;
    private int sizeSearch = 100;
    private int sizeProductImage = 100;
    private int sizeProductName = 100;
    private int sizeProductDesc = 100;
    private int sizePrice = 100;
    private int sizePhotoBadge = 100;
    private int sizeButtons = 100;
    private int sizeCartBar = 100;
    private int sizeFooter = 100;

    // ---- Look & feel -------------------------------------------------------------
    /** "classic" (original), "modern" or "elegant". */
    private String fontStyle = "classic";
    /** "default" (original), "sharp" or "round". */
    private String cornerStyle = "default";

    // ---- Fine-tuning of individual elements -----------------------------------------
    /** Turns off every animation and transition on the storefront. */
    private boolean animationsOff = false;
    /** element name -> (setting -> value), e.g. addButton -> {height: "52", color: "#ffffff"}.
     * Element names are checked for shape only (the storefront knows which ones exist);
     * every setting name and value is validated by SiteCustomizationService. */
    private Map<String, Map<String, String>> styles = new LinkedHashMap<>();

    // ---- Pop-up messages (toasts) ---------------------------------------------------
    /** Master switch for every pop-up message on the customer website. */
    private boolean toastsEnabled = true;
    /** How long a message stays on screen, in seconds (0.5 to 60). */
    private double toastSeconds = 3.2;
    /** "auto" (original: bottom centre, above the cart bar or open panel), or
     * top-left, top-center, top-right, middle-center, bottom-left, bottom-center, bottom-right. */
    private String toastPosition = "auto";
    /** Nudges the pop-up area, in pixels (negative = left / up). */
    private int toastOffsetX = 0;
    private int toastOffsetY = 0;
    /** The most pop-ups shown at once; the oldest goes when a new one needs room. */
    private int toastMax = 5;
    private boolean toastTapDismiss = false;
    /** message name -> overrides (text, enabled "0", seconds, tone); see SiteCustomizationService. */
    private Map<String, Map<String, String>> messages = new LinkedHashMap<>();

    public static List<Badge> defaultBadges() {
        List<Badge> list = new ArrayList<>();
        list.add(new Badge("✅", "Halal Certified", true));
        list.add(new Badge("🧊", "Never Frozen", true));
        list.add(new Badge("🚚", "Free Delivery", true));
        list.add(new Badge("🧼", "Hygienic Packaging", true));
        return list;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Badge {
        private String icon = "";
        private String text = "";
        private boolean enabled = true;
    }
}
