/* Products tab "Select products": tick many products, then change them all at once —
 * show / hide, out of stock / back in stock, category, or delete.
 *
 * While selecting, a bar covers the top of the screen: Cancel · "N selected" · Select all · Actions.
 * The Actions button appears as soon as one product is ticked; it opens a list of everything you can do.
 *
 * Light on purpose: it works on the list the Products tab already loaded (no extra fetch to select),
 * sends ONE request per action (POST /api/admin/products/bulk), then refreshes the list once. */
(() => {
  if (typeof Api === "undefined" || !window.AdminProducts) return;
  const view = document.getElementById("view-products");
  const list = document.getElementById("productsList");
  const sheetRoot = document.getElementById("sheetRoot");
  if (!view || !list || !sheetRoot) return;

  const style = document.createElement("style");
  style.textContent = `
    #view-products.pb-on #addProductFab,#view-products.pb-on .pi-bar,#view-products.pb-on .pi-panel{display:none}
    #view-products.pb-on .admin-product-card{position:relative;cursor:pointer;-webkit-tap-highlight-color:transparent}
    #view-products.pb-on .admin-product-card .thumb-upload-btn{display:none}
    #view-products.pb-on .admin-product-card::after{content:"";position:absolute;top:10px;left:10px;width:26px;height:26px;box-sizing:border-box;border-radius:50%;border:2px solid #fff;background:rgba(0,0,0,.4);z-index:3;color:#111;font-weight:800;font-size:15px;line-height:22px;text-align:center}
    #view-products.pb-on .admin-product-card.pb-sel{outline:2px solid var(--gold,#c9a227);outline-offset:-2px}
    #view-products.pb-on .admin-product-card.pb-sel::after{content:"✓";background:var(--gold,#c9a227);border-color:var(--gold,#c9a227)}
    .pb-bar{position:fixed;top:0;left:0;right:0;z-index:620;display:flex;align-items:center;gap:8px;padding:calc(10px + var(--safe-top,0px)) 12px 10px;background:var(--ink,#1a1408);color:#fff;box-shadow:0 6px 18px rgba(20,16,8,.3)}
    .pb-bar[hidden]{display:none}
    .pb-bar .pb-count{flex:1;min-width:0;font-size:15px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
    .pb-bar .btn{flex:0 0 auto;white-space:nowrap}
    .pb-bar .btn-ghost{color:#fff;border:1px solid rgba(255,255,255,.35);background:transparent}
    .pb-bar[data-busy="1"]{opacity:.6;pointer-events:none}
    .pb-menu{display:flex;flex-direction:column;gap:6px}
    .pb-item{display:flex;align-items:center;gap:12px;width:100%;text-align:left;padding:12px;border:1px solid var(--border,#e4dfd3);border-radius:12px;background:var(--surface,#fff);color:inherit;font:inherit;cursor:pointer}
    .pb-item:active{transform:scale(.99)}
    .pb-item .pb-ico{flex:0 0 auto;width:36px;height:36px;border-radius:10px;display:flex;align-items:center;justify-content:center;font-size:18px;background:rgba(127,127,127,.14)}
    .pb-item b{display:block;font-size:15px}
    .pb-item small{display:block;margin-top:1px;font-size:12.5px;color:var(--text-muted,#777)}
    .pb-item.pb-danger{border-color:rgba(179,38,30,.4)}
    .pb-item.pb-danger b{color:#b3261e}
    .pb-item.pb-danger .pb-ico{background:rgba(179,38,30,.12)}
  `;
  document.head.appendChild(style);

  const bar = document.createElement("div");
  bar.className = "pb-bar";
  bar.hidden = true;
  bar.innerHTML = `
    <button class="btn btn-ghost btn-sm" type="button" data-pb="cancel">✕ Cancel</button>
    <span class="pb-count" data-pb="count">Tap products to select</span>
    <button class="btn btn-ghost btn-sm" type="button" data-pb="all">Select all</button>
    <button class="btn btn-gold btn-sm" type="button" data-pb="actions" hidden>Actions ▾</button>`;
  document.body.appendChild(bar);
  const countEl = bar.querySelector('[data-pb="count"]');
  const allBtn = bar.querySelector('[data-pb="all"]');
  const actionsBtn = bar.querySelector('[data-pb="actions"]');

  const esc = (s) => Utils.escapeHtml(String(s));
  const selected = new Set();
  let on = false;
  let busy = false;
  let observer = null;

  const all = () => window.AdminProducts.getAll() || [];
  const byId = (id) => all().find((p) => p.id === id);

  // ---- select mode on / off ------------------------------------------
  function start() {
    if (on) return;
    if (!all().length) {
      Toast.show("There are no products to select yet.", "error");
      return;
    }
    on = true;
    selected.clear();
    view.classList.add("pb-on");
    bar.hidden = false;
    paint();
    // the list is rebuilt after every refresh; tick the right cards again each time
    observer = new MutationObserver(() => paint());
    observer.observe(list, { childList: true });
  }

  function stop() {
    if (!on) return;
    on = false;
    selected.clear();
    if (observer) observer.disconnect();
    observer = null;
    view.classList.remove("pb-on");
    bar.hidden = true;
    sheetRoot.innerHTML = "";
    list.querySelectorAll(".pb-sel").forEach((c) => c.classList.remove("pb-sel"));
  }

  document.addEventListener("freshcutz:logout", stop);
  // leaving the Products tab (e.g. with the bottom bar) ends selecting, so the bar never covers another tab
  document.addEventListener("click", (e) => {
    if (on && e.target.closest(".admin-tab")) stop();
  });

  function updateBar() {
    const n = selected.size;
    countEl.textContent = n ? `${n} selected` : "Tap products to select";
    actionsBtn.hidden = n === 0;
    allBtn.textContent = n && n === all().length ? "Clear" : "Select all";
  }

  /** Re-applies the ticks to the cards on screen and forgets products that no longer exist. */
  function paint() {
    const ids = new Set(all().map((p) => p.id));
    for (const id of [...selected]) if (!ids.has(id)) selected.delete(id);
    list.querySelectorAll(".admin-product-card").forEach((card) => {
      card.classList.toggle("pb-sel", selected.has(Number(card.dataset.id)));
    });
    updateBar();
  }

  function toggle(id) {
    if (selected.has(id)) selected.delete(id);
    else selected.add(id);
    const card = list.querySelector(`.admin-product-card[data-id="${id}"]`);
    if (card) card.classList.toggle("pb-sel", selected.has(id));
    updateBar();
  }

  // Clicks on a card only tick it while selecting — the card's own buttons and switches are switched off.
  list.addEventListener(
    "click",
    (e) => {
      if (!on) return;
      const card = e.target.closest(".admin-product-card");
      if (!card) return;
      e.preventDefault();
      e.stopPropagation();
      toggle(Number(card.dataset.id));
    },
    true
  );
  list.addEventListener(
    "change",
    (e) => {
      if (on) e.stopPropagation();
    },
    true
  );

  // ---- running an action ---------------------------------------------
  function setBusy(b) {
    busy = b;
    bar.dataset.busy = b ? "1" : "0";
  }

  const DONE = {
    ENABLE: "now visible", DISABLE: "now hidden", OUT_OF_STOCK: "marked out of stock", IN_STOCK: "back in stock",
    CATEGORY: "moved to the category", DELETE: "deleted",
  };

  async function run(action, value) {
    const ids = [...selected];
    setBusy(true);
    try {
      const r = await Api.post("/api/admin/products/bulk", { action, ids, value: value == null ? null : String(value) });
      const n = r.changed;
      Toast.show(n ? `${n} product${n === 1 ? "" : "s"} ${DONE[action]}` : "Nothing to change — they were already like that.", "success");
      if (action === "DELETE") selected.clear();
      if (n) await window.AdminProducts.refresh(true);
      paint();
    } catch (err) {
      Toast.show(err.message, "error");
    } finally {
      setBusy(false);
    }
  }

  function names(max) {
    const ns = [...selected].map((id) => byId(id)?.name).filter(Boolean);
    return ns.slice(0, max).join(", ") + (ns.length > max ? `, … and ${ns.length - max} more` : "");
  }

  function confirmDelete() {
    const n = selected.size;
    if (!confirm(`Delete ${n} product${n === 1 ? "" : "s"}?\n\n${names(5)}\n\nTheir photos are deleted too. This can't be undone.`)) return false;
    if (n >= 10) {
      const typed = prompt(`You are deleting ${n} products. Type DELETE to confirm.`);
      if (!typed || typed.trim().toUpperCase() !== "DELETE") {
        Toast.show("Nothing was deleted.", "error");
        return false;
      }
    }
    return true;
  }

  // ---- sheets --------------------------------------------------------
  function showSheet(title, bodyHtml, footerHtml) {
    sheetRoot.innerHTML = `
      <div class="overlay" data-role="overlay"></div>
      <div class="sheet" role="dialog" aria-modal="true">
        <div class="sheet-header"><h2>${esc(title)}</h2><button class="sheet-close" data-role="close">✕</button></div>
        <div class="sheet-body" data-role="body">${bodyHtml}</div>
        ${footerHtml ? `<div class="sheet-footer">${footerHtml}</div>` : ""}
      </div>`;
    const close = () => { sheetRoot.innerHTML = ""; };
    sheetRoot.querySelector('[data-role="overlay"]').addEventListener("click", close);
    sheetRoot.querySelector('[data-role="close"]').addEventListener("click", close);
    return { close, body: sheetRoot.querySelector('[data-role="body"]') };
  }

  const ACTIONS = [
    { key: "ENABLE", icon: "👁", title: "Show on shop", sub: "Make them visible to customers" },
    { key: "DISABLE", icon: "🙈", title: "Hide from shop", sub: "Customers won't see them" },
    { key: "OUT_OF_STOCK", icon: "🚫", title: "Mark out of stock", sub: "Shown, but can't be ordered" },
    { key: "IN_STOCK", icon: "✅", title: "Mark back in stock", sub: "Can be ordered again" },
    { key: "CATEGORY", icon: "🏷", title: "Change category…", sub: "Move them to another category" },
    { key: "DELETE", icon: "🗑", title: "Delete…", sub: "Remove them for good", danger: true },
  ];

  function actionsSheet() {
    const n = selected.size;
    const { close, body } = showSheet(
      `${n} product${n === 1 ? "" : "s"} selected`,
      `<div class="pb-menu">${ACTIONS.map((a) => `
        <button type="button" class="pb-item${a.danger ? " pb-danger" : ""}" data-act="${a.key}">
          <span class="pb-ico">${a.icon}</span><span><b>${esc(a.title)}</b><small>${esc(a.sub)}</small></span>
        </button>`).join("")}</div>`
    );
    body.addEventListener("click", (e) => {
      const btn = e.target.closest("[data-act]");
      if (!btn || busy) return;
      const key = btn.dataset.act;
      if (key === "CATEGORY") return categorySheet();
      close();
      if (key === "DELETE") {
        if (!confirmDelete()) return;
        return run("DELETE");
      }
      run(key);
    });
  }

  function categorySheet() {
    const n = selected.size;
    const { close, body } = showSheet(
      "Change category",
      `<p class="hint">${n} product${n === 1 ? "" : "s"} will be moved to:</p>
       <div class="field"><label>Category</label>${window.AdminProducts.categoryFieldHtml("", false)}</div>`,
      `<button class="btn btn-gold btn-block" type="button" data-role="ok">Move products</button>`
    );
    window.AdminProducts.bindCategoryField(body);
    sheetRoot.querySelector('[data-role="ok"]').addEventListener("click", async () => {
      const name = window.AdminProducts.readCategory(body);
      if (!name) {
        Toast.show("Pick a category or type a new one.", "error");
        return;
      }
      close();
      await run("CATEGORY", name);
    });
  }

  bar.addEventListener("click", (e) => {
    const btn = e.target.closest("[data-pb]");
    if (!btn || busy) return;
    const key = btn.dataset.pb;
    if (key === "cancel") return stop();
    if (key === "all") {
      const ps = all();
      if (selected.size === ps.length) selected.clear();
      else ps.forEach((p) => selected.add(p.id));
      return paint();
    }
    if (key === "actions" && selected.size) return actionsSheet();
  });

  window.ProductBulk = { start, stop };
})();
