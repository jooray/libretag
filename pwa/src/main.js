import './style.css';
import { App, DEFAULT_RELAYS } from './lib/app.js';
import { hasCache, clearCache } from './lib/vault.js';
import { LANGS, detectLang, getLang, setLang, t } from './lib/i18n.js';
import { APP_VERSION, initUpdates } from './lib/update.js';
import { parseKeyInput, shareFromHash, shareLink, shareWebLink } from './lib/keys.js';
import { epoch as epochOf } from './lib/lt2.js';
import { checkMnemonic } from './lib/mnemonic.js';
import { connectBunker, connectExtension, hasExtension, parseBunkerURL, probeRelays, startNostrConnect } from './lib/signer.js';
import qrcode from 'qrcode-generator';
import { nip19 } from 'nostr-tools';
import { ageShort, agoLong, agoAt, at, clock, coord, day, duration, every, npubShort, time } from './lib/format.js';
import {
  CRITICAL_BATTERY_PCT, LOW_BATTERY_PCT, cmdName, checkPlan, effectText, intervalMinOf, inferIntervalMin, IDLE_CHOICES_MIN, lastPos, levelOf, mapTarget, positionText,
  queuedText, quietTimes, zoneName, slip as slipOf, statusOf, statusSub, statusText, stationText, timelineDays,
} from './lib/status.js';
import { COMMAND_KINDS, TRACK_DURATIONS_MIN, TRACK_INTERVALS_S, trackCapAfterMin, trackReports } from './lib/mailbox.js';
import { followLinks } from './lib/welcome.js';
import { exportName, toCSV, toGPX } from './lib/export.js';
import { batteryGlyph, blaze, esc, ic } from './ui/blaze.js';
import { createMap } from './ui/map.js';

const app = new App();
const root = document.getElementById('root');
const $ = (sel, el = document) => el.querySelector(sel);
const $$ = (sel, el = document) => [...el.querySelectorAll(sel)];

// ------------------------------------------------------------------ small helpers

function applyTheme() {
  let th = 'system';
  try { th = localStorage.getItem('lt.theme') || 'system'; } catch { /* ignore */ }
  if (th === 'system') document.documentElement.removeAttribute('data-theme');
  else document.documentElement.dataset.theme = th;
}

let snackTimer;
function toast(text, ms = 4500) {
  $('.snack')?.remove();
  const el = document.createElement('div');
  el.className = 'snack'; el.setAttribute('role', 'status'); el.textContent = text;
  document.body.append(el);
  clearTimeout(snackTimer);
  snackTimer = setTimeout(() => el.remove(), ms);
}

function sheet(html, { onClose } = {}) {
  const scrim = document.createElement('div');
  scrim.className = 'scrim';
  scrim.innerHTML = '<div class="sheet" role="dialog" aria-modal="true"></div>';
  const el = scrim.firstElementChild;
  el.innerHTML = html;
  const close = () => { scrim.remove(); document.removeEventListener('keydown', onKey); onClose?.(); };
  const onKey = (e) => { if (e.key === 'Escape') close(); };
  scrim.addEventListener('click', (e) => { if (e.target === scrim) close(); });
  document.addEventListener('keydown', onKey);
  document.body.append(scrim);
  $('[autofocus], button, input', scrim)?.focus();
  return { el, close };
}

const theme = () => document.documentElement.dataset.theme || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
const SUPPORT = (ref) => 'https://hackyourself.io/support/s/libre-tag?lang=' + getLang() + '&theme=' + theme() + '&ref=' + ref;

// ------------------------------------------------------------------ keys screen (no login)

