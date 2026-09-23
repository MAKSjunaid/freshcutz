/* Service worker for FreshCutz Admin — handles Web Push notifications.
 * Registered only from admin.html (see admin-push.js); the customer
 * storefront never registers this, so customers are never asked to
 * allow notifications. */

self.addEventListener("install", () => {
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(self.clients.claim());
});

self.addEventListener("push", (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch {
    data = { title: "FreshCutz Admin", body: event.data ? event.data.text() : "" };
  }
  event.waitUntil(handlePush(data));
});

/** "Order delivered" is something the admin just did themselves from this
 * same panel, so skip the popup if a tab has the site open and visible —
 * no need to tell them something they already just watched happen. A new
 * order always shows regardless, since that's the one thing they must
 * not miss, whether the site is open or not. */
async function handlePush(data) {
  if (data.type === "order-delivered") {
    const clients = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    const siteIsOpen = clients.some((c) => c.visibilityState === "visible");
    if (siteIsOpen) return;
  }

  await self.registration.showNotification(data.title || "FreshCutz Admin", {
    body: data.body || "",
    icon: "images/logo.jpg",
    badge: "images/logo.jpg",
    tag: data.type || "freshcutz",
    data,
  });
}

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  event.waitUntil(focusAdmin());
});

async function focusAdmin() {
  const clients = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
  const existing = clients.find((c) => c.url.includes("admin.html"));
  if (existing) {
    existing.focus();
  } else {
    self.clients.openWindow("admin.html");
  }
}
