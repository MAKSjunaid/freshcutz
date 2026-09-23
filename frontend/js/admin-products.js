/* Products tab: list, add/edit form sheet, enable toggle, photo upload, delete. */

window.AdminProducts = (() => {
  const list = document.getElementById("productsList");
  const sheetRoot = document.getElementById("sheetRoot");
  let products = [];

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

  async function refresh() {
    list.innerHTML = `<div class="skeleton" style="height:96px;margin-bottom:12px;"></div>`.repeat(3);
    try {
      products = await Api.get("/api/admin/products");
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
        await Api.upload(`/api/admin/products/${id}/image`, formData);
        Toast.show("Photo updated", "success");
        refresh();
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
      refresh();
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
          <input name="category" value="${isEdit ? Utils.escapeHtml(product.category || "") : "Chicken Cuts"}" placeholder="e.g. Chicken Cuts, Eggs" />
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
        category: data.get("category")?.trim() || null,
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
        refresh();
      } catch (err) {
        Toast.show(err.message, "error");
        saveBtn.disabled = false;
        saveBtn.textContent = isEdit ? "Save Changes" : "Add Product";
      }
    });
  }

  document.getElementById("addProductFab").addEventListener("click", () => openForm(null));

  return { refresh };
})();