function keysScreen({ overlay = false, prefill = null } = {}) {
  const cached = hasCache() && !overlay && !prefill;
  let tab = prefill ? 'import' : 'nostr';
  let host;
  let scrimClose = () => {};
  let qrCtl = null;
  if (overlay) { const s = sheet('', { onClose: () => qrCtl?.cancel() }); host = s.el; scrimClose = s.close; } else { host = document.createElement('div'); host.className = 'screen'; }
  const done = () => { qrCtl?.cancel(); scrimClose(); renderApp(); };

  const stayOn = !overlay || app.stay || !app.tags.length;
  const lockFields = '<label class="check"><input type="checkbox" id="stay"' + (stayOn ? ' checked' : '') + '><span>' + t('stay.label') + '</span></label>'
    + '<p class="hint2">' + t('stay.hint') + '</p>';

  const draw = () => {
    qrCtl?.cancel(); qrCtl = null;
    const logo = overlay ? '' : '<div class="logo">' + blaze('yellow', 'stripe', 40) + '<b>LibreTag</b><small style="font:600 13px var(--narrow);border:1px solid var(--line);padding:1px 6px;color:var(--ink3)">' + t('app.web') + '</small></div>';
    let body;
    if (cached) {
      body = '<p class="lead">' + t('unlock.cache_lead') + '</p>'
        + '<div class="field"><label for="pp">' + t('unlock.passphrase') + '</label><input id="pp" type="password" autocomplete="current-password" autofocus></div>'
        + '<div class="row-btns"><button class="btn filled" id="go-cache">' + t('unlock.go') + '</button><button class="btn text" id="forget">' + t('unlock.forget') + '</button></div><div id="msg"></div>';
    } else {
      const tabs = '<div class="tabs" role="tablist">'
        + [['nostr', 'keys.tab_nostr'], ['phrase', 'keys.tab_phrase'], ['one', 'keys.tab_one']].map(([k, l]) => '<button role="tab" data-tab="' + k + '" aria-selected="' + (tab === k) + '">' + t(l) + '</button>').join('') + '</div>';
      let inner;
      if (tab === 'nostr') {
        inner = '<p class="lead">' + t('nostr.lead') + ' ' + t('nostr.never_nsec') + '</p>'
          + '<div class="row-btns" style="margin-top:0"><button class="btn filled" id="go-ext"' + (hasExtension() ? '' : ' disabled') + '>' + ic('plus') + t('nostr.ext') + '</button></div>'
          + '<p class="hint2" style="margin-left:0">' + t(hasExtension() ? 'nostr.ext_sub' : 'nostr.ext_none') + '</p>'
          + '<div class="qrbox"><div id="qr" class="qr" aria-label="QR"></div><p class="hint2" style="margin:8px 0 0">' + t('nostr.qr_hint') + '</p>'
          + '<div class="row-btns"><a class="btn tonal" id="qr-open" href="#">' + t('nostr.qr_open') + '</a><button class="btn text" id="qr-new">' + t('nostr.qr_new') + '</button></div></div>'
          + '<div class="field" style="margin-top:14px"><label for="bunker">' + t('nostr.bunker') + '</label><input id="bunker" type="text" autocomplete="off" autocapitalize="none" spellcheck="false" placeholder="' + esc(t('nostr.bunker_ph')) + '"></div>'
          + '<div class="row-btns" style="margin-top:0"><button class="btn tonal" id="go-bunker">' + t('nostr.bunker_go') + '</button></div>' + lockFields;
      } else if (tab === 'phrase') {
        inner = '<p class="lead">' + t('phrase.lead') + '</p>'
          + '<div class="msg" style="margin:0 0 14px">' + t('phrase.warn') + '</div>'
          + '<div class="field"><label for="phrase">' + t('phrase.label') + '</label><textarea id="phrase" rows="3" autocomplete="off" autocapitalize="none" spellcheck="false" autofocus></textarea></div>'
          + lockFields
          + '<div class="row-btns"><button class="btn filled" id="go-phrase">' + t('unlock.go') + '</button></div>';
      } else {
        inner = '<p class="lead">' + t('keys.import_lead') + '</p>'
          + '<div class="field"><textarea id="k0in" placeholder="' + esc(t('keys.import_ph')) + '" spellcheck="false" autocapitalize="none" autocomplete="off"></textarea></div>' + lockFields
          + '<div class="row-btns"><button class="btn filled" id="go-import">' + t('keys.import_go') + '</button><button class="btn tonal" id="scan">' + ic('qr') + t('keys.scan') + '</button></div>';
      }
      body = tabs + inner + '<div id="msg"></div>';
    }
    host.innerHTML = '<div class="card">' + logo + '<h1>' + (cached ? t('unlock.title') : t('keys.title')) + '</h1>' + body
      + '<p class="risk">' + t('unlock.risk') + '</p>'
      + (overlay ? '<div class="row-btns"><button class="btn text" id="close">' + t('close') + '</button></div>' : '') + '</div>';
    wire();
    if (prefill && tab === 'import' && $('#k0in', host)) $('#k0in', host).value = prefill;
    if (!cached && tab === 'nostr') startQr();
  };

  const msg = (text, err = false, busy = false) => {
    const m = $('#msg', host);
    if (m) m.innerHTML = text ? '<div class="msg ' + (err ? 'err' : '') + '" role="status">' + (busy ? '<span class="spin"></span>' : '') + esc(text) + '</div>' : '';
  };
  const lock = (on) => $$('button, input, textarea', host).forEach((b) => { b.disabled = on; });

  /** "Stay signed in on this device": write the session to IndexedDB (never switched off by a second sign-in). */
  const wantsStay = () => !!$('#stay', host)?.checked || app.stay;
  const keepStay = async () => { if (wantsStay() && !(await app.enableStay())) toast(t('stay.failed'), 6000); };

  /** Run after a signer is connected: find copy B and open the app. */
  const signerFlow = async (getSigner) => {
    const stay = wantsStay();
    lock(true); msg(t('nostr.waiting'), false, true);
    try {
      const signer = await getSigner();
      msg(t('nostr.searching'), false, true);
      const r = await app.restoreFromSigner(signer, () => {});
      if (r.state !== 'found' && !app.tags.length) { app.setSigner(null); msg(t('nostr.none'), true); return; }
      if (r.state !== 'found') { // signed in on top of tags we already have: this creates copy B
        const out = await app.saveBackup();
        if (stay) await keepStay();
        toast(out.b ? t('bk.done_both') : t('bk.failed'));
        return done();
      }
      const added = app.addFromBackup(r.payload);
      if (stay) await keepStay();
      toast(t('nostr.signed_in', { npub: npubShort(nip19.npubEncode(signer.pubkey)) }) + ' · ' + t('unlock.restored', { n: added.length }));
      done();
    } catch (e) {
      msg(t(e?.message === 'nip44 missing' ? 'nostr.ext_nip44' : 'nostr.failed'), true);
    } finally { lock(false); }
  };

  const startQr = async () => {
    const box = $('#qr', host);
    if (!box) return;
    box.innerHTML = '<span class="spin"></span>';
    const relays = await probeRelays(app.relays.filter((u) => u.startsWith('wss://')).length ? app.relays.filter((u) => u.startsWith('wss://')) : app.relays);
    if (!$('#qr', host)) return;
    const ctl = startNostrConnect(relays, app.pool, { name: 'LibreTag web', url: location.origin });
    qrCtl = ctl;
    const q = qrcode(0, 'M'); q.addData(ctl.uri); q.make();
    box.innerHTML = q.createSvgTag({ scalable: true, cellSize: 4, margin: 2 });
    const open = $('#qr-open', host); if (open) open.href = ctl.uri;
    ctl.promise.then(async (signer) => {
      if (qrCtl !== ctl) return;
      await signerFlow(async () => signer);
    }).catch(() => { if (qrCtl === ctl && $('#qr', host)) msg(t('nostr.failed'), true); });
  };

  const wire = () => {
    $('#close', host)?.addEventListener('click', () => { qrCtl?.cancel(); scrimClose(); });
    $$('[data-tab]', host).forEach((b) => b.addEventListener('click', () => { tab = b.dataset.tab; draw(); }));
    $('#forget', host)?.addEventListener('click', () => { clearCache(); location.reload(); });
    $('#pp', host)?.addEventListener('keydown', (e) => { if (e.key === 'Enter') $('#go-cache', host)?.click(); });
    $('#qr-new', host)?.addEventListener('click', () => { qrCtl?.cancel(); msg(''); startQr(); });

    $('#go-cache', host)?.addEventListener('click', async () => {
      const pp = $('#pp', host).value;
      if (!pp) return;
      lock(true); msg(t('unlock.deriving'), false, true);
      try {
        const r = await app.unlockCache(pp);
        if (r === 'ok') { await app.enableStay(); clearCache(); toast(t('stay.converted'), 6000); return done(); }
        msg(t('unlock.wrong'), true);
      } finally { lock(false); }
    });

    $('#go-ext', host)?.addEventListener('click', () => signerFlow(() => connectExtension()));
    $('#go-bunker', host)?.addEventListener('click', () => {
      const url = $('#bunker', host).value;
      if (!parseBunkerURL(url)) return msg(t('nostr.bunker_bad'), true);
      signerFlow(() => connectBunker(url, app.pool));
    });

    $('#go-phrase', host)?.addEventListener('click', async () => {
      const phrase = $('#phrase', host).value;
      const check = checkMnemonic(phrase);
      if (check !== 'ok') return msg(t(check === 'invalid' ? 'phrase.invalid' : 'phrase.wrong_length'), true);
      lock(true); msg(t('unlock.deriving'), false, true);
      try {
        const r = await app.restoreFromPhrase(phrase, (stage) => msg(t(stage === 'deriving' ? 'unlock.deriving' : 'unlock.searching'), false, true));
        if (r.state === 'found') {
          const added = app.addFromBackup(r.payload);
          await keepStay();
          toast(t('unlock.restored', { n: added.length }));
          return done();
        }
        msg(t({ none: 'unlock.none', deleted: 'unlock.deleted', unreadable: 'unlock.unreadable' }[r.state] ?? 'unlock.none'), true);
      } catch { msg(t('unlock.none'), true); } finally { lock(false); }
    });

    $('#go-import', host)?.addEventListener('click', async () => {
      const parsed = parseKeyInput($('#k0in', host).value);
      if (!parsed) return msg(t('keys.import_bad'), true);
      lock(true);
      try {
        const [added] = app.addFromParsed(parsed);
        await keepStay();
        if (added) toast(t('keys.added', { name: added.name }));
        done();
      } finally { lock(false); }
    });

    $('#scan', host)?.addEventListener('click', () => scanDialog((text) => {
      const ta = $('#k0in', host); if (ta) ta.value = text;
      if (parseKeyInput(text)) $('#go-import', host).click(); else msg(t('keys.import_bad'), true);
    }));
  };

  draw();
  if (!overlay) root.replaceChildren(host);
}

