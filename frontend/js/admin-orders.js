/* Orders tab: list + filter by status, mark delivered/cancelled/reopen.
 *
 * Fetches 10 orders at a time instead of the whole filtered list, loading
 * more as the admin scrolls — a status like Delivered or Cancelled only
 * ever grows over the life of the shop, so asking the database (and
 * shipping over the network) for every single one of them on every tab
 * visit gets slower and slower forever. Search is a real server-side
 * query for the same reason: filtering only what's already been
 * scrolled into view would silently miss matches further down a list
 * that's no longer fully loaded client-side. */

window.AdminOrders = (() => {
  const list = document.getElementById("ordersList");
  const filterBar = document.getElementById("orderStatusFilter");
  const searchInput = document.getElementById("orderSearchInput");
  let activeStatus = "NEW"; // default view: keep an eye on new incoming orders
  let searchTerm = "";
  const PAGE_SIZE = 10;

  // JSON of just the most recently fetched PAGE 0 — deliberately not the
  // whole (possibly many-pages-deep) currentOrders below, so a poll or
  // tab revisit can correctly tell "page 0 is unchanged, leave everything
  // as-is" apart from "something really did change, reset to page 0"
  // regardless of how many further pages have been scrolled in since.
  let firstPageSnapshot = null;
  let currentOrders = []; // every order loaded so far across all pages —
  // kept in sync so a delivered/cancelled/reopen action can be reflected
  // locally (see applyLocalStatusChange) without a server round trip.
  let lastCursor = null; // { statusUpdatedAt, id } of the last order from
  // the most recent real fetch (refresh or loadMore) — the seek cursor
  // loadMore() sends for the next page instead of a page number (see
  // queryFor). Deliberately separate from currentOrders, same reasoning
  // as firstPageSnapshot above: applyLocalStatusChange can remove the
  // very order this cursor points at (marking the last-visible order on
  // a status tab delivered/cancelled removes it from currentOrders), and
  // this must keep pointing at "the last order the server actually gave
  // us" regardless, or the next loadMore() would re-fetch orders we
  // already have (or skip ones we don't).
  let hasMore = false;
  let loadingMore = false;
  let scrollObserver = null;

  const REOPEN_WINDOW_MS = 24 * 60 * 60 * 1000; // "Reopen as New" only within 24h of being marked Delivered/Cancelled

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
    const actionedAt = order.statusUpdatedAt || order.createdAt;
    const age = Date.now() - new Date(actionedAt).getTime();
    if (age > REOPEN_WINDOW_MS) return "";
    return `<button class="btn btn-ghost btn-sm" data-action="reopen">↺ Reopen as New</button>`;
  }

  function card(order) {
    return `
    <div class="order-card" data-id="${order.id}">
      <div class="order-card-head">
        <div>
          <div class="order-code-lbl">${Utils.escapeHtml(order.orderCode)}</div>
          <div class="order-time">Placed: ${Utils.formatDateTime(order.createdAt)}</div>
          ${order.status !== "NEW" && order.statusUpdatedAt ? `<div class="order-time">${order.status === "DELIVERED" ? "Delivered" : "Cancelled"}: ${Utils.formatDateTime(order.statusUpdatedAt)}</div>` : ""}
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

  function emptyStateHtml() {
    return `<div class="empty-state"><div class="icon">📭</div><p>${searchTerm ? "No orders match your search." : "No orders here yet."}</p></div>`;
  }

  function loadMoreRowHtml() {
    return `<div id="ordersLoadMore" class="orders-load-more">
      <span class="spinner spinner-dark" hidden></span>
      <span hidden>Loading more orders…</span>
    </div>`;
  }

  function setLoadMoreSpinning(spinning) {
    const row = document.getElementById("ordersLoadMore");
    if (!row) return;
    row.querySelectorAll("span").forEach((el) => {
      el.hidden = !spinning;
    });
  }

  /** (Re)builds the query string for the current status/search — shared
   * by refresh() (cursor = null, the first page) and loadMore() (cursor
   * = lastCursor, everything after). Omits size/cursor for the New tab:
   * the backend returns every NEW order in one go regardless of what's
   * asked for (see OrderService.listPaged — New orders are few enough
   * that paging them isn't worth a second query), so sending them there
   * would just be misleading noise in the request with no effect on the
   * response. No "page" param at all anymore — cursorStatusUpdatedAt/
   * cursorId (the last order's own statusUpdatedAt/id) replace it, so the
   * database never has to walk past and discard rows just to find where
   * the next page starts (see OrderRepository.findOrdersWithItemsOneTrip).
   * Keyed on statusUpdatedAt (when an order last changed status) rather
   * than createdAt (when it was placed), so Delivered/Cancelled/All page
   * through in the same "most recently actioned first" order they're
   * displayed in. */
  function queryFor(cursor) {
    const params = new URLSearchParams();
    if (activeStatus) params.set("status", activeStatus);
    if (searchTerm) params.set("search", searchTerm);
    if (activeStatus !== "NEW") {
      params.set("size", String(PAGE_SIZE));
      if (cursor) {
        params.set("cursorStatusUpdatedAt", cursor.statusUpdatedAt);
        params.set("cursorId", String(cursor.id));
      }
    }
    return `/api/admin/orders?${params.toString()}`;
  }

  /** Watches the trailing "load more" row — once it scrolls into view
   * (the admin has reached the bottom of what's loaded so far) and
   * there's genuinely another page, fetches it. Re-created on every full
   * refresh() since that replaces the row's DOM node entirely; loadMore()
   * itself never touches the DOM structure around the row, so the same
   * observer instance keeps working across however many pages get
   * appended afterward. */
  function observeLoadMoreRow() {
    if (scrollObserver) scrollObserver.disconnect();
    const row = document.getElementById("ordersLoadMore");
    if (!row) return;
    // Same rootMargin trick as the storefront grid's reveal-on-scroll
    // (see storefront.js): starts loading the next page a bit before the
    // row is actually scrolled into view, so there's no visible pause at
    // the bottom waiting for a request to even start.
    scrollObserver = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting) loadMore();
      },
      { rootMargin: "0px 0px 200px 0px" }
    );
    scrollObserver.observe(row);
  }

  /** The seek cursor pointing just past the last order in this batch —
   * what the next loadMore() call needs to ask for the following page.
   * null for an empty batch (nothing to point past). */
  function cursorFrom(orders) {
    if (!orders.length) return null;
    const last = orders[orders.length - 1];
    return { statusUpdatedAt: last.statusUpdatedAt, id: last.id };
  }

  async function refresh() {
    const firstLoadForThisFilter = firstPageSnapshot === null;
    if (firstLoadForThisFilter) {
      list.innerHTML = `<div class="skeleton" style="height:140px;margin-bottom:12px;"></div>`.repeat(2);
    }
    try {
      const res = await Api.get(queryFor(null));
      // Only trustworthy as the Orders-tab badge count while NOT
      // searching — a search narrows totalElements down to just the
      // matches, which isn't the true "how many new orders" figure the
      // badge needs. Unsearched, this is exactly that figure (status-
      // filtered server-side, across every page, not just this one), so
      // it still saves a separate /summary/new-count request the same
      // way it did before pagination (most notably right at login, where
      // New is the default tab).
      if (activeStatus === "NEW" && !searchTerm) window.AdminStats?.setNewOrdersCount(res.totalElements);

      const snapshot = JSON.stringify(res.orders);
      if (snapshot === firstPageSnapshot) return; // page 0 is unchanged — leave
      // everything as-is, including any further pages already scrolled in;
      // see firstPageSnapshot's own comment up top for why this compares
      // against just page 0 rather than the full (possibly much longer)
      // currentOrders.
      firstPageSnapshot = snapshot;
      currentOrders = res.orders;
      hasMore = res.hasMore;
      lastCursor = cursorFrom(res.orders);

      if (!res.orders.length) {
        list.innerHTML = emptyStateHtml();
      } else {
        list.innerHTML = res.orders.map(card).join("") + (hasMore ? loadMoreRowHtml() : "");
        if (hasMore) observeLoadMoreRow();
      }
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

  async function loadMore() {
    if (!hasMore || loadingMore || !lastCursor) return;
    loadingMore = true;
    setLoadMoreSpinning(true);
    try {
      const res = await Api.get(queryFor(lastCursor));
      currentOrders = currentOrders.concat(res.orders);
      hasMore = res.hasMore;
      if (res.orders.length) lastCursor = cursorFrom(res.orders);
      const row = document.getElementById("ordersLoadMore");
      const cardsHtml = res.orders.map(card).join("");
      if (row) {
        row.insertAdjacentHTML("beforebegin", cardsHtml);
        if (!hasMore) row.remove();
        else setLoadMoreSpinning(false);
      } else {
        // Row got removed from under us somehow (shouldn't normally
        // happen) — fall back to appending straight onto the list so the
        // page isn't silently dropped.
        list.insertAdjacentHTML("beforeend", cardsHtml);
      }
    } catch (err) {
      Toast.show(err.message, "error");
      setLoadMoreSpinning(false);
    } finally {
      loadingMore = false;
    }
  }

  /** Resets every bit of pagination state back to "nothing loaded" —
   * shared by the filter tabs and the search box below, since either one
   * changes what the first page should contain and neither can safely
   * keep whatever was already scrolled in under the old filter/search. */
  function resetPagination() {
    firstPageSnapshot = null;
    currentOrders = [];
    hasMore = false;
    lastCursor = null;
    if (scrollObserver) {
      scrollObserver.disconnect();
      scrollObserver = null;
    }
  }

  filterBar.addEventListener("click", (e) => {
    const btn = e.target.closest("[data-status]");
    if (!btn) return;
    activeStatus = btn.dataset.status;
    filterBar.querySelectorAll(".admin-tab").forEach((t) => t.classList.toggle("active", t === btn));
    searchTerm = "";
    searchInput.value = "";
    resetPagination();
    refresh();
  });

  searchInput.addEventListener(
    "input",
    Utils.debounce(() => {
      searchTerm = searchInput.value.trim();
      resetPagination();
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

    // Flash the instant the button is pressed — not after the PATCH comes
    // back. The admin should see their tap register right away; waiting
    // for a server round trip first is exactly the "nothing happened for
    // a moment" feeling that was reported. flashStartedAt lets
    // applyLocalStatusChange below work out how much of this flash is
    // still left to play once the request actually resolves, instead of
    // always tacking on a full extra FLASH_MS of waiting regardless of
    // how long that took.
    const flashClass = FLASH_CLASS[action];
    if (flashClass) card.classList.add("is-flashing", flashClass);
    const flashStartedAt = Date.now();

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
      // AdminStats refresh() call here otherwise on purpose: the Sales
      // tab's own visit (switchTo("stats") in admin-main.js) already
      // re-fetches its rolling-window cards the moment anyone actually
      // opens that tab.
      window.AdminStats?.adjustNewOrdersCount(action === "reopen" ? 1 : -1);
      // Delivered/Cancelled/Reopen can all change which orders belong to
      // a day's DELIVERED bucket on the Sales tab (marking delivered adds
      // one, reopening a delivered order removes one) — if that day was
      // already expanded there earlier in this session, its cached order
      // list would otherwise keep showing the old, now-stale set even
      // though a fresh Apply's totals are already correct (see
      // admin-stats.js's invalidateDayOrdersCache for the full story).
      window.AdminStats?.invalidateDayOrdersCache();
      applyLocalStatusChange(Number(id), updated, card, action, flashStartedAt);
    } catch (err) {
      // The action didn't actually happen — undo the optimistic flash
      // rather than leaving the card looking like something succeeded.
      if (flashClass) card.classList.remove("is-flashing", flashClass);
      Toast.show(err.message, "error");
      actionBtn.disabled = false;
    }
  });

  // Named to match the CSS classes in admin-cards.css (.flash-delivered /
  // .flash-cancelled / .flash-reopened) and keyed by the exact action
  // string actionsFor() puts in data-action (also the URL segment PATCHed
  // above) — "delivered"/"cancelled"/"reopen".
  const FLASH_CLASS = { delivered: "flash-delivered", cancelled: "flash-cancelled", reopen: "flash-reopened" };
  // Must match the CSS animations' own durations (orderCardFlash/
  // orderCardLeave in admin-cards.css) — there's no clean way to read a
  // running CSS animation's length back out in JS, so these are kept in
  // sync by hand instead of computed from one shared source.
  const FLASH_MS = 700;
  const LEAVE_MS = 420;

  /** Reflects a delivered/cancelled/reopen action locally instead of
   *  re-fetching the whole filtered order list from the server for a
   *  change we already know the exact outcome of (the PATCH above
   *  already returned the updated order). Whichever status filter is
   *  active decides what "reflecting it" means:
   *   - a specific status tab (New/Delivered/Cancelled): the order no
   *     longer belongs there — actionsFor() only ever offers an action
   *     that moves an order OUT of its current status, so this is
   *     always a removal, never a case of staying but changing. The
   *     flash itself already started back in the click listener above
   *     (the instant the button was pressed, not after this PATCH
   *     resolved) — here it's just left to finish out whatever of its
   *     700ms is still remaining before sliding/fading the card out and
   *     then actually removing it, so there's always a clear, readable
   *     confirmation of which action fired before the card leaves, no
   *     matter how fast or slow the request itself was.
   *   - the All tab: the order stays in view either way, so its card is
   *     swapped in place for one built from the updated order — carrying
   *     a fresh flash onto the new card (outerHTML makes a brand new
   *     element, so the old one's already-playing flash doesn't come
   *     along on its own) so that's still visibly confirmed too, just
   *     without a removal.
   *  Doesn't touch firstPageSnapshot — that's meant to reflect a real
   *  server fetch of page 0, and this is a purely local edit, so it's
   *  deliberately left stale here. The next poll-triggered refresh()
   *  will compare against it, see a real difference (because something
   *  really did change), and resync for real — exactly the same outcome
   *  as if this had been kept in sync by hand, with far less to get
   *  wrong in the meantime. */
  function applyLocalStatusChange(id, updated, cardEl, action, flashStartedAt) {
    const index = currentOrders.findIndex((o) => o.id === id);
    const flashClass = FLASH_CLASS[action];

    if (activeStatus === "") {
      if (index !== -1) currentOrders[index] = updated;
      cardEl.outerHTML = card(updated);
      const freshEl = list.querySelector(`.order-card[data-id="${id}"]`);
      if (freshEl && flashClass) freshEl.classList.add("is-flashing", flashClass);
      return;
    }

    // Only wait out whatever's left of the flash that already started at
    // click time — a slow request that already ran past FLASH_MS on its
    // own shouldn't then also tack on a full extra wait before the card
    // starts leaving.
    const flashRemaining = Math.max(0, FLASH_MS - (Date.now() - flashStartedAt));
    setTimeout(() => {
      cardEl.classList.add("is-leaving");
      setTimeout(() => {
        if (index !== -1) currentOrders.splice(index, 1);
        cardEl.remove();
        if (!currentOrders.length) {
          // Genuinely more orders exist beyond this page (hasMore) — pull
          // the next page in rather than claiming "no orders here" when
          // there plainly are, just not loaded yet. The load-more row is
          // still sitting in the DOM either way (only the individual
          // order card above it was removed), so loadMore() has exactly
          // the same insertion point it always does.
          if (hasMore) loadMore();
          else list.innerHTML = emptyStateHtml();
        }
      }, LEAVE_MS);
    }, flashRemaining);
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
