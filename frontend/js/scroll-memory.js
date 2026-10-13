/* Remembers the scroll position of each tab separately.
 *
 * Admin tabs, the Orders status filter, the Sales sub-tabs and the customer
 * category chips all swap content inside ONE scrolling page, so the browser
 * carries the old scroll position over to the new content. This gives every
 * tab its own position instead:
 *   - leaving a tab remembers where you were in it;
 *   - coming back restores exactly that spot;
 *   - a tab you haven't opened yet starts at its top (for customer categories:
 *     the start of the product list).
 *
 * It listens for clicks in the capture phase (so it sees the old tab before the
 * page's own handlers switch it) and needs no changes to any other script. */
(() => {
  const html = document.documentElement;
  const pos = {};                       // "group:key" -> scrollY

  const headerH = () => {
    const h = document.querySelector(".site-header, .admin-header-group");
    return h ? h.offsetHeight : 0;
  };

  // Start of the product list on the customer page: the spot where the category
  // chips have just stuck under the header.
  function catalogStart() {
    const hero = document.getElementById("heroSection");
    const heroEnd = hero && hero.offsetParent !== null ? hero.offsetTop + hero.offsetHeight : 0;
    return Math.max(0, heroEnd - headerH());
  }

  const GROUPS = [
    { name: "view",      item: ".admin-header-group .admin-tab[data-view]", key: (el) => el.dataset.view,        fresh: () => 0 },
    { name: "ostatus",   item: "#orderStatusFilter .admin-tab",             key: (el) => el.dataset.status || "", fresh: () => 0 },
    { name: "salespane", item: "#salesSubtabs .sales-subtab",               key: (el) => el.dataset.pane,       fresh: () => 0 },
    { name: "category",  item: "#categoryScroll .cat-chip",                 key: (el) => el.dataset.cat,        fresh: (y) => Math.min(y, catalogStart()) },
  ];

  let token = 0; // a newer restore (or the user touching the page) cancels an older one

  function jumpTo(y) {
    // html has scroll-behavior: smooth, so say "instant" explicitly
    window.scrollTo({ top: y, left: 0, behavior: "instant" });
    // the hide-on-scroll headers listen for this so they re-sync and stay visible
    document.dispatchEvent(new Event("freshcutz:scroll-restored"));
  }

  // While a tab's content is swapped/reloaded the page can briefly get SHORTER,
  // and the browser then yanks the scroll position up (visible as a flash, with
  // the header popping in and out). Pinning the page height until the saved
  // position is back means the scroll never gets yanked in the first place.
  const pin = () => { document.body.style.minHeight = html.scrollHeight + "px"; };
  const unpin = () => { document.body.style.minHeight = ""; };

  // The new tab's content may still be loading (so the page is momentarily too
  // short to scroll that far) — keep nudging for a moment until it can.
  function restore(y) {
    const mine = ++token;
    let tries = 0;
    (function step() {
      if (mine !== token) return;
      jumpTo(y);
      const reached = Math.abs(window.scrollY - y) < 2;
      if ((reached && tries >= 4) || ++tries > 16) { unpin(); return; }
      setTimeout(step, 90);
    })();
  }

  const cancel = () => { token++; unpin(); };
  ["wheel", "touchstart", "keydown", "mousedown"].forEach((t) =>
    window.addEventListener(t, cancel, { passive: true, capture: true })
  );

  // Runs right AFTER the page's own tab handler (same click, before the browser
  // paints), so the very first frame already shows the tab at its own scroll
  // position — no intermediate frame at the old position, no visible jump.
  let pending = null;
  document.addEventListener("click", () => {
    if (pending === null) return;
    const target = pending;
    pending = null;
    jumpTo(target);
    restore(target); // keeps nudging while the tab's list is still loading
  });

  document.addEventListener(
    "click",
    (e) => {
      if (!e.target.closest) return;
      for (const g of GROUPS) {
        const clicked = e.target.closest(g.item);
        if (!clicked) continue;

        const current = document.querySelector(g.item + ".active");
        const from = current ? g.key(current) : null;
        const to = g.key(clicked);
        if (from === to) return;                   // same tab tapped again

        const y = Math.max(0, window.scrollY);
        if (from !== null) pos[g.name + ":" + from] = y;

        const saved = pos[g.name + ":" + to];
        const target = saved !== undefined ? saved : g.fresh(y);

        // hold the page height steady while the page's own handler swaps the
        // content, then move to the tab's spot
        pin();
        pending = target;
        return;
      }
    },
    true
  );
})();
