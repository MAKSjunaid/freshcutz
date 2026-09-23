/* Sales summary cards on the Stats tab, the "new orders" nav badge, plus
 * the date-range filter, xlsx export/import controls. */

window.AdminStats = (() => {
  const grid = document.getElementById("statsGrid");
  const pill = document.getElementById("newOrdersPill");
  const fromInput = document.getElementById("statsFrom");
  const toInput = document.getElementById("statsTo");
  const applyBtn = document.getElementById("applyRangeBtn");
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
    } catch (err) {
      if (firstLoad) {
        grid.innerHTML = `<div class="empty-state" style="grid-column:1/-1;"><p>${Utils.escapeHtml(err.message)}</p></div>`;
      }
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
      const s = await Api.get(`/api/admin/orders/summary/range?from=${range.from}&to=${range.to}`);
      rangeResult.hidden = false;
      rangeResult.innerHTML = card(
        `${s.from} → ${s.to}`,
        Utils.formatMoney(s.total),
        `${s.orders} order${s.orders === 1 ? "" : "s"}`
      );
    } catch (err) {
      Toast.show(err.message, "error");
    } finally {
      applyBtn.disabled = false;
      applyBtn.textContent = "Apply";
    }
  }

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

  applyBtn.addEventListener("click", applyRange);
  exportFilteredBtn.addEventListener("click", () => exportOrders(true));
  exportAllBtn.addEventListener("click", () => exportOrders(false));
  importFile.addEventListener("change", importOrders);

  return { refresh };
})();
