/* The two checkout steps: "Your Details" (name + phone) then "Delivery
 * Details" (address). Split out of checkout.js to keep that file under the
 * project's size limit — same reasoning as storefront-sync.js being split
 * out of storefront.js. Uses window.CheckoutSheet (checkout.js) for the
 * shared sheet shell and the success screen.
 *
 * Right after phone is entered, this calls the public lookup endpoint —
 * if it's a returning customer, their flat/address/landmark from their
 * last order are filled in automatically instead of retyped, and their
 * name/phone never has to be entered again on the address step. */

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

  function openDeliveryStep(customerName, phone, existing) {
    const S = window.CheckoutSheet;
    const prefill = existing && existing.found ? existing : null;

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

    const sheet = S.renderSheet(body, prefill ? "Welcome back! Confirm your address" : "Delivery Details");
    const form = sheet.querySelector("#checkoutForm");
    const placeBtn = sheet.querySelector("#placeOrderBtn");

    sheet.querySelector('[data-role="back"]').addEventListener("click", () => openContactStep(customerName, phone));

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
      window.CheckoutSheet.openSuccess(order);
    } catch (err) {
      Toast.show(err.message, "error");
      btn.disabled = false;
      btn.textContent = "Place Order";
    }
  }

  return { open: openContactStep };
})();
