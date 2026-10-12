/* Cart sheet -> checkout (checkout-form.js) -> order success. Everything
 * renders into #sheetRoot so index.html stays a static skeleton.
 *
 * Exposes window.CheckoutSheet — the shared sheet shell (renderSheet/
 * closeSheet) and openSuccess — so checkout-form.js can render into the
 * same sheet without both files fighting over their own copies of it. */

(() => {
  const root = document.getElementById("sheetRoot");
  let currentView = null; // "cart" | "checkout" | "success" | null — which sheet is open right now

  // While any sheet (cart / checkout / success / My Account) is open, the
  // page behind it must not scroll. overflow:hidden on <body> alone is
  // ignored by iOS Safari, so the body is pinned in place instead (and the
  // scroll position restored on close, so the page doesn't jump to the top).
  let scrollLockY = null;

  function lockPageScroll() {
    if (scrollLockY !== null) return; // already locked (e.g. cart -> checkout)
    scrollLockY = window.scrollY || document.documentElement.scrollTop || 0;
    const b = document.body.style;
    b.position = "fixed";
    b.top = `-${scrollLockY}px`;
    b.left = "0";
    b.right = "0";
    b.width = "100%";
  }

  function unlockPageScroll() {
    if (scrollLockY === null) return;
    const y = scrollLockY;
    scrollLockY = null;
    const b = document.body.style;
    b.position = "";
    b.top = "";
    b.left = "";
    b.right = "";
    b.width = "";
    window.scrollTo({ top: y, left: 0, behavior: "instant" }); // instant: html has scroll-behavior: smooth
  }

  function closeSheet() {
    currentView = null;
    root.innerHTML = "";
    document.documentElement.style.removeProperty("--toast-sheet-gap");
    unlockPageScroll();
  }

  function renderSheet(bodyHtml, title) {
    // A toast from whatever the customer just did on the previous screen
    // (e.g. "added to cart") shouldn't still be sitting on top of this new
    // sheet a couple seconds later — every sheet (cart/checkout/success,
    // plus profile.js's, since they all call this same renderSheet) opens
    // clean instead of inheriting the last screen's toast.
    Toast.dismissAll();
    lockPageScroll();
    root.innerHTML = `
      <div class="overlay" data-role="overlay"></div>
      <div class="sheet" role="dialog" aria-modal="true">
        <div class="sheet-header">
          <h2>${title}</h2>
          <button class="sheet-close" data-role="close" aria-label="Close">✕</button>
        </div>
        ${bodyHtml}
      </div>`;
    root.querySelector('[data-role="overlay"]').addEventListener("click", closeSheet);
    root.querySelector('[data-role="close"]').addEventListener("click", closeSheet);

    // A toast shown while this sheet is up floats just above it (see
    // Toast.show()'s .toast-above-sheet toggle + the CSS var) — measured
    // now, with the sheet's real content (every cart item, etc.) already
    // in the DOM, so the gap matches how tall this sheet actually rendered
    // instead of a fixed guess.
    const sheetEl = root.querySelector(".sheet");
    document.documentElement.style.setProperty("--toast-sheet-gap", `${sheetEl.getBoundingClientRect().height + 14}px`);

    return root;
  }

  function cartItemHtml(item) {
    const img = Utils.resolveImageUrl(item.imageUrl);
    return `
    <div class="cart-item" data-key="${item.productId}:${item.variant}">
      <img src="${img}" alt="${Utils.escapeHtml(item.name)}" onerror="this.style.visibility='hidden'" />
      <div class="cart-item-main">
        <div class="cart-item-name">${Utils.escapeHtml(item.name)}</div>
        <div class="cart-item-variant">${Utils.escapeHtml(item.variantLabel)} · ${Utils.formatMoney(item.unitPrice)} each</div>
        <div class="cart-item-row">
          <div class="qty-stepper show" style="flex:0 0 auto;">
            <button data-action="dec">−</button>
            <span class="qty-value">${item.quantity}</span>
            <button data-action="inc">+</button>
          </div>
          <span class="cart-item-price">${Utils.formatMoney(item.unitPrice * item.quantity)}</span>
        </div>
      </div>
      <button type="button" class="cart-item-delete" data-action="delete" aria-label="Remove ${Utils.escapeHtml(item.name)} from cart" title="Remove item">
              <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 6h18"/><path d="M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6"/><path d="M14 11v6"/></svg>
            </button>
    </div>`;
  }

  /** Updates one cart-sheet row's qty/price plus the footer total in place,
   *  instead of re-rendering the whole sheet (which replayed the overlay/
   *  sheet entrance animation and caused a visible flash on every tap). */
  function patchCartRow(sheet, row, product, variant, productId = product && product.id) {
    const items = Cart.getItems();
    if (items.length === 0) {
      openCart(); // cart just became empty — layout changes entirely, full re-render is fine here
      return;
    }
    const qty = Cart.quantityOf(productId, variant);
    if (qty <= 0) {
      row.remove();
    } else {
      const unitPrice = variant === "variant2" ? product.variant2Price : product.variant1Price;
      row.querySelector(".qty-value").textContent = qty;
      row.querySelector(".cart-item-price").textContent = Utils.formatMoney(unitPrice * qty);
    }
    const totalEl = sheet.querySelector(".sheet-footer .summary-row.total span:last-child");
    if (totalEl) totalEl.textContent = Utils.formatMoney(Cart.getTotal());
  }

  function openCart() {
    currentView = "cart";
    const items = Cart.getItems();
    if (items.length === 0) {
      renderSheet(
        `<div class="sheet-body"><div class="empty-state"><div class="icon">🛒</div><p>${Utils.escapeHtml(SiteCustomize.t("cartEmptyText"))}</p></div></div>`,
        Utils.escapeHtml(SiteCustomize.t("cartTitle"))
      );
      return;
    }

    const body = `
      <div class="sheet-body">${items.map(cartItemHtml).join("")}</div>
      <div class="sheet-footer">
        <div class="summary-row total"><span>${SiteCustomize.html("totalLabel")}</span><span>${Utils.formatMoney(Cart.getTotal())}</span></div>
        <button class="btn btn-gold btn-block" style="margin-top:10px;" data-role="checkout-btn">${Utils.escapeHtml(SiteCustomize.t("checkoutButtonText"))}</button>
        <div style="text-align:center;margin-top:8px;">
          <button class="btn btn-ghost btn-sm" data-role="clear-btn">${SiteCustomize.html("clearCartText")}</button>
        </div>
      </div>`;

    const sheet = renderSheet(body, Utils.escapeHtml(SiteCustomize.t("cartTitle")));

    sheet.querySelector(".sheet-body").addEventListener("click", (e) => {
      const row = e.target.closest(".cart-item");
      if (!row) return;
      const [productId, variant] = row.dataset.key.split(":");
      // Trash icon: drop the whole line at once, whatever its quantity (and
      // even if the product has since left the catalog).
      if (e.target.closest('[data-action="delete"]')) {
        Cart.remove(Number(productId), variant);
        patchCartRow(sheet, row, null, variant, Number(productId));
        return;
      }
      const product = window.Storefront.getProduct(Number(productId));
      if (!product) return;
      const current = Cart.quantityOf(Number(productId), variant);
      let next = current;
      if (e.target.closest('[data-action="inc"]')) {
        next = current + 1;
      } else if (e.target.closest('[data-action="dec"]')) {
        next = current - 1;
      } else {
        return;
      }
      Cart.setQuantity(product, variant, next);
      patchCartRow(sheet, row, product, variant);
    });

    sheet.querySelector('[data-role="checkout-btn"]').addEventListener("click", openCheckout);
    sheet.querySelector('[data-role="clear-btn"]').addEventListener("click", () => {
      Cart.clear();
      openCart();
    });
  }

  function openCheckout() {
    if (window.StoreStatus && !window.StoreStatus.isOnline()) {
      currentView = "checkout";
      renderSheet(
        `<div class="sheet-body"><div class="empty-state"><div class="icon">🔴</div><p>${Utils.escapeHtml(SiteCustomize.t("offlineSheetText"))}</p></div></div>`,
        "Store Offline"
      );
      return;
    }
    currentView = "checkout";
    // Already logged in via the profile icon (profile.js)? Skip straight
    // to the final confirm-and-place-order screen instead of asking for
    // a name/phone we already have on file.
    const loggedInPhone = window.ProfileSheet && window.ProfileSheet.getSavedPhone();
    if (loggedInPhone) {
      window.CheckoutForm.openFinalStep(loggedInPhone);
    } else {
      window.CheckoutForm.open();
    }
  }

  /** What was actually ordered, so the customer has a record even if they
   * close this screen and never note down the order code — and something
   * worth screenshotting, since there's no order-lookup page for them to
   * come back to. */
  function orderSummaryHtml(order) {
    const rows = (order.items || [])
      .map(
        (i) => `<div class="summary-row">
          <span>${i.quantity} × ${Utils.escapeHtml(i.productName)} (${Utils.escapeHtml(i.variantLabel)})</span>
          <span>${Utils.formatMoney(i.lineTotal)}</span>
        </div>`
      )
      .join("");
    const notes = order.notes
      ? `<div class="order-notes"><span class="order-notes-label">${SiteCustomize.html("yourNoteLabel")}</span> ${Utils.escapeHtml(order.notes)}</div>`
      : "";
    return `
      <div class="order-summary-list">
        ${rows}
        <div class="summary-row total"><span>${SiteCustomize.html("totalLabel")}</span><span>${Utils.formatMoney(order.totalAmount)}</span></div>
        ${notes}
      </div>`;
  }

  function openSuccess(order) {
    currentView = "success";
    const body = `
      <div class="sheet-body">
        <div class="order-success">
          <div class="success-check">✓</div>
          <h2 class="anim-fade-up" style="animation-delay:120ms;">${Utils.escapeHtml(SiteCustomize.t("successTitle"))}</h2>
          ${SiteCustomize.on("showSuccessMessage") && SiteCustomize.t("successMessage") ? `<p class="anim-fade-up" style="animation-delay:180ms;color:var(--text-muted);margin-top:6px;">${Utils.escapeHtml(SiteCustomize.t("successMessage"))}</p>` : ""}
          <div class="order-id-chip anim-fade-up" style="animation-delay:240ms;">
            <span class="order-id-label">${SiteCustomize.html("orderIdLabel")}</span>
            <span class="order-id-value">${Utils.escapeHtml(order.orderCode)}</span>
          </div>
        </div>
        <div class="anim-fade-up" style="animation-delay:300ms;">${orderSummaryHtml(order)}</div>
        ${SiteCustomize.on("showScreenshotHint") ? `<p class="screenshot-hint anim-fade-up" style="animation-delay:360ms;">${SiteCustomize.html("screenshotHintText")}</p>` : ""}
        <button class="btn btn-dark btn-block anim-fade-up" style="animation-delay:420ms;margin-top:var(--sp-3);" data-role="continue">${SiteCustomize.html("continueShoppingText")}</button>
      </div>`;
    const sheet = renderSheet(body, "");
    sheet.querySelector(".sheet-header h2").parentElement.style.borderBottom = "none";
    sheet.querySelector('[data-role="continue"]').addEventListener("click", closeSheet);
  }

  // The header cart icon is gone (see profile.js) — the bottom cart-bar's
  // "View Cart" button, which already appears the moment something's
  // added, is the only entry point into the cart sheet now.
  document.getElementById("viewCartBtn").addEventListener("click", openCart);

  /** A price changed or a product got disabled from Admin while this
   * customer already had it in their cart — Cart.syncWithCatalog() (run on
   * every catalog load/poll in storefront.js) just dropped or corrected it.
   * Let them know, and if they're looking at the cart or mid-checkout,
   * refresh what they see so they're never confirming stale details. */
  Cart.onCatalogSync(({ removedNames }) => {
    removedNames.forEach((name) => Toast.msg("itemRemoved", { name }));
    if (currentView === "cart" || currentView === "checkout") {
      openCart();
    }
  });

  window.CheckoutSheet = { renderSheet, closeSheet, openCart, openSuccess };
})();
