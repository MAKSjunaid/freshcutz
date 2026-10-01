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
  let currentOrders = []; // the orders currently on screen (parsed, not JSON) —
  // kept in sync with lastSnapshot so a delivered/cancelled/reopen action
  // can be reflected locally (see applyLocalStatusChange) without waiting
  // on a full re-fetch of this same list.

  const REOPEN_WINDOW_MS = 24 * 60 * 60 * 1000; // "Reopen as New" only within 24h of order placed

  const badgeClass = { NEW: "badge-new", DELIVERED: "badge-delivered", CANCELLED: "badge-cancelled" };

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
      // This IS the full set of NEW orders whenever that's the active
      // filter (status-filtered server-side, not narrowed by the local
      // search box below) — exactly the number the Orders-tab badge
      // needs, so hand it over directly instead of letting a separate
      // /summary/new-count request ask the server for the same count a
      // second time right alongside this one (most notably right at
      // login, where New is the default tab).
      if (activeStatus === "NEW") window.AdminStats?.setNewOrdersCount(fetched.length);
      const orders = searchTerm ? fetched.filter(matchesSearch) : fetched;
      const snapshot = JSON.stringify(orders);
      if (snapshot === lastSnapshot) return; // nothing changed — don't touch the DOM
      lastSnapshot = snapshot;
      currentOrders = orders;
      list.innerHTML = orders.length
        ? orders.map(card).join("")
        : `<div class="empty-state"><div class="icon">📭</div><p>${searchTerm ? "No orders match your search." : "No orders here yet."}</p></div>`;
      // No badge refresh here on purpose. refresh() only ever runs for
      // reasons that can't move the "new orders" count by themselves —
      // switching filter tabs, searching, the initial load, or a poll-
      // triggered refresh (whose caller, admin-main.js's pollTick, has
      // already just fetched the badge itself to decide whether to call
      // this at all). A delivered/cancelled/reopen action — the one
      // thing that DOES move the count — never reaches this function
      // anymore; it updates the badge directly via adjustNewOrdersCount
      // (see the click handler below).
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
      // The PATCH endpoints already return the full updated order — use
      // that directly instead of re-fetching this whole filtered list
      // (or the Orders badge) from scratch for a change we already know
      // the exact outcome of.
      const updated = await Api.patch(`/api/admin/orders/${id}/${action}`);
      Toast.show(
        action === "delivered" ? "Marked as delivered" : action === "cancelled" ? "Order cancelled" : "Order reopened",
        "success"
      );
      // Every action here moves an order into or out of NEW status by
      // exactly one, so the badge can be adjusted locally too — no
      // AdminStats call here otherwise on purpose: the Sales tab's own
      // visit (switchTo("stats") in admin-main.js) already re-fetches on
      // its own the moment anyone actually opens that tab.
      window.AdminStats?.adjustNewOrdersCount(action === "reopen" ? 1 : -1);
      applyLocalStatusChange(Number(id), updated, card);
    } catch (err) {
      Toast.show(err.message, "error");
      actionBtn.disabled = false;
    }
  });

  /** Reflects a delivered/cancelled/reopen action locally instead of
   *  re-fetching the whole filtered order list from the server for a
   *  change we already know the exact outcome of (the PATCH above
   *  already returned the updated order). Whichever status filter is
   *  active decides what "reflecting it" means:
   *   - a specific status tab (New/Delivered/Cancelled): the order no
   *     longer belongs there — actionsFor() only ever offers an action
   *     that moves an order OUT of its current status, so this is
   *     always a removal, never a case of staying but changing.
   *   - the All tab: the order stays in view either way, so its card is
   *     swapped in place for one built from the updated order.
   *  Keeps currentOrders/lastSnapshot in sync too, so a background poll
   *  shortly after doesn't see a mismatch against what's already
   *  correctly on screen and force an unnecessary full re-render. */
  function applyLocalStatusChange(id, updated, cardEl) {
    const index = currentOrders.findIndex((o) => o.id === id);
    if (activeStatus === "") {
      if (index !== -1) currentOrders[index] = updated;
      cardEl.outerHTML = card(updated);
    } else {
      if (index !== -1) currentOrders.splice(index, 1);
      cardEl.remove();
      if (!list.children.length) {
        list.innerHTML = `<div class="empty-state"><div class="icon">📭</div><p>${searchTerm ? "No orders match your search." : "No orders here yet."}</p></div>`;
      }
    }
    lastSnapshot = JSON.stringify(currentOrders);
  }

  /** Whether the currently active filter is one a brand-new incoming
   *  order (always status NEW) could ever show up in — the New tab
   *  itself, or All. Lets the background poll (admin-main.js) skip
   *  re-fetching this list on a pure "new order arrived" badge increase
   *  when it's sitting on Delivered or Cancelled, since neither of
   *  those can ever gain an order that's still NEW. */
  function affectedByNewOrder() {
    return activeStatus === "" || activeStatus === "NEW";
  }

  return { refresh, affectedByNewOrder };
})();
