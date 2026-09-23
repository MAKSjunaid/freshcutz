/* Web Push notifications for the admin panel. A new order always shows a
 * real OS-level notification; an order-delivered notification is
 * suppressed by sw.js when this device already has the site open (see
 * sw.js for why) — from this file's side, both are the exact same
 * "subscribe once" flow.
 *
 * iOS only allows Web Push from a page added to the Home Screen (Apple's
 * rule) — isIos()/isStandalone() below detect that and show a one-time
 * hint instead of a button that can't work yet. */

window.AdminPush = (() => {
  const btn = document.getElementById("pushToggleBtn");
  const iosBanner = document.getElementById("pushIosBanner");
  const IOS_HINT_DISMISSED_KEY = "freshcutz_admin_ios_push_hint_dismissed";
  let registration = null;

  function isIos() {
    return /iphone|ipad|ipod/i.test(navigator.userAgent);
  }

  function isStandalone() {
    return window.navigator.standalone === true || window.matchMedia("(display-mode: standalone)").matches;
  }

  function supported() {
    return "serviceWorker" in navigator && "PushManager" in window && "Notification" in window;
  }

  function urlBase64ToUint8Array(base64String) {
    const padding = "=".repeat((4 - (base64String.length % 4)) % 4);
    const base64 = (base64String + padding).replace(/-/g, "+").replace(/_/g, "/");
    const raw = window.atob(base64);
    const output = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; i++) output[i] = raw.charCodeAt(i);
    return output;
  }

  async function init() {
    if (isIos() && !isStandalone()) {
      if (iosBanner && localStorage.getItem(IOS_HINT_DISMISSED_KEY) !== "1") {
        iosBanner.hidden = false;
      }
      return; // PushManager doesn't exist in a plain iOS Safari tab
    }
    if (!supported() || !btn) return;

    try {
      registration = await navigator.serviceWorker.register("sw.js");
    } catch {
      return;
    }
    btn.hidden = false;
    btn.addEventListener("click", onToggleClick);
    refreshButton();
  }

  async function refreshButton() {
    if (!registration || !btn) return;
    const sub = await registration.pushManager.getSubscription();
    btn.classList.toggle("is-active", !!sub);
    btn.title = sub ? "Order alerts on — tap to turn off" : "Enable order alerts on this device";
  }

  async function onToggleClick() {
    const existing = await registration.pushManager.getSubscription();
    if (existing) {
      await unsubscribe(existing);
    } else {
      await subscribe();
    }
    refreshButton();
  }

  async function subscribe() {
    const permission = await Notification.requestPermission();
    if (permission !== "granted") {
      Toast.show("Notification permission was not granted", "error");
      return;
    }
    try {
      const { publicKey } = await Api.get("/api/admin/push/vapid-public-key");
      if (!publicKey) {
        Toast.show("Push notifications aren't configured on the server yet", "error");
        return;
      }
      const sub = await registration.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: urlBase64ToUint8Array(publicKey),
      });
      await Api.post("/api/admin/push/subscribe", sub.toJSON());
      Toast.show("Order alerts enabled on this device", "success");
    } catch (err) {
      Toast.show("Couldn't enable alerts: " + err.message, "error");
    }
  }

  async function unsubscribe(sub) {
    try {
      await Api.post("/api/admin/push/unsubscribe", { endpoint: sub.endpoint });
      await sub.unsubscribe();
      Toast.show("Order alerts turned off on this device", "success");
    } catch (err) {
      Toast.show(err.message, "error");
    }
  }

  document.getElementById("pushIosBannerClose")?.addEventListener("click", () => {
    localStorage.setItem(IOS_HINT_DISMISSED_KEY, "1");
    iosBanner.hidden = true;
  });

  document.addEventListener("freshcutz:login", init);

  return {};
})();
