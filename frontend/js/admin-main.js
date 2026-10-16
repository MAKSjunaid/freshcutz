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
  // Still checks for new orders every 15 s, but the server answers from a
  // small in-memory cache (it only re-reads the database when an order really
  // changes), so this no longer keeps the Neon database awake. A push
  // notification (sw.js -> the "message" listener below) and returning to the
  // app also refresh immediately.
  const POLL_MS = 15000; // 15 s — answered from an in-memory cache on the server, so it does not touch the DB
  let pollTimer = null;
  let loggedIn = false;
  let currentView = "orders"; // matches the tab marked "active" in admin.html

  function switchTo(viewName) {
    currentView = viewName;
    tabs.forEach((t) => t.classList.toggle("active", t.dataset.view === viewName));
    views.forEach((v) => v.classList.toggle("active", v.id === `view-${viewName}`));
    if (viewName === "products" && window.AdminProducts) window.AdminProducts.refresh();
    if (viewName === "orders" && window.AdminOrders) window.AdminOrders.refresh();
    // Sales reloads only when stale / older than a few minutes / a new day — see
    // AdminStats.refreshIfNeeded — so flipping back and forth costs nothing.
    if (viewName === "stats" && window.AdminStats) window.AdminStats.refreshIfNeeded();
    if (viewName === "customers" && window.AdminCustomers) window.AdminCustomers.refresh();
    if (viewName === "customize" && window.AdminCustomize) window.AdminCustomize.refresh();
  }

  tabs.forEach((tab) => tab.addEventListener("click", () => switchTo(tab.dataset.view)));

  async function pollTick() {
    // Keeps the Orders-tab badge live no matter which tab is open — one
    // small COUNT(*), not the full dashboard summary. It also reports
    // back HOW the "new orders" count moved since the last tick (see
    // admin-stats.js's noteNewOrdersCount): 0 means nothing changed
    // anywhere, so there's nothing for a full re-fetch to pick up and
    // it's skipped entirely. A nonzero delta means something did, but
    // its sign says what kind of something, which decides how much of
    // a refetch is actually warranted:
    //  - delta > 0: an order arrived or was reopened into NEW status.
    //    Either way the only views that can ever show it are the New
    //    and All filters (see AdminOrders.affectedByNewOrder) — it can
    //    never be sitting on Delivered or Cancelled, and it can't touch
    //    the Sales tab's money totals either, since those only ever
    //    count DELIVERED orders and a NEW order isn't one yet.
    //  - delta < 0: an order left NEW for DELIVERED or CANCELLED. Which
    //    of the two isn't knowable from the count alone, and either one
    //    can change what Orders (any filter) or Sales money totals show
    //    — a delivery adds to the money totals, a cancellation can
    //    still remove the order from whatever list currently has it —
    //    so this case keeps the previous, unconditional refetch.
    // (This poll only ever needs to catch a change made from another
    // admin session/device — this session's own delivered/cancelled/
    // reopen actions already update themselves locally; see
    // admin-orders.js's applyLocalStatusChange and adjustNewOrdersCount.)
    const delta = await window.AdminStats?.refreshBadge();
    if (!delta) return;
    if (currentView === "orders" && (delta < 0 || window.AdminOrders?.affectedByNewOrder?.())) {
      window.AdminOrders?.refresh();
    }
    // Sales now lists New orders too, so a brand-new order (delta > 0)
    // changes what it shows just like a delivered/cancelled one does.
    // When Sales isn't the visible tab nothing is fetched — it's just
    // marked stale and reloads the next time it's opened.
    // A period that ended before today can't be touched by a new order, so
    // onRemoteChange leaves it alone (no requests, no re-render).
    if (currentView === "stats") {
      window.AdminStats?.onRemoteChange();
    } else {
      window.AdminStats?.noteRemoteChange();
    }
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
    // Orders is the default visible tab, so its list is worth loading
    // right away — same reasoning for the store toggle below, which
    // lives in the topbar and is always on screen no matter which tab
    // is open. The Sales tab's full multi-bucket summary is neither:
    // it's inside a hidden view the admin hasn't opened yet, so it's
    // loaded lazily the moment the admin actually switches to Sales
    // (see switchTo() above), exactly like Products and Customers
    // already only load once their own tab is opened. No separate
    // badge fetch here either: Orders' default filter is New, so the
    // very refresh() below already asks the server for exactly the
    // NEW orders and hands their count straight to the badge itself
    // (see admin-orders.js) — a second /summary/new-count request for
    // the same number right alongside it would just be duplicate work.
    window.AdminOrders?.refresh();
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
  // A push notification just arrived (sw.js tells every open page): refresh now.
  if ("serviceWorker" in navigator) {
    navigator.serviceWorker.addEventListener("message", (e) => {
      if (loggedIn && e.data && e.data.type === "freshcutz-push") pollTick();
    });
  }

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
