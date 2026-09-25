/* The two checkout steps: "Your Details" (name + phone) then "Delivery
 * Details" (address). Split out of checkout.js to keep that file under the
 * project's size limit — same reasoning as storefront-sync.js being split
 * out of storefront.js. Uses window.CheckoutSheet (checkout.js) for the
 * shared sheet shell and the success screen.
 *
 * Right after phone is entered, this calls the public lookup endpoint —
 * if it's a returning customer, their flat/address/landmark from their
 * last order are filled in automatically instead of retyped, and their
 * name/phone never has to be entered again on the address step.
 *
 * For a returning customer with a complete saved address, the delivery
 * step opens as a read-only summary card (renderAddressSummary) instead
 * of the full form — same idea as the admin order view, just the flat
 * no. and address at a glance — with a "Change address" control that
 * drops into the normal editable form (renderAddressForm) if they need
 * to fix anything. First-time customers, or anyone with an incomplete
 * saved address, go straight to the editable form.
 *
 * A customer already logged in via the profile icon (see profile.js)
 * skips the "Your Details" name/phone step entirely — checkout.js calls
 * openFinalStep(phone) instead of open(), which jumps straight to the
 * same address-summary screen (retitled "Final Step" and with the cart's
 * items listed too, via renderAddressSummary's skipContactStep option)
 * since there's nothing left to ask them. */

