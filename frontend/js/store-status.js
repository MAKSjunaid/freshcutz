/* Polls whether the store is currently accepting orders (admin can flip
 * this offline for the night / when overwhelmed) and shows a banner when
 * it isn't. checkout.js checks window.StoreStatus.isOnline() before
 * opening the checkout form, as a proactive UX layer — the real gate is
 * server-side in OrderService.placeOrder(), so this can't be bypassed by
 * skipping the banner. */

window.StoreStatus = (() => {
  const POLL_MS = 15000;
  const banner = document.getElementById("storeOfflineBanner");
  let online = true;

  function apply() {
    if (banner) banner.hidden = online;
  }

  async function check() {
    try {
      const status = await Api.get("/api/store/status");
      online = status.online !== false;
    } catch {
      // can't reach the server right now — don't block checkout on a guess
    } finally {
      apply();
    }
  }

  check();
  setInterval(check, POLL_MS);

  return { isOnline: () => online };
})();
