/* Admin "Customize" tab: edit how the customer website looks — logo, brand
 * name, tagline, hero text, the badge row, which header parts are shown,
 * and the main colors — and save it. Saving (PUT /api/admin/site) is pushed
 * to every open customer tab within about a second (see site-customize.js).
 *
 * The form keeps its own copy of the settings (`cfg`) and only sends it on
 * Save, so half-finished edits never reach customers. The logo is the one
 * exception: choosing/removing a logo is an upload, so it takes effect
 * immediately (and says so on screen). */

window.AdminCustomize = (() => {
  const root = document.getElementById("customizeRoot");
  const DEFAULT_LOGO = "images/logo.jpg";
  const MAX_BADGES = 8;
  const MAX_LOGO_BYTES = 1024 * 1024;

  let cfg = null; // working copy of the settings (SiteConfig on the server)
  let logoUrl = null; // custom logo path from the server, or null for the built-in one
  let dirty = false;
  let loading = false;

  // Saved designs ("templates"): up to four; customers see the one marked LIVE (primary).
  let templates = []; // [{slot, name, saved, primary}]
  let slot = 0; // the template being edited
  let tplName = "";
  let tplPrimary = false;
  let tplSaved = true; // false for a slot that has never been saved (it starts as a copy of the live design)

  const COLOR_GROUPS = [
    {
      title: "Header",
      fields: [
        ["headerBg", "Header background"],
        ["brandNameColor", "Brand name"],
        ["taglineColor", "Tagline"],
      ],
    },
    {
      title: "Hero banner",
      fields: [
        ["heroBgStart", "Background (glow)"],
        ["heroBgEnd", "Background (edge)"],
        ["heroHeadingColor", "Heading"],
        ["heroHighlightColor", "Highlighted words"],
        ["heroTextColor", "Hero line"],
      ],
    },
    {
      title: "Badges",
      fields: [
        ["badgeBg", "Badge background"],
        ["badgeText", "Badge text"],
      ],
    },
    {
      title: "Announcement bar",
      fields: [
        ["announcementBg", "Background"],
        ["announcementColor", "Text"],
      ],
    },
    {
      title: "Page, products & cart",
      fields: [
        ["pageBg", "Page background"],
        ["textColor", "Main text"],
        ["cardBg", "Product card background"],
        ["priceColor", "Prices"],
        ["categoryActiveBg", "Selected category"],
        ["accentColor", "Buttons & accents"],
        ["cartBarBg", "Cart bar at the bottom"],
      ],
    },
    {
      title: "Footer",
      fields: [
        ["footerBg", "Background"],
        ["footerTextColor", "Text"],
      ],
    },
  ];

  const esc = (s) => Utils.escapeHtml(s == null ? "" : String(s));

  // ---- markup ------------------------------------------------------------
  function toggleRow(key, label, hint) {
    return `
      <div class="cz-row">
        <div><div class="cz-row-label">${label}</div>${hint ? `<div class="cz-hint">${hint}</div>` : ""}</div>
        <label class="switch"><input type="checkbox" data-bool="${key}" /><span class="track"></span></label>
      </div>`;
  }

  function textField(key, label, max, hint) {
    return `
      <div class="field">
        <label>${label}</label>
        <input type="text" data-text="${key}" maxlength="${max}" autocomplete="off" />
        ${hint ? `<div class="hint">${hint}</div>` : ""}
      </div>`;
  }

  const TEXT_SIZES = [
    ["sizeBrandName", "Brand name"],
    ["sizeTagline", "Tagline"],
    ["sizeAnnouncement", "Announcement bar"],
    ["sizeHeroHeading", "Hero heading"],
    ["sizeHeroText", "Hero line"],
    ["sizeBadges", "Badges"],
    ["sizeCategories", "Category chips"],
    ["sizeSearch", "Search box", "Can only get bigger — phones need at least this size to avoid zooming in."],
    ["sizeProductName", "Product name"],
    ["sizeProductDesc", "Product description"],
    ["sizePrice", "Prices"],
    ["sizePhotoBadge", "Labels on photos (% OFF, Out of stock)"],
    ["sizeButtons", "Buttons (Add to cart, checkout…)"],
    ["sizeCartBar", "Cart bar at the bottom"],
    ["sizeFooter", "Footer"],
  ];
  const IMAGE_SIZES = [
    ["sizeLogo", "Logo"],
    ["sizeHeaderIcons", "Header buttons (WhatsApp, account)"],
    ["sizeProductImage", "Product photo height"],
  ];

  function sizeField([key, label, hint]) {
    return `
      <div class="cz-size">
        <div class="cz-size-top">
          <label for="cz-s-${key}">${label}</label>
          <span class="cz-size-input"><input type="number" inputmode="numeric" id="cz-s-${key}" data-size="${key}" min="10" max="1000" step="1" /> %</span>
        </div>
        ${hint ? `<div class="cz-hint">${hint}</div>` : ""}
      </div>`;
  }

  // ---- pop-up messages & editable texts ---------------------------------------------------
  function numField(key, label, min, max, step, hint) {
    return `
      <div class="field">
        <label for="cz-n-${key}">${label}</label>
        <input type="number" id="cz-n-${key}" data-num="${key}" min="${min}" max="${max}" step="${step}" inputmode="decimal" />
        ${hint ? `<div class="hint">${hint}</div>` : ""}
      </div>`;
  }

  function messagesSection() {
    const groups = SiteElements.MESSAGE_GROUPS.map(
      (g) => `
        <div class="cz-mgroup">
          <div class="cz-mgroup-title">${esc(g.title)}</div>
          ${g.items
            .map(
              (m) => `
            <div class="cz-msg" data-msg-box="${m.key}">
              <button type="button" class="cz-mel-head" data-action="msg-toggle"><span>${esc(m.label)}</span><span class="cz-mel-count" hidden>edited</span></button>
              <div class="cz-mel-body" hidden></div>
            </div>`
            )
            .join("")}
        </div>`
    ).join("");
    return `
      <div class="cz-hint" style="margin-bottom:8px;">
        Pop-up messages are the little notes that appear briefly on the customer website — for example
        “Chicken added to cart”. Set them all here, then open any message below to change its wording,
        how long it stays, its style, or switch just that one off. Colors, size and shadow of the pop-ups are under
        <b>Fine-tune → Pop-up messages</b>.
      </div>
      ${toggleRow("toastsEnabled", "Show pop-up messages", "Turn this off to hide every pop-up message on the customer website.")}
      ${numField("toastSeconds", "How long each pop-up stays (seconds)", 0.5, 60, 0.1, "Original: 3.2. A message can have its own time below.")}
      ${selectField("toastPosition", "Where pop-ups appear", [
        ["auto", "Automatic (original — bottom, above the cart bar)"],
        ["top-left", "Top left"],
        ["top-center", "Top center"],
        ["top-right", "Top right"],
        ["middle-center", "Middle of the screen"],
        ["bottom-left", "Bottom left"],
        ["bottom-center", "Bottom center"],
        ["bottom-right", "Bottom right"],
      ])}
      ${numField("toastOffsetX", "Nudge sideways (pixels)", -500, 500, 1, "Positive moves right, negative moves left.")}
      ${numField("toastOffsetY", "Nudge up / down (pixels)", -500, 500, 1, "Positive moves down, negative moves up.")}
      ${numField("toastMax", "Most pop-ups on screen at once", 1, 10, 1, "When more arrive, the oldest one goes.")}
      ${toggleRow("toastTapDismiss", "Tap a pop-up to close it")}
      <div class="cz-mfilter-gap"></div>
      ${groups}`;
  }

  function msgBody(key) {
    const m = SiteElements.MESSAGES[key];
    const o = (cfg.messages && cfg.messages[key]) || {};
    const id = `cz-msg-${key}`;
    const toast = m.kind === "toast";
    return `
      <div class="cz-mfield" style="margin-top:8px;">
        <label for="${id}-text">Wording</label>
        <input type="text" id="${id}-text" data-msg="${key}" data-mfield="text" maxlength="200" placeholder="${esc(m.def)}" value="${esc(o.text || "")}" autocomplete="off" />
        <div class="cz-hint">${m.vars ? `You can use ${esc(m.vars)} — they are replaced automatically. ` : ""}Leave empty to keep the original wording.</div>
      </div>
      ${
        toast
          ? `
      <div class="cz-row">
        <div><div class="cz-row-label">Show this message</div></div>
        <label class="switch"><input type="checkbox" data-msg="${key}" data-mfield="enabled" ${o.enabled === "0" ? "" : "checked"} /><span class="track"></span></label>
      </div>
      <div class="cz-mgrid" style="margin-top:6px;">
        <div class="cz-mfield">
          <label for="${id}-seconds">Stays on screen (seconds)</label>
          <input type="text" inputmode="decimal" id="${id}-seconds" data-msg="${key}" data-mfield="seconds" maxlength="5" placeholder="same as all (${cfg.toastSeconds ?? 3.2})" value="${esc(o.seconds || "")}" autocomplete="off" />
        </div>
        <div class="cz-mfield">
          <label for="${id}-tone">Style</label>
          <select id="${id}-tone" data-msg="${key}" data-mfield="tone">
            <option value="">Original (${esc(m.tone)})</option>
            ${["success", "error", "info"].map((t) => `<option value="${t}" ${o.tone === t ? "selected" : ""}>${t[0].toUpperCase() + t.slice(1)}${t === "success" ? " (green edge)" : t === "error" ? " (red edge)" : " (blue edge)"}</option>`).join("")}
          </select>
        </div>
      </div>
      <button type="button" class="btn btn-outline btn-sm" data-action="msg-show" data-msg="${key}" style="margin-top:10px;">▶ Show it on the live preview</button>`
          : ""
      }
      <button type="button" class="btn btn-ghost btn-sm" data-action="msg-reset" data-msg="${key}" style="margin-top:10px;">Back to original</button>`;
  }

  function setMsg(key, field, raw, input) {
    const v = String(raw).trim();
    if (!cfg.messages) cfg.messages = {};
    const entry = cfg.messages[key] || (cfg.messages[key] = {});
    if (field === "enabled") {
      if (raw === false) entry.enabled = "0";
      else delete entry.enabled;
    } else if (!v) {
      delete entry[field];
      input.classList.remove("bad");
    } else if (field === "seconds" && !(/^\d{1,2}(\.\d{1,2})?$/.test(v) && Number(v) >= 0.5 && Number(v) <= 60)) {
      input.classList.add("bad");
      if (!Object.keys(entry).length) delete cfg.messages[key];
      return;
    } else {
      entry[field] = v;
      input.classList.remove("bad");
    }
    if (!Object.keys(entry).length) delete cfg.messages[key];
    updateMsgBadges();
    edited();
  }

  function updateMsgBadges() {
    root.querySelectorAll("[data-msg-box]").forEach((box) => {
      const on = !!(cfg.messages && cfg.messages[box.dataset.msgBox]);
      box.querySelector(".cz-mel-count").hidden = !on;
      box.classList.toggle("changed", on);
    });
  }

  let pendingToast = null;
  function postToast(key) {
    const frame = document.getElementById("czLiveFrame");
    if (frame && frame.contentWindow) frame.contentWindow.postMessage({ type: "freshcutz-preview-toast", key }, location.origin);
  }

  function showToastOnPreview(key) {
    const live = document.getElementById("czLive");
    live.scrollIntoView({ behavior: "smooth", block: "center" });
    if (!live.open) {
      pendingToast = key; // the frame loads first, then shows it (see the "ready" message below)
      live.open = true;
    } else {
      postPreview();
      setTimeout(() => postToast(key), 200);
    }
  }

  // ---- fine-tune: one expandable panel per element of the website ----------------
  function microSection() {
    const groups = SiteElements.GROUPS.map(
      (g) => `
        <div class="cz-mgroup">
          <div class="cz-mgroup-title">${esc(g.title)}</div>
          ${g.items
            .map(
              ([key, label]) => `
            <div class="cz-mel" data-el="${key}" data-label="${esc(label.toLowerCase())}">
              <button type="button" class="cz-mel-head" data-action="mel-toggle"><span>${esc(label)}</span><span class="cz-mel-count" hidden></span></button>
              <div class="cz-mel-body" hidden></div>
            </div>`
            )
            .join("")}
        </div>`
    ).join("");
    return `
      <div class="cz-hint" style="margin-bottom:8px;">
        Pick any piece of the customer website and change its colors, size, spacing, borders, shadow, animation
        or hide it completely. Leave a box empty to keep the original. Numbers without a unit mean pixels
        (type <b>50%</b> or <b>2rem</b> to use another unit). Open the live preview above to watch the changes.
      </div>
      ${toggleRow("animationsOff", "Turn off all animations & transitions", "Every movement on the website stops — cards appear instantly, buttons don't animate.")}
      <input type="search" id="czMFilter" class="cz-mfilter" placeholder="🔎 Find an element (e.g. search, price, button)" autocomplete="off" />
      <label class="cz-mchanged"><input type="checkbox" id="czMChanged" /> Show only the ones I've changed</label>
      ${groups}`;
  }

  function microField(key, p, val) {
    const id = `cz-m-${key}-${p.key}`;
    let input;
    if (p.type === "select") {
      input = `<select id="${id}" data-mel="${key}" data-mprop="${p.key}"><option value="">Default</option>${p.options
        .map(([v, l]) => `<option value="${v}" ${v === val ? "selected" : ""}>${esc(l)}</option>`)
        .join("")}</select>`;
    } else if (p.type === "color") {
      const ok = /^#[0-9a-f]{6}$/i.test(val);
      input = `<div class="cz-mcolor"><input type="color" data-mpick value="${ok ? val : "#000000"}" aria-label="${esc(p.label)} picker" /><input type="text" id="${id}" data-mel="${key}" data-mprop="${p.key}" maxlength="11" placeholder="default" spellcheck="false" autocomplete="off" value="${esc(val)}" /></div>`;
    } else {
      input = `<input type="text" id="${id}" data-mel="${key}" data-mprop="${p.key}" maxlength="40" placeholder="${esc(p.hint || "default")}" spellcheck="false" autocomplete="off" value="${esc(val)}" />`;
    }
    return `<div class="cz-mfield"><label for="${id}">${esc(p.label)}</label>${input}</div>`;
  }

  function microBody(key) {
    const st = (cfg.styles && cfg.styles[key]) || {};
    return (
      SiteElements.PROP_GROUPS.map(
        (pg) => `
        <div class="cz-mprop-group">
          <div class="cz-color-group-title">${esc(pg.title)}</div>
          <div class="cz-mgrid">${pg.props.map((p) => microField(key, p, st[p.key] ?? "")).join("")}</div>
        </div>`
      ).join("") +
      `<button type="button" class="btn btn-ghost btn-sm" data-action="mel-clear" data-el="${key}">Clear everything set for this element</button>`
    );
  }

  function setMicro(el, prop, raw, input) {
    const v = String(raw).trim();
    if (!cfg.styles) cfg.styles = {};
    if (!v) {
      if (cfg.styles[el]) {
        delete cfg.styles[el][prop];
        if (!Object.keys(cfg.styles[el]).length) delete cfg.styles[el];
      }
      input.classList.remove("bad");
    } else if (SiteElements.isValid(prop, v)) {
      (cfg.styles[el] = cfg.styles[el] || {})[prop] = SiteElements.PROPS[prop].type === "color" ? v.toLowerCase() : v;
      input.classList.remove("bad");
    } else {
      input.classList.add("bad"); // not stored until it is a value the website can use
      return;
    }
    updateMicroCounts();
    edited();
  }

  function updateMicroCounts() {
    root.querySelectorAll(".cz-mel").forEach((box) => {
      const n = Object.keys((cfg.styles && cfg.styles[box.dataset.el]) || {}).length;
      const badge = box.querySelector(".cz-mel-count");
      badge.hidden = n === 0;
      badge.textContent = `${n} set`;
      box.classList.toggle("changed", n > 0);
    });
  }

  function applyMicroFilter() {
    const filter = document.getElementById("czMFilter");
    const only = document.getElementById("czMChanged");
    if (!filter || !only) return;
    const q = filter.value.trim().toLowerCase();
    root.querySelectorAll(".cz-mgroup").forEach((group) => {
      let any = false;
      group.querySelectorAll(".cz-mel").forEach((box) => {
        const show =
          (!q || box.dataset.label.includes(q) || box.dataset.el.toLowerCase().includes(q)) &&
          (!only.checked || box.classList.contains("changed"));
        box.hidden = !show;
        if (show) any = true;
      });
      group.hidden = !any;
    });
  }

  // ---- live preview: the real storefront in a frame --------------------------------------
  let previewTimer = null;
  let liveWidth = 390;

  function sizeLiveFrame() {
    const wrap = document.getElementById("czLiveWrap");
    const frame = document.getElementById("czLiveFrame");
    if (!wrap || !frame) return;
    const height = 640;
    const scale = Math.min(1, (wrap.clientWidth || liveWidth) / liveWidth);
    frame.style.width = `${liveWidth}px`;
    frame.style.height = `${height / scale}px`;
    frame.style.transform = `scale(${scale})`;
    wrap.style.height = `${height}px`;
  }

  function postPreview() {
    const frame = document.getElementById("czLiveFrame");
    if (!frame || !frame.contentWindow || !cfg) return;
    frame.contentWindow.postMessage(
      { type: "freshcutz-preview", site: { config: { ...cfg, heroHeading: (cfg.heroHeading || "").replace(/\s+$/, "") }, logoUrl } },
      location.origin
    );
  }

  function schedulePreview() {
    clearTimeout(previewTimer);
    previewTimer = setTimeout(postPreview, 120);
  }

  function openLive() {
    const wrap = document.getElementById("czLiveWrap");
    if (!wrap || wrap.firstElementChild) return;
    wrap.innerHTML = `<iframe id="czLiveFrame" title="Live preview of the customer website" src="index.html?preview=1"></iframe>`;
    sizeLiveFrame();
  }

  window.addEventListener("message", (e) => {
    if (e.origin === location.origin && e.data && e.data.type === "freshcutz-preview-ready") {
      postPreview();
      if (pendingToast) {
        const key = pendingToast;
        pendingToast = null;
        setTimeout(() => postToast(key), 700);
      }
    }
  });
  window.addEventListener("resize", sizeLiveFrame);
  // <details> "toggle" doesn't bubble, so listen in the capture phase
  root.addEventListener(
    "toggle",
    (e) => {
      if (e.target.id === "czLive" && e.target.open) {
        openLive();
        sizeLiveFrame();
      }
    },
    true
  );

  function selectField(key, label, options, hint) {
    return `
      <div class="field">
        <label>${label}</label>
        <select data-select="${key}">${options.map(([v, l]) => `<option value="${v}">${l}</option>`).join("")}</select>
        ${hint ? `<div class="hint">${hint}</div>` : ""}
      </div>`;
  }

  function section(title, inner, open = false) {
    return `<details class="cz-card" ${open ? "open" : ""}><summary><h3>${title}</h3></summary><div class="cz-card-body">${inner}</div></details>`;
  }

  function colorField(key, label) {
    return `
      <div class="cz-color">
        <label for="cz-c-${key}">${label}</label>
        <div class="cz-color-inputs">
          <input type="color" id="cz-c-${key}" data-color="${key}" />
          <input type="text" class="cz-hex" data-hex="${key}" maxlength="7" spellcheck="false" autocomplete="off" aria-label="${label} hex code" />
        </div>
      </div>`;
  }

  function build() {
    root.innerHTML = `
      <div class="cz-card cz-tpl">
        <h3>Templates</h3>
        <div class="cz-hint">
          Keep up to 4 designs. Pick one to edit it — customers only ever see the one marked <b>LIVE</b>, so you can
          experiment on the others safely. Press <b>Save &amp; make live</b> to switch customers to the design you are editing.
        </div>
        <div class="cz-tpl-tabs" id="czTplTabs"></div>
        <div class="cz-tpl-edit">
          <label for="czTplName">Name of this template</label>
          <input type="text" id="czTplName" maxlength="30" autocomplete="off" />
        </div>
        <div class="cz-tpl-status" id="czTplStatus"></div>
        <button type="button" class="btn btn-ghost btn-sm" data-action="tpl-clear" id="czTplClear" hidden>Clear this template</button>
      </div>

      <div class="cz-intro">
        Change how the customer website looks and reads. Changes only reach customers when the template you are
        editing is the <b>LIVE</b> one (the logo is shared by all templates and changes as soon as you choose it).
        Tap a section to open it.
        Every switch turns that part on or off; wherever a text box can be left empty, empty simply hides it.
      </div>

      <div class="cz-preview-wrap">
        <div class="cz-preview-title">Preview</div>
        <div class="cz-preview" id="czPreview"></div>
      </div>

      <details class="cz-card" id="czLive">
        <summary><h3>🔴 Live preview of the real website</h3></summary>
        <div class="cz-card-body">
          <div class="cz-live-bar">
            <button type="button" class="btn btn-outline btn-sm" data-action="live-size" data-w="390">📱 Phone</button>
            <button type="button" class="btn btn-outline btn-sm" data-action="live-size" data-w="768">Tablet</button>
            <button type="button" class="btn btn-outline btn-sm" data-action="live-size" data-w="1200">💻 Computer</button>
            <a class="btn btn-ghost btn-sm" href="index.html" target="_blank" rel="noopener">Open site ↗</a>
          </div>
          <div class="cz-live-wrap" id="czLiveWrap"></div>
          <div class="cz-hint">This is the actual customer website, showing your changes as you make them. Nothing is public until you press Save.</div>
        </div>
      </details>

      ${section(
        "Brand & header",
        `
        <div class="cz-logo-row">
          <div class="cz-logo-box"><img id="czLogoImg" alt="Logo" /></div>
          <div class="cz-logo-actions">
            <label class="btn btn-outline btn-sm file-btn">
              ⬆ Upload logo
              <input type="file" id="czLogoFile" accept="image/png,image/jpeg,image/webp" hidden />
            </label>
            <button type="button" class="btn btn-ghost btn-sm" data-action="logo-remove" id="czLogoRemove">Use original logo</button>
            <div class="cz-hint">PNG, JPG or WEBP, up to 1 MB. A square image works best. Applies immediately.</div>
          </div>
        </div>
        ${toggleRow("showLogo", "Show logo")}
        ${textField("brandName", "Brand name", 40)}
        ${textField("tagline", "Tagline (under the name, on larger screens)", 80)}
        ${toggleRow("showTagline", "Show tagline")}
        ${textField("pageTitle", "Browser tab title", 80, "Leave empty to build it from the brand name and tagline.")}
        ${toggleRow("showWhatsapp", "Show WhatsApp button")}
        ${textField("whatsappNumber", "WhatsApp number (customers are taken to this chat)", 20, "Include the country code, e.g. +91 98765 43210. Leave empty to use the default number.")}
        ${toggleRow("showAccount", "Show My Account button", "Customers use this to see their past orders.")}
      `,
        true
      )}

      ${section(
        "Announcement bar",
        `
        ${toggleRow("showAnnouncement", "Show the announcement bar", "A thin colored strip under the header — good for offers, holiday timings or delivery news.")}
        <div class="field">
          <label>Announcement</label>
          <textarea data-text="announcementText" maxlength="200" rows="2" placeholder="e.g. 🎉 Free delivery on orders above ₹500 this week!"></textarea>
        </div>
      `
      )}

      ${section(
        "Hero banner",
        `
        ${toggleRow("showHero", "Show the hero banner", "The dark section at the top with the heading and badges.")}
        ${textField("heroHeading", "Heading", 60, "e.g. “Fresh Chicken,”")}
        ${textField("heroHighlight", "Highlighted words (shown in the highlight color)", 40, "e.g. “Cut to Order”")}
        <div class="field">
          <label>Line under the heading</label>
          <textarea data-text="heroText" maxlength="200" rows="2"></textarea>
        </div>
        ${toggleRow("showHeroText", "Show the line under the heading")}
        ${selectField("heroAlign", "Text alignment", [["center", "Centered"], ["left", "Left"]])}
        ${toggleRow("showHeroButton", "Show a button in the banner", "Takes customers straight down to the products.")}
        ${textField("heroButtonText", "Button text", 30)}
      `
      )}

      ${section(
        "Badges",
        `
        ${toggleRow("showBadges", "Show the badge row")}
        <div id="czBadges" class="cz-badges"></div>
        <button type="button" class="btn btn-outline btn-sm" data-action="badge-add" id="czBadgeAdd">+ Add badge</button>
      `
      )}

      ${section(
        "Shop page",
        `
        ${toggleRow("showSearch", "Show the search box")}
        ${textField("searchPlaceholder", "Search box hint text", 60)}
        ${toggleRow("showCategories", "Show the category bar")}
        ${toggleRow("showProductDescription", "Show product descriptions")}
        ${toggleRow("showMrp", "Show the crossed-out original price")}
        ${toggleRow("showDiscountBadge", "Show the “% OFF” badge on photos")}
        ${textField("addToCartText", "“Add to cart” button text", 24)}
        ${textField("outOfStockText", "“Out of stock” label", 24)}
        ${textField("emptyProductsText", "Message when there are no products", 160)}
        ${textField("noSearchResultsText", "Message when a search finds nothing", 160)}
      `
      )}

      ${section(
        "Cart & checkout",
        `
        ${textField("viewCartText", "Cart bar button (bottom of the screen)", 30)}
        ${textField("cartTitle", "Cart title", 40)}
        ${textField("cartEmptyText", "Message when the cart is empty", 160)}
        ${textField("checkoutButtonText", "Checkout button text", 40)}
        ${textField("placeOrderText", "Place-order button text", 30)}
        ${toggleRow("showCartAnimations", "Cart animations", "Item flies into the cart, the cart bar bounces, and the order-placed tick draws itself")}
        ${toggleRow("showLandmarkField", "Ask for a landmark in the address form")}
        ${toggleRow("showNotesField", "Ask for special instructions")}
        ${textField("notesLabel", "Special-instructions label", 60)}
        ${textField("successTitle", "Order-placed heading", 60)}
        ${toggleRow("showSuccessMessage", "Show a message under that heading")}
        ${textField("successMessage", "Order-placed message", 200)}
        ${toggleRow("showScreenshotHint", "Show the “take a screenshot” reminder")}
      `
      )}

      ${section(
        "Store-offline messages",
        `
        <div class="cz-hint" style="margin-bottom:6px;">Shown when you switch the store offline from the Orders tab.</div>
        ${textField("offlineBannerText", "Banner at the top", 200)}
        ${textField("offlineSheetText", "Message when someone tries to check out", 200)}
      `
      )}

      ${section(
        "Footer",
        `
        ${toggleRow("showFooter", "Show a footer at the bottom of the page", "Each line below only appears when you fill it in.")}
        <div class="field">
          <label>About text</label>
          <textarea data-text="footerAbout" maxlength="300" rows="2" placeholder="e.g. Freshly cut chicken delivered to your door every morning."></textarea>
        </div>
        ${textField("footerAddress", "Address", 200)}
        ${textField("footerHours", "Opening hours", 120, "e.g. Orders open 6 AM – 10 PM")}
        ${textField("footerPhone", "Phone number", 24, "Customers can tap it to call.")}
        ${textField("footerEmail", "Email", 100)}
        ${textField("instagramUrl", "Instagram link", 200, "Full address, e.g. https://instagram.com/yourshop")}
        ${textField("facebookUrl", "Facebook link", 200, "Full address, e.g. https://facebook.com/yourshop")}
        ${textField("footerCopyright", "Copyright line", 120, "{year} becomes the current year and {brand} your brand name. Empty hides the line.")}
      `
      )}

      ${section(
        "Sizes",
        `
        <div class="cz-hint" style="margin-bottom:8px;">Type a number: 100 is the original size, 50 is half, 200 is double, 400 is four times — anything from 10 to 1000. The preview updates as you type.</div>
        <div class="cz-color-group-title">Images & icons</div>
        ${IMAGE_SIZES.map(sizeField).join("")}
        <div class="cz-color-group-title" style="margin-top:14px;">Text</div>
        ${TEXT_SIZES.map(sizeField).join("")}
        <button type="button" class="btn btn-ghost btn-sm" data-action="sizes-reset" style="margin-top:10px;">Reset all sizes to 100%</button>
      `
      )}

      ${section(
        "Look & feel",
        `
        ${selectField("fontStyle", "Font style", [["classic", "Classic (original)"], ["modern", "Modern — clean sans-serif everywhere"], ["elegant", "Elegant — serif everywhere"]])}
        ${selectField("cornerStyle", "Corners", [["default", "Rounded (original)"], ["sharp", "Sharp — square, minimal"], ["round", "Extra round — soft and bubbly"]])}
      `
      )}

      ${section("Pop-up messages & texts", messagesSection())}

      ${section("Fine-tune any element (advanced)", microSection())}

      ${section(
        "Colors",
        COLOR_GROUPS.map(
          (g) => `
          <div class="cz-color-group">
            <div class="cz-color-group-title">${g.title}</div>
            <div class="cz-color-grid">${g.fields.map(([k, l]) => colorField(k, l)).join("")}</div>
          </div>`
        ).join("")
      )}

      <div class="cz-savebar">
        <span class="cz-dirty" id="czDirty" hidden>Unsaved changes</span>
        <button type="button" class="btn btn-ghost btn-sm" data-action="reset">Reset to original</button>
        <button type="button" class="btn btn-gold" data-action="save" id="czSave" disabled>Save template</button>
        <button type="button" class="btn btn-dark" data-action="publish" id="czPublish">Save &amp; make live</button>
      </div>`;
  }

  // ---- filling the form from cfg ------------------------------------------
  function fill() {
    root.querySelectorAll("[data-text]").forEach((el) => (el.value = cfg[el.dataset.text] ?? ""));
    root.querySelectorAll("[data-bool]").forEach((el) => (el.checked = cfg[el.dataset.bool] !== false));
    root.querySelectorAll("[data-size]").forEach((el) => (el.value = sizeOf(el.dataset.size)));
    root.querySelectorAll("[data-num]").forEach((el) => (el.value = cfg[el.dataset.num] ?? ""));
    root.querySelectorAll("[data-select]").forEach((el) => (el.value = cfg[el.dataset.select] ?? el.options[0].value));
    root.querySelectorAll("[data-color]").forEach((el) => (el.value = cfg[el.dataset.color] || "#000000"));
    root.querySelectorAll("[data-hex]").forEach((el) => (el.value = cfg[el.dataset.hex] || ""));
    renderBadges();
    renderLogo();
    root.querySelectorAll(".cz-mel").forEach((box) => {
      const body = box.querySelector(".cz-mel-body");
      if (!body.hidden) body.innerHTML = microBody(box.dataset.el);
    });
    root.querySelectorAll("[data-msg-box]").forEach((box) => {
      const body = box.querySelector(".cz-mel-body");
      if (!body.hidden) body.innerHTML = msgBody(box.dataset.msgBox);
    });
    updateMsgBadges();
    updateMicroCounts();
    applyMicroFilter();
    renderPreview();
    markDirty(false);
  }

  function sizeOf(key) {
    const n = Number(cfg[key]);
    return Number.isFinite(n) && n >= 10 ? Math.min(1000, n) : 100;
  }

  function renderLogo() {
    const img = document.getElementById("czLogoImg");
    img.src = logoUrl ? Utils.resolveImageUrl(logoUrl) : DEFAULT_LOGO;
    document.getElementById("czLogoRemove").hidden = !logoUrl;
  }

  function renderBadges() {
    const host = document.getElementById("czBadges");
    const list = cfg.badges || [];
    host.innerHTML = list
      .map(
        (b, i) => `
        <div class="cz-badge-row" data-index="${i}">
          <input type="text" class="cz-badge-icon" data-badge="icon" value="${esc(b.icon)}" maxlength="12" placeholder="😀" aria-label="Badge icon" />
          <input type="text" class="cz-badge-text" data-badge="text" value="${esc(b.text)}" maxlength="40" placeholder="Badge text" aria-label="Badge text" />
          <label class="switch" title="Show this badge"><input type="checkbox" data-badge="enabled" ${b.enabled !== false ? "checked" : ""} /><span class="track"></span></label>
          <div class="cz-badge-tools">
            <button type="button" data-action="badge-up" aria-label="Move up" ${i === 0 ? "disabled" : ""}>▲</button>
            <button type="button" data-action="badge-down" aria-label="Move down" ${i === list.length - 1 ? "disabled" : ""}>▼</button>
            <button type="button" data-action="badge-del" aria-label="Delete badge">🗑</button>
          </div>
        </div>`
      )
      .join("");
    document.getElementById("czBadgeAdd").disabled = list.length >= MAX_BADGES;
  }

  function renderPreview() {
    const c = cfg;
    const logoSrc = logoUrl ? Utils.resolveImageUrl(logoUrl) : DEFAULT_LOGO;
    const chips = (c.badges || [])
      .filter((b) => b.enabled !== false && b.text)
      .map(
        (b) =>
          `<span class="cz-chip" style="background:${rgba(c.badgeBg, 0.14)};border-color:${rgba(c.badgeBg, 0.4)};color:${c.badgeText};font-size:${(11 * sizeOf("sizeBadges")) / 100}px">${esc(b.icon ? `${b.icon} ${b.text}` : b.text)}</span>`
      )
      .join("");
    const heading = (c.heroHeading || "").replace(/\s+$/, "");
    document.getElementById("czPreview").innerHTML = `
      <div class="cz-pv-header" style="background:${c.headerBg}">
        <div class="cz-pv-brand">
          ${c.showLogo !== false ? `<span class="cz-pv-logo" style="width:${(30 * sizeOf("sizeLogo")) / 100}px;height:${(30 * sizeOf("sizeLogo")) / 100}px"><img src="${esc(logoSrc)}" alt="" /></span>` : ""}
          <div>
            <div class="cz-pv-name" style="color:${c.brandNameColor};font-size:${(14 * sizeOf("sizeBrandName")) / 100}px">${esc(c.brandName)}</div>
            ${c.showTagline !== false && c.tagline ? `<div class="cz-pv-tag" style="color:${c.taglineColor};font-size:${(10 * sizeOf("sizeTagline")) / 100}px">${esc(c.tagline)}</div>` : ""}
          </div>
        </div>
        <div class="cz-pv-icons" style="font-size:${(13 * sizeOf("sizeHeaderIcons")) / 100}px">${c.showWhatsapp !== false ? "<i>💬</i>" : ""}${c.showAccount !== false ? "<i>👤</i>" : ""}</div>
      </div>
      ${c.showAnnouncement && c.announcementText ? `<div class="cz-pv-announce" style="background:${c.announcementBg};color:${c.announcementColor};font-size:${(11 * sizeOf("sizeAnnouncement")) / 100}px">${esc(c.announcementText)}</div>` : ""}
      ${
        c.showHero !== false
          ? `<div class="cz-pv-hero" style="background:radial-gradient(circle at 20% 20%, ${c.heroBgStart} 0%, ${c.heroBgEnd} 60%)">
              <div class="cz-pv-h1" style="color:${c.heroHeadingColor};font-size:${(18 * sizeOf("sizeHeroHeading")) / 100}px">${esc(heading)}${heading && c.heroHighlight ? " " : ""}<span style="color:${c.heroHighlightColor}">${esc(c.heroHighlight)}</span></div>
              ${c.showHeroText !== false && c.heroText ? `<div class="cz-pv-p" style="color:${c.heroTextColor};font-size:${(12 * sizeOf("sizeHeroText")) / 100}px">${esc(c.heroText)}</div>` : ""}
              ${c.showBadges !== false && chips ? `<div class="cz-pv-chips">${chips}</div>` : ""}
              ${c.showHeroButton && c.heroButtonText ? `<span class="cz-pv-btn cz-pv-hero-btn" style="background:${c.accentColor}">${esc(c.heroButtonText)}</span>` : ""}
            </div>`
          : ""
      }
      <div class="cz-pv-page" style="background:${c.pageBg};color:${c.textColor}">
        <span class="cz-pv-btn" style="background:${c.accentColor};font-size:${(12 * sizeOf("sizeButtons")) / 100}px">${esc(c.addToCartText || "Add to cart")}</span>
        <span class="cz-pv-note" style="color:${c.textColor}">page background &amp; buttons</span>
      </div>
      ${c.showFooter ? `<div class="cz-pv-footer" style="background:${c.footerBg};color:${c.footerTextColor}">${esc(c.footerAbout || c.brandName)}</div>` : ""}`;
    schedulePreview();
  }

  function rgba(hex, a) {
    const n = parseInt((hex || "#000000").slice(1), 16);
    return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${a})`;
  }

  function markDirty(value = true) {
    dirty = value;
    document.getElementById("czSave").disabled = !(dirty || !tplSaved);
    document.getElementById("czDirty").hidden = !dirty;
  }

  function edited() {
    markDirty(true);
    renderPreview();
  }

  // ---- events --------------------------------------------------------------
  root.addEventListener("input", (e) => {
    const t = e.target;
    if (t.id === "czMFilter") return applyMicroFilter();
    if (t.id === "czTplName") {
      tplName = t.value;
      markDirty(true);
      const label = root.querySelector(".cz-tpl-tab.on .cz-tpl-name");
      if (label) label.textContent = tplName.trim() || `Template ${slot}`;
      return;
    }
    if (t.dataset.mpick !== undefined) {
      const text = t.parentElement.querySelector("[data-mprop]");
      text.value = t.value;
      return setMicro(text.dataset.mel, text.dataset.mprop, t.value, text);
    }
    if (t.dataset.mprop) return setMicro(t.dataset.mel, t.dataset.mprop, t.value, t);
    if (t.dataset.msg && t.dataset.mfield && t.type !== "checkbox") return setMsg(t.dataset.msg, t.dataset.mfield, t.value, t);
    if (t.dataset.num) {
      const n = Number(t.value);
      const min = Number(t.min);
      const max = Number(t.max);
      if (t.value !== "" && Number.isFinite(n) && n >= min && n <= max) {
        cfg[t.dataset.num] = n;
        edited();
      }
      return;
    }
    if (t.dataset.text) {
      cfg[t.dataset.text] = t.value;
      edited();
    } else if (t.dataset.size) {
      // typing: accept any whole number from 10 to 1000; anything else waits
      // until the box is left (see the change handler) instead of fighting the typist
      const n = Number(t.value);
      if (t.value !== "" && Number.isFinite(n) && n >= 10 && n <= 1000) {
        cfg[t.dataset.size] = Math.round(n);
        edited();
      }
    } else if (t.dataset.color) {
      cfg[t.dataset.color] = t.value;
      root.querySelector(`[data-hex="${t.dataset.color}"]`).value = t.value;
      edited();
    } else if (t.dataset.hex) {
      let v = t.value.trim();
      if (v && v[0] !== "#") v = `#${v}`;
      if (/^#[0-9a-fA-F]{6}$/.test(v)) {
        cfg[t.dataset.hex] = v.toLowerCase();
        root.querySelector(`[data-color="${t.dataset.hex}"]`).value = v;
        edited();
      }
    } else if (t.dataset.badge === "icon" || t.dataset.badge === "text") {
      const i = Number(t.closest(".cz-badge-row").dataset.index);
      cfg.badges[i][t.dataset.badge] = t.value;
      edited();
    }
  });

  root.addEventListener("change", (e) => {
    const t = e.target;
    if (t.id === "czMChanged") return applyMicroFilter();
    if (t.dataset.msg && t.dataset.mfield) {
      if (t.type === "checkbox") return setMsg(t.dataset.msg, "enabled", t.checked ? true : false, t);
      if (t.tagName === "SELECT") return setMsg(t.dataset.msg, t.dataset.mfield, t.value, t);
      return;
    }
    if (t.dataset.num) {
      // left empty or out of range: back to the last good value
      const n = Number(t.value);
      if (t.value === "" || !Number.isFinite(n) || n < Number(t.min) || n > Number(t.max)) t.value = cfg[t.dataset.num] ?? "";
      return;
    }
    if (t.dataset.mprop) {
      if (t.tagName === "SELECT") return setMicro(t.dataset.mel, t.dataset.mprop, t.value, t);
      {
        // leaving a box holding something unusable: put the last good value back
        const saved = (cfg.styles && cfg.styles[t.dataset.mel] && cfg.styles[t.dataset.mel][t.dataset.mprop]) || "";
        if (t.value.trim() !== saved && !SiteElements.isValid(t.dataset.mprop, t.value)) {
          t.value = saved;
          t.classList.remove("bad");
        }
      }
      return;
    }
    if (t.dataset.bool) {
      cfg[t.dataset.bool] = t.checked;
      edited();
    } else if (t.dataset.size) {
      t.value = sizeOf(t.dataset.size); // empty / out-of-range → back to the last good value
      cfg[t.dataset.size] = Number(t.value);
      edited();
    } else if (t.dataset.select) {
      cfg[t.dataset.select] = t.value;
      edited();
    } else if (t.dataset.badge === "enabled") {
      const i = Number(t.closest(".cz-badge-row").dataset.index);
      cfg.badges[i].enabled = t.checked;
      edited();
    } else if (t.id === "czLogoFile") {
      uploadLogo(t);
    } else if (t.dataset.hex) {
      // leaving the hex box with something invalid: put the last good value back
      t.value = cfg[t.dataset.hex] || "";
    }
  });

  root.addEventListener("click", (e) => {
    const btn = e.target.closest("[data-action]");
    if (!btn) return;
    const action = btn.dataset.action;
    const row = btn.closest(".cz-badge-row");
    const i = row ? Number(row.dataset.index) : -1;

    if (action === "badge-add") {
      if (cfg.badges.length >= MAX_BADGES) return;
      cfg.badges.push({ icon: "⭐", text: "", enabled: true });
      renderBadges();
      edited();
      const inputs = root.querySelectorAll('[data-badge="text"]');
      inputs[inputs.length - 1]?.focus();
    } else if (action === "badge-del") {
      cfg.badges.splice(i, 1);
      renderBadges();
      edited();
    } else if (action === "badge-up" && i > 0) {
      [cfg.badges[i - 1], cfg.badges[i]] = [cfg.badges[i], cfg.badges[i - 1]];
      renderBadges();
      edited();
    } else if (action === "badge-down" && i < cfg.badges.length - 1) {
      [cfg.badges[i + 1], cfg.badges[i]] = [cfg.badges[i], cfg.badges[i + 1]];
      renderBadges();
      edited();
    } else if (action === "mel-toggle") {
      const box = btn.closest(".cz-mel");
      const body = box.querySelector(".cz-mel-body");
      if (body.hidden) {
        body.innerHTML = microBody(box.dataset.el);
        body.hidden = false;
      } else {
        body.hidden = true;
      }
      box.classList.toggle("open", !body.hidden);
    } else if (action === "msg-toggle") {
      const box = btn.closest("[data-msg-box]");
      const body = box.querySelector(".cz-mel-body");
      if (body.hidden) {
        body.innerHTML = msgBody(box.dataset.msgBox);
        body.hidden = false;
      } else {
        body.hidden = true;
      }
      box.classList.toggle("open", !body.hidden);
    } else if (action === "msg-show") {
      showToastOnPreview(btn.dataset.msg);
    } else if (action === "msg-reset") {
      if (cfg.messages) delete cfg.messages[btn.dataset.msg];
      btn.closest(".cz-mel-body").innerHTML = msgBody(btn.dataset.msg);
      updateMsgBadges();
      edited();
    } else if (action === "mel-clear") {
      const key = btn.dataset.el;
      if (cfg.styles) delete cfg.styles[key];
      btn.closest(".cz-mel-body").innerHTML = microBody(key);
      updateMicroCounts();
      applyMicroFilter();
      edited();
    } else if (action === "live-size") {
      liveWidth = Number(btn.dataset.w) || 390;
      sizeLiveFrame();
    } else if (action === "sizes-reset") {
      root.querySelectorAll("[data-size]").forEach((el) => {
        cfg[el.dataset.size] = 100;
        el.value = 100;
      });
      edited();
    } else if (action === "save") {
      save();
    } else if (action === "reset") {
      reset();
    } else if (action === "publish") {
      publish();
    } else if (action === "tpl-pick") {
      pickTemplate(Number(btn.dataset.slot));
    } else if (action === "tpl-clear") {
      clearTemplate();
    } else if (action === "logo-remove") {
      removeLogo();
    }
  });

  // ---- server calls ----------------------------------------------------------
  function applyResponse(res) {
    cfg = res.config;
    cfg.badges = (cfg.badges || []).map((b) => ({ icon: b.icon || "", text: b.text || "", enabled: b.enabled !== false }));
    cfg.styles = cfg.styles && typeof cfg.styles === "object" ? cfg.styles : {};
    cfg.messages = cfg.messages && typeof cfg.messages === "object" ? cfg.messages : {};
    logoUrl = res.logoUrl || null;
  }

  const saveLabel = () => (tplPrimary ? "Save (updates live website)" : "Save template");

  function applyDetail(d) {
    applyResponse({ config: d.config, logoUrl: d.logoUrl });
    slot = d.slot;
    tplName = d.name || `Template ${d.slot}`;
    tplPrimary = !!d.primary;
    tplSaved = !!d.saved;
  }

  async function loadTemplates() {
    templates = await Api.get("/api/admin/site/templates");
  }

  function renderTemplates() {
    const tabs = document.getElementById("czTplTabs");
    if (!tabs) return;
    tabs.innerHTML = templates
      .map(
        (t) => `
        <button type="button" class="cz-tpl-tab ${t.slot === slot ? "on" : ""}" data-action="tpl-pick" data-slot="${t.slot}">
          <span class="cz-tpl-name">${esc(t.slot === slot ? tplName : t.name)}</span>
          ${t.primary ? `<span class="cz-tpl-badge live">LIVE</span>` : t.saved ? "" : `<span class="cz-tpl-badge empty">empty</span>`}
        </button>`
      )
      .join("");
    const name = document.getElementById("czTplName");
    if (name && document.activeElement !== name) name.value = tplName;
    document.getElementById("czTplStatus").innerHTML = tplPrimary
      ? `<b>This is the LIVE design.</b> Saving it updates your customer website straight away.`
      : `Customers <b>can't see</b> this template. Save it to keep your changes, or press <b>Save &amp; make live</b> to show it to customers.${
          tplSaved ? "" : " (Not saved yet — it started as a copy of your live design.)"
        }`;
    document.getElementById("czTplClear").hidden = !(tplSaved && !tplPrimary);
    document.getElementById("czPublish").hidden = tplPrimary;
    const save = document.getElementById("czSave");
    if (!save.querySelector(".spinner")) save.textContent = saveLabel();
    markDirty(dirty);
  }

  async function save() {
    const btn = document.getElementById("czSave");
    btn.disabled = true;
    btn.innerHTML = `<span class="spinner"></span> Saving...`;
    try {
      const payload = { ...cfg, heroHeading: (cfg.heroHeading || "").replace(/\s+$/, "") };
      const res = await Api.put(`/api/admin/site/templates/${slot}`, { name: tplName, config: payload });
      applyDetail(res);
      fill();
      await loadTemplates();
      btn.textContent = saveLabel();
      renderTemplates();
      Toast.show(
        res.primary ? "Saved — the customer website is updated" : `“${res.name}” saved — customers still see your live design`,
        "success"
      );
      return true;
    } catch (err) {
      Toast.show(err.message, "error");
      btn.disabled = false;
      return false;
    } finally {
      btn.textContent = saveLabel();
    }
  }

  /** Saves the template being edited (if needed) and makes it the one customers see. */
  async function publish() {
    if (tplPrimary) return;
    if (!window.confirm(`Show “${tplName.trim() || `Template ${slot}`}” to your customers now?`)) return;
    const btn = document.getElementById("czPublish");
    btn.disabled = true;
    btn.innerHTML = `<span class="spinner"></span> Publishing...`;
    try {
      if ((dirty || !tplSaved) && !(await save())) return;
      const res = await Api.post(`/api/admin/site/templates/${slot}/primary`);
      applyDetail(res);
      fill();
      await loadTemplates();
      renderTemplates();
      Toast.show(`“${res.name}” is now your live design`, "success");
    } catch (err) {
      Toast.show(err.message, "error");
    } finally {
      btn.disabled = false;
      btn.innerHTML = "Save &amp; make live";
    }
  }

  async function pickTemplate(n) {
    if (!n || n === slot) return;
    if (dirty && !window.confirm("You have unsaved changes in this template. Switch anyway and lose them?")) return;
    try {
      applyDetail(await Api.get(`/api/admin/site/templates/${n}`));
      fill();
      renderTemplates();
    } catch (err) {
      Toast.show(err.message, "error");
    }
  }

  async function clearTemplate() {
    if (!window.confirm(`Clear “${tplName.trim() || `Template ${slot}`}”? Its saved design is deleted (your live design is not affected).`)) return;
    try {
      templates = await Api.del(`/api/admin/site/templates/${slot}`);
      applyDetail(await Api.get(`/api/admin/site/templates/${slot}`));
      fill();
      renderTemplates();
      Toast.show("Template cleared", "success");
    } catch (err) {
      Toast.show(err.message, "error");
    }
  }

  /** Puts the template being edited back to the original look. Nothing is saved until you press Save. */
  async function reset() {
    if (!window.confirm("Put this template back to the original look? Nothing changes for customers until you save it (your logo is not touched).")) return;
    try {
      const def = await Api.get("/api/admin/site/templates/defaults");
      applyResponse({ config: def, logoUrl });
      fill();
      markDirty(true);
      Toast.show("Original look loaded — press Save to keep it", "success");
    } catch (err) {
      Toast.show(err.message, "error");
    }
  }

  async function uploadLogo(input) {
    const file = input.files && input.files[0];
    input.value = "";
    if (!file) return;
    if (!/^image\/(png|jpe?g|webp)$/i.test(file.type)) {
      Toast.show("The logo must be a PNG, JPG or WEBP image", "error");
      return;
    }
    if (file.size > MAX_LOGO_BYTES) {
      Toast.show("That logo is too large (max 1 MB) — use a smaller image", "error");
      return;
    }
    const form = new FormData();
    form.append("file", file);
    try {
      const res = await Api.upload("/api/admin/site/logo", form);
      logoUrl = res.logoUrl || null; // text edits in the form stay as they are
      renderLogo();
      renderPreview();
      Toast.show("Logo updated on the customer website", "success");
    } catch (err) {
      Toast.show(err.message, "error");
    }
  }

  async function removeLogo() {
    try {
      const res = await Api.del("/api/admin/site/logo");
      logoUrl = res.logoUrl || null;
      renderLogo();
      renderPreview();
      Toast.show("Back to the original logo", "success");
    } catch (err) {
      Toast.show(err.message, "error");
    }
  }

  /** Loads the templates when the tab opens. Never overwrites edits that
   *  haven't been saved yet. */
  async function refresh() {
    if (dirty || loading) return;
    loading = true;
    try {
      await loadTemplates();
      if (!slot) slot = (templates.find((t) => t.primary) || templates[0]).slot;
      const detail = await Api.get(`/api/admin/site/templates/${slot}`);
      if (!root.firstElementChild) build();
      applyDetail(detail);
      fill();
      renderTemplates();
    } catch (err) {
      if (!cfg) {
        root.innerHTML = `<div class="empty-state"><p>${esc(err.message)}</p><p class="cz-hint">If you just updated the website, copy the new backend files and restart the backend.</p></div>`;
      }
    } finally {
      loading = false;
    }
  }

  return { refresh };
})();
