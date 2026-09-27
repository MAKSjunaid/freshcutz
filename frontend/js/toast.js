/* Tiny toast notification helper. Call Toast.show("message", "success"|"error"). */

const Toast = (() => {
  function host() {
    let el = document.getElementById("toastHost");
    if (!el) {
      el = document.createElement("div");
      el.id = "toastHost";
      document.body.appendChild(el);
    }
    return el;
  }

  function show(message, type = "success", duration = 3200) {
    const hostEl = host();
    // A cart/checkout/profile sheet renders its markup into #sheetRoot, so
    // a non-empty #sheetRoot means one's currently open — its footer button
    // sits at the very bottom of the screen and varies in height sheet to
    // sheet, so the toast floats near the top instead of risking a clash
    // with it (see the .toast-above-sheet rule in components.css).
    const sheetOpen = !!document.getElementById("sheetRoot")?.childElementCount;
    hostEl.classList.toggle("toast-above-sheet", sheetOpen);

    const el = document.createElement("div");
    el.className = `toast ${type}`;
    el.textContent = message;
    hostEl.appendChild(el);
    const timer = setTimeout(() => fadeOut(el), duration);
    el.dataset.timerId = timer; // so dismissAll() can cancel it instead of double-fading
  }

  function fadeOut(el) {
    if (el.dataset.fading) return; // already on its way out
    el.dataset.fading = "1";
    el.style.transition = "opacity 200ms ease, transform 200ms ease";
    el.style.opacity = "0";
    el.style.transform = "translateY(6px)";
    setTimeout(() => el.remove(), 220);
  }

  /** Instantly clears any toast still on screen — e.g. an "added to cart"
   *  toast that hasn't timed out yet when the customer opens the cart or
   *  checkout sheet right after. A toast belongs to the screen it was
   *  triggered from and shouldn't linger into the next one. */
  function dismissAll() {
    host()
      .querySelectorAll(".toast")
      .forEach((el) => {
        clearTimeout(Number(el.dataset.timerId));
        fadeOut(el);
      });
  }

  return { show, dismissAll };
})();
