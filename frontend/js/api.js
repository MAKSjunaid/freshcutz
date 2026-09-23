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

    let response;
    try {
      response = await fetch(`${base()}${path}`, { method, headers, body: payload });
    } catch (networkErr) {
      throw new Error(
        "Could not reach the FreshCutz server. Check your internet connection or that the backend is running."
      );
    }

    if (response.status === 401 && path.startsWith("/api/admin")) {
      localStorage.removeItem("freshcutz_admin_token");
      if (!path.endsWith("/auth/login")) {
        window.dispatchEvent(new CustomEvent("freshcutz:unauthorized"));
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
  };
})();
