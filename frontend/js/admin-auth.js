/* Admin login/logout + gate that shows either the login screen or the app. */

const AdminAuth = (() => {
  const TOKEN_KEY = "freshcutz_admin_token";

  const loginScreen = document.getElementById("loginScreen");
  const adminApp = document.getElementById("adminApp");
  const loginForm = document.getElementById("loginForm");
  const loginBtn = document.getElementById("loginBtn");
  const loginError = document.getElementById("loginError");

  function hasToken() {
    return !!localStorage.getItem(TOKEN_KEY);
  }

  function showLogin() {
    loginScreen.style.display = "flex";
    adminApp.hidden = true;
  }

  function showApp() {
    loginScreen.style.display = "none";
    adminApp.hidden = false;
  }

  function logout(silent) {
    const token = localStorage.getItem(TOKEN_KEY);
    localStorage.removeItem(TOKEN_KEY);
    showLogin();
    if (token) {
      Api.post("/api/admin/auth/logout").catch(() => {});
    }
    document.dispatchEvent(new CustomEvent("freshcutz:logout"));
    if (!silent) {
      Toast.show("Logged out", "success");
    }
  }

  async function handleLogin(e) {
    e.preventDefault();
    loginError.textContent = "";
    const data = new FormData(loginForm);
    const username = data.get("username")?.trim();
    const password = data.get("password");

    loginBtn.disabled = true;
    loginBtn.innerHTML = `<span class="spinner"></span> Signing in...`;
    try {
      const res = await Api.post("/api/admin/auth/login", { username, password });
      localStorage.setItem(TOKEN_KEY, res.token);
      showApp();
      document.dispatchEvent(new CustomEvent("freshcutz:login"));
    } catch (err) {
      loginError.textContent = err.message || "Sign in failed";
    } finally {
      loginBtn.disabled = false;
      loginBtn.textContent = "Sign In";
    }
  }

  function init() {
    loginForm.addEventListener("submit", handleLogin);
    document.getElementById("logoutBtn").addEventListener("click", () => logout(false));
    // Api.js dispatches this for ANY failed /api/admin request - a real
    // 401 (token expired/invalid - the backend's token store is
    // in-memory, see TokenStore.java, so a backend restart forgets
    // every session instantly) or the backend being flat-out
    // unreachable. Either way the admin app is not usable right now,
    // so both drop straight back to the login screen - same as
    // clearing the browser's stored data would - rather than leaving
    // a half-working screen full of raw error toasts.
    window.addEventListener("freshcutz:unauthorized", (e) => {
      const reason = e.detail && e.detail.reason;
      Toast.show(
        reason === "offline"
          ? "Could not reach the server — please sign in again"
          : "Your session expired — please sign in again",
        "error"
      );
      logout(true);
    });

    if (hasToken()) {
      showApp();
      document.dispatchEvent(new CustomEvent("freshcutz:login"));
    } else {
      showLogin();
    }
  }

  document.addEventListener("DOMContentLoaded", init);

  return { logout };
})();
