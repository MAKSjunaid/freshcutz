/* Tab switching, kicking off each view's first data load after login, and
 * polling in the background so a new order shows up on its own instead of
 * only appearing once the admin happens to click a tab. */

(() => {
  const tabs = document.querySelectorAll(".admin-tabs > .admin-tab[data-view]");
  const views = document.querySelectorAll(".admin-view");
  const POLL_MS = 15000;
  let pollTimer = null;

  function switchTo(viewName) {
    tabs.forEach((t) => t.classList.toggle("active", t.dataset.view === viewName));
    views.forEach((v) => v.classList.toggle("active", v.id === `view-${viewName}`));
    if (viewName === "products" && window.AdminProducts) window.AdminProducts.refresh();
    if (viewName === "orders" && window.AdminOrders) window.AdminOrders.refresh();
    if (viewName === "stats" && window.AdminStats) window.AdminStats.refresh();
    if (viewName === "customers" && window.AdminCustomers) window.AdminCustomers.refresh();
  }

  tabs.forEach((tab) => tab.addEventListener("click", () => switchTo(tab.dataset.view)));

  function startPolling() {
    stopPolling();
    pollTimer = setInterval(() => {
      window.AdminOrders?.refresh();
      window.AdminStats?.refresh();
    }, POLL_MS);
  }

  function stopPolling() {
    if (pollTimer) clearInterval(pollTimer);
    pollTimer = null;
  }

  document.addEventListener("freshcutz:login", () => {
    window.AdminOrders?.refresh();
    window.AdminStats?.refresh();
    window.AdminStore?.refresh();
    startPolling();
  });

  document.addEventListener("freshcutz:logout", stopPolling);
})();
