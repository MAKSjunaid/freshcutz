/* Products tab "Bulk actions" menu: download the Excel template, "Import products" (many products from one
 * Excel sheet), "Add photos" (many photos at once, matched to the products by file name), "Select products"
 * (opens the select mode in admin-product-bulk.js) and "Download all products" (Excel).
 *
 * Photos are made smaller here in the browser first (max 1200 px, JPEG) so a bulk upload stays
 * quick and light on the free server, then sent one at a time through the normal "Change photo"
 * endpoint. A file belongs to the product whose name it spells: "mutton-curry-cut.jpg",
 * "Mutton Curry Cut.png" and "001-mutton-curry-cut.jpeg" all match "Mutton Curry Cut". */
(() => {
  if (typeof Api === "undefined") return;
  const view = document.getElementById("view-products");
  const listEl = document.getElementById("productsList");
  if (!view || !listEl) return;

  const style = document.createElement("style");
  style.textContent = `
    .pi-bar{display:flex;gap:8px;flex-wrap:wrap;margin:0 0 12px;position:relative}
    .pi-menu{position:absolute;top:100%;left:0;z-index:40;margin-top:6px;min-width:270px;max-width:calc(100vw - 32px);padding:6px;border:1px solid var(--border,#e2e2e2);border-radius:12px;background:var(--surface,#fff);box-shadow:0 10px 30px rgba(0,0,0,.18)}
    .pi-menu[hidden]{display:none}
    .pi-item{display:block;width:100%;text-align:left;padding:9px 10px;border:0;border-radius:8px;background:transparent;color:inherit;font:inherit;cursor:pointer}
    .pi-item:hover,.pi-item:focus-visible{background:rgba(127,127,127,.14);outline:none}
    .pi-item[disabled]{opacity:.5;cursor:default}
    .pi-item b{display:block;font-size:14px}
    .pi-item span{display:block;font-size:12px;color:var(--text-muted,#777);margin-top:1px}
    .pi-panel{border:1px solid var(--border,#e2e2e2);border-radius:12px;padding:12px 14px;margin:0 0 14px;background:var(--surface,#fff);font-size:14px;line-height:1.45}
    .pi-panel[hidden]{display:none}
    .pi-row{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-top:10px}
    .pi-check{display:flex;gap:8px;align-items:center;cursor:pointer}
    .pi-hint{color:var(--text-muted,#777);font-size:13px;margin-top:4px}
    .pi-title{font-weight:700;margin-bottom:2px}
    .pi-bad{color:#b3261e;font-size:13px;margin-top:6px;max-height:160px;overflow:auto}
    .pi-bad div{margin-top:2px}
    .pi-spin{display:inline-block;width:14px;height:14px;border:2px solid currentColor;border-right-color:transparent;border-radius:50%;animation:pispin .7s linear infinite;vertical-align:-2px;margin-right:6px}
    @keyframes pispin{to{transform:rotate(360deg)}}
  `;
  document.head.appendChild(style);

  const bar = document.createElement("div");
  bar.className = "pi-bar";
  bar.innerHTML = `
    <button class="btn btn-gold btn-sm" type="button" id="piBulkBtn" aria-haspopup="true" aria-expanded="false">⚙ Bulk actions ▾</button>
    <div class="pi-menu" id="piMenu" role="menu" hidden>
      <button class="pi-item" type="button" role="menuitem" id="piTemplateBtn"><b>📄 Download template</b><span>Excel sheet with one sample row to fill in</span></button>
      <button class="pi-item" type="button" role="menuitem" id="piImportBtn"><b>⬆ Import products</b><span>Add many products from an Excel sheet</span></button>
      <button class="pi-item" type="button" role="menuitem" id="piPhotosBtn"><b>🖼 Add photos</b><span>Many photos at once, matched by file name</span></button>
      <button class="pi-item" type="button" role="menuitem" id="piSelectBtn"><b>☑ Select products</b><span>Hide, out of stock, category, prices or delete many at once</span></button>
      <button class="pi-item" type="button" role="menuitem" id="piExportBtn"><b>📥 Download all products</b><span>Your whole catalog as an Excel file</span></button>
    </div>
    <input type="file" id="piXlsx" accept=".xlsx" hidden />
    <input type="file" id="piPics" accept="image/png,image/jpeg,image/webp" multiple hidden />`;
  const panel = document.createElement("div");
  panel.className = "pi-panel";
  panel.hidden = true;
  view.insertBefore(panel, listEl);
  view.insertBefore(bar, panel);

  const bulkBtn = bar.querySelector("#piBulkBtn");
  const menu = bar.querySelector("#piMenu");
  const templateBtn = bar.querySelector("#piTemplateBtn");
  const importBtn = bar.querySelector("#piImportBtn");
  const photosBtn = bar.querySelector("#piPhotosBtn");
  const selectBtn = bar.querySelector("#piSelectBtn");
  const exportBtn = bar.querySelector("#piExportBtn");
  const xlsxInput = bar.querySelector("#piXlsx");
  const picsInput = bar.querySelector("#piPics");

  // While the menu is open the button turns into "Cancel", so there is always a way back out.
  function setMenu(open) {
    menu.hidden = !open;
    bulkBtn.setAttribute("aria-expanded", String(open));
    bulkBtn.textContent = open ? "✕ Cancel" : "⚙ Bulk actions ▾";
  }
  function closeMenu() {
    setMenu(false);
  }
  bulkBtn.addEventListener("click", (e) => {
    e.stopPropagation();
    setMenu(menu.hidden);
  });
  document.addEventListener("click", (e) => {
    if (!menu.hidden && !menu.contains(e.target)) closeMenu();
  });
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") closeMenu();
  });
  let busy = false;

  document.addEventListener("freshcutz:logout", () => {
    panel.hidden = true;
    panel.innerHTML = "";
  });

  const esc = (s) => Utils.escapeHtml(String(s));
  function setBusy(b) {
    busy = b;
    [bulkBtn, templateBtn, importBtn, photosBtn, selectBtn, exportBtn].forEach((el) => { el.disabled = b; });
  }

  // ---- Import products ------------------------------------------------
  function openImportPanel(note) {
    if (busy) return;
    panel.hidden = false;
    panel.innerHTML = `
      <div class="pi-title">Import products from Excel</div>
      ${note ? `<div class="pi-hint">${note}</div>` : ""}
      <div class="pi-hint">Use the FreshCutz template (Bulk actions → Download template). Products whose name is already in your shop are skipped, so importing the same file twice is safe.</div>
      <div class="pi-row">
        <label class="pi-check"><input type="checkbox" id="piShow" checked /> Show the products on the shop right away</label>
      </div>
      <div class="pi-row">
        <button class="btn btn-gold btn-sm" type="button" id="piPick">Choose Excel file…</button>
        <button class="btn btn-ghost btn-sm" type="button" id="piClose">Close</button>
      </div>
      <div id="piOut"></div>`;
    panel.querySelector("#piPick").addEventListener("click", () => xlsxInput.click());
    panel.querySelector("#piClose").addEventListener("click", () => { panel.hidden = true; });
  }
  importBtn.addEventListener("click", () => { closeMenu(); openImportPanel(""); });

  // ---- Download template / Download all products ----------------------
  async function download(path, filename, doneText) {
    if (busy) return;
    setBusy(true);
    try {
      const { blob } = await Api.downloadBlob(path);
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 5000);
      Toast.show(doneText, "success");
      return true;
    } catch (err) {
      Toast.show(err.message, "error");
      return false;
    } finally {
      setBusy(false);
    }
  }
  templateBtn.addEventListener("click", async () => {
    closeMenu();
    const ok = await download("/api/admin/products/template", "FreshCutz-products-template.xlsx", "Template downloaded");
    if (ok) openImportPanel("Fill in the downloaded sheet (replace or delete the EXAMPLE row), then choose it here.");
  });
  exportBtn.addEventListener("click", () => {
    closeMenu();
    download("/api/admin/products/export", "FreshCutz-products.xlsx", "Products downloaded");
  });
  selectBtn.addEventListener("click", () => {
    closeMenu();
    if (window.ProductBulk) window.ProductBulk.start();
    else Toast.show("Select mode is not available — refresh the page.", "error");
  });

  xlsxInput.addEventListener("change", async () => {
    const file = xlsxInput.files[0];
    if (!file) return;
    const out = panel.querySelector("#piOut");
    if (!out) return;
    const show = panel.querySelector("#piShow")?.checked !== false;
    setBusy(true);
    out.innerHTML = `<div class="pi-hint"><span class="pi-spin"></span>Importing… (about ${show ? "a few seconds" : "a few seconds"})</div>`;
    try {
      const fd = new FormData();
      fd.append("file", file);
      fd.append("show", show ? "true" : "false");
      const r = await Api.upload("/api/admin/products/import", fd);
      const lines = [
        `<div><b>${r.created}</b> product${r.created === 1 ? "" : "s"} added${r.hidden ? ` (${r.hidden} kept hidden)` : ""}.</div>`,
      ];
      if (r.skipped) lines.push(`<div>${r.skipped} skipped — already in your shop: ${esc((r.skippedNames || []).slice(0, 8).join(", "))}${(r.skippedNames || []).length > 8 ? "…" : ""}</div>`);
      if ((r.errors || []).length) lines.push(`<div class="pi-bad">${r.errors.map((e) => `<div>⚠️ ${esc(e)}</div>`).join("")}</div>`);
      if (r.created) lines.push(`<div class="pi-hint">Next: use <b>Add photos</b> to attach the pictures.</div>`);
      out.innerHTML = lines.join("");
      Toast.show(`${r.created} product${r.created === 1 ? "" : "s"} added`, r.errors && r.errors.length ? "error" : "success");
      if (r.created) await window.AdminProducts?.refresh(true);
    } catch (err) {
      out.innerHTML = `<div class="pi-bad">⚠️ ${esc(err.message)}</div>`;
      Toast.show(err.message, "error");
    } finally {
      xlsxInput.value = ""; // so the same file can be chosen again
      setBusy(false);
    }
  });

  // ---- Add photos -----------------------------------------------------
  photosBtn.addEventListener("click", () => {
    closeMenu();
    if (busy) return;
    panel.hidden = false;
    panel.innerHTML = `
      <div class="pi-title">Add photos in bulk</div>
      <div class="pi-hint">Select many photos at once. Each photo goes to the product whose name its file is called, for example <b>mutton-curry-cut.jpg</b> → "Mutton Curry Cut". JPG, PNG or WEBP.</div>
      <div class="pi-row">
        <label class="pi-check"><input type="checkbox" id="piReplace" /> Replace photos that products already have</label>
      </div>
      <div class="pi-row">
        <button class="btn btn-gold btn-sm" type="button" id="piPickPics">Choose photos…</button>
        <button class="btn btn-ghost btn-sm" type="button" id="piClose">Close</button>
      </div>
      <div id="piOut"></div>`;
    panel.querySelector("#piPickPics").addEventListener("click", () => picsInput.click());
    panel.querySelector("#piClose").addEventListener("click", () => { panel.hidden = true; });
  });

  function slug(text) {
    return String(text).toLowerCase().replace(/&/g, "and").replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
  }
  function fileKey(name) {
    let base = name.replace(/\.[a-z0-9]+$/i, "");
    base = base.replace(/^\d{1,3}[-_. ]+/, "");
    return slug(base);
  }

  async function shrink(file) {
    const MAX = 1200;
    try {
      const bmp = await createImageBitmap(file);
      const scale = Math.min(1, MAX / Math.max(bmp.width, bmp.height));
      const canvas = document.createElement("canvas");
      canvas.width = Math.max(1, Math.round(bmp.width * scale));
      canvas.height = Math.max(1, Math.round(bmp.height * scale));
      const ctx = canvas.getContext("2d");
      ctx.fillStyle = "#fff"; // JPEG has no transparency
      ctx.fillRect(0, 0, canvas.width, canvas.height);
      ctx.drawImage(bmp, 0, 0, canvas.width, canvas.height);
      if (bmp.close) bmp.close();
      const blob = await new Promise((resolve) => canvas.toBlob(resolve, "image/jpeg", 0.85));
      if (blob) return blob;
    } catch (_) {
      /* fall through: send the original if it is small enough */
    }
    if (file.size <= 2 * 1024 * 1024) return file;
    throw new Error("this image can't be read or is over 2 MB");
  }

  picsInput.addEventListener("change", async () => {
    const files = Array.from(picsInput.files || []);
    const out = panel.querySelector("#piOut");
    if (!files.length || !out) return;
    const replace = panel.querySelector("#piReplace")?.checked === true;
    setBusy(true);
    out.innerHTML = `<div class="pi-hint"><span class="pi-spin"></span>Reading your products…</div>`;
    try {
      const products = await Api.get("/api/admin/products");
      const byKey = new Map();
      products.forEach((p) => {
        const k = slug(p.name);
        if (!byKey.has(k)) byKey.set(k, p);
      });
      const unmatched = [];
      const kept = [];
      const todo = [];
      const used = new Set();
      files.sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }));
      for (const f of files) {
        const p = byKey.get(fileKey(f.name));
        if (!p) { unmatched.push(f.name); continue; }
        if (used.has(p.id)) { kept.push(`${f.name} (a second photo for ${p.name})`); continue; }
        if (p.imageUrl && !replace) { kept.push(`${p.name} (already has a photo)`); continue; }
        used.add(p.id);
        todo.push({ file: f, product: p });
      }
      let done = 0;
      const failed = [];
      for (const item of todo) {
        out.innerHTML = `<div class="pi-hint"><span class="pi-spin"></span>Uploading photo ${done + 1} of ${todo.length}: ${esc(item.product.name)}…</div>`;
        try {
          const blob = await shrink(item.file);
          const fd = new FormData();
          fd.append("file", blob, "photo.jpg");
          await Api.upload(`/api/admin/products/${item.product.id}/image`, fd);
          done++;
        } catch (err) {
          failed.push(`${item.file.name}: ${err.message}`);
        }
      }
      const lines = [`<div><b>${done}</b> photo${done === 1 ? "" : "s"} added.</div>`];
      if (kept.length) lines.push(`<div class="pi-hint">Left as they were (${kept.length}): ${esc(kept.slice(0, 6).join("; "))}${kept.length > 6 ? "…" : ""}</div>`);
      if (unmatched.length) lines.push(`<div class="pi-bad"><div>⚠️ No product matches these file names (${unmatched.length}) — rename them to the product name:</div><div>${esc(unmatched.join(", "))}</div></div>`);
      if (failed.length) lines.push(`<div class="pi-bad">${failed.map((m) => `<div>⚠️ ${esc(m)}</div>`).join("")}</div>`);
      out.innerHTML = lines.join("");
      Toast.show(`${done} photo${done === 1 ? "" : "s"} added`, failed.length || unmatched.length ? "error" : "success");
      if (done) await window.AdminProducts?.refresh(true);
    } catch (err) {
      out.innerHTML = `<div class="pi-bad">⚠️ ${esc(err.message)}</div>`;
      Toast.show(err.message, "error");
    } finally {
      picsInput.value = "";
      setBusy(false);
    }
  });
})();
