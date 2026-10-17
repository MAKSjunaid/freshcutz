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
  // Set only while waiting for the very first catalog push of the page's
  // life (cleared the moment applyProducts() above gets one). The stream
  // is now the ONLY source for the initial catalog — there's no separate
  // GET left to surface a "couldn't load" error the way one used to — so
  // if nothing has arrived after a generous wait, this shows a friendly
  // message instead of leaving the loading placeholders up forever. The
  // wait is deliberately long: the backend runs on Render's free tier,
  // which can take 30-60+ seconds to wake up from a cold start, and that
  // is completely normal, not a failure.
  let initialLoadTimer = null;

  /* The category tab the admin chose to open the shop on ("" = the normal "All" tab). It is looked up once per
   * page load, next to the catalog stream, and applied once — only if the customer has not already tapped a
   * different tab — so later live updates never switch tabs under them. undefined = not known yet. */
  let defaultCategory;
  let defaultApplied = false;

  function applyDefaultCategory(products, redraw) {
    const S = window.Storefront;
    if (defaultApplied || defaultCategory === undefined || !S || !products.length) return;
    defaultApplied = true;
    if (!defaultCategory || S.getActiveCategory() !== "All") return;
    if (typeof SiteCustomize !== "undefined" && !SiteCustomize.on("showCategories")) return; // the category bar is switched off
    // a default that no longer has any visible product would show an empty shop: fall back to "All"
    if (!products.some((p) => p.enabled && p.category === defaultCategory)) return;
    S.setActiveCategory(defaultCategory, redraw);
  }

  Api.get("/api/store/default-category")
    .then((r) => {
      defaultCategory = (r && r.category) || "";
    })
    .catch(() => {
      defaultCategory = ""; // couldn't ask: just open on "All"
    })
    .then(() => {
      const S = window.Storefront;
      if (S) applyDefaultCategory(S.getAllProducts(), true); // the catalog got here first
    });

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

  /** Applies a freshly received products array — the very first push on
   * connecting (this is now the ONLY source for the initial catalog; see
   * connect() below) or a later one after an admin change — the same way
   * the old poll-and-diff loop did: skip entirely if nothing changed,
   * patch existing cards in place when the same products are still
   * visible, otherwise re-render the grid (which correctly covers going
   * from the empty starting state to a full grid on that first push). */
  function applyProducts(fresh) {
    const S = window.Storefront;
    if (!S) return; // storefront.js hasn't finished setting up yet — see
    // the script-order note by connect() below for why this shouldn't
    // actually happen, kept only as a defensive no-op

    if (initialLoadTimer) {
      clearTimeout(initialLoadTimer);
      initialLoadTimer = null;
    }
    const snapshot = JSON.stringify(fresh);
    if (snapshot === lastProductsSnapshot) return; // nothing changed
    lastProductsSnapshot = snapshot;

    applyDefaultCategory(fresh, false); // before the first drawing, so there is no flash of "All"
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
    // Only the page's very first connection ever needs the load-timeout
    // fallback above — once lastProductsSnapshot is set, the catalog has
    // already loaded at least once, so a later reconnect (e.g. the tab
    // becoming visible again) has nothing to show a "couldn't load"
    // message for even if it's slow.
    if (lastProductsSnapshot === null) {
      initialLoadTimer = setTimeout(() => {
        initialLoadTimer = null;
        window.Storefront?.showLoadError(
          "Having trouble loading the menu — please check your connection or try refreshing."
        );
      }, 45000);
    }
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
    if (initialLoadTimer) {
      clearTimeout(initialLoadTimer);
      initialLoadTimer = null;
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