/** QR scan with the browser's own BarcodeDetector (nothing is uploaded); pasting works everywhere. */
function scanDialog(onText) {
  if (!('BarcodeDetector' in window) || !navigator.mediaDevices?.getUserMedia) return toast(t('keys.scan_none'));
  const s = sheet('<div class="scan"><h2>' + t('keys.scan') + '</h2><video autoplay playsinline muted></video><div class="row-btns"><button class="btn text" id="x">' + t('cancel') + '</button></div></div>');
  let stream; let stop = false;
  const end = () => { stop = true; stream?.getTracks().forEach((tr) => tr.stop()); s.close(); };
  $('#x', s.el).addEventListener('click', end);
  navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } }).then(async (st) => {
    stream = st;
    const v = $('video', s.el); v.srcObject = st;
    const det = new window.BarcodeDetector({ formats: ['qr_code'] });
    while (!stop) {
      await new Promise((r) => setTimeout(r, 250));
      try {
        const codes = await det.detect(v);
        if (codes.length) { const text = codes[0].rawValue; end(); onText(text); return; }
      } catch { /* video not ready yet */ }
    }
  }).catch(() => { end(); toast(t('keys.scan_denied')); });
}

// ------------------------------------------------------------------ the app screen

let route = { page: 'home', id: null };
let hideMap = false;
let mapCtl = null;
let focusN = null;
let tick = null;
let clickWired = false;

