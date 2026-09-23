/* Small shared helpers used by both the storefront and admin scripts. */

const Utils = (() => {
  const currencyFormatter = new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    maximumFractionDigits: 0,
  });

  function formatMoney(value) {
    const num = Number(value || 0);
    return currencyFormatter.format(num);
  }

  function formatDateTime(isoString) {
    if (!isoString) return "";
    const d = new Date(isoString);
    return d.toLocaleString("en-IN", {
      day: "2-digit",
      month: "short",
      hour: "2-digit",
      minute: "2-digit",
    });
  }

  function debounce(fn, wait) {
    let timer;
    return (...args) => {
      clearTimeout(timer);
      timer = setTimeout(() => fn(...args), wait);
    };
  }

  function escapeHtml(str) {
    if (str == null) return "";
    return String(str)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function discountPercent(price, mrp) {
    const p = Number(price);
    const m = Number(mrp);
    if (!m || m <= p) return 0;
    return Math.round(((m - p) / m) * 100);
  }

  function resolveImageUrl(imageUrl) {
    if (!imageUrl) return "";
    if (imageUrl.startsWith("http")) return imageUrl;
    return `${window.FRESHCUTZ_API_BASE}${imageUrl}`;
  }

  function validatePhone(value) {
    const digits = (value || "").replace(/\D/g, "");
    return digits.length >= 10;
  }

  return {
    formatMoney,
    formatDateTime,
    debounce,
    escapeHtml,
    discountPercent,
    resolveImageUrl,
    validatePhone,
  };
})();