window.CheckoutForm = (() => {
  function openContactStep(prefillName = "", prefillPhone = "") {
    const S = window.CheckoutSheet;
    const body = `
      <form class="sheet-body" id="contactForm" novalidate>
        <div class="field">
          <label for="cf-name">Your name</label>
          <input id="cf-name" name="customerName" required placeholder="e.g. Ayesha Khan" value="${Utils.escapeHtml(prefillName)}" />
          <div class="error" data-err="customerName"></div>
        </div>
        <div class="field">
          <label for="cf-phone">Phone number</label>
          <input id="cf-phone" name="phone" type="tel" required placeholder="10-digit mobile number" value="${Utils.escapeHtml(prefillPhone)}" />
          <div class="error" data-err="phone"></div>
        </div>
      </form>
      <div class="sheet-footer">
        <button class="btn btn-gold btn-block" id="contactNextBtn" form="contactForm">Next →</button>
      </div>`;

    const sheet = S.renderSheet(body, "Your Details");
    const form = sheet.querySelector("#contactForm");
    const nextBtn = sheet.querySelector("#contactNextBtn");

    nextBtn.addEventListener("click", async (e) => {
      e.preventDefault();
      const data = new FormData(form);
      const errors = {};
      if (!data.get("customerName")?.trim()) errors.customerName = "Please enter your name";
      if (!Utils.validatePhone(data.get("phone"))) errors.phone = "Enter a valid 10-digit phone number";
      if (Object.keys(errors).length > 0) {
        showErrors(sheet, errors);
        return;
      }

      const customerName = data.get("customerName").trim();
      const phone = Utils.normalizePhone(data.get("phone"));

      nextBtn.disabled = true;
      nextBtn.innerHTML = `<span class="spinner"></span> Checking...`;
      let existing = null;
      try {
        existing = await Api.get(`/api/customers/lookup?phone=${encodeURIComponent(phone)}`);
      } catch {
        existing = null; // a lookup hiccup shouldn't block checkout — just start with a blank address
      }
      openDeliveryStep(customerName, phone, existing);
    });
  }

  /** A saved address only counts as "complete" (worth showing as a
   * read-only summary) if it actually has the two required fields —
   * otherwise fall straight through to the normal editable form. */
  function hasSavedAddress(prefill) {
    return !!(prefill && prefill.flatNo?.trim() && prefill.addressLine?.trim());
  }

  function checkoutFooterHtml() {
    return `
      <div class="sheet-footer">
        <div class="summary-row"><span>Items</span><span>${Cart.getLineCount()}</span></div>
        <div class="summary-row total"><span>Total (Cash on Delivery)</span><span>${Utils.formatMoney(Cart.getTotal())}</span></div>
        <button class="btn btn-gold btn-block" style="margin-top:10px;" id="placeOrderBtn" form="checkoutForm">
          Place Order
        </button>
      </div>`;
  }

  function openDeliveryStep(customerName, phone, existing) {
    const prefill = existing && existing.found ? existing : null;
    if (hasSavedAddress(prefill)) {
      renderAddressSummary(customerName, phone, prefill);
    } else {
      renderAddressForm(customerName, phone, prefill);
    }
  }

  /** Entry point for a customer already logged in via the profile icon —
   * checkout.js calls this instead of open() so they never see the
   * "Your Details" name/phone step again; their name comes straight from
   * the same lookup profile.js already trusts. Falls back to the normal
   * name/phone flow if the saved login doesn't resolve to anything
   * (e.g. it was cleared server-side), same defensive pattern profile.js
   * itself uses. */
  async function openFinalStep(phone) {
    const S = window.CheckoutSheet;
    S.renderSheet(
      `<div class="sheet-body"><div class="empty-state"><div class="icon">🛒</div><p>Loading your details…</p></div></div>`,
      "Final Step"
    );

    let existing;
    try {
      existing = await Api.get(`/api/customers/lookup?phone=${encodeURIComponent(phone)}`);
    } catch {
      existing = null;
    }

    if (!existing || !existing.found || !hasSavedAddress(existing)) {
      openContactStep();
      return;
    }

    renderAddressSummary(existing.name, phone, existing, { skipContactStep: true });
  }

  /** Line-by-line cart contents, shown on the "Final Step" screen (a
   * logged-in customer skipping straight past the name/phone step has
   * never otherwise seen their cart summarized before placing the order,
   * unlike the normal flow where they just came from the cart sheet). */
  function cartItemsSectionHtml() {
    // Exactly the order-success screen's own summary card (see
    // checkout.js's orderSummaryHtml) — same .order-summary-list/
    // .summary-row classes, so what you confirm here and what you see
    // right after placing the order look identical.
    const rows = Cart.getItems()
      .map(
        (i) => `<div class="summary-row">
          <span>${i.quantity} × ${Utils.escapeHtml(i.name)} (${Utils.escapeHtml(i.variantLabel)})</span>
          <span>${Utils.formatMoney(i.unitPrice * i.quantity)}</span>
        </div>`
      )
      .join("");
    return `
      <div class="field">
        <label>Items</label>
        <div class="order-summary-list">
          ${rows}
          <div class="summary-row total"><span>Total</span><span>${Utils.formatMoney(Cart.getTotal())}</span></div>
        </div>
      </div>`;
  }

  /** Returning customer, complete saved address: the "Final Step" screen
   * — address summary (🏠 flat/house, 📍 landmark) the way admin sees it
   * on an order, the cart's items in the same card style as the
   * order-success screen, and the notes field, all before Place Order.
   * "Change address" swaps in the normal editable form.
   *
   * Two ways in, one visual difference: arriving via the profile-login
   * shortcut (opts.skipContactStep, from openFinalStep) has no "Back" —
   * there's no prior step in that shortened flow — and a plain "Final
   * Step" title. Arriving here after typing a phone number that turned
   * out to match a saved address (the normal openDeliveryStep path) keeps
   * the "← Back" (to fix the name/phone just typed) and greets them by
   * name, since this is the first they're seeing of "Final Step". */
  function renderAddressSummary(customerName, phone, prefill, opts = {}) {
    const S = window.CheckoutSheet;
    const skipContactStep = !!opts.skipContactStep;

    const backButtonHtml = skipContactStep
      ? ""
      : `<button type="button" class="btn btn-ghost btn-sm" data-role="back" style="margin-bottom:4px;">← Back</button>`;

    const body = `
      <form class="sheet-body" id="checkoutForm" novalidate>
        ${backButtonHtml}
        <div class="address-summary-card">
          <div class="address-summary-row">🏠 ${Utils.escapeHtml(prefill.flatNo)}, ${Utils.escapeHtml(prefill.addressLine)}</div>
          ${prefill.landmark ? `<div class="address-summary-row">📍 ${Utils.escapeHtml(prefill.landmark)}</div>` : ""}
          <button type="button" class="btn btn-ghost btn-sm" data-role="edit-address" style="margin-top:var(--sp-2);">✏️ Change address</button>
        </div>
        <input type="hidden" name="flatNo" value="${Utils.escapeHtml(prefill.flatNo)}" />
        <input type="hidden" name="addressLine" value="${Utils.escapeHtml(prefill.addressLine)}" />
        <input type="hidden" name="landmark" value="${Utils.escapeHtml(prefill.landmark || "")}" />
        ${cartItemsSectionHtml()}
        <div class="field">
          <label for="cf-notes">Special instructions (optional)</label>
          <textarea id="cf-notes" name="notes" rows="2" placeholder="e.g. Call before delivering"></textarea>
        </div>
      </form>
      ${checkoutFooterHtml()}`;

    const title = skipContactStep
      ? "Final Step"
      : `Welcome back, ${Utils.escapeHtml(customerName)}! Final Step`;

    const sheet = S.renderSheet(body, title);
    const form = sheet.querySelector("#checkoutForm");
    const placeBtn = sheet.querySelector("#placeOrderBtn");

    const backBtn = sheet.querySelector('[data-role="back"]');
    if (backBtn) {
      backBtn.addEventListener("click", () => openContactStep(customerName, phone));
    }
    sheet.querySelector('[data-role="edit-address"]').addEventListener("click", () =>
      renderAddressForm(customerName, phone, prefill, opts)
    );

    placeBtn.addEventListener("click", async (e) => {
      e.preventDefault();
      // Address fields are hidden inputs carrying already-known-good saved
      // values here, so there's nothing to validate — only notes is free text.
      await submitOrder(customerName, phone, form, placeBtn);
    });
  }

  /** The normal editable address form — used for first-time customers,
   * anyone whose saved address is incomplete, and returning customers who
   * tapped "Change address" on the summary card. */
  function renderAddressForm(customerName, phone, prefill, opts = {}) {
    const S = window.CheckoutSheet;
    const cameFromSummary = hasSavedAddress(prefill);

    const body = `
      <form class="sheet-body" id="checkoutForm" novalidate>
        <button type="button" class="btn btn-ghost btn-sm" data-role="back" style="margin-bottom:4px;">← Back</button>
        <div class="field">
          <label for="cf-flat">Flat / House no.</label>
          <input id="cf-flat" name="flatNo" required placeholder="e.g. Flat 302, Block B" value="${Utils.escapeHtml(prefill?.flatNo || "")}" />
          <div class="error" data-err="flatNo"></div>
        </div>
        <div class="field">
          <label for="cf-address">Building name / Address</label>
          <textarea id="cf-address" name="addressLine" rows="2" required placeholder="Building, street, area">${Utils.escapeHtml(prefill?.addressLine || "")}</textarea>
          <div class="error" data-err="addressLine"></div>
        </div>
        <div class="field">
          <label for="cf-landmark">Landmark (optional)</label>
          <input id="cf-landmark" name="landmark" placeholder="e.g. Near XYZ apartments" value="${Utils.escapeHtml(prefill?.landmark || "")}" />
        </div>
        <div class="field">
          <label for="cf-notes">Special instructions (optional)</label>
          <textarea id="cf-notes" name="notes" rows="2" placeholder="e.g. Call before delivering"></textarea>
        </div>
      </form>
      ${checkoutFooterHtml()}`;

    const title = cameFromSummary
      ? "Edit your delivery address"
      : "Delivery Details — just this once! We'll remember it for next time ✨";

    const sheet = S.renderSheet(body, title);
    const form = sheet.querySelector("#checkoutForm");
    const placeBtn = sheet.querySelector("#placeOrderBtn");

    sheet.querySelector('[data-role="back"]').addEventListener("click", () => {
      if (cameFromSummary) {
        renderAddressSummary(customerName, phone, prefill, opts);
      } else {
        openContactStep(customerName, phone);
      }
    });

    placeBtn.addEventListener("click", async (e) => {
      e.preventDefault();
      const errors = validateDelivery(form);
      if (Object.keys(errors).length > 0) {
        showErrors(sheet, errors);
        return;
      }
      await submitOrder(customerName, phone, form, placeBtn);
    });
  }

  function validateDelivery(form) {
    const errors = {};
    const data = new FormData(form);
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

  async function submitOrder(customerName, phone, form, btn) {
    const data = new FormData(form);
    const payload = {
      customerName,
      phone,
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
      // Any successful order — whichever checkout path got them here — logs
      // the customer into the profile system with this phone number, so
      // "My Orders"/"My Address" are ready for them next time without a
      // separate login step.
      window.ProfileSheet && window.ProfileSheet.savePhone(phone);
      window.CheckoutSheet.openSuccess(order);
    } catch (err) {
      Toast.show(err.message, "error");
      btn.disabled = false;
      btn.textContent = "Place Order";
    }
  }

  return { open: openContactStep, openFinalStep };
})();