const parseHash = () => {
  const m = location.hash.match(/^#\/t\/([0-9a-f]+)/);
  route = m && app.tag(m[1]) ? { page: 'tag', id: m[1] } : { page: 'home', id: null };
};

function renderApp() {
  parseHash();
  root.innerHTML = '<div id="app"><header class="bar" id="bar"></header><p class="signote" id="signote" role="status"></p>'
    + '<div class="main" id="main"><div id="mapwrap"><div id="map"></div><div class="map-btns" id="mapbtns"></div>'
    + '<div class="map-empty" id="mapempty" hidden><span>' + esc(t('map.no_position')) + '</span></div></div>'
    + '<div class="panes"><section class="list" id="list"></section><section class="detail" id="detail"></section></div></div></div>';
  mapCtl = createMap($('#map'));
  if (!clickWired) { document.addEventListener('click', onClick); clickWired = true; }
  paint();
  clearInterval(tick);
  tick = setInterval(() => { if (document.visibilityState === 'visible' && !document.activeElement?.matches('select')) paint(); }, 20_000);
}

app.subscribe(() => { if ($('#app')) paint(); });
window.addEventListener('hashchange', () => { if ($('#app')) { focusN = null; paint(); } });

function paint() {
  parseHash();
  const now = Date.now();
  $('#main').dataset.route = route.page;
  paintBar();
  paintNote();
  paintList(now);
  paintDetail(now);
  paintMap(now);
}

function paintNote() {
  const el = $('#signote');
  if (!el) return;
  el.textContent = app.signerOffline ? t('signer.offline') : '';
}

function connState() {
  const rs = app.relayStatus();
  const ok = rs.filter((r) => r.ok).length;
  return { ok, n: rs.length, cls: ok === 0 ? 'off' : ok < rs.length ? 'part' : '' };
}

function paintBar() {
  const c = connState();
  const inTag = route.page === 'tag';
  const label = c.ok === 0 && app.tags.length === 0 ? t('conn.connecting') : t('conn.relays', { ok: c.ok, n: c.n });
  $('#bar').className = 'bar ' + (inTag ? 'nav' : '');
  $('#bar').innerHTML = '<h1>' + (inTag ? '' : blaze('yellow', 'stripe', 28)) + '<span>LibreTag</span><small>' + t('app.web') + '</small></h1>'
    + '<button class="conn" data-a="relays" aria-label="' + esc(t('a11y.connection', { s: label })) + '"><span class="dotc ' + c.cls + '"></span>' + esc(label) + '</button>'
    + '<button class="ib" data-a="menu" aria-haspopup="dialog" aria-label="' + esc(t('a11y.menu')) + '">' + ic('more') + '</button>';
}

const tipSvg = '<span class="tip" aria-hidden="true"><svg viewBox="0 0 20 100" preserveAspectRatio="none"><path class="tf" d="M0 0 19 50 0 100Z"/><path class="ts" d="M0 0 19 50 0 100" vector-effect="non-scaling-stroke"/></svg></span>';
const statusIcon = (s) => ({ LOST: 'alert', ALARM: 'alert', SILENT: 'clock', BATTERY_CRITICAL: 'battery', LOW_BATTERY: 'battery', QUIET: 'moon', TRACKING: 'pin' }[s.kind] || '');

function ariaRow(tag, s, now) {
  const p = lastPos(tag);
  const parts = [tag.name + ', ' + t('type.' + tag.type), statusText(tag, s, now, true), positionText(p, true, now)];
  if (s.heardAt) parts.push(t('a11y.heard', { ago: ageShort(s.heardAt, now) }));
  if (s.battery != null) parts.push(t('a11y.battery', { n: s.battery }));
  return parts.join('. ').replaceAll(' · ', ', ');
}

function postHTML(tag, now) {
  const s = statusOf(tag, now);
  const lvl = levelOf(s);
  const p = lastPos(tag);
  const text = statusText(tag, s, now, true);
  const sp = slipOf(tag, s, now);
  const sel = route.id === tag.id;
  const low = s.battery != null && s.battery <= LOW_BATTERY_PCT;
  const crit = s.battery != null && s.battery <= CRITICAL_BATTERY_PCT;
  const ico = statusIcon(s);
  return '<button class="post ' + lvl + ' ' + (sel ? 'sel' : '') + '" data-a="open" data-v="' + tag.id + '" aria-label="' + esc(ariaRow(tag, s, now)) + '"' + (sel ? ' aria-current="true"' : '') + '>'
    + '<span class="plate">' + (lvl ? '<span class="band ' + lvl + '">' + (ico ? ic(ico) : '') + '<span>' + esc(text) + '</span></span>' : '')
    + '<span class="mk">' + blaze(tag.mark.color, tag.mark.symbol, 28) + '</span>'
    + '<span class="main-col"><span class="nm">' + esc(tag.name) + '<span class="ty">' + t('type.' + tag.type) + '</span></span>'
    + (lvl ? '' : '<span class="ss">' + (ico ? ic(ico) : '') + '<span>' + esc(text) + '</span></span>')
    + '<span class="pl">' + esc(positionText(p, true, now)) + '</span>'
    + (sp ? '<span class="slip ' + sp.state + '">' + esc(sp.text) + '</span>' : '') + '</span>'
    + '<span class="meta"><span class="age">' + (s.heardAt ? ageShort(s.heardAt, now) : '') + '</span>'
    + (s.battery != null ? '<span class="bat ' + (crit ? 'crit' : low ? 'low' : '') + '">' + batteryGlyph(s.battery, low) + t('batt.value', { n: s.battery }) + '</span>' : '') + '</span>'
    + '</span>' + tipSvg + '</button>';
}

function paintList(now) {
  const tags = app.sorted(now);
  const need = tags.filter((x) => ['crit', 'warn'].includes(levelOf(statusOf(x, now)))).length;
  const loading = app.syncing.size && !tags.some((x) => x.positions.length);
  const sum = [t('home.tags', { n: tags.length }), need ? t('home.need_look', { n: need }) : ''].filter(Boolean).join(' · ');
  $('#list').innerHTML = '<p class="note" style="margin-top:0">' + esc(t('note.web')) + '</p>'
    + (tags.length ? '<p class="pl" style="margin:0 2px 10px;font-weight:600">' + esc(sum) + '</p>' : '<p class="hint">' + esc(t('home.no_tags')) + '</p>')
    + (loading ? '<p class="pl" style="margin:0 2px 10px"><span class="spin"></span>' + t('sync.loading') + '</p>' : '')
    + tags.map((x) => postHTML(x, now)).join('')
    + '<div class="row-btns" style="margin-top:12px"><button class="btn tonal" data-a="add">' + ic('plus') + t('keys.add_more') + '</button></div>';
}

function paintMap(now) {
  const wrap = $('#mapwrap');
  wrap.classList.toggle('collapsed', hideMap);
  $('#mapbtns').innerHTML = '<button class="chip hide-map" data-a="togglemap">' + ic('map', 'sm') + t(hideMap ? 'map.show' : 'map.hide') + '</button><button class="chip" data-a="fit">' + t('map.fit') + '</button>';
  if (hideMap) return;
  const n = mapCtl.update({ tags: app.tags, selectedId: route.id, focusN, now });
  $('#mapempty').hidden = n > 0;
}

function row(k, v, sub = '', acts = '') {
  return '<div class="rw"><div class="k">' + esc(k) + '</div><div class="v">' + v + (sub ? '<small>' + sub + '</small>' : '') + (acts ? '<div class="acts">' + acts + '</div>' : '') + '</div></div>';
}

function paintDetail(now) {
  const el = $('#detail');
  const tags = app.sorted(now);
  const tag = route.id && app.tag(route.id);
  if (!tag) {
    const need = tags.filter((x) => ['crit', 'warn'].includes(levelOf(statusOf(x, now))));
    el.innerHTML = '<div class="hint"><span class="sum">' + esc(t('home.tags', { n: tags.length })) + (need.length ? ', ' + esc(t('home.need_look', { n: need.length })) : '') + '</span><p>' + esc(t('home.pick')) + '</p></div>';
    return;
  }
  const keep = el.scrollTop;
  const i = tags.findIndex((x) => x.id === tag.id);
  const s = statusOf(tag, now);
  const lvl = levelOf(s);
  const p = lastPos(tag);
  const target = mapTarget(tag, p);
  const sp = slipOf(tag, s, now);
  const ico = statusIcon(s);
  const can = tag.chain.canCommand;
  const im = intervalMinOf(tag);
  const det = inferIntervalMin(tag.positions);
  const fromBackup = Number.isFinite(tag.extra?.interval_min) && tag.extra.interval_min === tag.intervalMin;
  const src = fromBackup ? 'backup' : tag.intervalMin != null ? 'set' : det ? 'est' : 'unknown';
  const bnote = t('interval.from_backup');
  const rev = tag.extra?.report_every, cs = tag.extra?.check_s;
  const extraNotes = (Number.isInteger(rev) && rev > 1 ? '<p>' + esc(t('interval.report_every', { n: rev }) + ' ' + bnote) + '</p>' : '')
    + (Number.isFinite(cs) ? '<p>' + esc(t('interval.check_s', { n: cs }) + ' ' + bnote) + '</p>' : '');
  const plan = checkPlan(tag);
  const primary = s.kind === 'LOST' || s.kind === 'TRACKING' ? 'NORMAL' : 'BEEP';
  const keyed = (k) => '<button class="btn ' + (k === primary ? 'filled' : 'outlined') + '" data-a="cmd" data-v="' + k + '"' + (can ? '' : ' disabled') + '>' + esc(cmdName(k)) + '</button>';
  const when = quietTimes(s, now);
  const posParts = p ? positionText(p, true, now).split(' · ') : [];
  const whereMain = p ? esc(p.hasFix && posParts.length > 1 ? posParts[0] : posParts.slice(0, 1).join('')) : esc(t('pos.none'));
  let whereSub = '';
  if (p && p.hasFix) whereSub = esc(coord(p.lat, p.lon));
  else if (p && p.fixTime > 0) whereSub = esc(t('where.last_gps', { ago: agoAt(p.fixTime * 1000, now), coord: coord(p.lat, p.lon) }));
  else if (p) whereSub = esc(t('where.web_area_sub'));
  const markName = (t('mark.' + tag.mark.color) + ' ' + t('mark.' + tag.mark.symbol)).toLowerCase();
  let whenSub = [];
  if (s.kind === 'QUIET' || s.nextCheckAt || (s.approx && s.nextAt)) whenSub = when;
  else if (s.kind === 'SILENT') whenSub = [t('when.overdue', { t: clock(p.receivedAt + (s.intervalS || 3600) * 1000, now) })];
  else if (s.nextAt) whenSub = [t('when.next', { t: clock(s.nextAt, now) })];

  el.innerHTML = '<header class="dbar"><button class="ib back-only" data-a="home" aria-label="' + esc(t('a11y.back')) + '">' + ic('back') + '</button>'
    + '<div class="ttl">' + blaze(tag.mark.color, tag.mark.symbol, 30) + '<div style="min-width:0"><h2>' + esc(tag.name) + '</h2><small>' + esc(t('detail.sub', { type: t('type.' + tag.type), mark: markName, i: i + 1, n: tags.length })) + '</small>' + (tag.share ? '<small class="sharedby">' + esc(t(tag.share.ce ? 'shared.label_control' : 'shared.label_view')) + '</small>' : '') + '</div></div>'
    + (tag.k0 ? '<button class="ib" data-a="share" aria-label="' + esc(t('share.btn')) + '">' + ic('share') + '</button>' : '')
    + '<button class="ib" data-a="edit" aria-label="' + esc(t('edit.title')) + '">' + ic('edit') + '</button>'
    + '<button class="ib" data-a="step" data-v="-1" aria-label="' + esc(t('a11y.prev')) + '"' + (i <= 0 ? ' disabled' : '') + '>' + ic('chevL') + '</button>'
    + '<button class="ib" data-a="step" data-v="1" aria-label="' + esc(t('a11y.next')) + '"' + (i >= tags.length - 1 ? ' disabled' : '') + '>' + ic('chevR') + '</button></header>'
    + '<div class="pad"><div class="bigpost ' + lvl + '"><span class="plate"><p class="bstx">' + (ico ? ic(ico) : '') + '<span>' + esc(statusText(tag, s, now)) + '</span></p><p class="bsub">' + esc(statusSub(tag, s, now)) + '</p></span>' + tipSvg + '</div>'
    + (tag.alarm ? '<div class="row-btns" style="margin:8px 0 0"><button class="btn tonal" data-a="clearalarm">' + t('alarm.clear') + '</button></div>' : '')
    + '<div class="rows">'
    + row(t('row.where'), whereMain, whereSub, target ? '<button class="btn tonal" data-a="osm">' + ic('map', 'sm') + t('open_in_map') + '</button><button class="btn outlined" data-a="copycoord">' + t('copy_coords') + '</button>' : '')
    + row(t('row.when'), p ? esc(t('when.heard', { ago: agoLong(p.receivedAt, now), t: (p.approxUntil != null ? '~' : '') + at(p.receivedAt, now) })) : esc(t('when.waiting')), whenSub.map(esc).join('<br>'))
    + row(t('row.battery'), s.battery == null ? esc(t('batt.unknown')) : esc(t(s.battery <= CRITICAL_BATTERY_PCT ? 'batt.critical' : s.battery <= LOW_BATTERY_PCT ? 'batt.low' : 'batt.value', { n: s.battery })),
      s.battery != null && s.battery <= CRITICAL_BATTERY_PCT ? esc(t('batt.critical_sub')) : '')
    + row(t('row.path'), esc(t('route.nostr')), esc(t('route.nostr_sub')))
    + '</div>'
    + '<div class="cmds" style="margin-top:22px">' + COMMAND_KINDS.map(keyed).join('') + '</div>'
    + '<p class="eff">' + ic('clock') + '<span>' + esc(can ? effectText(tag, s, now) : t('keys.view_only')) + '</span></p>'
    + (sp ? '<div class="dslip"><span class="slip ' + sp.state + '">' + esc(sp.text) + '</span>' + (tag.pending && !tag.pending.sentAt ? ' <button class="btn text" data-a="cancelcmd">' + t('slip.cancel') + '</button>' : '') + '</div>' : '')
    + (can ? '<div class="setting"><label for="iv">' + t('interval.label') + '</label><select id="iv"><option value="">' + esc(det ? t('interval.auto', { every: every(det * 60) }) : t('interval.unknown')) + '</option>'
      + [...new Set([...IDLE_CHOICES_MIN, ...(tag.intervalMin ? [tag.intervalMin] : [])])].sort((x, y) => x - y).map((m) => '<option value="' + m + '"' + (tag.intervalMin === m ? ' selected' : '') + '>' + esc(every(m * 60)) + '</option>').join('') + '</select>'
      + '<p>' + esc(t('interval.src_' + src)) + (plan ? ' ' + esc(t('note.checks', { every: every(im * 60) })) : '') + '</p></div>' : '')
    + timelineHTML(tag, now) + '</div>';
  el.scrollTop = keep;
}

/** Timeline day header: "Today", "Yesterday", or "Mon 16 Nov". */
function dayLabel(ms, now) {
  const d0 = new Date(ms).toDateString();
  if (d0 === new Date(now).toDateString()) return t('tl.today');
  const y = new Date(now); y.setHours(12, 0, 0, 0); y.setDate(y.getDate() - 1);
  if (d0 === y.toDateString()) return t('tl.yesterday');
  return day(ms);
}

function timelineHTML(tag, now) {
  const days = timelineDays(tag, now);
  const count = tag.positions.length;
  if (!days.length) return '<h3 class="sec">' + t('timeline') + '</h3><p class="pl">' + t('timeline.empty') + '</p>';
  const quietName = (reason, zone) => (reason === 'owner' ? t('stn.quiet_owner') : reason === 'parked' ? t('stn.quiet_parked') : t('stn.quiet', { zone: zone || t('zone.home') }));
  const items = days.map((d) => '<div class="tl-day">' + esc(dayLabel(d.at, now)) + '</div>' + d.items.slice().reverse().map((it) => {
    if (it.type === 'report') {
      const p = it.p;
      const sub = t('stn.sub', { n: p.n, b: p.battery != null ? t('batt.value', { n: p.battery }) : t('batt.unknown') });
      const tt = p.quiet ? quietName(p.quietReason, zoneName(tag, p))
        : p.mode === 'LOST' ? t('stn.lost') + ' · ' + stationText(p) : p.mode === 'TRACK' ? t('stn.track') + ' · ' + stationText(p) : stationText(p);
      return '<button class="stn ' + (focusN === p.n ? 'on' : '') + '" data-a="focus" data-v="' + p.n + '"><span class="tm">' + time(p.receivedAt) + '</span><span><span class="tt">' + esc(tt) + '</span><br><span class="ts2">' + esc(sub) + '</span></span></button>';
    }
    if (it.type === 'quiet') {
      return '<div class="stn fold"><span class="tt">' + esc(quietName(it.reason, it.zone)) + '<br><span class="ts2">' + esc(it.to ? t('stn.gap_sub', { a: time(it.from), b: time(it.to) }) : t('stn.since', { a: time(it.from) })) + '</span></span></div>';
    }
    return '<div class="stn fold"><span class="tt">' + esc(t('stn.gap')) + '<br><span class="ts2">' + esc(t('stn.gap_sub', { a: time(it.from), b: time(it.to) })) + '</span></span></div>';
  }).join('')).join('');
  return '<h3 class="sec">' + t('timeline') + '<span>' + esc(t('timeline.reports', { n: count })) + '</span></h3>' + items
    + '<div class="row-btns" style="margin-top:14px"><button class="btn tonal" data-a="export" data-v="gpx">' + esc(t('export.gpx')) + '</button>'
    + '<button class="btn outlined" data-a="export" data-v="csv">' + esc(t('export.csv')) + '</button></div><p class="pl">' + esc(t('export.note')) + '</p>';
}

// ------------------------------------------------------------------ actions

function onClick(e) {
  const b = e.target.closest('[data-a]');
  if (!b) return;
  const a = b.dataset.a; const v = b.dataset.v;
  const tag = route.id && app.tag(route.id);
  switch (a) {
    case 'open': location.hash = '#/t/' + v; break;
    case 'home': location.hash = '#/'; break;
    case 'step': { const tags = app.sorted(); const i = tags.findIndex((x) => x.id === route.id) + Number(v); if (tags[i]) location.hash = '#/t/' + tags[i].id; break; }
    case 'togglemap': hideMap = !hideMap; paint(); setTimeout(() => mapCtl.invalidate(), 50); break;
    case 'fit': mapCtl.fit(); focusN = null; paintMap(Date.now()); break;
    case 'focus': focusN = Number(v); paint(); break;
    case 'add': keysScreen({ overlay: true }); break;
    case 'menu': menu(); break;
    case 'relays': relaysDialog(); break;
    case 'cmd': if (tag) sendCommand(tag, v); break;
    case 'cancelcmd': if (tag) app.cancelCommand(tag.id).then(() => toast(t('snack.cancelled'))); break;
    case 'clearalarm': if (tag) { app.clearAlarm(tag.id); toast(t('alarm.cleared')); } break;
    case 'edit': if (tag) editDialog(tag); break;
    case 'share': if (tag?.k0) shareDialog(tag); break;
    case 'export': if (tag) exportHistory(tag, v); break;
    case 'osm': if (tag) { const tg = mapTarget(tag, lastPos(tag)); if (tg) window.open('https://www.openstreetmap.org/?mlat=' + tg.lat + '&mlon=' + tg.lon + '#map=16/' + tg.lat + '/' + tg.lon, '_blank', 'noopener,noreferrer'); } break;
    case 'copycoord': if (tag) { const tg = mapTarget(tag, lastPos(tag)); if (tg) navigator.clipboard?.writeText(coord(tg.lat, tg.lon)).then(() => toast(t('coords_copied'))); } break;
    default: break;
  }
}

document.addEventListener('change', (e) => {
  if (e.target.id === 'iv' && route.id) app.setInterval(route.id, e.target.value ? Number(e.target.value) : null);
});

function sendCommand(tag, kind) {
  if (kind === 'LOST') {
    const s = sheet('<h2>' + esc(t('lost.title', { name: tag.name })) + '</h2><p>' + esc(t('lost.text')) + '</p><div class="row-btns"><button class="btn critf" id="yes">' + t('lost.ok') + '</button><button class="btn text" id="no" autofocus>' + t('cancel') + '</button></div>');
    $('#no', s.el).addEventListener('click', s.close);
    $('#yes', s.el).addEventListener('click', () => { s.close(); doSend(tag, kind); });
    return;
  }
  if (kind === 'TRACK') { trackSheet(tag); return; }
  doSend(tag, kind);
}

/** Track: how often and how long (every 5 min for 1 h preselected), the report count and the daily limit. */
function trackSheet(tag) {
  const opt = (v, sel, label) => '<option value="' + v + '"' + (v === sel ? ' selected' : '') + '>' + esc(label) + '</option>';
  const s = sheet('<h2>' + esc(t('track.title')) + '</h2>'
    + '<div class="setting"><label for="tiv">' + esc(t('track.how_often')) + '</label><select id="tiv">' + TRACK_INTERVALS_S.map((v) => opt(v, 300, every(v))).join('') + '</select></div>'
    + '<div class="setting"><label for="tdur">' + esc(t('track.how_long')) + '</label><select id="tdur">' + TRACK_DURATIONS_MIN.map((v) => opt(v, 60, duration(v * 60_000))).join('') + '</select></div>'
    + '<p id="tsum"></p><p id="tcap" class="warn" hidden></p>'
    + '<div class="row-btns"><button class="btn filled" id="yes"></button><button class="btn text" id="no">' + t('cancel') + '</button></div>');
  const iv = () => Number($('#tiv', s.el).value), dur = () => Number($('#tdur', s.el).value);
  const update = () => {
    $('#tsum', s.el).textContent = t('track.reports', { n: trackReports(iv(), dur()), t: clock(Date.now() + dur() * 60_000) });
    const cap = trackCapAfterMin(iv(), dur());
    $('#tcap', s.el).hidden = cap == null;
    if (cap != null) $('#tcap', s.el).textContent = t('track.cap_warn', { d: duration(cap * 60_000) });
    $('#yes', s.el).textContent = t('track.ok_for', { d: duration(dur() * 60_000) });
  };
  $('#tiv', s.el).addEventListener('change', update);
  $('#tdur', s.el).addEventListener('change', update);
  $('#no', s.el).addEventListener('click', s.close);
  $('#yes', s.el).addEventListener('click', () => { s.close(); doSend(tag, 'TRACK', iv(), dur()); });
  update();
}

async function doSend(tag, kind, arg = 0, durationMin = 0) {
  const text = queuedText(tag, kind, statusOf(tag));
  const r = await app.queueCommand(tag.id, kind, arg, durationMin);
  toast(r.ok ? text : t('snack.cmd_failed', { c: cmdName(kind) }));
}

/** Save the tag's history as a file (GPX: the GPS fixes as a track; CSV: every report). Made here, sent nowhere. */
function exportHistory(tag, kind) {
  const text = kind === 'gpx' ? toGPX(tag) : toCSV(tag);
  const blob = new Blob([text], { type: kind === 'gpx' ? 'application/gpx+xml' : 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = exportName(tag, kind); a.rel = 'noopener';
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
  const fixes = tag.positions.filter((p) => p.hasFix).length;
  toast(kind === 'gpx' && !fixes ? t('export.no_fixes') : t('export.saved', { name: a.download }));
}

function shareDialog(tag) {
  let control = false;
  const epoch = epochOf(Math.max(0, tag.lastN));
  const s = sheet('');
  const draw = () => {
    const link = shareLink(tag, tag.chain, epoch, control);
    const web = shareWebLink(link);
    const q = qrcode(0, 'M'); q.addData(link); q.make();
    const lvl = (v, title, sub) => '<label class="lvl"><input type="radio" name="lvl" value="' + v + '"' + (control === (v === 'c') ? ' checked' : '') + '><span><b>' + esc(t(title)) + '</b><br>' + esc(t(sub)) + '</span></label>';
    const field = (id, label, val) => '<div class="field"><label for="' + id + '">' + esc(label) + '</label><div class="copyrow"><input id="' + id + '" type="text" readonly value="' + esc(val) + '"><button class="btn tonal" data-copy="' + id + '">' + esc(t('share.copy')) + '</button></div></div>';
    s.el.innerHTML = '<h2>' + esc(t('share.title', { name: tag.name })) + '</h2><p>' + esc(t('share.explain')) + '</p>'
      + '<div class="lvls">' + lvl('v', 'share.view', 'share.view_sub') + lvl('c', 'share.control', 'share.control_sub') + '</div>'
      + '<div class="qr" aria-label="QR">' + q.createSvgTag({ scalable: true, cellSize: 4, margin: 2 }) + '</div><p style="text-align:center;margin-top:8px">' + esc(t('share.qr_hint')) + '</p>'
      + field('sl-app', t('share.link_app'), link) + field('sl-web', t('share.link_web'), web)
      + '<p>' + esc(t('share.revoke')) + '</p><div class="row-btns"><button class="btn filled" id="sdone">' + t('close') + '</button></div>';
    $$('input[name=lvl]', s.el).forEach((r) => r.addEventListener('change', () => { control = r.value === 'c'; draw(); }));
    $$('[data-copy]', s.el).forEach((b) => b.addEventListener('click', () => {
      const inp = $('#' + b.dataset.copy, s.el); inp.select();
      navigator.clipboard?.writeText(inp.value).then(() => toast(t('share.copied')), () => {});
    }));
    $('#sdone', s.el).addEventListener('click', s.close);
  };
  draw();
}

/** A shared tag arrives (pasted link or the web fallback link): confirm, then add it. */
function sharedDialog(link) {
  const parsed = parseKeyInput(link);
  if (!parsed?.shared) return toast(t('keys.import_bad'), 6000);
  const s = sheet('<h2>' + t('shared.title') + '</h2><p>' + esc(t(parsed.shared.ce ? 'shared.text_control' : 'shared.text_view')) + '</p>'
    + '<div class="field"><label for="shn">' + t('edit.name') + '</label><input id="shn" type="text" value="' + esc(parsed.name || 'Tag') + '" maxlength="40" autocomplete="off"></div>'
    + '<div class="row-btns"><button class="btn filled" id="shadd">' + t('shared.add') + '</button><button class="btn text" id="shno">' + t('cancel') + '</button></div>');
  $('#shno', s.el).onclick = s.close;
  $('#shadd', s.el).onclick = () => {
    const [added] = app.addFromParsed({ ...parsed, name: $('#shn', s.el).value.trim() || parsed.name });
    s.close();
    if (added) { toast(t('shared.added', { name: added.name })); location.hash = '#/t/' + added.id; }
  };
}

/** "Follow your tag on Nostr": the npub its DMs come from, a nostr: link and njump.me for clients without one. */
function followBox(tag) {
  if (!tag.k0 || !tag.chain?.dmSk) return '';
  const f = followLinks(tag.chain.dmSk);
  return '<div class="qrbox" style="margin:0 0 16px"><h3 style="margin:0 0 4px">' + esc(t('follow.title')) + '</h3><p class="pl">' + esc(t('follow.why')) + '</p>'
    + '<div class="copyrow"><input id="efnpub" type="text" readonly value="' + esc(f.npub) + '" aria-label="npub"><button class="btn tonal" id="efcopy">' + esc(t('copy')) + '</button></div>'
    + '<div class="row-btns" style="margin-top:8px"><a class="btn filled" href="' + esc(f.app) + '">' + esc(t('follow.open')) + '</a>'
    + '<a class="btn text" href="' + esc(f.web) + '" target="_blank" rel="noopener noreferrer">' + esc(t('follow.web')) + '</a></div></div>';
}

function editDialog(tag) {
  const types = ['person', 'thing', 'vehicle'];
  const s = sheet('<h2>' + t('edit.title') + '</h2>'
    + '<div class="field"><label for="en">' + t('edit.name') + '</label><input id="en" type="text" value="' + esc(tag.name) + '" maxlength="40" autocomplete="off"></div>'
    + '<div class="field"><label for="et">' + t('edit.type') + '</label><select id="et">' + types.map((x) => '<option value="' + x + '"' + (tag.type === x ? ' selected' : '') + '>' + t('type.' + x) + '</option>').join('') + '</select></div>'
    + (tag.imported || !tag.k0 ? '' : '<p class="pl">' + esc(app.canSaveBackup ? '' : t('edit.not_saved')) + '</p>')
    + (tag.imported ? '<div class="row-btns" style="margin:0 0 8px"><button class="btn tonal" id="eadd">' + t('edit.add_backup') + '</button></div>' : '')
    + (tag.k0 ? '<div class="row-btns" style="margin:0 0 8px"><button class="btn tonal" id="ewelcome">' + t('welcome.send') + '</button></div>' : '')
    + '<div id="emsg"></div>' + followBox(tag) + '<div class="row-btns"><button class="btn filled" id="esave">' + t('edit.save') + '</button><button class="btn text" id="ecancel">' + t('cancel') + '</button>'
    + '<button class="btn crito" id="eremove" style="margin-left:auto">' + t('edit.remove') + '</button></div>');
  const report = (r) => {
    if (!r || r.skipped) return r?.skipped ? toast(t('edit.not_saved'), 6000) : null;
    const ok = (r.a ?? 0) + (r.b ?? 0);
    toast(ok ? t('edit.saved') : t('edit.failed'));
    return null;
  };
  $('#ecancel', s.el).onclick = s.close;
  const eadd = $('#eadd', s.el);
  if (eadd) eadd.onclick = async () => {
    s.close();
    const saved = await app.addToBackup(tag.id);
    if (saved) toast(t('edit.added')); else backupDialog();
  };
  const efc = $('#efcopy', s.el);
  if (efc) efc.onclick = () => { const inp = $('#efnpub', s.el); inp.select(); navigator.clipboard?.writeText(inp.value).then(() => toast(t('follow.copied')), () => {}); };
  const ew = $('#ewelcome', s.el);
  if (ew) ew.onclick = async () => {
    const msg = $('#emsg', s.el);
    if (!app.ownerPubkey) { msg.innerHTML = '<p class="pl">' + esc(t('welcome.no_owner')) + '</p>'; return; }
    ew.disabled = true;
    msg.innerHTML = '<p class="pl">' + esc(t('welcome.sending')) + '</p>';
    let r = null;
    try { r = await app.sendWelcome(app.tag(tag.id) ?? tag, t('welcome.dm', { name: tag.name })); } catch { r = []; }
    ew.disabled = false;
    const ok = (r ?? []).filter((x) => x.ok).length;
    const why = (r ?? []).filter((x) => !x.ok).map((x) => x.url.replace(/^wss:\/\//, '') + ': ' + (x.reason || t('welcome.no_answer'))).join('; ');
    msg.innerHTML = '<p class="pl">' + esc(ok ? t('welcome.sent', { n: ok }) : t('welcome.failed', { why })) + '</p>'
      + (r && r.nip17 === false && ok ? '<p class="pl">' + esc(t('welcome.no_inbox')) + '</p>' : '');
  };
  $('#esave', s.el).onclick = async () => {
    const name = $('#en', s.el).value.trim();
    s.close();
    report(await app.editTag(tag.id, { name: name || tag.name, type: $('#et', s.el).value }));
  };
  $('#eremove', s.el).onclick = () => {
    s.el.innerHTML = '<h2>' + esc(t('edit.remove')) + '</h2><p>' + esc(t('edit.remove_text', { name: tag.name })) + '</p><div class="row-btns"><button class="btn critf" id="eyes">' + t('edit.remove') + '</button><button class="btn text" id="eno" autofocus>' + t('cancel') + '</button></div>';
    $('#eno', s.el).onclick = s.close;
    $('#eyes', s.el).onclick = async () => { s.close(); location.hash = '#/'; report(await app.removeTag(tag.id)); };
  };
}

function backupDialog() {
  if (!app.tags.some((x) => x.k0 && !x.imported)) { toast(t('bk.nothing')); return; }
  const s = sheet('');
  let phrase = '';
  let words = [];
  const step1 = () => {
    s.el.innerHTML = '<h2>' + t('bk.title') + '</h2><p>' + t('bk.lead') + '</p><div class="msg">' + t('phrase.warn') + '</div>'
      + '<div class="row-btns"><button class="btn filled" id="gen">' + t('bk.generate') + '</button><button class="btn text" id="no">' + t('cancel') + '</button></div>';
    $('#no', s.el).onclick = s.close;
    $('#gen', s.el).onclick = () => { phrase = app.newPhrase(); words = phrase.split(' '); step2(); };
  };
  const step2 = () => {
    s.el.innerHTML = '<h2>' + t('bk.title') + '</h2><ol class="words">' + words.map((w) => '<li>' + esc(w) + '</li>').join('') + '</ol><p>' + t('bk.shown') + '</p>'
      + '<div class="row-btns"><button class="btn filled" id="next">' + t('bk.next') + '</button></div>';
    $('#next', s.el).onclick = step3;
  };
  const step3 = () => {
    const idx = [2, 6, 10];
    s.el.innerHTML = '<h2>' + t('bk.title') + '</h2><p>' + t('bk.confirm', { a: 3, b: 7, c: 11 }) + '</p>'
      + idx.map((i) => '<div class="field"><label for="w' + i + '">' + t('bk.word', { n: i + 1 }) + '</label><input id="w' + i + '" type="text" autocomplete="off" autocapitalize="none" spellcheck="false"></div>').join('')
      + '<div id="bmsg"></div><div class="row-btns"><button class="btn filled" id="save">' + t('edit.save') + '</button><button class="btn text" id="no">' + t('cancel') + '</button></div>';
    $('#no', s.el).onclick = s.close;
    $('#save', s.el).onclick = async () => {
      if (!idx.every((i) => $('#w' + i, s.el).value.trim().toLowerCase() === words[i])) { $('#bmsg', s.el).innerHTML = '<div class="msg err">' + t('bk.wrong') + '</div>'; return; }
      $$('button, input', s.el).forEach((b) => { b.disabled = true; });
      $('#bmsg', s.el).innerHTML = '<div class="msg"><span class="spin"></span>' + t('bk.saving') + '</div>';
      let out;
      try { out = await app.createBackup(phrase); } catch { out = { a: 0 }; }
      phrase = ''; words = [];
      s.close();
      toast(out.a ? t(out.b ? 'bk.done_both' : 'bk.done') : t('bk.failed'), 6000);
    };
  };
  step1();
}

function menu() {
  let th = 'system';
  try { th = localStorage.getItem('lt.theme') || 'system'; } catch { /* ignore */ }
  const who = app.signer ? '<p class="pl" style="margin:0 0 8px">' + esc(t('nostr.signed_in', { npub: npubShort(nip19.npubEncode(app.signer.pubkey)) })) + '</p>' : '';
  const bi = app.backupInfo;
  const bk = (bi.b ? '<p class="pl" style="margin:0 0 4px">' + esc(t('bk.from_b', { t: at(bi.b * 1000) })) + '</p>' : '')
    + (bi.a ? '<p class="pl" style="margin:0 0 4px">' + esc(t('bk.from_a', { t: at(bi.a * 1000) })) + '</p>' : '');
  const s = sheet('<h2>' + t('menu.title') + '</h2>' + who + bk
    + '<div class="menu-row"><span>' + t('menu.language') + '</span><span class="seg">' + LANGS.map(([c, n]) => '<button data-lang="' + c + '" aria-pressed="' + (getLang() === c) + '">' + n + '</button>').join('') + '</span></div>'
    + '<div class="menu-row"><span>' + t('menu.theme') + '</span><span class="seg">' + ['system', 'light', 'dark'].map((x) => '<button data-theme="' + x + '" aria-pressed="' + (th === x) + '">' + t('menu.theme_' + x) + '</button>').join('') + '</span></div>'
    + '<button class="menu-item" data-m="add">' + ic('plus') + t('keys.add_more') + '</button>'
    + '<button class="menu-item" data-m="backup">' + ic('check') + t('bk.menu') + '</button>'
    + (app.canSaveBackup ? '<button class="menu-item" data-m="bcheck">' + ic('check') + t('bk.check') + '</button>' : '')
    + '<button class="menu-item" data-m="relays">' + ic('wifi') + t('menu.relays') + '</button>'
    + '<a class="menu-item" href="' + SUPPORT('menu') + '" target="_blank" rel="noopener noreferrer">' + ic('heart') + t('menu.support') + '</a>'
    + '<button class="menu-item" data-m="about">' + ic('info') + t('menu.about') + '</button>'
    + '<button class="menu-item" data-m="update">' + ic('check') + t('menu.refresh') + '</button>'
    + '<button class="menu-item" data-m="forget" style="color:var(--crit)">' + ic('close') + t('menu.signout') + '</button>');
  s.el.addEventListener('click', async (e) => {
    const b = e.target.closest('button'); if (!b) return;
    if (b.dataset.lang) { setLang(b.dataset.lang); s.close(); paint(); }
    else if (b.dataset.theme) { try { localStorage.setItem('lt.theme', b.dataset.theme); } catch { /* ignore */ } applyTheme(); s.close(); menu(); }
    else if (b.dataset.m === 'add') { s.close(); keysScreen({ overlay: true }); }
    else if (b.dataset.m === 'backup') { s.close(); backupDialog(); }
    else if (b.dataset.m === 'bcheck') {
      b.disabled = true; b.lastChild.textContent = t('bk.checking');
      const r = await app.checkBackup().catch(() => ({ offline: true, changes: 0 }));
      s.close();
      toast(!r || r.offline ? t('bk.check_offline') : r.changes ? t('bk.updated', { n: r.changes }) : t('bk.uptodate'), 5000);
    }
    else if (b.dataset.m === 'relays') { s.close(); relaysDialog(); }
    else if (b.dataset.m === 'about') { s.close(); aboutDialog(); }
    else if (b.dataset.m === 'update') { s.close(); updates.check(); toast(t('update.working'), 2500); }
    else if (b.dataset.m === 'forget') { await app.forgetAll(); location.hash = ''; location.reload(); }
  });
}

function aboutDialog() {
  const s = sheet('<h2>LibreTag ' + t('app.web') + '</h2><p style="font:600 14px var(--narrow);color:var(--ink3)">' + t('about.version', { v: APP_VERSION }) + '</p>'
    + '<p>' + t('about.text1') + '</p><p>' + t('about.text2') + '</p><p>' + t('about.privacy') + '</p><p style="font-size:13px">' + t('about.licences') + '</p>'
    + '<div class="row-btns"><button class="btn tonal" id="c">' + t('close') + '</button></div>');
  $('#c', s.el).addEventListener('click', s.close);
}

function relaysDialog() {
  const s = sheet('');
  const draw = () => {
    const rs = app.relayStatus();
    s.el.innerHTML = '<h2>' + t('menu.relays') + '</h2>'
      + rs.map((r) => '<div class="relay"><span class="dotc ' + (r.ok ? '' : 'off') + '"></span><span class="u">' + esc(r.url) + '</span><span>' + t(r.ok ? 'relay.working' : 'relay.failing') + '</span><button class="ib" style="width:40px;height:40px" data-rm="' + esc(r.url) + '" aria-label="' + esc(t('relay.remove', { u: r.url })) + '">' + ic('close') + '</button></div>').join('')
      + '<div class="field" style="margin-top:14px"><label for="ru">' + t('relay.url') + '</label><input id="ru" type="text" inputmode="url" autocapitalize="none" spellcheck="false" placeholder="wss://"></div><div id="rmsg"></div>'
      + '<div class="row-btns"><button class="btn filled" id="radd">' + t('relay.add') + '</button><button class="btn text" id="rreset">' + t('relay.reset') + '</button><button class="btn text" id="rclose">' + t('close') + '</button></div>';
    $('#radd', s.el).onclick = () => {
      const u = $('#ru', s.el).value.trim();
      if (!/^wss:\/\/[^\s/]+(\/\S*)?$/.test(u) && !/^ws:\/\/(127\.0\.0\.1|localhost)(:\d+)?(\/\S*)?$/.test(u)) { $('#rmsg', s.el).innerHTML = '<div class="msg err">' + t('relay.bad') + '</div>'; return; }
      if (!app.relays.includes(u)) app.setRelays([...app.relays, u]);
      draw();
    };
    $('#rreset', s.el).onclick = () => { app.setRelays([...DEFAULT_RELAYS]); draw(); };
    $('#rclose', s.el).onclick = () => s.close();
    $$('[data-rm]', s.el).forEach((b) => {
      b.onclick = () => {
        if (app.relays.length <= 1) { $('#rmsg', s.el).innerHTML = '<div class="msg err">' + t('relay.last') + '</div>'; return; }
        app.setRelays(app.relays.filter((x) => x !== b.dataset.rm)); draw();
      };
    });
  };
  draw();
  const iv = setInterval(() => { if (!s.el.isConnected) clearInterval(iv); else if (document.activeElement?.id !== 'ru') draw(); }, 2500);
}

// ------------------------------------------------------------------ boot

setLang(detectLang());
applyTheme();

const updates = initUpdates({
  canReload: () => {
    const a = document.activeElement;
    return !(a && (a.tagName === 'INPUT' || a.tagName === 'TEXTAREA') && a.value);
  },
  beforeReload: () => app.stashForReload(),
  onUpdated: () => setTimeout(() => toast(t('update.done', { v: APP_VERSION }), 6000), 600),
  onWorking: () => toast(t('update.working'), 3000),
});

// Web fallback link: https://.../app/#share=<payload>. The fragment never reaches a server; drop it from the address bar.
const incomingShare = shareFromHash(location.hash);
if (incomingShare) history.replaceState(null, '', location.pathname + location.search);
window.addEventListener('hashchange', () => {
  const l = shareFromHash(location.hash);
  if (l && $('#app')) { history.replaceState(null, '', location.pathname + location.search); sharedDialog(l); }
});

if (await app.restoreStay() || app.takeStash()) { renderApp(); if (incomingShare) sharedDialog(incomingShare); }
else keysScreen({ prefill: incomingShare });
const wake = () => { app.retryPending(); app.autoCheckBackup(); };
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') wake(); });
window.addEventListener('online', wake);
setInterval(() => { if (document.visibilityState === 'visible') wake(); }, 60_000);
if (location.search.includes('debug')) window.__ltDebug = app;
