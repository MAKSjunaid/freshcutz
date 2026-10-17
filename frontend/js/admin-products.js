/* Products tab: list, add/edit form sheet, enable toggle, photo upload, delete. */

window.AdminProducts = (() => {
  const list = document.getElementById("productsList");
  const sheetRoot = document.getElementById("sheetRoot");
  let products = [];
  // Whether the catalog has been fetched at least once this login session.
  // Unlike Orders, nothing outside this admin's own actions normally
  // changes the product catalog, and every one of those actions (toggle,
  // toggle-stock, delete, photo upload) already updates `products` and the
  // DOM locally below — so once loaded, a plain tab visit never needs to
  // ask the server again. Reset on logout so a later login always starts
  // from a real fetch rather than carrying over a stale/empty catalog
  // from whoever was signed in before (this is a single-page app — logout
  // doesn't reload the page).
  let loaded = false;
  document.addEventListener("freshcutz:logout", () => {
    loaded = false;
  });

  // ---- category picker: used by the add/edit form and by Bulk actions -------
  // Not a native <select>: on iPhone, the native picker is still closing when the text box asks for focus, so
  // the keyboard never stays open. This list is made of our own buttons, so tapping "New category…" is a real tap
  // and the text box can take focus right inside it — the keyboard opens and stays.
  // The hint "Select category", then "New category…", then the categories your products already use.
  const NEW_CATEGORY = "__new__";
  const BOX = "border:1.5px solid #e3dcc9;border-radius:var(--r-sm);background:#fff;box-sizing:border-box;width:100%;";
  const CHEVRON = "url(&quot;data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='8' viewBox='0 0 12 8'%3E%3Cpath d='M1 1l5 5 5-5' fill='none' stroke='%23555' stroke-width='1.8' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E&quot;)";

  /** Distinct categories in use (case-insensitive), alphabetical, with how many products use each. */
  function categoryList() {
    const found = new Map();
    for (const p of products) {
      const name = (p.category || "").trim();
      if (!name) continue;
      const key = name.toLowerCase();
      const hit = found.get(key);
      if (hit) hit.n++;
      else found.set(key, { name, n: 1 });
    }
    return [...found.values()].sort((a, b) => a.name.localeCompare(b.name));
  }

  /** HTML for the picker. `current` = the category to preselect; with none, the box shows the grey hint
   * "Select category" — nothing is chosen for you. */
  function categoryFieldHtml(current) {
    const cats = categoryList();
    const cur = (current || "").trim();
    if (cur && !cats.some((c) => c.name.toLowerCase() === cur.toLowerCase())) {
      cats.push({ name: cur, n: 0 });
      cats.sort((a, b) => a.name.localeCompare(b.name));
    }
    const shown = cats.find((c) => c.name.toLowerCase() === cur.toLowerCase());
    const value = shown ? shown.name : "";
    const opt = (v, text, on) =>
      `<button type="button" role="option" data-value="${Utils.escapeHtml(v)}" aria-selected="${on}" style="display:block;width:100%;text-align:left;padding:12px 13px;font-size:16px;border:0;border-bottom:1px solid #f0ead9;background:${on ? "#f8f1dc" : "transparent"};color:inherit;font-weight:${on ? 700 : 400};cursor:pointer;">${Utils.escapeHtml(text)}</button>`;
    return `
      <div data-role="cat-pick" data-value="${Utils.escapeHtml(value)}">
        <button type="button" data-role="cat-trigger" aria-haspopup="listbox" aria-expanded="false"
          style="${BOX}display:flex;align-items:center;text-align:left;padding:11px 38px 11px 13px;font-size:16px;color:${value ? "inherit" : "#8a8575"};background:#fff ${CHEVRON} no-repeat right 14px center / 12px 8px;cursor:pointer;">
          <span data-role="cat-label" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">${Utils.escapeHtml(value || "Select category")}</span>
        </button>
        <div data-role="cat-list" role="listbox" hidden style="${BOX}margin-top:6px;max-height:240px;overflow-y:auto;-webkit-overflow-scrolling:touch;">
          ${opt(NEW_CATEGORY, "➕ New category…", false)}
          ${cats.map((c) => opt(c.name, c.name, !!value && c.name === value)).join("")}
        </div>
        <input data-role="cat-new" maxlength="60" placeholder="Type the new category name" autocomplete="off" style="margin-top:8px;display:none;" />
      </div>`;
  }

  function setCategoryValue(pick, value) {
    const trigger = pick.querySelector('[data-role="cat-trigger"]');
    const label = pick.querySelector('[data-role="cat-label"]');
    const list = pick.querySelector('[data-role="cat-list"]');
    const inp = pick.querySelector('[data-role="cat-new"]');
    pick.dataset.value = value;
    list.hidden = true;
    trigger.setAttribute("aria-expanded", "false");
    trigger.style.borderColor = "#e3dcc9";
    const isNew = value === NEW_CATEGORY;
    label.textContent = isNew ? "➕ New category…" : value || "Select category";
    trigger.style.color = value ? "inherit" : "#8a8575";
    list.querySelectorAll("[role=option]").forEach((o) => {
      const on = !isNew && !!value && o.dataset.value === value;
      o.setAttribute("aria-selected", on);
      o.style.fontWeight = on ? "700" : "400";
      o.style.background = on ? "#f8f1dc" : "transparent";
    });
    inp.style.display = isNew ? "" : "none";
    // Right here, inside the person's own tap, so iPhone opens the keyboard and keeps it open.
    if (isNew) inp.focus();
  }

  // one listener for every picker on the page (they are re-created each time a form opens)
  document.addEventListener("click", (e) => {
    const trigger = e.target.closest('[data-role="cat-trigger"]');
    const option = e.target.closest('[data-role="cat-list"] [role="option"]');
    if (option) {
      e.preventDefault();
      setCategoryValue(option.closest('[data-role="cat-pick"]'), option.dataset.value);
      return;
    }
    document.querySelectorAll('[data-role="cat-pick"]').forEach((pick) => {
      const list = pick.querySelector('[data-role="cat-list"]');
      const t = pick.querySelector('[data-role="cat-trigger"]');
      const open = trigger === t ? list.hidden : false; // tapping the box toggles; tapping elsewhere closes
      list.hidden = !open;
      t.setAttribute("aria-expanded", String(open));
      t.style.borderColor = open ? "var(--gold)" : "#e3dcc9";
    });
  });

  /** Makes the box exactly as tall as the other boxes in the same form. */
  function bindCategoryField(root) {
    const trigger = root.querySelector('[data-role="cat-trigger"]');
    if (!trigger) return;
    const ref = root.querySelector('input:not([data-role="cat-new"]):not([type="checkbox"]):not([type="hidden"])');
    const h = ref ? ref.offsetHeight : 0;
    if (h) trigger.style.minHeight = h + "px";
  }

  /** The chosen (or newly typed) category name; "" = none. A typed name that already exists (any capitals) reuses it. */
  function readCategory(root) {
    const pick = root.querySelector('[data-role="cat-pick"]');
    if (!pick || !pick.dataset.value) return ""; // nothing chosen
    if (pick.dataset.value !== NEW_CATEGORY) return pick.dataset.value;
    const inp = pick.querySelector('[data-role="cat-new"]');
    const typed = (inp ? inp.value : "").trim().replace(/\s+/g, " ");
    const hit = categoryList().find((c) => c.name.toLowerCase() === typed.toLowerCase());
    return hit ? hit.name : typed;
  }

  function priceLine(p) {
    const v1 = `${Utils.escapeHtml(p.variant1Label)}: ${Utils.formatMoney(p.variant1Price)}`;
    const v2 = p.variant2Label ? ` · ${Utils.escapeHtml(p.variant2Label)}: ${Utils.formatMoney(p.variant2Price)}` : "";
    return v1 + v2;
  }

  function statusBadgesHtml(p) {
    return `${p.enabled ? '<span class="badge badge-live">Live</span>' : '<span class="badge badge-off">Hidden</span>'}
      ${p.outOfStock ? '<span class="badge badge-off">Out of Stock</span>' : ""}`;
  }

  function card(p) {
    const img = Utils.resolveImageUrl(p.imageUrl);
    return `
    <div class="admin-product-card" data-id="${p.id}">
      <div class="admin-product-thumb">
        <img src="${img}" alt="${Utils.escapeHtml(p.name)}" />
        <button class="thumb-upload-btn" data-action="upload">Change photo</button>
      </div>
      <div class="admin-product-main">
        <div class="name-row">
          <span class="name">${Utils.escapeHtml(p.name)}</span>
          <label class="switch">
            <input type="checkbox" data-action="toggle" ${p.enabled ? "checked" : ""} />
            <span class="track"></span>
          </label>
        </div>
        <div class="name-row">
          <span class="name" style="font-size:12px;font-weight:600;color:var(--text-muted);">Out of stock</span>
          <label class="switch">
            <input type="checkbox" data-action="toggle-stock" ${p.outOfStock ? "checked" : ""} />
            <span class="track"></span>
          </label>
        </div>
        <div class="admin-product-prices">${priceLine(p)}</div>
        <div class="admin-product-prices" data-role="status-badges">${statusBadgesHtml(p)}</div>
        <div class="admin-product-actions">
          <button class="btn btn-outline btn-sm" data-action="edit">Edit</button>
          <button class="btn btn-ghost btn-sm" data-action="delete">Delete</button>
        </div>
      </div>
      <input type="file" accept="image/png,image/jpeg,image/webp" data-role="file-input" hidden />
    </div>`;
  }

  async function refresh(force = false) {
    // force=true is only used right after Save (see saveBtn's click
    // handler): saving can silently reposition every OTHER product's
    // displayOrder too (see reposition() in ProductService), in a way
    // only the server knows the full result of, so that one case still
    // needs a real re-fetch to get the list back in the right order.
    if (loaded && !force) return;
    list.innerHTML = `<div class="skeleton" style="height:96px;margin-bottom:12px;"></div>`.repeat(3);
    try {
      products = await Api.get("/api/admin/products");
      loaded = true;
      list.innerHTML = products.length
        ? products.map(card).join("")
        : `<div class="empty-state"><div class="icon">🍗</div><p>No products yet — tap + to add your first one.</p></div>`;
    } catch (err) {
      list.innerHTML = `<div class="empty-state"><p>${Utils.escapeHtml(err.message)}</p></div>`;
    }
  }

  list.addEventListener("click", (e) => {
    const card = e.target.closest(".admin-product-card");
    if (!card) return;
    const id = Number(card.dataset.id);
    const product = products.find((p) => p.id === id);

    if (e.target.closest('[data-action="upload"]')) {
      card.querySelector('[data-role="file-input"]').click();
    } else if (e.target.closest('[data-action="edit"]')) {
      openForm(product);
    } else if (e.target.closest('[data-action="delete"]')) {
      deleteProduct(product);
    }
  });

  list.addEventListener("change", async (e) => {
    const card = e.target.closest(".admin-product-card");
    if (!card) return;
    const id = Number(card.dataset.id);
    const product = products.find((p) => p.id === id);

    if (e.target.matches('[data-action="toggle"]')) {
      const enabled = e.target.checked;
      try {
        await Api.patch(`/api/admin/products/${id}/enabled`, { enabled });
        product.enabled = enabled;
        // Patch just this card's badges in place — a full refresh() replaces
        // the whole list's innerHTML, which was destroying the just-toggled
        // (still-focused) checkbox and jumping the page back to the top.
        card.querySelector('[data-role="status-badges"]').innerHTML = statusBadgesHtml(product);
        Toast.show(enabled ? `${product.name} is now visible` : `${product.name} is now hidden`, "success");
      } catch (err) {
        e.target.checked = !enabled;
        Toast.show(err.message, "error");
      }
    } else if (e.target.matches('[data-action="toggle-stock"]')) {
      const outOfStock = e.target.checked;
      try {
        await Api.patch(`/api/admin/products/${id}/out-of-stock`, { outOfStock });
        product.outOfStock = outOfStock;
        card.querySelector('[data-role="status-badges"]').innerHTML = statusBadgesHtml(product);
        Toast.show(outOfStock ? `${product.name} marked out of stock` : `${product.name} marked back in stock`, "success");
      } catch (err) {
        e.target.checked = !outOfStock;
        Toast.show(err.message, "error");
      }
    } else if (e.target.matches('[data-role="file-input"]')) {
      const file = e.target.files[0];
      if (!file) return;
      const formData = new FormData();
      formData.append("file", file);
      try {
        const updated = await Api.upload(`/api/admin/products/${id}/image`, formData);
        Toast.show("Photo updated", "success");
        // Only this one product's image changed — nothing else in the
        // catalog moved, and the response's imageUrl already carries a
        // fresh cache-buster (?v=<updatedAt>, see ProductResponse), so
        // swapping this card's <img> src straight from it shows the new
        // photo immediately without re-fetching the whole list.
        product.imageUrl = updated.imageUrl;
        card.querySelector(".admin-product-thumb img").src = Utils.resolveImageUrl(updated.imageUrl);
      } catch (err) {
        Toast.show(err.message, "error");
      }
    }
  });

  async function deleteProduct(product) {
    if (!confirm(`Delete "${product.name}"? This can't be undone.`)) return;
    try {
      await Api.del(`/api/admin/products/${product.id}`);
      Toast.show("Product deleted", "success");
      // Deleting doesn't reposition anything else (ProductService.delete
      // just removes the row, no reindexing) — so the rest of the list
      // is still exactly right as-is; just drop this one card locally
      // instead of re-fetching the whole catalog for it.
      products = products.filter((p) => p.id !== product.id);
      list.querySelector(`.admin-product-card[data-id="${product.id}"]`)?.remove();
      if (!products.length) {
        list.innerHTML = `<div class="empty-state"><div class="icon">🍗</div><p>No products yet — tap + to add your first one.</p></div>`;
      }
    } catch (err) {
      Toast.show(err.message, "error");
    }
  }

  function openForm(product) {
    const isEdit = !!product;
    const body = `
      <form class="sheet-body" id="productForm">
        <div class="field">
          <label>Name</label>
          <input name="name" required value="${isEdit ? Utils.escapeHtml(product.name) : ""}" placeholder="e.g. Chicken Curry Cut" />
        </div>
        <div class="field">
          <label>Description (optional)</label>
          <textarea name="description" rows="2">${isEdit ? Utils.escapeHtml(product.description || "") : ""}</textarea>
        </div>
        <div class="field">
          <label>Category</label>
          ${categoryFieldHtml(isEdit ? product.category : "")}
        </div>
        <div class="field">
          <label>Size 1 label</label>
          <input name="variant1Label" required value="${isEdit ? Utils.escapeHtml(product.variant1Label) : "500g"}" />
        </div>
        <div style="display:flex;gap:10px;">
          <div class="field" style="flex:1;">
            <label>Size 1 price (₹)</label>
            <input name="variant1Price" type="number" min="0" step="1" required value="${isEdit ? product.variant1Price : ""}" />
          </div>
          <div class="field" style="flex:1;">
            <label>Size 1 MRP (₹, optional)</label>
            <input name="variant1Mrp" type="number" min="0" step="1" value="${isEdit && product.variant1Mrp != null ? product.variant1Mrp : ""}" />
          </div>
        </div>
        <label style="display:flex;align-items:center;gap:8px;font-size:13px;font-weight:600;">
          <input type="checkbox" id="hasVariant2" ${isEdit && product.variant2Label ? "checked" : ""} />
          Also sell a second size (e.g. 1kg)
        </label>
        <div id="variant2Fields" style="display:${isEdit && product.variant2Label ? "block" : "none"};display:flex;flex-direction:column;gap:12px;">
          <div class="field">
            <label>Size 2 label</label>
            <input name="variant2Label" value="${isEdit ? Utils.escapeHtml(product.variant2Label || "") : "1kg"}" />
          </div>
          <div style="display:flex;gap:10px;">
            <div class="field" style="flex:1;">
              <label>Size 2 price (₹)</label>
              <input name="variant2Price" type="number" min="0" step="1" value="${isEdit ? product.variant2Price ?? "" : ""}" />
            </div>
            <div class="field" style="flex:1;">
              <label>Size 2 MRP (₹, optional)</label>
              <input name="variant2Mrp" type="number" min="0" step="1" value="${isEdit && product.variant2Mrp != null ? product.variant2Mrp : ""}" />
            </div>
          </div>
        </div>
        <div class="field">
          <label>Position (1 = shows first)</label>
          <input name="displayOrder" type="number" min="1" value="${isEdit ? products.findIndex((p) => p.id === product.id) + 1 : products.length + 1}" />
        </div>
        ${!isEdit ? `<p class="hint">You can add a photo from the product list right after saving.</p>` : ""}
      </form>
      <div class="sheet-footer">
        <button class="btn btn-gold btn-block" id="saveProductBtn" form="productForm">${isEdit ? "Save Changes" : "Add Product"}</button>
      </div>`;

    sheetRoot.innerHTML = `
      <div class="overlay" data-role="overlay"></div>
      <div class="sheet" role="dialog" aria-modal="true">
        <div class="sheet-header"><h2>${isEdit ? "Edit Product" : "Add Product"}</h2><button class="sheet-close" data-role="close">✕</button></div>
        ${body}
      </div>`;

    const close = () => (sheetRoot.innerHTML = "");
    bindCategoryField(sheetRoot);
    sheetRoot.querySelector('[data-role="overlay"]').addEventListener("click", close);
    sheetRoot.querySelector('[data-role="close"]').addEventListener("click", close);

    const hasV2 = sheetRoot.querySelector("#hasVariant2");
    const v2Fields = sheetRoot.querySelector("#variant2Fields");
    hasV2.addEventListener("change", () => {
      v2Fields.style.display = hasV2.checked ? "flex" : "none";
    });

    const form = sheetRoot.querySelector("#productForm");
    const saveBtn = sheetRoot.querySelector("#saveProductBtn");
    saveBtn.addEventListener("click", async (e) => {
      e.preventDefault();
      const data = new FormData(form);
      const payload = {
        name: data.get("name")?.trim(),
        description: data.get("description")?.trim() || null,
        category: readCategory(form) || null,
        variant1Label: data.get("variant1Label")?.trim(),
        variant1Price: Number(data.get("variant1Price")),
        variant1Mrp: data.get("variant1Mrp") ? Number(data.get("variant1Mrp")) : null,
        variant2Label: hasV2.checked ? data.get("variant2Label")?.trim() || null : null,
        variant2Price: hasV2.checked && data.get("variant2Price") ? Number(data.get("variant2Price")) : null,
        variant2Mrp: hasV2.checked && data.get("variant2Mrp") ? Number(data.get("variant2Mrp")) : null,
        enabled: isEdit ? product.enabled : true,
        outOfStock: isEdit ? product.outOfStock : false,
        displayOrder: Number(data.get("displayOrder")) || 0,
      };

      saveBtn.disabled = true;
      saveBtn.innerHTML = `<span class="spinner"></span> Saving...`;
      try {
        if (isEdit) {
          await Api.put(`/api/admin/products/${product.id}`, payload);
        } else {
          await Api.post("/api/admin/products", payload);
        }
        Toast.show("Product saved", "success");
        close();
        refresh(true);
      } catch (err) {
        Toast.show(err.message, "error");
        saveBtn.disabled = false;
        saveBtn.textContent = isEdit ? "Save Changes" : "Add Product";
      }
    });
  }

  document.getElementById("addProductFab").addEventListener("click", () => openForm(null));

  // getAll + the category picker are also used by Bulk actions (admin-product-bulk.js)
  return { refresh, getAll: () => products, categories: categoryList, categoryFieldHtml, bindCategoryField, readCategory };
})();
