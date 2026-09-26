/* Storefront: loads the catalog, renders category chips + product cards,
 * and wires up add-to-cart / quantity controls via event delegation.
 * Cards animate in as they scroll into view (IntersectionObserver).
 *
 * IMPORTANT: interactions (variant toggle, +/-, add) patch the existing
 * card DOM in place instead of re-rendering it. Replacing a card's HTML
 * on every click is what caused the visible "flash" — the browser has to
 * reload the image and the entrance animation plays again. Patching just
 * the text/classes that changed keeps it instant and flicker-free. */

(() => {
  let allProducts = [];
  let activeCategory = "All";
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
    whatsappLink.href = `https://wa.me/${phone}`;
    Cart.onChange(syncAllCardQuantities);
    Cart.onChange(updateCartBadges);
    loadProducts();
    updateCartBadges();
    initHeaderScrollEffect();
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

  async function loadProducts() {
    grid.innerHTML = skeletonCards(3);
    try {
      allProducts = await Api.get("/api/products");
      renderCategories();
      renderGrid();
      Cart.syncWithCatalog(allProducts);
    } catch (err) {
      grid.innerHTML = "";
      emptyState.hidden = false;
      emptyState.querySelector("p").textContent = err.message;
    }
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
    const cats = ["All", ...new Set(allProducts.map((p) => p.category).filter(Boolean))];
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

  function renderGrid() {
    const visible = allProducts.filter(
      (p) => p.enabled && (activeCategory === "All" || p.category === activeCategory) && matchesSearch(p)
    );
    emptyState.hidden = visible.length > 0;
    emptyState.querySelector("p").textContent = searchTerm
      ? "We checked the whole farm. Nothing matched!"
      : "No products available right now. Please check back soon.";
    grid.innerHTML = visible.map((p, i) => renderCard(p, i)).join("");
    grid.querySelectorAll(".product-card").forEach((el) => revealObserver.observe(el));
  }

  /** The Add/qty-stepper controls, or a disabled "Out of Stock" label in
   * their place — kept separate from renderCard so a poll picking up a
   * stock change can just swap this one piece (see storefront-sync.js). */
  function footerHtml(p, variant) {
    if (p.outOfStock) {
      return `<button class="add-btn out-of-stock-btn" disabled>Out of Stock</button>`;
    }
    const qty = Cart.quantityOf(p.id, variant);
    return `
      <button class="add-btn ${qty > 0 ? "hide" : ""}" data-action="add">Add to cart</button>
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
    const img = Utils.resolveImageUrl(p.imageUrl);

    return `
    <div class="product-card ${p.outOfStock ? "out-of-stock" : ""}" data-id="${p.id}" style="--i:${index % 8}">
      <div class="product-media">
        <div class="product-media-frame">
          <img src="${img}" alt="${Utils.escapeHtml(p.name)}" loading="lazy" onerror="this.style.opacity=0" />
          <span class="discount-badge" ${discount > 0 && !p.outOfStock ? "" : "hidden"}>${discount}% OFF</span>
          <span class="out-of-stock-badge" ${p.outOfStock ? "" : "hidden"}>Out of Stock</span>
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
    renderCategories();
    renderGrid();
  });

  const applySearchTerm = Utils.debounce(() => {
    searchTerm = searchInput.value.trim();
    renderGrid();
  }, 200);

  searchInput.addEventListener("input", () => {
    searchClearX.hidden = !searchInput.value;
    applySearchTerm();
  });

  searchClearX.addEventListener("click", () => {
    searchInput.value = "";
    searchClearX.hidden = true;
    searchTerm = "";
    renderGrid();
    // Blur rather than refocus — clearing shouldn't leave the cursor
    // blinking/keyboard up; typing again means tapping the box on purpose.
    searchInput.blur();
  });

  grid.addEventListener("click", (e) => {
    const card = e.target.closest(".product-card");
    if (!card) return;
    const productId = Number(card.dataset.id);
    const product = allProducts.find((p) => p.id === productId);
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
      Cart.setQuantity(product, variant, 1);
      patchQtyUI(card, productId, variant, true);
      Toast.show(`${product.name} added to cart`, "success");
    } else if (incBtn) {
      const variant = cardVariant[productId] || "variant1";
      Cart.setQuantity(product, variant, Cart.quantityOf(productId, variant) + 1);
      patchQtyUI(card, productId, variant, true);
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
    getProduct: (id) => allProducts.find((p) => p.id === id),
    getAllProducts: () => allProducts,
    setAllProducts: (products) => { allProducts = products; },
    getActiveCategory: () => activeCategory,
    cardVariant,
    grid,
    renderCategories,
    renderGrid,
    patchVariantUI,
    footerHtml,
  };

  document.addEventListener("DOMContentLoaded", init);
})();
