// The service worker only runs in production builds; in development it
// would serve stale modules over Vite's dev server.
export function registerServiceWorker() {
  // This is a web page, not an installable app: never show an install prompt.
  window.addEventListener("beforeinstallprompt", (event) => event.preventDefault());
  if (!import.meta.env.PROD || !("serviceWorker" in navigator)) return;
  window.addEventListener("load", () => {
    navigator.serviceWorker.register("/sw.js").catch((err) => console.warn("service worker:", err));
  });
}
