/* Cart sheet -> checkout form -> order success. Everything renders into
 * #sheetRoot so index.html stays a static skeleton. */

(() => {
  const root = document.getElementById("sheetRoot");
  let currentView = null; // "cart" | "checkout" | "success" | null — which sheet is open right now

  function closeSheet() {
    currentView = null;
    root.innerHTML = "";
  }

  function renderSheet(bodyHtml, title) {
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
    </div>`;
  }

  /** Updates one cart-sheet row's qty/price plus the footer total in place,
   *  instead of re-rendering the whole sheet (which replayed the overlay/
   *  sheet entrance animation and caused a visible flash on every tap). */
  function patchCartRow(sheet, row, product, variant) {
    const items = Cart.getItems();
    if (items.length === 0) {
      openCart(); // cart just became empty — layout changes entirely, full re-render is fine here
      return;
    }
    const qty = Cart.quantityOf(product.id, variant);
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
        `<div class="sheet-body"><div class="empty-state"><div class="icon">🛒</div><p>Your cart is empty. Add something delicious!</p></div></div>`,
        "Your Cart"
      );
      return;
    }

    const body = `
      <div class="sheet-body">${items.map(cartItemHtml).join("")}</div>
      <div class="sheet-footer">
        <div class="summary-row total"><span>Total</span><span>${Utils.formatMoney(Cart.getTotal())}</span></div>
        <button class="btn btn-gold btn-block" style="margin-top:10px;" data-role="checkout-btn">Proceed to Checkout →</button>
        <div style="text-align:center;margin-top:8px;">
          <button class="btn btn-ghost btn-sm" data-role="clear-btn">Clear cart</button>
        </div>
      </div>`;

    const sheet = renderSheet(body, "Your Cart");

    sheet.querySelector(".sheet-body").addEventListener("click", (e) => {
      const row = e.target.closest(".cart-item");
      if (!row) return;
      const [productId, variant] = row.dataset.key.split(":");
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
        `<div class="sheet-body"><div class="empty-state"><div class="icon">🔴</div><p>We're currently offline and not accepting new orders. Please check back soon!</p></div></div>`,
        "Store Offline"
      );
      return;
    }
    currentView = "checkout";
    const body = `
      <form class="sheet-body" id="checkoutForm" novalidate>
        <div class="field">
          <label for="cf-name">Your name</label>
          <input id="cf-name" name="customerName" required placeholder="e.g. Ayesha Khan" />
          <div class="error" data-err="customerName"></div>
        </div>
        <div class="field">
          <label for="cf-phone">Phone number</label>
          <input id="cf-phone" name="phone" type="tel" required placeholder="10-digit mobile number" />
          <div class="error" data-err="phone"></div>
        </div>
        <div class="field">
          <label for="cf-flat">Flat / House no.</label>
          <input id="cf-flat" name="flatNo" required placeholder="e.g. Flat 302, Block B" />
          <div class="error" data-err="flatNo"></div>
        </div>
        <div class="field">
          <label for="cf-address">Building name / Address</label>
          <textarea id="cf-address" name="addressLine" rows="2" required placeholder="Building, street, area"></textarea>
          <div class="error" data-err="addressLine"></div>
        </div>
        <div class="field">
          <label for="cf-landmark">Landmark (optional)</label>
          <input id="cf-landmark" name="landmark" placeholder="e.g. Near XYZ apartments" />
        </div>
        <div class="field">
          <label for="cf-notes">Order notes (optional)</label>
          <textarea id="cf-notes" name="notes" rows="2" placeholder="e.g. Call before delivering"></textarea>
        </div>
      </form>
      <div class="sheet-footer">
        <div class="summary-row"><span>Items</span><span>${Cart.getLineCount()}</span></div>
        <div class="summary-row total"><span>Total (Cash on Delivery)</span><span>${Utils.formatMoney(Cart.getTotal())}</span></div>
        <button class="btn btn-gold btn-block" style="margin-top:10px;" id="placeOrderBtn" form="checkoutForm">
          Place Order
        </button>
      </div>`;

    const sheet = renderSheet(body, "Delivery Details");
    const form = sheet.querySelector("#checkoutForm");
    const placeBtn = sheet.querySelector("#placeOrderBtn");

    placeBtn.addEventListener("click", async (e) => {
      e.preventDefault();
      const errors = validate(form);
      if (Object.keys(errors).length > 0) {
        showErrors(sheet, errors);
        return;
      }
      await submitOrder(form, placeBtn);
    });
  }

  function validate(form) {
    const errors = {};
    const data = new FormData(form);
    if (!data.get("customerName")?.trim()) errors.customerName = "Please enter your name";
    if (!Utils.validatePhone(data.get("phone"))) errors.phone = "Enter a valid 10-digit phone number";
    if (!data.get("flatNo")?.trim()) errors.flatNo = "Please enter your flat / house no.";
    if (!data.get("addressLine")?.trim()) errors.addressLine = "Please enter your address";
    return errors;
  }

  function showErrors(sheet, errors) {
    sheet.querySelectorAll(".error").forEach((el) => (el.textContent = ""));
    Object.entries(errors).forEach(([field, msg]) => {
      const el = sheet.querySelector(`[data-err="${field}"]`);
      if (el) el.textContent = msg;
    });
    const firstField = Object.keys(errors)[0];
    sheet.querySelector(`[name="${firstField}"]`)?.focus();
  }

  async function submitOrder(form, btn) {
    const data = new FormData(form);
    const payload = {
      customerName: data.get("customerName").trim(),
      phone: data.get("phone").trim(),
      flatNo: data.get("flatNo").trim(),
      addressLine: data.get("addressLine").trim(),
      landmark: data.get("landmark")?.trim() || null,
      notes: data.get("notes")?.trim() || null,
      items: Cart.toOrderItems(),
    };

    btn.disabled = true;
    btn.innerHTML = `<span class="spinner"></span> Placing order...`;
    try {
      const order = await Api.post("/api/orders", payload);
      Cart.clear();
      openSuccess(order);
    } catch (err) {
      Toast.show(err.message, "error");
      btn.disabled = false;
      btn.textContent = "Place Order";
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
      ? `<div class="order-notes"><span class="order-notes-label">Your note:</span> ${Utils.escapeHtml(order.notes)}</div>`
      : "";
    return `
      <div class="order-summary-list">
        ${rows}
        <div class="summary-row total"><span>Total</span><span>${Utils.formatMoney(order.totalAmount)}</span></div>
        ${notes}
      </div>`;
  }

  function openSuccess(order) {
    currentView = "success";
    const body = `
      <div class="sheet-body">
        <div class="order-success">
          <div class="success-check">✓</div>
          <h2 class="anim-fade-up" style="animation-delay:120ms;">Order Placed!</h2>
          <p class="anim-fade-up" style="animation-delay:180ms;color:var(--text-muted);margin-top:6px;">We'll call you to confirm delivery timing.</p>
          <div class="order-id-chip anim-fade-up" style="animation-delay:240ms;">
            <span class="order-id-label">Order ID:</span>
            <span class="order-id-value">${Utils.escapeHtml(order.orderCode)}</span>
          </div>
        </div>
        <div class="anim-fade-up" style="animation-delay:300ms;">${orderSummaryHtml(order)}</div>
        <p class="screenshot-hint anim-fade-up" style="animation-delay:360ms;">📸 Take a screenshot of this page to keep for your reference.</p>
        <button class="btn btn-dark btn-block anim-fade-up" style="animation-delay:420ms;margin-top:var(--sp-3);" data-role="continue">Continue Shopping</button>
      </div>`;
    const sheet = renderSheet(body, "");
    sheet.querySelector(".sheet-header h2").parentElement.style.borderBottom = "none";
    sheet.querySelector('[data-role="continue"]').addEventListener("click", closeSheet);
  }

  document.getElementById("cartIconBtn").addEventListener("click", openCart);
  document.getElementById("viewCartBtn").addEventListener("click", openCart);

  /** A price changed or a product got disabled from Admin while this
   * customer already had it in their cart — Cart.syncWithCatalog() (run on
   * every catalog load/poll in storefront.js) just dropped or corrected it.
   * Let them know, and if they're looking at the cart or mid-checkout,
   * refresh what they see so they're never confirming stale details. */
  Cart.onCatalogSync(({ removedNames }) => {
    removedNames.forEach((name) => Toast.show(`${name} was removed from your cart — no longer available`, "error"));
    if (currentView === "cart" || currentView === "checkout") {
      openCart();
    }
  });
})();
