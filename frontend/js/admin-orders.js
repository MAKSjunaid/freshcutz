/* Orders tab: list + filter by status, mark delivered/cancelled/reopen. */

window.AdminOrders = (() => {
  const list = document.getElementById("ordersList");
  const filterBar = document.getElementById("orderStatusFilter");
  const searchInput = document.getElementById("orderSearchInput");
  let activeStatus = "NEW"; // default view: keep an eye on new incoming orders
  let searchTerm = "";
  let lastSnapshot = null; // last rendered orders, as JSON — lets refresh() skip
  // re-rendering (and re-playing every card's entrance animation) when a
  // background poll finds nothing has actually changed.

  const REOPEN_WINDOW_MS = 24 * 60 * 60 * 1000; // "Reopen as New" only within 24h of order placed

  const badgeClass = { NEW: "badge-new", DELIVERED: "badge-delivered", CANCELLED: "badge-cancelled" };

  // Marking an order delivered/cancelled/reopened changes what the Sales
  // tab's summary cards and "new orders" badge should show — but nothing
  // here used to tell that tab, so it kept showing whatever it last
  // fetched until the next 15-second background poll happened to notice,
  // or until switching tabs kicked off a fresh (but not instant) fetch,
  // leaving stale numbers on screen in the meantime. refreshStatsSoon()
  // below fixes that at the source: called right after such an action
  // succeeds, it asks AdminStats to refresh in the background (whether
  // or not the Sales tab is even the one currently open), so by the time
  // anyone looks at it, it's already correct.
  //
  // Debounced rather than called directly: clearing out several orders
  // in a quick burst would otherwise fire one /api/admin/orders/summary
  // request per click, when only the state after the LAST one in the
  // burst actually matters — this waits a moment after the most recent
  // action before asking, coalescing a burst into a single request.
  const refreshStatsSoon = Utils.debounce(() => window.AdminStats?.refresh(), 400);

  function itemsSummary(order) {
    return order.items
      .map((i) => `<div>${i.quantity} × ${Utils.escapeHtml(i.productName)} (${Utils.escapeHtml(i.variantLabel)}) — ${Utils.formatMoney(i.lineTotal)}</div>`)
      .join("");
  }

  function actionsFor(order) {
    if (order.status === "NEW") {
      return `
        <button class="btn btn-gold btn-sm" data-action="delivered">✓ Mark Delivered</button>
        <button class="btn btn-ghost btn-sm" data-action="cancelled">Cancel Order</button>`;
    }
    const age = Date.now() - new Date(order.createdAt).getTime();
    if (age > REOPEN_WINDOW_MS) return "";
    return `<button class="btn btn-ghost btn-sm" data-action="reopen">↺ Reopen as New</button>`;
  }

  function card(order) {
    return `
    <div class="order-card" data-id="${order.id}">
      <div class="order-card-head">
        <div>
          <div class="order-code-lbl">${Utils.escapeHtml(order.orderCode)}</div>
          <div class="order-time">${Utils.formatDateTime(order.createdAt)}</div>
        </div>
        <span class="badge ${badgeClass[order.status] || ""}">${order.status}</span>
      </div>
      <div class="order-customer">
        <strong>${Utils.escapeHtml(order.customerName)}</strong>
        📞 <a href="tel:${Utils.escapeHtml(order.phone)}">${Utils.escapeHtml(order.phone)}</a><br/>
        🏠 ${Utils.escapeHtml(order.flatNo)}, ${Utils.escapeHtml(order.addressLine)}
        ${order.landmark ? `<br/>📍 ${Utils.escapeHtml(order.landmark)}` : ""}
        ${order.notes ? `<br/>📝 ${Utils.escapeHtml(order.notes)}` : ""}
      </div>
      <div class="order-items-list">${itemsSummary(order)}</div>
      <div class="order-total-row"><span>Total</span><span>${Utils.formatMoney(order.totalAmount)}</span></div>
      ${actionsFor(order) ? `<div class="order-actions">${actionsFor(order)}</div>` : ""}
    </div>`;
  }

  function matchesSearch(order) {
    const q = searchTerm.toLowerCase();
    return (
      (order.orderCode || "").toLowerCase().includes(q) ||
      (order.phone || "").toLowerCase().includes(q) ||
      (order.customerName || "").toLowerCase().includes(q)
    );
  }

  async function refresh() {
    const firstLoadForThisFilter = lastSnapshot === null;
    if (firstLoadForThisFilter) {
      list.innerHTML = `<div class="skeleton" style="height:140px;margin-bottom:12px;"></div>`.repeat(2);
    }
    try {
      // Search filters within whatever the status tab is already showing —
      // it doesn't reach across tabs (an order marked Delivered shouldn't
      // turn up while browsing the Cancelled tab).
      const path = activeStatus ? `/api/admin/orders?status=${activeStatus}` : "/api/admin/orders";
      const fetched = await Api.get(path);
      const orders = searchTerm ? fetched.filter(matchesSearch) : fetched;
      const snapshot = JSON.stringify(orders);
      if (snapshot === lastSnapshot) return; // nothing changed — don't touch the DOM
      lastSnapshot = snapshot;
      list.innerHTML = orders.length
        ? orders.map(card).join("")
        : `<div class="empty-state"><div class="icon">📭</div><p>${searchTerm ? "No orders match your search." : "No orders here yet."}</p></div>`;
      // The order list just changed — the Orders-tab badge count may
      // have too, but nothing here needs the full Sales summary (today/
      // week/month/all-time), so keep this to the one cheap badge query.
      window.AdminStats?.refreshBadge();
    } catch (err) {
      if (firstLoadForThisFilter) {
        list.innerHTML = `<div class="empty-state"><p>${Utils.escapeHtml(err.message)}</p></div>`;
      }
      // otherwise a background poll hiccup shouldn't wipe out what's already shown
    }
  }

  filterBar.addEventListener("click", (e) => {
    const btn = e.target.closest("[data-status]");
    if (!btn) return;
    activeStatus = btn.dataset.status;
    filterBar.querySelectorAll(".admin-tab").forEach((t) => t.classList.toggle("active", t === btn));
    searchTerm = "";
    searchInput.value = "";
    lastSnapshot = null; // switching filters should always show a fresh load
    refresh();
  });

  searchInput.addEventListener(
    "input",
    Utils.debounce(() => {
      searchTerm = searchInput.value.trim();
      lastSnapshot = null; // the filtered set just changed — force a fresh render
      refresh();
    }, 250)
  );

  list.addEventListener("click", async (e) => {
    const card = e.target.closest(".order-card");
    const actionBtn = e.target.closest("[data-action]");
    if (!card || !actionBtn) return;
    const id = card.dataset.id;
    const action = actionBtn.dataset.action;
    actionBtn.disabled = true;
    try {
      await Api.patch(`/api/admin/orders/${id}/${action}`);
      Toast.show(
        action === "delivered" ? "Marked as delivered" : action === "cancelled" ? "Order cancelled" : "Order reopened",
        "success"
      );
      refresh();
      refreshStatsSoon();
    } catch (err) {
      Toast.show(err.message, "error");
      actionBtn.disabled = false;
    }
  });

  return { refresh };
})();
