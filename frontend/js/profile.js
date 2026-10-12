/* Customer "My Account" — tap the profile icon in the header, enter the
 * phone number you've ordered with, and see your saved address plus your
 * past orders. There's no separate signup/password: "logging in" here just
 * means "look me up the same way checkout already recognizes a returning
 * customer" (see CustomerLookupResponse). A phone with no past orders has
 * nothing to show, so login only succeeds for someone who's ordered before
 * — a brand new customer places their first order through the cart like
 * always, and can "log in" with that phone number afterwards.
 *
 * Reuses window.CheckoutSheet's bottom-sheet shell (renderSheet/
 * closeSheet) from checkout.js — that shell just renders into #sheetRoot,
 * it isn't actually checkout-specific, so there's no reason to duplicate
 * the overlay/sheet markup and animations here. */

window.ProfileSheet = (() => {
  const STORAGE_KEY = "freshcutz_profile_phone";
  const badgeClass = { NEW: "badge-new", DELIVERED: "badge-delivered", CANCELLED: "badge-cancelled" };

  function getSavedPhone() {
    try {
      return localStorage.getItem(STORAGE_KEY) || "";
    } catch {
      return "";
    }
  }

  function savePhone(phone) {
    try {
      localStorage.setItem(STORAGE_KEY, phone);
    } catch {
      /* storage unavailable — they'll just have to log in again next visit */
    }
  }

  function clearPhone() {
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
      /* ignore */
    }
  }

  function openProfileIcon() {
    const phone = getSavedPhone();
    if (phone) {
      openProfile(phone);
    } else {
      openLogin();
    }
  }

  function openLogin() {
    const S = window.CheckoutSheet;
    const body = `
      <form class="sheet-body" id="profileLoginForm" novalidate>
        <p style="font-size:13px;color:var(--text-muted);">
          Enter the phone number you've ordered with to see your saved address and past orders.
        </p>
        <div class="field">
          <label for="pf-phone">Phone number</label>
          <input id="pf-phone" name="phone" type="tel" required placeholder="10-digit mobile number" />
          <div class="error" data-err="phone"></div>
        </div>
      </form>
      <div class="sheet-footer">
        <button class="btn btn-gold btn-block" id="profileLoginBtn" form="profileLoginForm">${SiteCustomize.html("loginButtonText")}</button>
      </div>`;

    const sheet = S.renderSheet(body, "My Account");
    const form = sheet.querySelector("#profileLoginForm");
    const btn = sheet.querySelector("#profileLoginBtn");

    btn.addEventListener("click", async (e) => {
      e.preventDefault();
      const data = new FormData(form);
      const raw = data.get("phone");
      if (!Utils.validatePhone(raw)) {
        sheet.querySelector('[data-err="phone"]').textContent = "Enter a valid 10-digit phone number";
        return;
      }
      const phone = Utils.normalizePhone(raw);

      btn.disabled = true;
      btn.innerHTML = `<span class="spinner"></span> Logging in...`;
      let existing;
      try {
        existing = await Api.get(`/api/customers/lookup?phone=${encodeURIComponent(phone)}`);
      } catch (err) {
        btn.disabled = false;
        btn.textContent = "Login";
        Toast.msg("serverError", { message: err.message });
        return;
      }

      if (!existing || !existing.found) {
        btn.disabled = false;
        btn.textContent = "Login";
        sheet.querySelector('[data-err="phone"]').textContent =
          "We couldn't find any past orders for this number — place an order first!";
        return;
      }

      savePhone(phone);
      openProfile(phone);
    });
  }

  async function openProfile(phone) {
    const S = window.CheckoutSheet;
    S.renderSheet(
      `<div class="sheet-body"><div class="empty-state"><div class="icon">👤</div><p>${SiteCustomize.html("loadingAccountText")}</p></div></div>`,
      "My Account"
    );

    let profile;
    try {
      profile = await Api.get(`/api/customers/lookup?phone=${encodeURIComponent(phone)}`);
    } catch (err) {
      Toast.msg("serverError", { message: err.message });
      profile = null;
    }

    if (!profile || !profile.found) {
      // The saved phone no longer matches anything on file — treat this
      // like a logout instead of showing a broken profile screen.
      clearPhone();
      openLogin();
      return;
    }

    renderProfile(phone, profile);
  }

  function addressSummaryHtml(profile) {
    return `
      <div class="address-summary-card">
        <div class="address-summary-row">🏠 ${Utils.escapeHtml(profile.flatNo)}, ${Utils.escapeHtml(profile.addressLine)}</div>
        ${profile.landmark && SiteCustomize.on("showLandmarkField") ? `<div class="address-summary-row">📍 ${Utils.escapeHtml(profile.landmark)}</div>` : ""}
        <button type="button" class="btn btn-ghost btn-sm" data-role="edit-address" style="margin-top:var(--sp-2);">✏️ Update name &amp; address</button>
      </div>`;
  }

  function renderProfile(phone, profile) {
    const S = window.CheckoutSheet;
    const body = `
      <div class="sheet-body">
        <h3 style="margin:0;font-size:14px;">${SiteCustomize.html("myAddressTitle")}</h3>
        ${addressSummaryHtml(profile)}
        <button type="button" class="btn btn-ghost btn-block" data-role="logout">${SiteCustomize.html("logoutButtonText")}</button>
        <h3 style="margin-top:var(--sp-2);margin-bottom:0;font-size:14px;">${SiteCustomize.html("myOrdersTitle")}</h3>
        <div id="profileOrdersList"></div>
      </div>`;

    const sheet = S.renderSheet(body, `Hi, ${Utils.escapeHtml(profile.name)}!`);

    sheet.querySelector('[data-role="edit-address"]').addEventListener("click", () =>
      renderAddressEdit(phone, profile)
    );
    sheet.querySelector('[data-role="logout"]').addEventListener("click", () => {
      clearPhone();
      S.closeSheet();
      Toast.msg("loggedOut");
    });

    loadOrders(phone, sheet.querySelector("#profileOrdersList"));
  }

  async function loadOrders(phone, container) {
    container.innerHTML = `<div class="skeleton" style="height:110px;border-radius:var(--r-md);margin-top:8px;"></div>`;
    try {
      const orders = await Api.get(`/api/customers/orders?phone=${encodeURIComponent(phone)}`);
      container.innerHTML = orders.length
        ? orders.map(orderCardHtml).join("")
        : `<div class="empty-state"><p>${SiteCustomize.html("noOrdersText")}</p></div>`;
      container.querySelectorAll('[data-action="reorder"]').forEach((btn) => {
        btn.addEventListener("click", () => {
          const order = orders.find((o) => o.orderCode === btn.dataset.orderCode);
          if (order) reorder(order);
        });
      });
    } catch (err) {
      container.innerHTML = `<div class="empty-state"><p>${Utils.escapeHtml(err.message)}</p></div>`;
    }
  }

  /** "Reorder" on a past order — adds each line back to the cart at
   *  today's price/stock (never the snapshotted order-time values), just
   *  like tapping "Add to cart" on the product grid would. Anything no
   *  longer sold, out of stock, or missing the size it was ordered in gets
   *  skipped and rolled into one summary toast instead of failing silently
   *  or one toast per item. */
  function reorder(order) {
    const unavailable = [];
    const toAdd = []; // { product, variant, quantity } — figured out before touching the cart at all

    order.items.forEach((item) => {
      const product = window.Storefront.getProduct(item.productId);
      if (!product) {
        unavailable.push(item.productName); // no longer in the (enabled) catalog at all
        return;
      }
      let variant = item.variant;
      if (variant !== "variant1" && variant !== "variant2") {
        // Order placed before the variant key itself was recorded —
        // fall back to matching the saved label text against this
        // product's current ones.
        variant = item.variantLabel === product.variant2Label ? "variant2" : "variant1";
      }
      const stillOffered = variant === "variant2" ? !!product.variant2Price : true;
      if (!stillOffered || product.outOfStock) {
        unavailable.push(item.productName);
        return;
      }
      toAdd.push({ product, variant, quantity: item.quantity });
    });

    if (toAdd.length > 0) {
      // Reorder replaces the cart with just this order — whatever was
      // already sitting in there gets cleared first, rather than piling
      // this order on top of it. Only clears once we know there's at
      // least one item to actually reorder, so an order that's entirely
      // unavailable never wipes out an existing cart for nothing.
      Cart.clear();
      toAdd.forEach(({ product, variant, quantity }) => {
        Cart.setQuantity(product, variant, quantity);
      });
      window.CheckoutSheet.openCart();
      Toast.msg("reorderAdded");
    }
    if (unavailable.length > 0) {
      Toast.msg("reorderUnavailable", {
        names: unavailable.join(", "),
        isAre: unavailable.length === 1 ? "is" : "are",
      });
    }
  }

  function orderItemsHtml(order) {
    return order.items
      .map(
        (i) =>
          `<div>${i.quantity} × ${Utils.escapeHtml(i.productName)} (${Utils.escapeHtml(i.variantLabel)}) — ${Utils.formatMoney(i.lineTotal)}</div>`
      )
      .join("");
  }

  function orderCardHtml(order) {
    return `
    <div class="profile-order-card">
      <div class="profile-order-head">
        <div>
          <div class="profile-order-code">${SiteCustomize.html("orderIdLabel")} ${Utils.escapeHtml(order.orderCode)}</div>
          <div class="profile-order-time">${Utils.formatDateTime(order.createdAt)}</div>
        </div>
        <span class="badge ${badgeClass[order.status] || ""}">${order.status}</span>
      </div>
      <div class="profile-order-address">
        🏠 ${Utils.escapeHtml(order.flatNo)}, ${Utils.escapeHtml(order.addressLine)}
        ${order.landmark ? `<br/>📍 ${Utils.escapeHtml(order.landmark)}` : ""}
        ${order.notes ? `<br/>📝 ${Utils.escapeHtml(order.notes)}` : ""}
      </div>
      <div class="profile-order-items">${orderItemsHtml(order)}</div>
      <div class="profile-order-total"><span>${SiteCustomize.html("totalLabel")}</span><span>${Utils.formatMoney(order.totalAmount)}</span></div>
      <button
        type="button"
        class="btn btn-gold btn-sm btn-block"
        style="margin-top:8px;"
        data-action="reorder"
        data-order-code="${Utils.escapeHtml(order.orderCode)}"
      >${SiteCustomize.html("reorderButtonText")}</button>
    </div>`;
  }

  function renderAddressEdit(phone, profile) {
    const S = window.CheckoutSheet;
    const body = `
      <form class="sheet-body" id="profileAddressForm" novalidate>
        <button type="button" class="btn btn-ghost btn-sm" data-role="back" style="margin-bottom:4px;">← Back</button>
        <div class="field">
          <label for="pf-name">Your name</label>
          <input id="pf-name" name="name" required value="${Utils.escapeHtml(profile.name || "")}" />
          <div class="error" data-err="name"></div>
        </div>
        <div class="field">
          <label for="pf-flat">Flat / House no.</label>
          <input id="pf-flat" name="flatNo" required value="${Utils.escapeHtml(profile.flatNo || "")}" />
          <div class="error" data-err="flatNo"></div>
        </div>
        <div class="field">
          <label for="pf-address">Building name / Address</label>
          <textarea id="pf-address" name="addressLine" rows="2" required>${Utils.escapeHtml(profile.addressLine || "")}</textarea>
          <div class="error" data-err="addressLine"></div>
        </div>
        ${
          SiteCustomize.on("showLandmarkField")
            ? `<div class="field">
          <label for="pf-landmark">Landmark (optional)</label>
          <input id="pf-landmark" name="landmark" value="${Utils.escapeHtml(profile.landmark || "")}" />
        </div>`
            : `<input type="hidden" name="landmark" value="${Utils.escapeHtml(profile.landmark || "")}" />`
        }
      </form>
      <div class="sheet-footer">
        <button class="btn btn-gold btn-block" id="pfSaveBtn" form="profileAddressForm">${SiteCustomize.html("updateDetailsText")}</button>
      </div>`;

    const sheet = S.renderSheet(body, "Edit your name and address");
    const form = sheet.querySelector("#profileAddressForm");
    const saveBtn = sheet.querySelector("#pfSaveBtn");

    sheet.querySelector('[data-role="back"]').addEventListener("click", () => renderProfile(phone, profile));

    saveBtn.addEventListener("click", async (e) => {
      e.preventDefault();
      const data = new FormData(form);
      const errors = {};
      if (!data.get("name")?.trim()) errors.name = SiteCustomize.text("errName");
      if (!data.get("flatNo")?.trim()) errors.flatNo = SiteCustomize.text("errFlat");
      if (!data.get("addressLine")?.trim()) errors.addressLine = SiteCustomize.text("errAddress");
      if (Object.keys(errors).length > 0) {
        sheet.querySelectorAll(".error").forEach((el) => (el.textContent = ""));
        Object.entries(errors).forEach(([field, msg]) => {
          const el = sheet.querySelector(`[data-err="${field}"]`);
          if (el) el.textContent = msg;
        });
        return;
      }

      const payload = {
        phone,
        name: data.get("name").trim(),
        flatNo: data.get("flatNo").trim(),
        addressLine: data.get("addressLine").trim(),
        landmark: data.get("landmark")?.trim() || null,
      };

      saveBtn.disabled = true;
      saveBtn.innerHTML = `<span class="spinner"></span> Updating...`;
      try {
        const updated = await Api.put("/api/customers/address", payload);
        Toast.msg("detailsUpdated");
        renderProfile(phone, updated);
      } catch (err) {
        Toast.msg("serverError", { message: err.message });
        saveBtn.disabled = false;
        saveBtn.textContent = SiteCustomize.text("updateDetailsText");
      }
    });
  }

  document.getElementById("profileIconBtn").addEventListener("click", openProfileIcon);

  return { open: openProfileIcon, getSavedPhone, savePhone };
})();
