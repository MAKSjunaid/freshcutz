/* Storefront: draws the category chips + product cards and wires up add-to-cart / quantity controls via
 * event delegation. The data itself (small pages of products, the product pool, live updates) lives in
 * storefront-sync.js (CatalogStore); this file asks it for "the products of the tab I'm on" and shows them,
 * 12 at a time, adding more as the customer scrolls near the end.
 * Cards animate in as they scroll into view (IntersectionObserver).
 *
 * IMPORTANT: interactions (variant toggle, +/-, add) patch the existing
 * card DOM in place instead of re-rendering it. Replacing a card's HTML
 * on every click is what caused the visible "flash" — the browser has to
 * reload the image and the entrance animation plays again. Patching just
 * the text/classes that changed keeps it instant and flicker-free. */

(() => {
  const PAGE = 12; // cards added each time the customer scrolls near the end
  let activeCategory = "All";
  let shown = PAGE; // how many cards the current view should have (grows by 12 as the customer scrolls)
  let drawn = 0; // how many cards are actually on the page right now
  const shownByTab = {}; // tab -> how many cards it had, so coming back to a tab restores it
  let ready = false; // the first catalog answer has been drawn
  const cardVariant = {}; // productId -> "variant1" | "variant2"

  const grid = document.getElementById("productGrid");
  const emptyState = document.getElementById("emptyState");
  const categoryScroll = document.getElementById("categoryScroll");
  const whatsappLink = document.getElementById("whatsappLink");
  const searchInput = document.getElementById("searchInput");
  const searchClearX = document.getElementById("searchClearX");
  let searchTerm = ""; // trimmed, lowercased comparisons happen in matchesSearch()

  /* Low threshold + a positive bottom rootMargin means a card starts
   * animating in while it's still just below the viewport, instead of
   * needing to be scrolled substantially into view first. */
  const revealObserver = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add("is-visible");
          revealObserver.unobserve(entry.target);
        }
      });
    },
    { threshold: 0.01, rootMargin: "0px 0px 200px 0px" }
  );

  function init() {
    const phone = (window.FRESHCUTZ_WHATSAPP || "").replace(/\D/g, "");
    // An admin-set number (see site-customize.js) wins over the config.js default.
    if (!whatsappLink.dataset.custom) whatsappLink.href = `https://wa.me/${phone}`;
    Cart.onChange(syncAllCardQuantities);
    Cart.onChange(updateCartBadges);
    // The first screen (category tabs + the first 12 products of the default tab) is already being fetched by
    // storefront-sync.js — one request. Show loading placeholders until it arrives.
    grid.innerHTML = skeletonCards(3);
    updateCartBadges();
    initHeaderScrollEffect();
    initInfiniteScroll();
    CatalogStore.bindUi({ current, refreshed });
    CatalogStore.ready.then(({ category }) => {
      // the admin's default tab; the plain "All" tab when the category bar is switched off
      activeCategory = category && SiteCustomize.on("showCategories") ? category : "All";
      shown = shownByTab[activeCategory] || PAGE;
      ready = true;
      renderCategories();
      renderGrid();
      pump();
    });
  }

  /** Fades/shrinks/blurs the FRESHCUTZ brand text once the page scrolls a
   * little, smoothly (via the CSS transitions on .brand-text), instead of
   * keeping it full-size the whole time. rAF-throttled so it stays smooth. */
  function initHeaderScrollEffect() {
    const header = document.querySelector(".site-header");
    if (!header) return;
    let ticking = false;
    function apply() {
      header.classList.toggle("is-scrolled", window.scrollY > 24);
      ticking = false;
    }
    window.addEventListener(
      "scroll",
      () => {
        if (!ticking) {
          requestAnimationFrame(apply);
          ticking = true;
        }
      },
      { passive: true }
    );
  }

  function skeletonCards(n) {
    return Array.from({ length: n })
      .map(
        () => `<div class="product-card is-visible"><div class="product-media"><div class="product-media-frame skeleton"></div></div>
          <div class="product-body">
            <div class="skeleton" style="height:16px;border-radius:6px;width:70%;"></div>
            <div class="skeleton" style="height:14px;border-radius:6px;"></div>
            <div class="skeleton" style="height:24px;border-radius:6px;width:40%;"></div>
          </div></div>`
      )
      .join("");
  }

  function renderCategories() {
    const cats = ["All", ...CatalogStore.tabs().map((t) => t.name)];
    categoryScroll.innerHTML = cats
      .map(
        (c) =>
          `<button class="cat-chip ${c === activeCategory ? "active" : ""}" data-cat="${Utils.escapeHtml(c)}">${Utils.escapeHtml(c)}</button>`
      )
      .join("");
  }

  function matchesSearch(p) {
    if (!searchTerm) return true;
    const q = searchTerm.toLowerCase();
    return (
      (p.name || "").toLowerCase().includes(q) ||
      (p.description || "").toLowerCase().includes(q) ||
      (p.category || "").toLowerCase().includes(q)
    );
  }

  /** What the current view lists: the tab's products that are held (no gaps), narrowed by the search words. */
  function currentList() {
    const list = CatalogStore.run(activeCategory);
    return searchTerm ? list.filter(matchesSearch) : list;
  }

  /** Is there more to show for this view — in memory, or still to download? */
  function moreAvailable(list) {
    if (shown < list.length) return true;
    return !searchTerm && list.length < CatalogStore.count(activeCategory);
  }

  function renderGrid() {
    const list = currentList();
    const slice = list.slice(0, shown);
    const waiting = slice.length === 0 && moreAvailable(list); // nothing to draw yet, but more is on its way
    emptyState.hidden = slice.length > 0 || waiting || !ready;
    emptyState.querySelector("p").textContent = searchTerm
      ? SiteCustomize.t("noSearchResultsText")
      : SiteCustomize.t("emptyProductsText");
    grid.innerHTML = waiting || !ready ? skeletonCards(3) : slice.map((p, i) => renderCard(p, i)).join("");
    drawn = waiting || !ready ? 0 : slice.length;
    grid.querySelectorAll(".product-card").forEach((el) => revealObserver.observe(el));
    setRetry(false);
  }

  /** Adds cards to the end of the grid without touching the ones already there (no flash, scroll stays put). */
  function appendCards(items, startIndex) {
    if (!items.length) return;
    const before = grid.children.length;
    grid.insertAdjacentHTML("beforeend", items.map((p, i) => renderCard(p, startIndex + i)).join(""));
    [...grid.children].slice(before).forEach((el) => revealObserver.observe(el));
    drawn += items.length;
  }

  // ---- loading more as the customer scrolls -----------------------------------------------

  const sentinel = document.createElement("div"); // sits right after the grid; reaching it = "load more"
  sentinel.id = "gridSentinel";
  sentinel.style.cssText = "height:1px;";
  const retryBtn = document.createElement("button");
  retryBtn.type = "button";
  retryBtn.hidden = true;
  retryBtn.textContent = "Couldn't load more — tap to retry";
  retryBtn.style.cssText = "display:block;margin:12px auto;padding:10px 16px;border:0;border-radius:999px;background:var(--bg-soft,rgba(0,0,0,.08));color:inherit;font:inherit;cursor:pointer;";
  grid.after(sentinel);
  sentinel.after(retryBtn);

  let loading = false;
  let busyKey = ""; // which view the running download is for

  function setRetry(on) {
    retryBtn.hidden = !on;
  }

  /** Shows the next cards of the current view. Returns how many were added. Cards already downloaded are just
   * revealed; only when they run out is the next small page asked for (only the missing products). */
  async function showMore() {
    if (loading || !ready) return 0;
    const key = activeCategory + "|" + searchTerm;
    let list = currentList();
    if (drawn >= list.length) {
      if (searchTerm || list.length >= CatalogStore.count(activeCategory)) return 0; // nothing more anywhere
      loading = true;
      busyKey = key;
      const before = grid.children.length;
      grid.insertAdjacentHTML("beforeend", skeletonCards(2));
      [...grid.children].slice(before).forEach((el) => el.classList.add("skel-more"));
      let got = 0;
      try {
        got = await CatalogStore.loadMore(activeCategory);
        setRetry(false);
      } catch {
        setRetry(true);
      } finally {
        grid.querySelectorAll(".skel-more").forEach((el) => el.remove());
        loading = false;
      }
      if (key !== busyKey || key !== activeCategory + "|" + searchTerm) {
        pump(); // the customer moved to another tab meanwhile: this download was for the old one, so look again
        return 0;
      }
      if (!got) return 0;
      list = currentList();
    }
    const from = drawn;
    const to = Math.min(list.length, drawn + PAGE);
    if (to <= from) return 0;
    const wasEmptyGrid = drawn === 0;
    shown = Math.max(shown, to);
    if (!searchTerm) shownByTab[activeCategory] = shown;
    if (wasEmptyGrid) renderGrid(); // replaces the loading placeholders
    else appendCards(list.slice(from, to), from);
    return to - from;
  }

  /** Keeps loading while the end of the grid is close to the screen (a short first page, or a big screen). */
  async function pump(force = false) {
    for (let i = 0; i < 20; i++) {
      // nothing real drawn yet (just placeholders) = load whatever the position; otherwise only when the end is near
      const nothingDrawn = !grid.querySelector(".product-card[data-id]");
      // a tab that holds fewer than one page of cards so far (some were already downloaded for another tab) gets
      // topped up to a full page right away, wherever the end of the grid is
      const shortPage = force && drawn < PAGE && !searchTerm && moreAvailable(currentList());
      if (!nothingDrawn && !shortPage && sentinel.getBoundingClientRect().top > window.innerHeight + 700) return;
      if (!(await showMore())) return;
      await new Promise((r) => requestAnimationFrame(r));
    }
  }

  function initInfiniteScroll() {
    new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) pump();
      },
      { rootMargin: "0px 0px 700px 0px" }
    ).observe(sentinel);
    retryBtn.addEventListener("click", () => {
      setRetry(false);
      pump();
    });
  }

  // ---- live updates (storefront-sync.js calls these) -----------------------------------------------

  /** What the customer is looking at, so a refresh knows how much to read again. */
  function current() {
    return { category: activeCategory, shown: searchTerm ? PAGE : shown, full: !!searchTerm };
  }

  /** The catalog changed and the data was read again: update the page in place (no flash when the same
   * products are still there; otherwise redraw). */
  function refreshed({ category }) {
    if (category !== activeCategory) {
      activeCategory = category; // the tab the customer was on is gone
      shown = shownByTab[category] || PAGE;
    }
    renderCategories();
    if (!ready) return;
    const list = currentList().slice(0, shown);
    const domIds = [...grid.querySelectorAll(".product-card[data-id]")].map((c) => Number(c.dataset.id));
    const same = list.length === domIds.length && list.every((p, i) => p.id === domIds[i]);
    if (same) {
      list.forEach((p) => {
        const card = grid.querySelector(`.product-card[data-id="${p.id}"]`);
        if (card) patchCardContent(card, p);
      });
    } else {
      renderGrid();
    }
    pump();
  }

  /** Updates one card's name/description/image/price/discount to match a freshly-read product, without touching
   * anything else about the card (its scroll position, reveal state, or qty stepper). */
  function patchCardContent(card, product) {
    const nameEl = card.querySelector(".product-name");
    if (nameEl) nameEl.textContent = product.name;
    const descEl = card.querySelector(".product-desc");
    if (descEl) descEl.textContent = product.description || "";
    const img = card.querySelector(".product-media-frame img");
    const resolved = Utils.mediumImageUrl(product.imageUrl);
    if (img && img.getAttribute("src") !== resolved) img.src = resolved;
    patchVariantUI(card, product, cardVariant[product.id] || "variant1");

    card.classList.toggle("out-of-stock", !!product.outOfStock);
    const stockBadge = card.querySelector(".out-of-stock-badge");
    if (stockBadge) stockBadge.hidden = !product.outOfStock;
    const footer = card.querySelector(".product-footer");
    if (footer) footer.innerHTML = footerHtml(product, cardVariant[product.id] || "variant1");
  }

  /** The Add/qty-stepper controls, or a disabled "Out of Stock" label in
   * their place — kept separate from renderCard so a poll picking up a
   * stock change can just swap this one piece (see storefront-sync.js). */
  function footerHtml(p, variant) {
    if (p.outOfStock) {
      return `<button class="add-btn out-of-stock-btn" disabled>${Utils.escapeHtml(SiteCustomize.t("outOfStockText"))}</button>`;
    }
    const qty = Cart.quantityOf(p.id, variant);
    return `
      <button class="add-btn ${qty > 0 ? "hide" : ""}" data-action="add">${Utils.escapeHtml(SiteCustomize.t("addToCartText"))}</button>
      <div class="qty-stepper ${qty > 0 ? "show" : ""}">
        <button data-action="dec" aria-label="Decrease">−</button>
        <span class="qty-value">${qty}</span>
        <button data-action="inc" aria-label="Increase">+</button>
      </div>`;
  }

  function renderCard(p, index) {
    const variant = cardVariant[p.id] || "variant1";
    const hasVariant2 = !!p.variant2Label;
    const price = variant === "variant2" ? p.variant2Price : p.variant1Price;
    const mrp = variant === "variant2" ? p.variant2Mrp : p.variant1Mrp;
    const discount = Utils.discountPercent(price, mrp);
    const img = Utils.mediumImageUrl(p.imageUrl); // the ~25 KB card-size photo, not the full-size original

    return `
    <div class="product-card ${p.outOfStock ? "out-of-stock" : ""}" data-id="${p.id}" style="--i:${index % 8}">
      <div class="product-media">
        <div class="product-media-frame">
          <img src="${img}" alt="${Utils.escapeHtml(p.name)}" loading="lazy" onerror="this.style.opacity=0" />
          <span class="discount-badge" ${discount > 0 && !p.outOfStock ? "" : "hidden"}>${discount}% OFF</span>
          <span class="out-of-stock-badge" ${p.outOfStock ? "" : "hidden"}>${Utils.escapeHtml(SiteCustomize.t("outOfStockText"))}</span>
        </div>
      </div>
      <div class="product-body">
        <div class="product-name">${Utils.escapeHtml(p.name)}</div>
        ${p.description ? `<div class="product-desc">${Utils.escapeHtml(p.description)}</div>` : ""}
        ${hasVariant2 ? `
        <div class="variant-toggle" data-role="variant-toggle">
          <button data-action="variant" data-variant="variant1" class="${variant === "variant1" ? "active" : ""}">${Utils.escapeHtml(p.variant1Label)}</button>
          <button data-action="variant" data-variant="variant2" class="${variant === "variant2" ? "active" : ""}">${Utils.escapeHtml(p.variant2Label)}</button>
        </div>` : `<div class="variant-toggle"><button class="active" disabled>${Utils.escapeHtml(p.variant1Label)}</button></div>`}
        <div class="price-row">
          <span class="price-now" data-role="price-now">${Utils.formatMoney(price)}</span>
          <span class="price-mrp" data-role="price-mrp" ${discount > 0 ? "" : "hidden"}>${Utils.formatMoney(mrp)}</span>
        </div>
        <div class="product-footer">${footerHtml(p, variant)}</div>
      </div>
    </div>`;
  }

  /** Re-renders only the price/discount bits of a card after a variant switch. */
  function patchVariantUI(card, product, variant) {
    const price = variant === "variant2" ? product.variant2Price : product.variant1Price;
    const mrp = variant === "variant2" ? product.variant2Mrp : product.variant1Mrp;
    const discount = Utils.discountPercent(price, mrp);

    card.querySelectorAll('[data-action="variant"]').forEach((btn) => {
      btn.classList.toggle("active", btn.dataset.variant === variant);
    });
    card.querySelector('[data-role="price-now"]').textContent = Utils.formatMoney(price);
    card.querySelector('[data-role="price-mrp"]').textContent = Utils.formatMoney(mrp);
    card.querySelector('[data-role="price-mrp"]').hidden = discount <= 0;
    const badge = card.querySelector(".discount-badge");
    badge.textContent = `${discount}% OFF`;
    badge.hidden = discount <= 0 || product.outOfStock;
  }

  /** Updates just the Add button / qty stepper of one card. A no-op for an
   *  out-of-stock card — its footer is just a disabled label, no stepper to
   *  patch — which matters beyond this one card: this runs inside a
   *  Cart.onChange loop over every card, and throwing on a missing element
   *  here used to abort that loop partway through, silently skipping the
   *  cart-bar badge update that's registered right after it. */
  function patchQtyUI(card, productId, variant, pulse) {
    const qty = Cart.quantityOf(productId, variant);
    const addBtn = card.querySelector(".add-btn");
    const stepper = card.querySelector(".qty-stepper");
    const qtyValue = card.querySelector(".qty-value");
    if (!addBtn || !stepper || !qtyValue) return;
    addBtn.classList.toggle("hide", qty > 0);
    stepper.classList.toggle("show", qty > 0);
    if (qtyValue.textContent !== String(qty)) {
      qtyValue.textContent = qty;
      if (pulse) {
        qtyValue.classList.remove("pulse");
        // eslint-disable-next-line no-unused-expressions
        qtyValue.offsetWidth; // restart the animation
        qtyValue.classList.add("pulse");
      }
    }
  }

  /** Keeps every card's qty controls in sync with the cart (checkout,
   *  "Clear cart", or edits made from inside the cart sheet all change
   *  the cart without touching a card directly). */
  function syncAllCardQuantities() {
    grid.querySelectorAll(".product-card[data-id]").forEach((card) => {
      const productId = Number(card.dataset.id);
      const variant = cardVariant[productId] || "variant1";
      patchQtyUI(card, productId, variant, false);
    });
  }

  categoryScroll.addEventListener("click", (e) => {
    const chip = e.target.closest(".cat-chip");
    if (!chip) return;
    activeCategory = chip.dataset.cat;
    shown = searchTerm ? PAGE : shownByTab[activeCategory] || PAGE;
    renderCategories();
    if (searchTerm && !CatalogStore.tabComplete(activeCategory)) {
      searchThenRender(); // searching in this tab: it needs all of its products first
      return;
    }
    renderGrid();
    pump(true); // a tab that holds only a few of its products so far fetches the missing ones
  });

  let searchSeq = 0;

  /** Search looks inside the tab being viewed, so that tab must be held in full first. A small tab that is already
   * downloaded costs nothing; otherwise only its missing products are downloaded (the whole list for "All"). */
  async function searchThenRender() {
    const seq = ++searchSeq;
    const tab = activeCategory;
    emptyState.hidden = true;
    grid.innerHTML = skeletonCards(3);
    drawn = 0;
    try {
      await CatalogStore.loadTab(tab);
    } catch {
      if (seq === searchSeq) window.Storefront.showLoadError("Couldn't load the search — please check your connection.");
      return;
    }
    if (seq !== searchSeq) return; // a newer search or tab change took over
    renderCategories(); // the tabs may have been re-worked out from a fresh copy
    renderGrid();
    pump();
  }

  const applySearchTerm = Utils.debounce(async () => {
    searchTerm = searchInput.value.trim();
    shown = searchTerm ? PAGE : shownByTab[activeCategory] || PAGE;
    if (searchTerm && !CatalogStore.tabComplete(activeCategory)) {
      await searchThenRender();
      return;
    }
    searchSeq++;
    renderGrid();
    pump();
  }, 200);

  // Start getting the tab's missing products as soon as the customer taps the box (nothing happens when the tab
  // is already complete) — by the time they've typed a word it's here.
  searchInput.addEventListener("focus", () => {
    CatalogStore.loadTab(activeCategory).catch(() => {});
  });

  searchInput.addEventListener("input", () => {
    searchClearX.hidden = !searchInput.value;
    applySearchTerm();
  });

  searchClearX.addEventListener("click", () => {
    searchInput.value = "";
    searchClearX.hidden = true;
    searchTerm = "";
    searchSeq++;
    shown = shownByTab[activeCategory] || PAGE;
    renderGrid();
    pump();
    // Blur rather than refocus — clearing shouldn't leave the cursor
    // blinking/keyboard up; typing again means tapping the box on purpose.
    searchInput.blur();
  });

  grid.addEventListener("click", (e) => {
    const card = e.target.closest(".product-card");
    if (!card) return;
    const productId = Number(card.dataset.id);
    const product = CatalogStore.getProduct(productId);
    if (!product) return;

    const variantBtn = e.target.closest('[data-action="variant"]');
    const addBtn = e.target.closest('[data-action="add"]');
    const incBtn = e.target.closest('[data-action="inc"]');
    const decBtn = e.target.closest('[data-action="dec"]');

    if (variantBtn) {
      cardVariant[productId] = variantBtn.dataset.variant;
      patchVariantUI(card, product, cardVariant[productId]);
      patchQtyUI(card, productId, cardVariant[productId], false);
    } else if (addBtn) {
      const variant = cardVariant[productId] || "variant1";
      const fromRect = addBtn.getBoundingClientRect(); // before the button hides itself
      Cart.setQuantity(product, variant, 1);
      patchQtyUI(card, productId, variant, true);
      Toast.msg("addedToCart", { name: product.name });
      if (window.CartFx) CartFx.fly(fromRect, card);
    } else if (incBtn) {
      const variant = cardVariant[productId] || "variant1";
      const fromRect = incBtn.getBoundingClientRect();
      Cart.setQuantity(product, variant, Cart.quantityOf(productId, variant) + 1);
      patchQtyUI(card, productId, variant, true);
      if (window.CartFx) CartFx.fly(fromRect, card);
    } else if (decBtn) {
      const variant = cardVariant[productId] || "variant1";
      Cart.setQuantity(product, variant, Cart.quantityOf(productId, variant) - 1);
      patchQtyUI(card, productId, variant, true);
    }
  });

  function updateCartBadges() {
    // The header used to also carry a cart-count badge next to a cart
    // icon; that icon's gone now (see profile.js) since the bottom
    // cart-bar below already appears the moment something's added, so
    // this only needs to keep that bar's own count/total in sync.
    const count = Cart.getLineCount(); // distinct items in the cart, not total quantity
    const total = Cart.getTotal();
    const barEl = document.getElementById("cartBar");
    barEl.classList.toggle("show", count > 0);
    document.getElementById("cartBarCount").textContent = `${count} item${count === 1 ? "" : "s"}`;
    document.getElementById("cartBarTotal").textContent = Utils.formatMoney(total);
  }

  window.Storefront = {
    // Any product the page knows: those on screen, plus the ones in the cart.
    getProduct: (id) => CatalogStore.getProduct(id),
    // Makes sure these products are known (fetches the missing ones by id) — used by "Reorder".
    ensureProducts: (ids) => CatalogStore.ensure(ids),
    getActiveCategory: () => activeCategory,
    cardVariant,
    grid,
    renderCategories,
    renderGrid,
    patchVariantUI,
    footerHtml,
    // Used by storefront-sync.js if the very first catalog answer hasn't
    // arrived after a generous wait — reuses the same empty-state spot
    // renderGrid() uses for "no products right now", so there's only
    // ever one visual slot for "nothing to show here" messaging.
    showLoadError: (message) => {
      emptyState.hidden = false;
      emptyState.querySelector("p").textContent = message;
    },
  };

  /* The admin changed something in the Customize tab: redraw the pieces that
   * carry admin-set wording (Add button, out-of-stock label, empty message),
   * and drop a category/search filter that the admin just hid. Skipped
   * until the first catalog has arrived, so it can't flash "no products". */
  document.addEventListener("freshcutz:site", () => {
    if (!SiteCustomize.on("showSearch") && searchTerm) {
      searchTerm = "";
      searchInput.value = "";
    }
    if (!SiteCustomize.on("showCategories") && activeCategory !== "All") {
      activeCategory = "All";
      shown = shownByTab.All || PAGE;
      if (ready) renderCategories();
    }
    if (ready) {
      renderGrid();
      pump();
    }
  });

  document.addEventListener("DOMContentLoaded", init);
})();
