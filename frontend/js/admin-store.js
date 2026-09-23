/* Store-wide Online/Offline toggle in the admin topbar. When offline,
 * customers see a banner and can't place orders (enforced server-side in
 * OrderService.placeOrder(); this toggle just controls that flag). */

window.AdminStore = (() => {
  const toggle = document.getElementById("storeOnlineToggle");
  const label = document.getElementById("storeToggleLabel");

  function applyLabel(online) {
    label.textContent = online ? "Online" : "Offline";
    label.style.color = online ? "" : "#ff9d90";
  }

  async function refresh() {
    try {
      const status = await Api.get("/api/admin/store");
      toggle.checked = status.online;
      applyLabel(status.online);
    } catch {
      // leave the toggle as-is — a background refresh failure shouldn't
      // yank control away from whatever the admin is looking at
    }
  }

  toggle.addEventListener("change", async () => {
    const online = toggle.checked;
    try {
      const status = await Api.patch("/api/admin/store/online", { online });
      applyLabel(status.online);
      Toast.show(status.online ? "Store is now online and accepting orders" : "Store is now offline — customers can't place orders", "success");
    } catch (err) {
      toggle.checked = !online;
      Toast.show(err.message, "error");
    }
  });

  return { refresh };
})();
