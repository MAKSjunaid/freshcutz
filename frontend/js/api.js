/* Thin fetch wrapper: builds URLs against FRESHCUTZ_API_BASE, attaches the
 * admin token when present, and turns non-2xx responses into thrown errors
 * with the server's message. */

const Api = (() => {
  function base() {
    return window.FRESHCUTZ_API_BASE || "";
  }

  function adminToken() {
    return localStorage.getItem("freshcutz_admin_token");
  }

  // True once the page is being refreshed / closed. Requests still in flight
  // are cancelled by the browser at that moment and fail like a network error —
  // that must never be mistaken for "the server is unreachable".
  let unloading = false;
  window.addEventListener("pagehide", () => { unloading = true; });
  window.addEventListener("beforeunload", () => { unloading = true; });
  window.addEventListener("pageshow", () => { unloading = false; });

  // One shared, short "is the server up?" check, used when an admin request
  // fails without any HTTP answer. Any HTTP reply at all (even an error) means
  // the server is up.
  let probe = null;
  function backendReachable() {
    if (!probe) {
      probe = (async () => {
        const ctrl = new AbortController();
        const timer = setTimeout(() => ctrl.abort(), 6000);
        try {
          await fetch(`${base()}/api/store/status`, { cache: "no-store", signal: ctrl.signal });
          return true;
        } catch (_) {
          return false;
        } finally {
          clearTimeout(timer);
        }
      })();
      probe.finally(() => setTimeout(() => { probe = null; }, 2000));
    }
    return probe;
  }

  async function request(method, path, body, isMultipart = false) {
    const headers = {};
    const token = adminToken();
    if (path.startsWith("/api/admin") && token) {
      headers["Authorization"] = `Bearer ${token}`;
    }

    let payload;
    if (isMultipart) {
      payload = body;
    } else if (body !== undefined) {
      headers["Content-Type"] = "application/json";
      payload = JSON.stringify(body);
    }

    const isAdminPath = path.startsWith("/api/admin");
    const isLoginPath = path.endsWith("/auth/login");

    let response;
    try {
      response = await fetch(`${base()}${path}`, { method, headers, body: payload });
    } catch (networkErr) {
      // No HTTP answer at all. Three very different things look like this:
      //  1. the page is being refreshed (the browser cancels in-flight
      //     requests) — nothing should happen, and above all the login must
      //     NOT be dropped;
      //  2. the server really is unreachable / still waking up — keep the
      //     login, tell the admin once, and let the next request retry;
      //  3. the server answered "401 not signed in" but the browser hid that
      //     answer (the backend's auth filter used to reply without CORS
      //     headers) — only this case means the session is really gone.
      // A quick check of a public endpoint tells 2 and 3 apart.
      if (unloading) {
        throw new Error("Request cancelled");
      }
      if (isAdminPath && !isLoginPath) {
        if (await backendReachable()) {
          if (!unloading && adminToken()) {
            localStorage.removeItem("freshcutz_admin_token");
            window.dispatchEvent(new CustomEvent("freshcutz:unauthorized", { detail: { reason: "expired" } }));
          }
          throw new Error("Your session expired — please sign in again");
        }
        if (!unloading) {
          window.dispatchEvent(new CustomEvent("freshcutz:offline"));
        }
      }
      throw new Error(
        "Could not reach the FreshCutz server. Check your internet connection or that the backend is running."
      );
    }

    if (response.status === 401 && isAdminPath) {
      localStorage.removeItem("freshcutz_admin_token");
      if (!isLoginPath) {
        window.dispatchEvent(new CustomEvent("freshcutz:unauthorized", { detail: { reason: "expired" } }));
      }
    }

    if (response.status === 204) {
      return null;
    }

    const contentType = response.headers.get("content-type") || "";
    const data = contentType.includes("application/json") ? await response.json() : null;

    if (!response.ok) {
      const message = (data && (data.message || (data.details || []).join(", "))) || "Something went wrong";
      throw new Error(message);
    }
    return data;
  }

  /** Downloads a file response (e.g. the orders .xlsx export) as a Blob,
   * carrying the admin token the same way `request` does — a plain <a>
   * link can't attach an Authorization header, so this uses fetch instead. */
  async function downloadBlob(path) {
    const headers = {};
    const token = adminToken();
    if (token) headers["Authorization"] = `Bearer ${token}`;

    const response = await fetch(`${base()}${path}`, { headers });
    if (!response.ok) {
      let message = "Could not download the file";
      try {
        const data = await response.json();
        message = data.message || message;
      } catch (_) {
        /* response wasn't JSON — keep the default message */
      }
      throw new Error(message);
    }
    const disposition = response.headers.get("content-disposition") || "";
    const match = disposition.match(/filename="?([^"]+)"?/);
    return { blob: await response.blob(), filename: match ? match[1] : "download.xlsx" };
  }

  return {
    get: (path) => request("GET", path),
    post: (path, body) => request("POST", path, body),
    put: (path, body) => request("PUT", path, body),
    patch: (path, body) => request("PATCH", path, body ?? {}),
    del: (path) => request("DELETE", path),
    upload: (path, formData) => request("POST", path, formData, true),
    downloadBlob,
    // For building an EventSource URL (EventSource has no fetch-style
    // wrapper of its own to go through request() above) against the same
    // FRESHCUTZ_API_BASE every other call uses.
    streamUrl: (path) => `${base()}${path}`,
  };
})();
