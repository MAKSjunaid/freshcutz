/* Keeps the storefront in sync with admin edits (price changes, enabling/
 * disabling a product) without the customer reloading the page. Split out
 * of storefront.js to keep that file under the project's size limit.
 *
 * Used to poll the catalog every 15 seconds forever; now opens one
 * Server-Sent Events connection to /api/products/stream instead and
 * simply waits — the backend pushes a fresh catalog the moment an admin
 * actually changes something (see AdminProductController), instead of
 * the browser asking again on a timer. When a product's fields simply
 * changed, existing cards are still patched in place instead of
 * re-rendered, so the grid doesn't flash (replaying entrance animations)
 * on every push — the same pattern used for the admin panel's own
 * polling. Cart.syncWithCatalog() (see cart.js) then drops/refreshes any
 * cart line that's gone stale. This never touches orders already placed
 * — those keep their own snapshot, taken (and re-verified server-side)
 * at the moment they were placed. */

(() => {
  let lastProductsSnapshot = null;

  function visibleIdSet(products, activeCategory) {
    return new Set(
      products.filter((p) => p.enabled && (activeCategory === "All" || p.category === activeCategory)).map((p) => p.id)
    );
  }

  /** Updates one card's name/description/image/price/discount to match a
   * freshly-pushed product, without touching anything else about the card
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

  /** Applies a freshly received products array — from either the initial
   * push on connecting or a later one after an admin change — the same
   * way the old poll-and-diff loop did: skip entirely if nothing
   * changed, patch existing cards in place when the same products are
   * still visible, otherwise re-render the grid. */
  function applyProducts(fresh) {
    const S = window.Storefront;
    if (!S) return; // storefront.js hasn't finished setting up yet — the
    // page's own initial GET already rendered the first catalog, so
    // there's nothing lost by skipping this particular push

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

  /* Only stay connected while this tab is actually visible — a
   * backgrounded or minimized tab was previously still fetching the full
   * catalog every 15 seconds forever, which is exactly the kind of
   * always-on traffic that ran up the database's monthly transfer
   * allowance; holding an open connection for hours isn't much better.
   * Reconnecting sends the current catalog immediately, so a change made
   * while away shows up right away instead of waiting for the next push. */
  let eventSource = null;

  function connect() {
    if (eventSource) return;
    eventSource = new EventSource(Api.streamUrl("/api/products/stream"));
    eventSource.addEventListener("products", (e) => {
      try {
        applyProducts(JSON.parse(e.data));
      } catch {
        // malformed payload — ignore this push, the next one will fix it
      }
    });
    // No further handling needed on error/close: EventSource retries the
    // connection on its own.
  }

  function disconnect() {
    if (eventSource) {
      eventSource.close();
      eventSource = null;
    }
  }

  document.addEventListener("visibilitychange", () => {
    if (document.hidden) {
      disconnect();
    } else {
      connect();
    }
  });

  if (!document.hidden) connect();
})();
