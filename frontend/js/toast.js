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
    const el = document.createElement("div");
    el.className = `toast ${type}`;
    el.textContent = message;
    host().appendChild(el);
    setTimeout(() => {
      el.style.transition = "opacity 200ms ease, transform 200ms ease";
      el.style.opacity = "0";
      el.style.transform = "translateY(6px)";
      setTimeout(() => el.remove(), 220);
    }, duration);
  }

  return { show };
})();
