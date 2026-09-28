/* Sales summary cards on the Stats tab, the "new orders" nav badge, plus
 * the date-range filter, xlsx export/import controls. */

window.AdminStats = (() => {
  const grid = document.getElementById("statsGrid");
  const pill = document.getElementById("newOrdersPill");
  const fromInput = document.getElementById("statsFrom");
  const toInput = document.getElementById("statsTo");
  const applyBtn = document.getElementById("applyRangeBtn");
  const clearBtn = document.getElementById("clearRangeBtn");
  const rangeResult = document.getElementById("rangeResult");
  const exportFilteredBtn = document.getElementById("exportFilteredBtn");
  const exportAllBtn = document.getElementById("exportAllBtn");
  const importFile = document.getElementById("importFile");
  const importResult = document.getElementById("importResult");
  let lastSnapshot = null; // skip re-rendering the cards on a background poll
  // that finds nothing changed, so they don't flash every poll cycle.

  function card(label, value, sub) {
    return `<div class="stat-card anim-fade-up">
      <div class="label">${label}</div>
      <div class="value">${value}</div>
      ${sub ? `<div class="sub">${sub}</div>` : ""}
    </div>`;
  }

  // Last known "new orders" badge count. In this app's order lifecycle,
  // EVERY mutation that could change what any Orders/Sales view shows — a
  // new order placed, one marked delivered, cancelled, or reopened —
  // moves an order into or out of NEW status, so it always shifts this
  // count by exactly ±1. That makes it a reliable (if not airtight —
  // two opposite mutations landing in the very same poll tick would net
  // to zero) signal for "did anything actually change" — used below to
  // skip re-fetching a full list or summary the background poll would
  // otherwise re-request every 15 seconds even when nothing moved.
  let lastKnownNewOrders = null;

  function noteNewOrdersCount(newOrders) {
    const changed = lastKnownNewOrders !== null && lastKnownNewOrders !== newOrders;
    lastKnownNewOrders = newOrders;
    return changed;
  }

  async function refresh() {
    const firstLoad = lastSnapshot === null;
    try {
      const s = await Api.get("/api/admin/orders/summary");
      const snapshot = JSON.stringify(s);
      if (snapshot !== lastSnapshot) {
        lastSnapshot = snapshot;
        grid.innerHTML = [
          card("Today", Utils.formatMoney(s.todayTotal), `${s.todayOrders} order${s.todayOrders === 1 ? "" : "s"}`),
          card("Last 7 days", Utils.formatMoney(s.weekTotal), `${s.weekOrders} order${s.weekOrders === 1 ? "" : "s"}`),
          card("Last 30 days", Utils.formatMoney(s.monthTotal), `${s.monthOrders} order${s.monthOrders === 1 ? "" : "s"}`),
          card("All time", Utils.formatMoney(s.allTimeTotal), `${s.allTimeOrders} order${s.allTimeOrders === 1 ? "" : "s"}`),
        ].join("");
      }
      pill.hidden = s.newOrders === 0;
      pill.textContent = s.newOrders;
      noteNewOrdersCount(s.newOrders);
    } catch (err) {
      if (firstLoad) {
        grid.innerHTML = `<div class="empty-state" style="grid-column:1/-1;"><p>${Utils.escapeHtml(err.message)}</p></div>`;
      }
    }
  }

  /** Keeps just the Orders-tab badge live — a single cheap COUNT(*) on
   *  the server, meant to be polled everywhere in the admin panel (not
   *  just the Sales tab) instead of the full multi-bucket summary that
   *  refresh() above fetches. Doesn't touch the stats grid at all.
   *  Returns whether the count actually moved since the last time either
   *  this or refresh() checked it, so the background poll (admin-main.js)
   *  knows whether it's worth re-fetching the currently visible tab's
   *  full data at all. */
  async function refreshBadge() {
    try {
      const { newOrders } = await Api.get("/api/admin/orders/summary/new-count");
      pill.hidden = newOrders === 0;
      pill.textContent = newOrders;
      return noteNewOrdersCount(newOrders);
    } catch (err) {
      // a background badge poll failing silently is fine — the next tick
      // will just try again, and whatever count is already showing stays
      // put rather than being replaced with an error.
      return false;
    }
  }

  function selectedRange() {
    const from = fromInput.value;
    const to = toInput.value;
    if (!from || !to) return null;
    return { from, to };
  }

  async function applyRange() {
    const range = selectedRange();
    if (!range) {
      Toast.show("Pick both a from and to date", "error");
      return;
    }
    applyBtn.disabled = true;
    applyBtn.innerHTML = `<span class="spinner"></span>`;
    try {
      // Just one request — the daily breakdown already carries each day's
      // own total and order count, so the range-wide total/count shown in
      // the card above it is summed from those client-side instead of
      // asking the backend to compute the same SUM+COUNT a second time.
      const daily = await Api.get(`/api/admin/orders/summary/range/daily?from=${range.from}&to=${range.to}`);
      const total = daily.reduce((sum, day) => sum + day.total, 0);
      const orders = daily.reduce((sum, day) => sum + day.orders, 0);
      rangeResult.hidden = false;
      rangeResult.innerHTML =
        card(`${range.from} → ${range.to}`, Utils.formatMoney(total), `${orders} order${orders === 1 ? "" : "s"}`) +
        dailyBreakdownHtml(daily);
      // The Today/Last 7 days/Last 30 days/All time cards are a different,
      // rolling-window view — once a specific date range is applied, this
      // range's own result is what matters, so those get out of the way.
      grid.hidden = true;
      clearBtn.hidden = false;
      // "Export filtered" only makes sense once a range is actually
      // applied — before that there's no filter for it to export.
      exportFilteredBtn.hidden = false;
    } catch (err) {
      Toast.show(err.message, "error");
    } finally {
      applyBtn.disabled = false;
      applyBtn.textContent = "Apply";
    }
  }

  function formatDateHeading(dateStr) {
    // dateStr is a plain "yyyy-MM-dd" — parsed as local midnight (not UTC)
    // so the weekday/date shown always matches the date the backend meant.
    const d = new Date(`${dateStr}T00:00:00`);
    return d.toLocaleDateString("en-IN", { weekday: "short", day: "2-digit", month: "short", year: "numeric" });
  }

  /** Same items-list markup as the Orders tab's own order cards (see
   *  admin-orders.js's itemsSummary) — kept as its own small copy here
   *  since AdminStats and AdminOrders don't share internals. */
  function itemsSummary(order) {
    return (order.items || [])
      .map(
        (i) =>
          `<div>${i.quantity} × ${Utils.escapeHtml(i.productName)} (${Utils.escapeHtml(i.variantLabel)}) — ${Utils.formatMoney(i.lineTotal)}</div>`
      )
      .join("");
  }

  /** Full order detail markup (phone, address, notes, items) — same shape
   *  an Orders-tab card shows. Filled into a row's detail panel once its
   *  full data has actually been fetched (see the click listener below);
   *  Apply itself only ever ships the lightweight row below. */
  function orderDetailHtml(o) {
    return `
      <div class="order-customer">
        📞 <a href="tel:${Utils.escapeHtml(o.phone)}">${Utils.escapeHtml(o.phone)}</a><br/>
        🏠 ${Utils.escapeHtml(o.flatNo)}, ${Utils.escapeHtml(o.addressLine)}
        ${o.landmark ? `<br/>📍 ${Utils.escapeHtml(o.landmark)}` : ""}
        ${o.notes ? `<br/>📝 ${Utils.escapeHtml(o.notes)}` : ""}
      </div>
      <div class="order-items-list">${itemsSummary(o)}</div>`;
  }

  /** One collapsed row — code, amount, customer, delivered time only.
   *  `row` is the lightweight DailySalesRow the backend sends; its detail
   *  panel starts empty and is filled in on first tap (see the click
   *  listener below). */
  function orderRowHtml(row) {
    return `
      <div class="daily-sales-row" data-id="${row.id}">
        <button type="button" class="daily-sales-row-summary" data-role="toggle">
          <div class="daily-sales-row-top">
            <span><span class="daily-sales-row-chevron">▸</span>${Utils.escapeHtml(row.orderCode)}</span>
            <span>${Utils.formatMoney(row.totalAmount)}</span>
          </div>
          <div class="daily-sales-row-sub">
            <span>${Utils.escapeHtml(row.customerName)}</span>
            <span>${Utils.formatDateTime(row.deliveredAt)}</span>
          </div>
        </button>
        <div class="daily-sales-row-details" hidden></div>
      </div>`;
  }

  /** The applied date range's orders, grouped day by day (newest first) —
   *  shown under the range total so an admin can see exactly which orders
   *  make up that total/count instead of just the two numbers. Each row
   *  is collapsed to its code/amount/customer/time by default (that's all
   *  Apply ever transfers) and expands in place to the full order detail
   *  — fetched from the server only at that moment, see the delegated
   *  click listener below. */
  function dailyBreakdownHtml(daily) {
    if (!daily || daily.length === 0) {
      return `<div class="empty-state"><p>No delivered orders in this range.</p></div>`;
    }
    return `<div class="daily-sales">
      ${daily
        .map(
          (day) => `
        <div class="daily-sales-group anim-fade-up">
          <div class="daily-sales-head">
            <span class="daily-sales-date">${formatDateHeading(day.date)}</span>
            <span class="daily-sales-day-total">${Utils.formatMoney(day.total)} · ${day.orders} order${day.orders === 1 ? "" : "s"}</span>
          </div>
          <div class="daily-sales-rows">
            ${day.rows.map(orderRowHtml).join("")}
          </div>
        </div>`
        )
        .join("")}
    </div>`;
  }

  // Full order details fetched on tap, keyed by order id, so re-opening a
  // row an admin already expanded doesn't hit the server again.
  const orderDetailCache = new Map();

  // Delegated once on the container rather than per-row, so it keeps
  // working across re-renders (a fresh Apply replaces rangeResult's
  // innerHTML entirely). Tapping anywhere on a row's summary toggles just
  // that row's detail panel, fetching its full data the first time it's
  // opened — Apply only ever ships the lightweight code/customer/date/
  // total columns for every order in range, so this is the one small
  // extra request per order an admin actually chooses to look at.
  rangeResult.addEventListener("click", async (e) => {
    if (e.target.closest("a")) return; // a tel: link inside an already-open row — dial, don't collapse it
    const toggleBtn = e.target.closest('[data-role="toggle"]');
    if (!toggleBtn) return;
    const row = toggleBtn.closest(".daily-sales-row");
    const details = row.querySelector(".daily-sales-row-details");
    const isOpen = row.classList.toggle("is-open");
    details.hidden = !isOpen;
    if (!isOpen) return;

    const id = row.dataset.id;
    if (orderDetailCache.has(id)) {
      details.innerHTML = orderDetailCache.get(id);
      return;
    }

    details.innerHTML = `<div class="daily-sales-row-loading"><span class="spinner spinner-dark"></span> Loading order details…</div>`;
    try {
      const order = await Api.get(`/api/admin/orders/${id}`);
      const html = orderDetailHtml(order);
      orderDetailCache.set(id, html);
      // The row may have been collapsed again while this was in flight —
      // don't fill it back in if the admin already closed it.
      if (row.classList.contains("is-open")) {
        details.innerHTML = html;
      }
    } catch (err) {
      details.innerHTML = `<div class="daily-sales-row-loading">${Utils.escapeHtml(err.message)}</div>`;
    }
  });

  function triggerDownload(blob, filename) {
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  }

  async function exportOrders(useRange) {
    const range = useRange ? selectedRange() : null;
    if (useRange && !range) {
      Toast.show("Pick both a from and to date first, or use Export all", "error");
      return;
    }
    const query = range ? `?from=${range.from}&to=${range.to}` : "";
    try {
      const { blob, filename } = await Api.downloadBlob(`/api/admin/orders/export${query}`);
      triggerDownload(blob, filename);
    } catch (err) {
      Toast.show(err.message, "error");
    }
  }

  async function importOrders() {
    const file = importFile.files[0];
    if (!file) return;
    const formData = new FormData();
    formData.append("file", file);
    try {
      const result = await Api.upload("/api/admin/orders/import", formData);
      const summary = `Updated ${result.updated} order${result.updated === 1 ? "" : "s"}` +
        (result.skipped ? `, ${result.skipped} skipped` : "");
      Toast.show(summary, result.skipped ? "error" : "success");
      renderImportResult(result);
      refresh();
      window.AdminOrders?.refresh();
    } catch (err) {
      Toast.show(err.message, "error");
    } finally {
      importFile.value = "";
    }
  }

  /** Shows exactly what the import did — which rows updated, and the exact
   * reason for any that were skipped — instead of leaving the admin to
   * guess whether their file actually changed anything. */
  function renderImportResult(result) {
    importResult.hidden = false;
    const errorLines = (result.errors || [])
      .map((e) => `<div>⚠️ ${Utils.escapeHtml(e)}</div>`)
      .join("");
    importResult.innerHTML = `
      <div class="stat-card anim-fade-up">
        <div class="label">Last import</div>
        <div class="value">${result.updated} updated</div>
        ${result.skipped ? `<div class="sub">${result.skipped} row${result.skipped === 1 ? "" : "s"} skipped</div>` : ""}
        ${errorLines ? `<div class="import-errors">${errorLines}</div>` : ""}
      </div>`;
  }

  /** Undoes applyRange(): empties the date pickers, drops the range result
   *  and its daily breakdown, and brings back the rolling-window cards. */
  function clearRange() {
    fromInput.value = "";
    toInput.value = "";
    rangeResult.hidden = true;
    rangeResult.innerHTML = "";
    grid.hidden = false;
    clearBtn.hidden = true;
    exportFilteredBtn.hidden = true;
  }

  applyBtn.addEventListener("click", applyRange);
  clearBtn.addEventListener("click", clearRange);
  exportFilteredBtn.addEventListener("click", () => exportOrders(true));
  exportAllBtn.addEventListener("click", () => exportOrders(false));
  importFile.addEventListener("change", importOrders);

  return { refresh, refreshBadge };
})();
