/* Customers tab: who's ordered, how many times, and what they ordered
 * before — grouped by phone number (see AdminCustomerController). Search
 * re-queries the server; expanding a customer's past orders just re-renders
 * from what's already loaded, no extra fetch. */

window.AdminCustomers = (() => {
  const list = document.getElementById("customersList");
  const searchInput = document.getElementById("customerSearchInput");
  let latestCustomers = [];
  const expanded = new Set(); // phone numbers whose past-orders list is open

  const badgeClass = { NEW: "badge-new", DELIVERED: "badge-delivered", CANCELLED: "badge-cancelled" };

  function orderRow(order) {
    const itemsSummary = (order.items || [])
      .map((i) => `${i.quantity} × ${Utils.escapeHtml(i.productName)}`)
      .join(", ");
    return `
      <div class="customer-order-row">
        <div class="customer-order-head">
          <span class="order-code-lbl">${Utils.escapeHtml(order.orderCode)}</span>
          <span class="badge ${badgeClass[order.status] || ""}">${order.status}</span>
        </div>
        <div class="customer-order-meta">${Utils.formatDateTime(order.createdAt)} · ${Utils.formatMoney(order.totalAmount)}</div>
        ${itemsSummary ? `<div class="customer-order-items">${Utils.escapeHtml(itemsSummary)}</div>` : ""}
      </div>`;
  }

  function card(customer) {
    const isOpen = expanded.has(customer.phone);
    return `
    <div class="customer-card" data-phone="${Utils.escapeHtml(customer.phone)}">
      <div class="customer-card-head">
        <div>
          <div class="customer-name">${Utils.escapeHtml(customer.name)}</div>
          <div class="customer-phone">📞 <a href="tel:${Utils.escapeHtml(customer.phone)}">${Utils.escapeHtml(customer.phone)}</a></div>
        </div>
        <div class="customer-stats">
          <span class="customer-order-count">${customer.orderCount} order${customer.orderCount === 1 ? "" : "s"}</span>
          <span class="customer-total-spent">${Utils.formatMoney(customer.totalSpent)} delivered</span>
        </div>
      </div>
      <button class="btn btn-ghost btn-sm" data-action="toggle">${isOpen ? "Hide past orders ▲" : "View past orders ▼"}</button>
      ${isOpen ? `<div class="customer-orders-list">${customer.orders.map(orderRow).join("")}</div>` : ""}
    </div>`;
  }

  function render() {
    list.innerHTML = latestCustomers.length
      ? latestCustomers.map(card).join("")
      : `<div class="empty-state"><div class="icon">👤</div><p>${
          searchInput.value.trim() ? "No customers match your search." : "No customers yet."
        }</p></div>`;
  }

  async function refresh() {
    list.innerHTML = `<div class="skeleton" style="height:80px;margin-bottom:12px;"></div>`.repeat(2);
    try {
      const q = searchInput.value.trim();
      const path = q ? `/api/admin/customers?q=${encodeURIComponent(q)}` : "/api/admin/customers";
      latestCustomers = await Api.get(path);
      render();
    } catch (err) {
      list.innerHTML = `<div class="empty-state"><p>${Utils.escapeHtml(err.message)}</p></div>`;
    }
  }

  list.addEventListener("click", (e) => {
    const cardEl = e.target.closest(".customer-card");
    const toggleBtn = e.target.closest('[data-action="toggle"]');
    if (!cardEl || !toggleBtn) return;
    const phone = cardEl.dataset.phone;
    if (expanded.has(phone)) expanded.delete(phone);
    else expanded.add(phone);
    render(); // just re-render from what's already loaded — no need to refetch
  });

  searchInput.addEventListener("input", Utils.debounce(refresh, 250));

  return { refresh };
})();
