/* The customer website's catalog: loads products in small pieces, keeps what it has already downloaded,
 * and keeps it up to date. (storefront.js draws the page; this file only deals with the data.)
 *
 * How little it downloads:
 *  - ONE request on page load (/api/products/start): the category tabs + the first 12 products of the tab the
 *    admin chose as the default (or of "All").
 *  - More products only when the customer scrolls near the end (/api/products/page), and only the ones that are
 *    missing: the page knows each product's place ("rank" in All, "catRank" in its category), so products
 *    downloaded for one tab are reused by every other tab.
 *  - The full list is downloaded only the first time the search box is used (/api/products/all); after that,
 *    searching is instant and makes no request.
 *  - Live updates: the server only says "the catalog changed" (a few bytes). A page that is still showing the
 *    current version asks nothing more; otherwise it re-reads just what is on screen.
 *  - Every answer carries a version (ETag), so a browser that already has it gets a tiny "304 not modified".
 *
 * Products a customer already has in the cart (or in a past order they reorder) can be anywhere in the
 * catalog, so those are fetched by id on their own (/api/products/by-ids) and kept apart from the ranked pool. */

(() => {
  const PAGE = 12;
  const MAX_PER_REQUEST = 100; // the server's limit for one page

  const catOf = (p) => (p.category || "").trim();
  // Asking with the catalog version we know (?v=) lets the browser keep each answer for good: reloading the page or
  // going back to a tab then needs no request at all, and a changed catalog simply has a new version (= new address).
  const vParam = (v) => (v ? `&v=${encodeURIComponent(v)}` : "");

  /** One consistent copy of what has been downloaded for ONE version of the catalog. */
  function makePool() {
    return {
      byId: new Map(), // id -> product
      byRank: new Map(), // rank (place in "All") -> product
      byCat: new Map(), // category -> Map(catRank -> product)
      complete: false, // every product is here (after the full list was downloaded)
    };
  }

  function addTo(pool, items) {
    for (const p of items) {
      pool.byId.set(p.id, p);
      pool.byRank.set(p.rank, p);
      const c = catOf(p);
      let m = pool.byCat.get(c);
      if (!m) pool.byCat.set(c, (m = new Map()));
      m.set(p.catRank, p);
    }
  }

  let pool = makePool();
  let tabs = []; // [{ name, count }]
  let total = 0;
  let version = null;
  const extra = new Map(); // products fetched by id (cart / reorder): NOT part of the ranked pool
  const gone = new Set(); // ids the live stream said are no longer on the shop (hidden / deleted): never asked for again
  let ui = null; // set by storefront.js: { current(), refreshed(info) }
  let started = false;

  // ---- reading what is held -----------------------------------------------

  /** The products of a tab that are held WITHOUT a gap, from its first one on ("All" or a category name). */
  function run(category) {
    const out = [];
    if (!category || category === "All") {
      for (let r = 0; pool.byRank.has(r); r++) out.push(pool.byRank.get(r));
    } else {
      const m = pool.byCat.get(category);
      if (m) for (let r = 0; m.has(r); r++) out.push(m.get(r));
    }
    return out;
  }

  /** How many products the tab has in total (hidden products are not counted). */
  function count(category) {
    if (!category || category === "All") return total;
    const t = tabs.find((x) => x.name === category);
    return t ? t.count : 0;
  }

  function getProduct(id) {
    return pool.byId.get(id) || extra.get(id) || null;
  }

  // ---- first load -----------------------------------------------------------

  const wait = (ms) => new Promise((r) => setTimeout(r, ms));

  /** Loads the first screen. Retries quietly: the backend on a free host can take a minute to wake up. */
  const ready = (async () => {
    let delay = 2000;
    let timer = setTimeout(() => {
      timer = null;
      window.Storefront?.showLoadError(
        "Having trouble loading the menu — please check your connection or try refreshing."
      );
    }, 45000);
    for (;;) {
      try {
        const r = await Api.get(`/api/products/start?limit=${PAGE}`);
        pool = makePool();
        addTo(pool, r.items || []);
        tabs = r.categories || [];
        total = r.total || 0;
        version = r.version;
        started = true;
        if (timer) clearTimeout(timer);
        return { category: r.category || "" };
      } catch {
        await wait(delay);
        delay = Math.min(delay * 1.5, 15000);
      }
    }
  })();

  // ---- more products --------------------------------------------------------

  /** Downloads the next page of a tab: the products after the last one held without a gap. Returns how many
   * new products arrived (0 = nothing new, e.g. the catalog changed meanwhile and a refresh is on its way). */
  async function loadMore(category, limit = PAGE) {
    const st = pool;
    const list = run(category);
    const after = list.length ? list[list.length - 1].rank : -1;
    const key = !category || category === "All" ? "" : category;
    const r = await Api.get(`/api/products/page?category=${encodeURIComponent(key)}&after=${after}&limit=${limit}${vParam(version)}`);
    if (st !== pool) return 0; // a refresh replaced everything while this was on its way
    if (r.version !== version) {
      stale = true;
      scheduleRefresh(0); // the catalog changed: these ranks no longer match what is held
      return 0;
    }
    addTo(pool, r.items || []);
    return (r.items || []).length;
  }

  // ---- a whole tab (search) --------------------------------------------------
  // Search looks inside the tab the customer is on, so it only needs THAT tab complete: a 2-product tab that is
  // already held costs nothing; a bigger one downloads just its missing products (up to 100 per request).
  // The "All" tab needs everything, which is one request for the full list.

  /** Is every product of this tab already held? */
  function tabComplete(category) {
    return pool.complete || run(category).length >= count(category);
  }

  const tabLoads = new Map();
  function loadTab(category) {
    const cat = !category || category === "All" ? "All" : category;
    if (tabComplete(cat)) return Promise.resolve();
    if (cat === "All") return loadAll();
    let p = tabLoads.get(cat);
    if (!p) {
      p = (async () => {
        for (let i = 0; i < 50 && !tabComplete(cat); i++) {
          if (!(await loadMore(cat, MAX_PER_REQUEST))) break;
        }
      })().finally(() => tabLoads.delete(cat));
      tabLoads.set(cat, p);
    }
    return p;
  }

  // ---- the full list ("All" tab search) ----------------------------------------

  let allPromise = null;

  /** Downloads every product, once. Used when the search box is used on the "All" tab. */
  function loadAll() {
    if (pool.complete) return Promise.resolve();
    if (!allPromise) {
      allPromise = (async () => {
        const r = await Api.get(`/api/products/all?${vParam(version).slice(1)}`);
        const items = r.items || [];
        if (r.version === version) {
          addTo(pool, items); // same catalog as what is held: just fill in the rest
        } else {
          adopt(items, r.version); // a newer catalog: start from this complete copy
        }
        pool.complete = true;
        started = true;
      })().finally(() => {
        allPromise = null;
      });
    }
    return allPromise;
  }

  /** Replaces everything with a complete copy of the catalog (tabs are worked out from it). */
  function adopt(items, newVersion) {
    const next = makePool();
    addTo(next, items);
    const counts = new Map();
    for (const p of items) {
      const c = catOf(p);
      if (c) counts.set(c, (counts.get(c) || 0) + 1);
    }
    pool = next;
    tabs = [...counts].map(([name, n]) => ({ name, count: n }));
    total = items.length;
    version = newVersion;
  }

  // ---- cart / reorder: products by id ----------------------------------------

  /** Makes sure these products are known (the ones still on sale). Products that are hidden or deleted stay unknown. */
  async function ensure(ids) {
    const missing = [...new Set(ids)].filter((id) => !getProduct(id) && !gone.has(id));
    for (let i = 0; i < missing.length; i += MAX_PER_REQUEST) {
      const items = await Api.get(`/api/products/by-ids?ids=${missing.slice(i, i + MAX_PER_REQUEST).join(",")}`);
      (items || []).forEach((p) => extra.set(p.id, p));
    }
  }

  /** Brings the cart in line with the live catalog (a price changed, a product was hidden...). Costs one small
   * request only when the cart holds a product that isn't already on the page. */
  async function syncCart() {
    const lines = Cart.getItems();
    if (!lines.length) return;
    const ids = [...new Set(lines.map((i) => i.productId))];
    try {
      await ensure(ids);
    } catch {
      return; // offline: try again on the next change
    }
    const live = ids.map(getProduct).filter(Boolean);
    Cart.syncWithCatalog(live);
  }

  // ---- staying up to date ----------------------------------------------------

  let refreshTimer = null;
  let refreshing = false;
  let again = false;
  const inbox = []; // what the live stream said changed, oldest first (a small change carries the products themselves)
  let stale = false; // a download was thrown away because the catalog changed meanwhile: the page must look again

  /** Asks (soon) whether the catalog changed. Several changes close together become one check. */
  let refreshDue = Infinity; // when the pending check will run
  function scheduleRefresh(delay) {
    if (!started) return;
    const due = Date.now() + delay;
    if (refreshTimer && refreshDue <= due) return; // an earlier check is already waiting
    clearTimeout(refreshTimer);
    refreshDue = due;
    refreshTimer = setTimeout(() => {
      refreshTimer = null;
      refreshDue = Infinity;
      refreshNow();
    }, delay);
  }

  async function refreshNow() {
    if (!started) return;
    if (refreshing) {
      again = true;
      return;
    }
    refreshing = true;
    try {
      do {
        again = false;
        let d = inbox.shift();
        if (d && d.version === version) {
          again = inbox.length > 0; // already up to date with this one
          continue;
        }
        // Not exactly the next change after what is held (the tab was hidden, a message was missed, or the stream only
        // said which version it is): ask what changed since the held version. The answer is a few products, or
        // "re-read what is on screen".
        if (!d || d.prev !== version) d = await Api.get(`/api/products/delta?since=${encodeURIComponent(version)}`);
        if (d.version === version) {
          if (stale) {
            stale = false;
            if (ui) ui.refreshed({ category: ui.current().category });
          }
          again = inbox.length > 0;
          continue;
        }
        if (!d.structural) await applyDelta(d);
        else if (d.order) await applyOrder(d);
        else await reload(d); // (the history was too old to say what changed: re-read what is on screen)
        if (inbox.length) again = true;
      } while (again);
    } catch {
      // offline or the server is waking up: the next change signal, reconnect or tab switch tries again
    } finally {
      refreshing = false;
    }
  }

  /** Only some products changed (price, name, photo, stock...): fetch just those, and only the ones this page holds.
   * Everything else on the page is still exactly right. When the page holds none of them, nothing is downloaded. */
  /** Is this page holding the product (ranked pool, or the cart/reorder extras)? */
  const holds = (id) => pool.byId.has(id) || extra.has(id);

  async function applyDelta(d) {
    const given = new Map((d.items || []).map((p) => [p.id, p])); // the products that came with the message
    const held = (d.ids || []).filter(holds);
    const missing = held.filter((id) => !given.has(id)); // (a big change only lists ids) fetch those
    for (let i = 0; i < missing.length; i += MAX_PER_REQUEST) {
      const items = await Api.get(`/api/products/by-ids?ids=${missing.slice(i, i + MAX_PER_REQUEST).join(",")}`);
      (items || []).forEach((p) => given.set(p.id, p));
    }
    for (const id of held) {
      const p = given.get(id);
      if (!p) continue;
      if (pool.byId.has(id)) addTo(pool, [p]); // same place in the shop order, new details
      if (extra.has(id)) extra.set(id, p);
    }
    version = d.version;
    if (held.length || stale) {
      stale = false;
      if (ui) ui.refreshed({ category: ui.current().category });
    }
    if (held.length) await syncCart();
  }

  /** Products were added / hidden / moved: the message carries the new order of the whole shop (and the products that
   * are new or changed), so every place, every tab count and every product is worked out right here — no request.
   * Products this page holds keep their data; only their places change. A page that held the first 12 of a tab may
   * now hold one or two fewer in a row (e.g. one was hidden); the ordinary "load more" fills that in when the
   * customer scrolls there. */
  async function applyOrder(d) {
    const order = d.order;
    const present = new Set(order);
    const given = new Map((d.items || []).map((p) => [p.id, p])); // new / changed products that came with the message
    // (a big change only lists ids) the changed products this page holds are fetched
    const missing = (d.ids || []).filter((id) => holds(id) && !given.has(id) && present.has(id));
    for (let i = 0; i < missing.length; i += MAX_PER_REQUEST) {
      const items = await Api.get(`/api/products/by-ids?ids=${missing.slice(i, i + MAX_PER_REQUEST).join(",")}`);
      (items || []).forEach((p) => given.set(p.id, p));
    }
    const next = makePool();
    const perCategory = new Map(); // category -> products so far
    order.forEach((id, i) => {
      const name = d.names[d.cats[i]] || "";
      const n = perCategory.get(name) || 0;
      perCategory.set(name, n + 1);
      const base = given.get(id) || pool.byId.get(id);
      if (base) addTo(next, [{ ...base, rank: i, catRank: n }]);
    });
    for (const id of [...extra.keys()]) {
      if (!present.has(id)) {
        extra.delete(id);
        gone.add(id); // the cart / reorder product was hidden or deleted
      } else if (given.has(id)) {
        extra.set(id, given.get(id));
      }
    }
    for (const id of present) gone.delete(id);
    for (const id of pool.byId.keys()) if (!present.has(id)) gone.add(id);
    next.complete = next.byId.size === order.length;
    pool = next;
    tabs = d.names.filter((n) => n).map((n) => ({ name: n, count: perCategory.get(n) }));
    total = order.length;
    version = d.version;
    stale = false;
    const view = ui ? ui.current() : { category: "All" };
    const category = view.category === "All" || tabs.some((x) => x.name === view.category) ? view.category : "All";
    if (ui) ui.refreshed({ category });
    await syncCart();
  }

  /** The catalog changed: read again only what the customer is looking at, then drop everything else held. */
  async function reload(t) {
    const view = ui ? ui.current() : { category: "All", shown: PAGE, full: false };
    if (pool.complete || (view.full && view.category === "All")) {
      // the search box downloaded everything earlier: get the complete current list
      const r = await Api.get(`/api/products/all?${vParam(t.version).slice(1)}`);
      adopt(r.items || [], r.version);
      pool.complete = true;
      stale = false;
      extra.clear(); // cart/reorder products are looked up again against the new catalog
      gone.clear();
      if (ui) ui.refreshed({ category: tabs.some((x) => x.name === view.category) ? view.category : "All" });
      await syncCart();
      return;
    }
    const category = view.category !== "All" && !t.categories.some((x) => x.name === view.category) ? "All" : view.category;
    const key = category === "All" ? "" : category;
    // while the customer is searching, the whole tab is kept (search looks through all of it)
    const tabCount = category === "All" ? t.total : (t.categories.find((x) => x.name === category) || {}).count || 0;
    const want = Math.max(PAGE, view.full ? tabCount : view.shown || PAGE);
    const next = makePool();
    let after = -1;
    for (;;) {
      const need = Math.min(MAX_PER_REQUEST, want - (after === -1 ? 0 : countIn(next, category)));
      if (need <= 0) break;
      const r = await Api.get(`/api/products/page?category=${encodeURIComponent(key)}&after=${after}&limit=${need}${vParam(t.version)}`);
      if (r.version !== t.version) {
        again = true; // it changed again while reading: start over
        return;
      }
      addTo(next, r.items || []);
      if (!r.items || !r.items.length || !r.hasMore) break;
      after = r.items[r.items.length - 1].rank;
    }
    pool = next;
    tabs = t.categories || [];
    total = t.total || 0;
    version = t.version;
    stale = false;
    extra.clear();
    gone.clear();
    if (ui) ui.refreshed({ category });
    await syncCart();
  }

  function countIn(p, category) {
    if (category === "All") return p.byRank.size;
    const m = p.byCat.get(category);
    return m ? m.size : 0;
  }

  // The live stream tells every open page the catalog's version when it connects, and exactly what changed after
  // every edit. A page that already has that version does nothing at all; a small change (a price...) arrives WITH
  // the changed products, so nothing is requested; a bigger one (hidden / added / moved) re-reads what is on screen.
  // A page that comes back after being hidden (or reconnects) tells the stream which version it holds, and the stream
  // sends what changed since then — so even catching up needs no separate request.
  let eventSource = null;
  let reconnectTimer = null;
  let reconnectDelay = 2000;

  const versionNum = (v) => parseInt(v, 36);

  function connect() {
    if (eventSource) return;
    clearTimeout(reconnectTimer);
    const es = (eventSource = new EventSource(
      Api.streamUrl(`/api/products/changes?since=${encodeURIComponent(version || "")}`)
    ));
    const announced = (e) => {
      reconnectDelay = 2000;
      let d = null;
      try {
        d = JSON.parse(e.data);
      } catch {
        // not understood: just check what changed since the held version
      }
      if (d && d.version) {
        const tail = inbox.length ? inbox[inbox.length - 1].version : version;
        if (d.version === tail || versionNum(d.version) < versionNum(tail)) return; // already known / an old message
        inbox.push(d);
        // A small change that carries its products (everything this page holds is included) is applied at once, with
        // no request. Anything that needs a download waits a moment, spread out so many customers online at once
        // don't all ask in the same instant.
        const instant =
          (!d.structural || !!d.order) &&
          d.prev === tail &&
          (d.ids || []).every((id) => !holds(id) || (d.items || []).some((p) => p.id === id));
        scheduleRefresh(instant ? 0 : 300 + Math.random() * 1500);
      } else {
        scheduleRefresh(300 + Math.random() * 1500); // check what changed since the held version
      }
    };
    es.addEventListener("state", announced);
    es.addEventListener("changed", announced);
    es.addEventListener("error", () => {
      // The connection dropped. Reconnect ourselves (not the browser's automatic retry) so the new connection asks
      // from the version held NOW.
      if (eventSource !== es) return;
      es.close();
      eventSource = null;
      if (document.hidden) return;
      reconnectTimer = setTimeout(connect, reconnectDelay);
      reconnectDelay = Math.min(reconnectDelay * 1.5, 20000);
    });
  }

  function disconnect() {
    if (eventSource) {
      eventSource.close();
      eventSource = null;
    }
    clearTimeout(reconnectTimer);
    clearTimeout(refreshTimer);
    refreshTimer = null;
    refreshDue = Infinity;
    inbox.length = 0; // coming back, the stream is asked for everything since the version held
  }

  // Only stay connected while this tab is visible: a hidden or minimised tab holds no connection.
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) disconnect();
    else connect();
  });

  ready.then(() => {
    if (!document.hidden) connect();
    syncCart(); // a cart saved on the last visit may hold products that changed since
  });

  window.CatalogStore = {
    PAGE,
    ready, // resolves with { category } once the first screen's data is here
    run,
    count,
    tabs: () => tabs,
    tabComplete,
    loadTab,
    getProduct,
    pooled: () => [...pool.byId.values()],
    loadMore,
    loadAll,
    ensure,
    syncCart,
    bindUi: (hooks) => {
      ui = hooks;
    },
  };
})();
