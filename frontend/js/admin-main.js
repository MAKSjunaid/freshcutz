/* Tab switching, kicking off each view's first data load after login, and
 * polling in the background so a new order shows up on its own instead of
 * only appearing once the admin happens to click a tab.
 *
 * The background poll only refreshes whichever tab is actually visible —
 * it used to always refresh both Orders' full list and Sales' full
 * multi-bucket summary every tick, no matter which tab was open, which
 * meant e.g. browsing Sales kept re-fetching the entire Orders list (at
 * whatever status filter it was last left on) for no reason. The one
 * thing that does need to stay live everywhere is the small "new orders"
 * badge on the Orders tab button — that's kept up by its own lightweight
 * endpoint (see admin-stats.js's refreshBadge), not the full summary. */

(() => {
  const tabs = document.querySelectorAll(".admin-tabs > .admin-tab[data-view]");
  const views = document.querySelectorAll(".admin-view");
  const POLL_MS = 15000;
  let pollTimer = null;
  let loggedIn = false;
  let currentView = "orders"; // matches the tab marked "active" in admin.html

  function switchTo(viewName) {
    currentView = viewName;
    tabs.forEach((t) => t.classList.toggle("active", t.dataset.view === viewName));
    views.forEach((v) => v.classList.toggle("active", v.id === `view-${viewName}`));
    if (viewName === "products" && window.AdminProducts) window.AdminProducts.refresh();
    if (viewName === "orders" && window.AdminOrders) window.AdminOrders.refresh();
    if (viewName === "stats" && window.AdminStats) window.AdminStats.refresh();
    if (viewName === "customers" && window.AdminCustomers) window.AdminCustomers.refresh();
  }

  tabs.forEach((tab) => tab.addEventListener("click", () => switchTo(tab.dataset.view)));

  async function pollTick() {
    // Keeps the Orders-tab badge live no matter which tab is open — one
    // small COUNT(*), not the full dashboard summary. It also reports
    // back whether the "new orders" count actually moved since the last
    // tick, which (see admin-stats.js's noteNewOrdersCount) reliably
    // means "did anything about the order set change at all" — so if it
    // didn't, there's nothing for a full re-fetch to pick up and it's
    // skipped entirely, whichever tab is open.
    const changed = await window.AdminStats?.refreshBadge();
    if (!changed) return;
    if (currentView === "orders") window.AdminOrders?.refresh();
    if (currentView === "stats") window.AdminStats?.refresh();
  }

  function startPolling() {
    stopPolling();
    pollTimer = setInterval(pollTick, POLL_MS);
  }

  function stopPolling() {
    if (pollTimer) clearInterval(pollTimer);
    pollTimer = null;
  }

  document.addEventListener("freshcutz:login", () => {
    loggedIn = true;
    window.AdminOrders?.refresh();
    window.AdminStats?.refresh();
    window.AdminStore?.refresh();
    startPolling();
  });

  document.addEventListener("freshcutz:logout", () => {
    loggedIn = false;
    stopPolling();
  });

  /* Same reasoning as the customer page's background-tab pause (see
   * storefront-sync.js): don't keep polling every 15 seconds while this
   * browser tab is backgrounded. Gated on loggedIn so this can't start
   * polling early if the tab becomes visible again while still sitting on
   * the login screen. */
  document.addEventListener("visibilitychange", () => {
    if (!loggedIn) return;
    if (document.hidden) {
      stopPolling();
    } else {
      pollTick();
      startPolling();
    }
  });
})();
