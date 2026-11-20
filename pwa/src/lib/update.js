// Mandatory automatic refresh (a rule for all PWAs): every release is versioned, the page checks for
// a new one on load, when it comes back to the foreground, when the network returns and every 15
// minutes, and reloads by itself. Nobody has to do a hard refresh.
const FLAG = 'lt.updated';

/** The version the page was built as (package.json). */
export const APP_VERSION = typeof __APP_VERSION__ !== 'undefined' ? __APP_VERSION__ : 'dev';

/** `canReload()` lets the app wait while a passphrase is being typed; `beforeReload()` saves the session (IndexedDB) or hands the keys over (sessionStorage). */
export function initUpdates({ canReload = () => true, beforeReload = () => {}, onUpdated = () => {}, onWorking = () => {} } = {}) {
  let reloading = false;
  const flag = sessionStorageGet(FLAG);
  if (flag) { sessionStorageDel(FLAG); onUpdated(flag); }

  const reload = async (nextVersion) => {
    if (reloading) return;
    if (!canReload()) { setTimeout(() => reload(nextVersion), 5000); return; }
    reloading = true;
    onWorking();
    try { await beforeReload(); } catch { /* reload anyway */ }
    sessionStorageSet(FLAG, nextVersion || APP_VERSION);
    try { await fetch(location.pathname, { cache: 'reload' }); } catch { /* offline: reload anyway */ }
    location.reload();
  };

  let reg = null;
  const hadController = !!navigator.serviceWorker?.controller;
  if ('serviceWorker' in navigator && import.meta.env?.PROD) {
    navigator.serviceWorker.register('./sw.js', { updateViaCache: 'none' }).then((r) => { reg = r; }).catch(() => {});
    navigator.serviceWorker.addEventListener('controllerchange', () => { if (hadController) reload(); });
  }

  /** version.json is the second, SW-independent signal (works where no service worker does) */
  const check = async () => {
    try { await reg?.update(); } catch { /* offline */ }
    try {
      const r = await fetch('./version.json?t=' + Date.now(), { cache: 'no-store' });
      if (!r.ok) return;
      const v = (await r.json()).version;
      if (v && v !== APP_VERSION) {
        // give the service worker a moment to install it and trigger controllerchange itself
        setTimeout(() => reload(v), reg ? 4000 : 0);
      }
    } catch { /* offline */ }
  };

  check();
  setInterval(check, 15 * 60_000);
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') check(); });
  window.addEventListener('online', check);
  return { check };
}

function sessionStorageGet(k) { try { return sessionStorage.getItem(k); } catch { return null; } }
function sessionStorageSet(k, v) { try { sessionStorage.setItem(k, v); } catch { /* ignore */ } }
function sessionStorageDel(k) { try { sessionStorage.removeItem(k); } catch { /* ignore */ } }
