/* Keeps the storefront in sync with admin edits (price changes, enabling/
 * disabling a product) without the customer reloading the page. Split out
 * of storefront.js to keep that file under the project's size limit.
 *
 * Polls the catalog periodically; when a product's fields simply changed,
 * existing cards are patched in place instead of re-rendered, so the grid
 * doesn't flash (replaying entrance animations) on every check — the same
 * pattern used for the admin panel's own polling. Cart.syncWithCatalog()
 * (see cart.js) then drops/refreshes any cart line that's gone stale. This
 * never touches orders already placed — those keep their own snapshot,
 * taken (and re-verified server-side) at the moment they were placed. */

(() => {
  const POLL_MS = 15000;
  let lastProductsSnapshot = null;

  function visibleIdSet(products, activeCategory) {
    return new Set(
      products.filter((p) => p.enabled && (activeCategory === "All" || p.category === activeCategory)).map((p) => p.id)
    );
  }

  /** Updates one card's name/description/image/price/discount to match a
   * freshly-fetched product, without touching anything else about the card
   * (its scroll position, reveal state, or qty stepper). */
  function patchCardContent(S, card, product) {
    const nameEl = card.querySelector(".product-name");
    if (nameEl) nameEl.textContent = product.name;
    const descEl = card.querySelector(".product-desc");
    if (descEl) descEl.textContent = product.description || "";
    const img = card.querySelector(".product-media-frame img");
    const resolved = Utils.resolveImageUrl(product.imageUrl);
    if (img && img.getAttribute("src") !== resolved) img.src = resolved;
    S.patchVariantUI(card, product, S.cardVariant[product.id] || "variant1");

    card.classList.toggle("out-of-stock", !!product.outOfStock);
    const stockBadge = card.querySelector(".out-of-stock-badge");
    if (stockBadge) stockBadge.hidden = !product.outOfStock;
    const footer = card.querySelector(".product-footer");
    if (footer) footer.innerHTML = S.footerHtml(product, S.cardVariant[product.id] || "variant1");
  }

  async function refreshProducts() {
    const S = window.Storefront;
    if (!S) return; // storefront.js hasn't finished setting up yet

    let fresh;
    try {
      fresh = await Api.get("/api/products");
    } catch {
      return; // a background poll hiccup shouldn't disrupt the page
    }
    const snapshot = JSON.stringify(fresh);
    if (snapshot === lastProductsSnapshot) return; // nothing changed
    lastProductsSnapshot = snapshot;

    const activeCategory = S.getActiveCategory();
    const prevVisible = visibleIdSet(S.getAllProducts(), activeCategory);
    S.setAllProducts(fresh);
    const nextVisible = visibleIdSet(fresh, activeCategory);
    const sameCards = prevVisible.size === nextVisible.size && [...prevVisible].every((id) => nextVisible.has(id));

    S.renderCategories();
    if (sameCards) {
      S.grid.querySelectorAll(".product-card[data-id]").forEach((card) => {
        const product = fresh.find((p) => p.id === Number(card.dataset.id));
        if (product) patchCardContent(S, card, product);
      });
    } else {
      S.renderGrid();
    }
    Cart.syncWithCatalog(fresh);
  }

  /* Only poll while this tab is actually visible — a backgrounded or
   * minimized tab was previously still fetching the full catalog every
   * 15 seconds forever, which is exactly the kind of always-on traffic
   * that ran up the database's monthly transfer allowance. Checks once
   * immediately on switching back, so a change made while away shows up
   * right away instead of waiting for the next 15-second tick. */
  let pollTimer = null;

  function startPolling() {
    if (pollTimer) return;
    pollTimer = setInterval(refreshProducts, POLL_MS);
  }

  function stopPolling() {
    if (pollTimer) clearInterval(pollTimer);
    pollTimer = null;
  }

  document.addEventListener("visibilitychange", () => {
    if (document.hidden) {
      stopPolling();
    } else {
      refreshProducts();
      startPolling();
    }
  });

  if (!document.hidden) startPolling();
})();
