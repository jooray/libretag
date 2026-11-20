// The web app's state and its Nostr traffic: relays, report sync, commands, key backup (copies A and B),
// optional sealed cache. Reading reports needs only the trackers' keys, never a login.
import { SimplePool } from 'nostr-tools/pool';
import { normalizeURL } from 'nostr-tools/utils';
import { b64decode, bytesToHex } from './bytes.js';
import { backupFilter, buildBackupEvent, derive, deriveFromS, restore } from './backup.js';
import { buildBackupBEvent, buildDeletionB, findBackupB, ownerRelays, randomLocator } from './backupB.js';
import { makeTag, tagsFromBackup } from './keys.js';
import { FIRST_SYNC, NOSTR_REPORT_KIND, REPORT_P_LEN, REPORT_P_LEN_STEALTH, ReportWindow, WINDOW_AHEAD } from './lt2.js';
import { fetchAllReports, findBeyondWindow } from './sync.js';
import { COMMAND_KINDS, LOST_MIN, TRACK_MIN, anchorEntries, buildCommand, cancelEvents, mailboxEntries, newGroup, nextSeq, notYetOut,
  pruneAnchors, sentOf, signEntry } from './mailbox.js';
import { mergePayloads, plaintextOf } from './merge.js';
import { checkMnemonic, normalizeMnemonic } from './mnemonic.js';
import { lazySigner, restoreSession } from './signer.js';
import { cacheKey, clearCache, openCache, readCacheBlob, saveHandoff, serializeTag, takeHandoff } from './vault.js';
import { clearSession, loadSession, saveSession } from './session.js';
import { statusOf, sortTags } from './status.js';
import { exactNs, mergeReport, reportTimeMs, upgrades } from './reports.js';
import { zoneStep } from './geo.js';
import { INBOX_INDEXERS, KIND_DM_RELAYS, MAX_EXTRA, KIND_RELAY_LIST, dmTargets, inboxLists, ownerHex, profileEvent, publishEach, welcomeEvent, welcomeWrap } from './welcome.js';
import { generateMnemonic } from '@scure/bip39';
import { wordlist } from '@scure/bip39/wordlists/english.js';

export const DEFAULT_RELAYS = ['wss://nostr.cypherpunk.today', 'wss://relay.primal.net', 'wss://nos.lol'];
const MAX_POSITIONS = 1000;
const CHUNK_N = 125; // counters per query in 'y' mode: 250 tags, like the Android app's filters
export const SYNC_BACK_S = 8 * 86400;  // reports expire after 14 days; a week back is what the timeline shows
const SYNC_OVERLAP_S = 600;            // relays may get an event late (bridge queue, other relays)
export const FETCH_PAGE = 500;
export const FETCH_ALL_MAX = 20_000;   // per relay and sync; above it, back to #y queries (see syncTag)
export const RESYNC_BLOCK = 2048;
export const RESYNC_CAP = 65_536;      // how far past the newest counter the resync looks
const nowS = () => Math.floor(Date.now() / 1000);

const loadRelays = () => {
  try { const r = JSON.parse(localStorage.getItem('lt.relays')); if (Array.isArray(r) && r.length) return r; } catch { /* default */ }
  return [...DEFAULT_RELAYS];
};

const markString = (m) => `${m.color}-${m.symbol}`;

export class App {
  constructor() {
    this.pool = new SimplePool({ enablePing: true, enableReconnect: true });
    this.relays = loadRelays();
    this.tags = [];
    this.listeners = new Set();
    this.stay = false;            // "stay signed in on this device": session and cache kept in IndexedDB
    this.backupInfo = { a: null, b: null, bId: null }; // created_at of the backup copies in use
    this.backupPending = false;   // a change is waiting for the signer to write copy B
    this.signer = null;           // Nostr signer (copy B), memory only
    this.aDerived = null;         // S and friends of the phrase (copy A), memory only, wiped on logout
    this.meta = { d: null, owner: null, tombstones: [] };
    this.syncing = new Set();
    this.firstSyncDone = new Set();
    this._subs = new Map();
    this._needSync = new Set();
    this._syncP = null;
    this._globalSub = null;
    this.queryMode = 'all';      // 'all': fetch every kind 4243 and match here; 'y': per-tag #y windows (fallback)
    this._saveTimer = null;
    this._claimSub = null;
    this._queues = new Map();
    this._anchoring = new Set();
  }

  subscribe(fn) { this.listeners.add(fn); return () => this.listeners.delete(fn); }
  emit() { for (const fn of this.listeners) fn(); }
  tag(id) { return this.tags.find((x) => x.id === id); }
  sorted(now = Date.now()) { return sortTags(this.tags, now); }
  get canSaveBackup() { return !!(this.signer || this.aDerived); }

  // ---------------------------------------------------------------- relays

  setRelays(list) {
    const old = this.relays;
    this.relays = list;
    try { localStorage.setItem('lt.relays', JSON.stringify(list)); } catch { /* ignore */ }
    this.pool.close(old.filter((u) => !list.includes(u)));
    this._globalSub?.close(); this._globalSub = null;
    // a new relay may hold older reports: read the whole span again
    for (const t of this.tags) { this._closeSub(t); t.syncedTo = null; this.syncTag(t); }
    this.emit();
  }

  relayStatus() {
    const st = this.pool.listConnectionStatus();
    return this.relays.map((u) => ({ url: u, ok: st.get(u) === true || st.get(normalizeURL(u)) === true }));
  }

  async publish(events, relays = this.relays) {
    let ok = 0;
    await Promise.all(events.flatMap((ev) => this.pool.publish(relays, ev, { maxWait: 6000 })
      .map((p) => p.then(() => { ok++; }, () => {}))));
    return ok;
  }

  // ---------------------------------------------------------------- welcome DM

  /** The owner's pubkey (hex): the signed-in signer, else the owner npub from the backup. */
  get ownerPubkey() { return ownerHex(this.signer?.pubkey) ?? ownerHex(this.meta.owner); }

  /**
   * The tag's welcome DM to the owner again (plus the tag's kind 0 profile). NIP-17 gift wrap to the
   * owner's kind 10050 relays; when there are none or none took it, NIP-04 to our relays and the owner's
   * read relays (the Android app's rule). Returns [{ url, ok, reason }] for the DM that counted, with
   * `nip17` true/false, or null without an owner or K0.
   */
  async sendWelcome(t, text) {
    const to = this.ownerPubkey;
    if (!to || !t?.k0 || !t.chain) return null;
    const dmSk = t.chain.dmSk;
    let lists = [];
    try {
      lists = await this.pool.querySync([...new Set([...this.relays, ...INBOX_INDEXERS])],
        { kinds: [KIND_RELAY_LIST, KIND_DM_RELAYS], authors: [to] }, { maxWait: 5000 });
    } catch { /* our relays only */ }
    const inbox = inboxLists(lists, to);
    const all = dmTargets(this.relays, [...new Set([...inbox.dm, ...inbox.read])]);
    const now = nowS();
    const profile = publishEach(this.pool, all, profileEvent(dmSk, now), dmSk).catch(() => []);
    let dm = null;
    if (inbox.dm.length) {
      const { wrap, wrapSk } = welcomeWrap(dmSk, to, text, now);
      dm = await publishEach(this.pool, inbox.dm.slice(0, MAX_EXTRA), wrap, wrapSk);
      dm.nip17 = true;
    }
    if (!dm || !dm.some((x) => x.ok)) {
      dm = await publishEach(this.pool, all, welcomeEvent(dmSk, to, text, now), dmSk);
      dm.nip17 = false;
    }
    await profile;
    return dm;
  }

  // ---------------------------------------------------------------- finding keys

  /** Copy A: BIP-39 phrase, derive, find the backup on the relays, decrypt. Free-form passphrases are refused. */
  async restoreFromPhrase(phrase, cb = () => {}) {
    const check = checkMnemonic(phrase);
    if (check !== 'ok') return { state: check };
    cb('deriving');
    const d = await derive(normalizeMnemonic(phrase));
    cb('searching');
    const events = await this.pool.querySync(this.relays, backupFilter(d), { maxWait: 8000 });
    const r = await restore(d, events);
    if (r.state === 'found') { this.aDerived = d; this.backupInfo.a = r.event.created_at; }
    return { ...r, derived: d };
  }

  /** Copy B: after a signer is connected, find and decrypt the owner's backup. */
  async restoreFromSigner(signer, cb = () => {}) {
    this.signer = signer;
    cb('searching');
    const relays = await ownerRelays(this.pool, this.relays, signer.pubkey);
    const found = await findBackupB(this.pool, relays, signer);
    if (found) { this.backupInfo.b = found.event.created_at; this.backupInfo.bId = found.event.id; }
    this.emit();
    return found ? { state: 'found', payload: found.payload, d: found.d } : { state: 'none' };
  }

  /** Add the tags of a decrypted backup (A and/or B). Remembers `d`, tombstones and the owner. */
  addFromBackup(payload) {
    if (payload.d && !this.meta.d) this.meta.d = payload.d;
    if (payload.owner && !this.meta.owner) this.meta.owner = payload.owner;
    for (const x of payload.tombstones ?? []) if (!this.meta.tombstones.some((y) => y.k0 === x.k0)) this.meta.tombstones.push(x);
    return this.addTags(tagsFromBackup(payload));
  }

  /** Add tags (already built by keys.js). Existing ids are kept. Returns the added ones. */
  addTags(built) {
    const added = [];
    for (const { chain, tag } of built) {
      if (this.tag(tag.id)) continue;
      const rec = { ...tag, chain, positions: [], lastN: -1, cmdSeq: null, pending: null, lastCommand: null,
        modeUntil: null, modeIntervalS: null, intervalMin: tag.intervalMin ?? null, zones: tag.zones ?? [], extra: tag.extra ?? {}, alarm: null, inZone: null };
      this.tags.push(rec);
      added.push(rec);
    }
    this.persist();
    this.emit();
    for (const t of added) this.syncTag(t);
    return added;
  }

  /** A single tracker by pasted or scanned key: read-only on the backup side, never written to it. */
  addFromParsed(parsed) {
    const b = makeTag(parsed, this.tags.map((x) => x.mark), parsed.name || 'Tag');
    b.tag.imported = true;
    return this.addTags([b]);
  }

  setSigner(signer) { this.signer = signer; this.persist(); this.emit(); }

  /** The stored signer does not answer (or a change is waiting for it): the UI shows a small note. */
  get signerOffline() { return !!this.signer && (this.signer.online === false || this.backupPending); }

  /** Try again to write a change that waited for the signer. Cheap when nothing is pending. */
  async retryPending() {
    if (!this.backupPending || !this.signer || this._retrying) return null;
    this._retrying = true;
    try { return await this.saveBackup(); } finally { this._retrying = false; this.emit(); }
  }

  // ---------------------------------------------------------------- changing and saving the backup

  /** Fields of a tag the web app does not own are kept as they came; the interval is the one we may change. */
  _extraOf(t) {
    const x = { ...(t.extra ?? {}) };
    if (t.intervalMin != null) x.interval_min = t.intervalMin;
    return x;
  }

  /** The tags the backup holds (not single imports, not shares). */
  _backupTags() { return this.tags.filter((t) => t.k0 && !t.imported); }

  _localPayload() {
    return {
      v: 1, created: nowS(), owner: this.meta.owner, d: this.meta.d,
      tags: this._backupTags().map((t) => ({ name: t.name, k0: t.k0, type: t.type, mark: markString(t.mark), updated: t.updated ?? 0, extra: this._extraOf(t) })),
      tombstones: this.meta.tombstones,
    };
  }

  /** Rename / retype a tag, then save the backup. */
  async editTag(id, { name, type }) {
    const t = this.tag(id);
    if (!t) return null;
    t.name = name || t.name;
    if (type) t.type = type;
    t.updated = nowS();
    this.persist(); this.emit();
    return t.imported ? null : this.saveBackup();
  }

  /** Remove a tag; a tombstone keeps the removal from coming back from the other copy. */
  async removeTag(id) {
    const t = this.tag(id);
    if (!t) return null;
    this._closeSub(t);
    this.tags = this.tags.filter((x) => x.id !== id);
    if (t.k0 && !t.imported) this.meta.tombstones = [...this.meta.tombstones.filter((x) => x.k0 !== t.k0), { k0: t.k0, removed: nowS() }];
    this.persist(); this.emit();
    return t.k0 && !t.imported ? this.saveBackup() : null;
  }

  /**
   * Save to every copy we can reach: read the current A and B, merge per tag (newest `updated`
   * wins, removals are tombstones), apply what others changed, write the result to both.
   * Returns { a: relays that took A | null, b: relays that took B | null }.
   */
  /**
   * Read the current copies A and B, merge them with what we hold (per tag, SPEC 12.2) and apply what
   * other devices changed. Writes nothing. Returns { merged, bRelays, changes, bOk } and records the
   * created_at of the copies used in `backupInfo`.
   */
  async _pull(now, { skipSame = false } = {}) {
    const parts = [this._localPayload()];
    if (this.aDerived) {
      try {
        const evs = await this.pool.querySync(this.relays, backupFilter(this.aDerived), { maxWait: 6000 });
        const r = await restore(this.aDerived, evs);
        if (r.state === 'found') { parts.push(r.payload); this.backupInfo.a = r.event.created_at; }
      } catch { /* write anyway */ }
    }
    let bRelays = this.relays;
    let bOk = true;
    if (this.signer) {
      try {
        bRelays = await ownerRelays(this.pool, this.relays, this.signer.pubkey);
        const f = await findBackupB(this.pool, bRelays, this.signer, 10, skipSame ? this.backupInfo.bId : null);
        if (f && !f.unchanged) { parts.push(f.payload); this.meta.d ||= f.d; this.backupInfo.b = f.event.created_at; this.backupInfo.bId = f.event.id; }
      } catch { bOk = false; }
    }
    const merged = mergePayloads(parts, now);
    let changes = 0;
    for (const e of merged.tags) {
      const t = this.tags.find((x) => x.k0 === e.k0 && !x.imported);
      if (e.removed != null) { if (t) { this._closeSub(t); this.tags = this.tags.filter((x) => x !== t); changes++; } continue; }
      if (t) {
        if ((e.updated ?? 0) > (t.updated ?? 0)) {
          changes++;
          t.name = e.name; t.type = e.type; t.updated = e.updated;
          t.extra = e.extra ?? {}; t.zones = Array.isArray(e.extra?.zones) ? e.extra.zones : [];
          if (Number.isFinite(e.extra?.interval_min)) t.intervalMin = e.extra.interval_min;
          const [color, symbol] = (e.mark || '').split('-');
          if (color && symbol) t.mark = { color, symbol };
        }
      } else if (!this.meta.tombstones.some((x) => x.k0 === e.k0 && x.removed >= (e.updated ?? 0))) {
        changes++;
        this.addTags(tagsFromBackup({ tags: [e] }));
      }
    }
    this.meta.tombstones = merged.tags.filter((e) => e.removed != null).map((e) => ({ k0: e.k0, removed: e.removed }));
    return { merged, bRelays, changes, bOk };
  }

  /** "Check for a newer backup": read, merge, apply, do not write. { changes, offline } or null when nothing can be read. */
  async checkBackup({ auto = false } = {}) {
    if (!this.canSaveBackup || this._checking) return null;
    this._checking = true;
    this._lastCheck = Date.now();
    try {
      const r = await this._pull(nowS(), { skipSame: auto });
      this.persist(); this.emit();
      return { changes: r.changes, offline: !r.bOk };
    } finally { this._checking = false; }
  }

  /** Every 10 minutes at most, and only when no change is waiting for the signer. */
  autoCheckBackup() {
    if (this.backupPending || Date.now() - (this._lastCheck ?? 0) < 10 * 60_000) return null;
    return this.checkBackup({ auto: true }).catch(() => null);
  }

  /**
   * Save to every copy we can reach: read the current A and B, merge per tag (newest `updated`
   * wins, removals are tombstones), apply what others changed, write the result to both.
   * Returns { a: relays that took A | null, b: relays that took B | null }.
   */
  async saveBackup() {
    if (!this.canSaveBackup) return { a: null, b: null, skipped: true };
    const now = nowS();
    const { merged, bRelays } = await this._pull(now);
    if (this.signer && !this.meta.d) this.meta.d = randomLocator();
    const text = plaintextOf({ created: now, owner: this.meta.owner, d: this.meta.d, tags: this._backupTags().map((t) => ({ ...t, extra: this._extraOf(t) })), tombstones: this.meta.tombstones });
    const out = { a: null, b: null };
    if (this.aDerived) { out.a = await this.publish([await buildBackupEvent(this.aDerived, text, now)]); if (out.a) this.backupInfo.a = now; }
    this.backupPending = false;
    if (this.signer && this.meta.d) {
      try {
        const ev = await buildBackupBEvent(this.signer, this.meta.d, text, now);
        out.b = await this.publish([ev], bRelays);
        if (out.b) { this.backupInfo.b = now; this.backupInfo.bId = ev.id; }
      } catch { this.backupPending = true; out.deferred = true; } // the signer did not answer: retried later
    }
    this.persist(); this.emit();
    return out;
  }

  /** Delete copy B (empty replacement plus a NIP-09 deletion). Copy A is deleted by its phrase in the Android app. */
  async deleteBackupB() {
    if (!this.signer || !this.meta.d) return 0;
    const now = nowS();
    const relays = await ownerRelays(this.pool, this.relays, this.signer.pubkey);
    return this.publish([await buildBackupBEvent(this.signer, this.meta.d, '', now, true), await buildDeletionB(this.signer, this.signer.pubkey, this.meta.d, now)], relays);
  }

  setInterval(id, minutes) {
    const t = this.tag(id);
    if (!t) return;
    t.intervalMin = minutes;
    if (!t.imported && t.k0) { t.updated = nowS(); if (this.canSaveBackup) this.saveBackup().catch(() => {}); }
    this.persist(); this.emit();
  }

  /** Clear the "left a zone" alarm. */
  clearAlarm(id) { const t = this.tag(id); if (t) { t.alarm = null; this.persist(); this.emit(); } }

  /** Put a single-tag import into the backup. Returns false when there is no backup to save to yet. */
  async addToBackup(id) {
    const t = this.tag(id);
    if (!t || !t.k0) return false;
    t.imported = false; t.updated = nowS();
    this.meta.tombstones = this.meta.tombstones.filter((x) => x.k0 !== t.k0);
    this.persist(); this.emit();
    if (!this.canSaveBackup) return false;
    await this.saveBackup();
    return true;
  }

  /**
   * A new backup from the web: a 12-word BIP-39 phrase from crypto.getRandomValues. The words are
   * shown once by the UI; `createBackup` writes copy A (and copy B when signed in).
   */
  newPhrase() { return generateMnemonic(wordlist, 128); }

  async createBackup(phrase, now = nowS()) {
    const d = await derive(normalizeMnemonic(phrase));
    this.aDerived = d;
    if (this.signer && !this.meta.d) this.meta.d = randomLocator();
    if (this.signer && !this.meta.owner) this.meta.owner = null;
    for (const t of this._backupTags()) t.updated ||= now;
    return this.saveBackup();
  }

  /** Sign out: memory, the stored session, cache and CryptoKey, the phrase's S, the signer and the subscriptions. */
  async forgetAll() {
    for (const id of [...this._queues.keys()]) this._stopQueue(id);
    this._globalSub?.close(); this._globalSub = null;
    for (const t of this.tags) this._closeSub(t);
    this.tags = [];
    this.stay = false;
    clearTimeout(this._saveTimer);
    this.aDerived = null;
    this.backupPending = false;
    this.signer?.close();
    this.signer = null;
    this.meta = { d: null, owner: null, tombstones: [] };
    this.backupInfo = { a: null, b: null, bId: null };
    clearCache();
    this.emit();
    await clearSession();
  }

  // ---------------------------------------------------------------- stay signed in (IndexedDB)

  /** Turn "stay signed in" on and write the session now. Returns false when the browser refuses storage. */
  async enableStay() {
    this.stay = true;
    return this.persistNow();
  }

  persist() {
    if (!this.stay) return;
    clearTimeout(this._saveTimer);
    this._saveTimer = setTimeout(() => this.persistNow(), 600);
  }

  async persistNow() {
    if (!this.stay) return false;
    clearTimeout(this._saveTimer);
    return saveSession({
      v: 1, meta: this.meta, tags: this.tags.map(serializeTag), backupPending: this.backupPending, backupInfo: this.backupInfo,
      S: this.aDerived ? Array.from(this.aDerived.S) : null,
      signer: this.signer?.session() ?? null,
    });
  }

  /** On load: open the stored session without any signer round-trip. The signer reconnects when it is needed. */
  async restoreStay() {
    const h = await loadSession();
    if (!h?.tags) return false;
    this.stay = true;
    if (h.S) this.aDerived = deriveFromS(Uint8Array.from(h.S));
    if (h.meta) this.meta = { d: h.meta.d ?? null, owner: h.meta.owner ?? null, tombstones: h.meta.tombstones ?? [] };
    if (h.signer) this.signer = lazySigner(h.signer, this.pool);
    this.backupPending = !!h.backupPending;
    if (h.backupInfo) this.backupInfo = { a: null, b: null, bId: null, ...h.backupInfo };
    this._loadSaved(h.tags);
    return true;
  }

  /** The old passphrase-locked blob (versions before this one): unlock it once, then it is converted. Returns 'ok' | 'wrong' | 'none'. */
  async unlockCache(passphrase) {
    const blob = readCacheBlob();
    if (!blob) return 'none';
    const d = await derive(normalizeMnemonic(checkMnemonic(passphrase) === 'ok' ? passphrase : '') || passphrase);
    let key = cacheKey(d.S);
    let data = await openCache(key, blob);
    let used = d;
    if (!data) { // a free-form lock passphrase is not lowercased
      const d2 = await derive(passphrase);
      key = cacheKey(d2.S); data = await openCache(key, blob); used = d2;
    }
    if (!data) return 'wrong';
    if (data.lockIsPhrase) this.aDerived = used;
    if (data.meta) this.meta = { d: data.meta.d ?? null, owner: data.meta.owner ?? null, tombstones: data.meta.tombstones ?? [] };
    this._loadSaved(data.tags);
    return 'ok';
  }

  _loadSaved(list) {
    for (const s of list) {
      const parsed = s.k0 ? { k0: s.k0, name: s.name, type: s.type, mark: s.mark }
        : { shared: s.share, name: s.name, type: s.type, mark: s.mark };
      const { chain, tag } = makeTag(parsed, [], s.name);
      this.tags.push({ ...tag, chain, positions: s.positions ?? [], lastN: s.lastN ?? -1, cmdSeq: s.cmdSeq,
        pending: s.pending, lastCommand: s.lastCommand, modeUntil: s.modeUntil,
        modeIntervalS: s.modeIntervalS, intervalMin: s.intervalMin ?? null, updated: s.updated ?? 0, imported: !!s.imported,
        extra: s.extra ?? {}, zones: s.zones ?? [], alarm: s.alarm ?? null, inZone: s.inZone ?? null, syncedTo: s.syncedTo ?? null,
        mbAnchors: s.mbAnchors ?? {} });
    }
    this.emit();
    for (const t of this.tags) this.syncTag(t);
  }

  // ---------------------------------------------------------------- reload handoff (automatic version refresh)

  /**
   * Without "stay signed in", keys, reports and the session cross the automatic reload in
   * sessionStorage (gone when the tab closes) and are deleted when read. With it, the IndexedDB
   * session is simply written before the reload.
   */
  stashForReload() {
    if (!this.tags.length) return;
    if (this.stay) return this.persistNow();
    saveHandoff({
      tags: this.tags.map(serializeTag), meta: this.meta,
      S: this.aDerived ? Array.from(this.aDerived.S) : null,
      signer: this.signer?.session() ?? null,
    });
  }

  takeStash() {
    const h = takeHandoff();
    if (!h?.tags?.length) return false;
    if (h.S) this.aDerived = deriveFromS(Uint8Array.from(h.S));
    if (h.meta) this.meta = h.meta;
    this._loadSaved(h.tags);
    if (h.signer) restoreSession(h.signer, this.pool).then((s) => { if (s) this.setSigner(s); });
    return true;
  }

  // ---------------------------------------------------------------- reading reports

  _closeSub(t) { this._subs.get(t.id)?.close(); this._subs.delete(t.id); }

  _window(t) {
    if (!t._win) t._win = new ReportWindow(t.chain, t.lastN, exactNs(t.positions));
    return t._win;
  }

  /**
   * Reading reports (audit 3.1, "metadata on relays"). By default the relays are asked for every
   * kind 4243 since the last sync, without any #y filter, and the events are matched here against each
   * tag's window: a relay learns only that someone reads LibreTag reports, not which tags belong
   * together. Paginated per relay (`until` + `limit`), deduplicated by id. When one relay has more than
   * FETCH_ALL_MAX events in that span (about 6 MB at ~300 B each, roughly a day of 800 tags at one
   * report an hour), downloading everything costs more than it hides, and the app falls back to the
   * old per-tag #y window queries for the rest of the session ('y' mode).
   * syncTag() calls coalesce into one sync for all tags that asked.
   */
  syncTag(t) {
    this._needSync.add(t.id);
    this.syncing.add(t.id);
    this.emit();
    if (!this._syncP) {
      this._syncP = (async () => {
        await Promise.resolve();
        try {
          while (this._needSync.size) {
            const ids = [...this._needSync];
            this._needSync.clear();
            const tags = ids.map((id) => this.tag(id)).filter(Boolean);
            try { await this._syncTags(tags); } catch { /* next sync tries again */ }
            for (const x of tags) {
              this.syncing.delete(x.id);
              this.firstSyncDone.add(x.id);
              if (x.pending) this.publishPending(x).catch(() => {}); // resume what a closed tab left unpublished
              this.publishAnchors(x).catch(() => {});
            }
            for (const id of ids) this.syncing.delete(id);
            this._startLive();
            this.emit();
          }
        } finally { this._syncP = null; }
      })();
    }
    return this._syncP;
  }

  async _syncTags(tags) {
    if (this.queryMode === 'all') {
      const now = nowS();
      const since = Math.min(...tags.map((t) => (t.syncedTo ? t.syncedTo - SYNC_OVERLAP_S : now - SYNC_BACK_S)));
      const r = await fetchAllReports(this.pool, this.relays, Math.max(since, now - SYNC_BACK_S), { max: FETCH_ALL_MAX, page: FETCH_PAGE });
      if (!r.overflow) {
        await this._matchAll(tags, r.events);
        for (const t of tags) t.syncedTo = now;
        this.persist();
        return;
      }
      this.queryMode = 'y';
    }
    for (const t of tags) await this._syncTagByY(t);
  }

  /** Match a batch of relay events against tags locally; stale tags search further (resync in blocks). */
  async _matchAll(tags, events) {
    const items = [];
    for (const ev of events.sort((x, y) => x.created_at - y.created_at)) {
      let p;
      try { p = b64decode(ev.content); } catch { continue; }
      if (p.length !== REPORT_P_LEN && p.length !== REPORT_P_LEN_STEALTH) continue;
      items.push({ ev, p, x: bytesToHex(p.slice(0, 4)), used: false });
    }
    for (const t of tags) {
      t._win = new ReportWindow(t.chain, t.lastN, exactNs(t.positions));
      // the window slides only on rebuild: repeat until a pass finds nothing new
      for (let pass = 0; pass < 40; pass++) {
        let n = 0;
        for (const it of items) {
          if (it.used || !t._win.table.has(it.x)) continue;
          if (await this._onEvent(t, it.ev, false, it.p)) { it.used = true; n++; }
        }
        t._win.rebuild();
        if (!n) break;
      }
      // only a tag that is overdue (or never heard) searches further: ~130 000 HMACs per tag
      const st = statusOf(t, Date.now());
      if (st.kind !== 'SILENT' && st.kind !== 'WAITING') continue;
      const rest = items.filter((it) => !it.used && it.ev.created_at > (t._resyncChecked ?? 0));
      if (!rest.length) continue;
      const hit = await findBeyondWindow(t.chain, t.lastN, rest, { cap: RESYNC_CAP });
      t._resyncChecked = Math.max(...rest.map((it) => it.ev.created_at));
      if (!hit) continue;
      { const tm = reportTimeMs(hit.item.ev); await this._addReport(t, hit.n, hit.report, tm.at, tm.until); }
      hit.item.used = true;
      t._win = new ReportWindow(t.chain, t.lastN, exactNs(t.positions));
      for (const it of items) if (!it.used && t._win.table.has(it.x) && await this._onEvent(t, it.ev, false, it.p)) it.used = true;
      t._win.rebuild();
    }
  }

  /** 'y' mode: query the relays for the counters we expect next (250 #y per REQ), as before. */
  async _syncTagByY(t) {
    const win = (t._win = new ReportWindow(t.chain, t.lastN, exactNs(t.positions)));
    const since = nowS() - SYNC_BACK_S;
    const pass = async (lo, hi) => {
      const ranges = [];
      for (let a = lo; a <= hi; a += CHUNK_N) ranges.push([a, Math.min(hi, a + CHUNK_N - 1)]);
      const found = [];
      let i = 0;
      const worker = async () => {
        while (i < ranges.length) {
          const [a, b] = ranges[i++];
          const tags = win.tagsRange(a, b);
          try {
            const evs = await this.pool.querySync(this.relays, { kinds: [NOSTR_REPORT_KIND], '#y': tags, since }, { maxWait: 6000 });
            found.push(...evs);
          } catch { /* a relay may be down; the others answer */ }
        }
      };
      await Promise.all([worker(), worker(), worker(), worker()]);
      found.sort((x, y) => x.created_at - y.created_at);
      let n = 0;
      for (const ev of found) if (await this._onEvent(t, ev, false)) n++;
      win.rebuild();
      return { n, found };
    };
    const floor = t.chain.minEpoch * 64;
    const before0 = t.lastN;
    if (t.lastN < 0) await pass(floor, floor + FIRST_SYNC - 1);
    for (let guard = 0; guard < 20; guard++) {
      const before = t.lastN;
      const { n: got } = await pass(Math.max(floor, t.lastN + 1), t.lastN + 256);
      if (!got && t.lastN >= 0 && guard === 0) await pass(t.lastN + 257, t.lastN + WINDOW_AHEAD);
      if (t.lastN === before) break;
    }
    // resync beyond the window, in blocks, once per session and only for a tag silent for 12 h:
    // each block costs ~17 REQs here and shows the relays more tags of the same tag
    const last = t.positions.at(-1)?.receivedAt ?? 0;
    if (t.lastN === before0 && !t._resyncByY && Date.now() - last > 12 * 3600_000) {
      t._resyncByY = true;
      const base = t.lastN < 0 ? floor + FIRST_SYNC : t.lastN + 1 + WINDOW_AHEAD;
      for (let lo = base; lo < base - WINDOW_AHEAD + RESYNC_CAP; lo += RESYNC_BLOCK) {
        const { found } = await pass(lo, lo + RESYNC_BLOCK - 1);
        const items = found.map((ev) => { try { const p = b64decode(ev.content); return { ev, p, x: bytesToHex(p.slice(0, 4)) }; } catch { return null; } }).filter(Boolean);
        const hit = items.length && await findBeyondWindow(t.chain, lo - 1 - WINDOW_AHEAD, items, { cap: WINDOW_AHEAD + RESYNC_BLOCK });
        if (hit) {
          { const tm = reportTimeMs(hit.item.ev); await this._addReport(t, hit.n, hit.report, tm.at, tm.until); }
          t._win = new ReportWindow(t.chain, t.lastN, exactNs(t.positions));
          for (const it of items) await this._onEvent(t, it.ev, false, it.p);
          break;
        }
      }
    }
  }

  /** Live: one subscription for all kind 4243 ('all' mode), or one #y subscription per tag ('y' mode). */
  _startLive() {
    if (this.queryMode === 'all') {
      if (this._globalSub) return;
      this._globalSub = this.pool.subscribeMany(this.relays, { kinds: [NOSTR_REPORT_KIND], since: nowS() - 120 }, {
        onevent: async (ev) => {
          for (const t of this.tags) if (this.firstSyncDone.has(t.id) && await this._onEvent(t, ev, true)) { t.syncedTo = nowS(); break; }
        },
      });
      return;
    }
    this._globalSub?.close(); this._globalSub = null;
    for (const t of this.tags) if (this.firstSyncDone.has(t.id) && !this._subs.has(t.id)) this._live(t);
  }

  _live(t) {
    this._closeSub(t);
    const win = this._window(t);
    const tags = win.tagsAhead(128);
    const sub = this.pool.subscribeMany(this.relays, { kinds: [NOSTR_REPORT_KIND], '#y': tags, since: nowS() - 3600 }, {
      onevent: async (ev) => {
        const got = await this._onEvent(t, ev, true);
        if (got) this._live(t);
      },
    });
    this._subs.set(t.id, sub);
  }

  /** Opens one relay event for a tag. true when it carried a new report. */
  async _onEvent(t, ev, live, decoded = null) {
    let p = decoded;
    if (!p) { try { p = b64decode(ev.content); } catch { return false; } }
    if (p.length !== REPORT_P_LEN && p.length !== REPORT_P_LEN_STEALTH) return false;
    const tm = reportTimeMs(ev);
    const hit = await this._window(t).open(p, live, tm.until != null);
    if (!hit) return false;
    await this._addReport(t, hit.n, hit.report, tm.at, tm.until);
    return true;
  }

  /** approxUntil (ms): a bridge drained the report late, the time lies in [receivedAt, approxUntil] (0.8.1) */
  async _addReport(t, n, r, receivedAt, approxUntil = null) {
    const isNew = n > t.lastN;
    const pos = { n, receivedAt, ...r };
    delete pos.neighbours; delete pos.wifi;
    pos.neighbourCount = r.neighbours.length;
    if (approxUntil != null) pos.approxUntil = Math.max(approxUntil, receivedAt);
    // the first exact copy of the newest report: its checks can be scheduled now
    const upgrade = upgrades(t, pos);
    mergeReport(t.positions, pos, MAX_POSITIONS);
    // the latest time it can have been heard
    const heardBy = pos.approxUntil ?? receivedAt;
    t.lastN = Math.max(t.lastN, n);
    if (isNew && r.hasFix && t.zones?.length) {
      const step = zoneStep(t.zones, t.inZone, r);
      if (step.left) t.alarm = { zone: step.left, at: receivedAt };
      t.inZone = step.inZone;
    }
    let confirmed = false;
    const pend = t.pending;
    if (pend && r.cmdAck && (pend.seq & 15) === r.cmdSeq && heardBy >= pend.queuedAt - 300_000) {
      confirmed = true;
      t.lastCommand = { kind: pend.kind, confirmedAt: receivedAt };
      if (pend.kind === 'LOST') { t.modeUntil = receivedAt + LOST_MIN * 60_000; t.modeIntervalS = 600; }
      else if (pend.kind === 'TRACK') { t.modeUntil = receivedAt + (pend.durationMin ?? TRACK_MIN) * 60_000; t.modeIntervalS = pend.intervalS ?? 300; }
      else if (pend.kind === 'NORMAL') { t.modeUntil = null; t.modeIntervalS = null; }
      // the tag took it: entries for later checks would only flood the mesh for nothing
      const rest = cancelEvents(pend.sent, t.chain);
      t.pending = null;
      this._stopQueue(t.id);
      if (rest.length) this.publish(rest).catch(() => {});
      this._watchClaims();
    } else if (pend && isNew && heardBy > pend.queuedAt) {
      pend.reportsSince = (pend.reportsSince ?? 0) + 1;
      if (pend.reportsSince >= 2) { pend.notConfirmed = true; pend.sentAt = null; }
    }
    if (r.mode === 'IDLE' && isNew && !confirmed) { t.modeUntil = null; t.modeIntervalS = null; }
    this.persist();
    this.emit();
    // keep the mailbox ahead of the tag while a command waits
    if (t.pending && (isNew || upgrade) && this.firstSyncDone.has(t.id)) this.publishPending(t).catch(() => {});
    // and the anchors for the next reports (PROTOCOL.md 4.2), whether a command waits or not
    if (isNew && this.firstSyncDone.has(t.id)) this.publishAnchors(t).catch(() => {});
  }

  /**
   * Anchors (PROTOCOL.md 4.2): kind 4244 with empty content, signed by sk_n, naming the tags of the next
   * reports, so a bridge knows the key a report commits to before the report is on air. Only for tags
   * this browser can command; published when missing or past half their lifetime, remembered in t.mbAnchors.
   */
  async publishAnchors(t, nowSec = nowS()) {
    if (!t?.chain?.canCommand || this._anchoring.has(t.id)) return 0;
    this._anchoring.add(t.id);
    try {
      t.mbAnchors = pruneAnchors(t.mbAnchors, t.lastN, nowSec);
      const entries = anchorEntries(t, t.mbAnchors, nowSec);
      if (!entries.length) return 0;
      const evs = entries.map((e) => signEntry(e, t.chain, nowSec));
      const ok = await this.publish(evs);
      if (ok) {
        for (const e of entries) t.mbAnchors[e.n] = Number(e.tags.find((x) => x[0] === 'expiration')[1]);
        this.persist();
      }
      return ok;
    } finally { this._anchoring.delete(t.id); }
  }

  // ---------------------------------------------------------------- commands

  /** Queue a command: sealed for the next reports (and checks), published to the mailbox signed with sk_n (PROTOCOL.md 4.2). */
  async queueCommand(id, kind, arg = 0, durationMin = 0) {
    const t = this.tag(id);
    if (!t || !t.chain.canCommand || !COMMAND_KINDS.includes(kind)) return { ok: 0, events: 0 };
    const seq = nextSeq(t);
    t.cmdSeq = seq;
    this._stopQueue(t.id);
    t.pending = { seq, kind, body: [...buildCommand(kind, seq, arg, durationMin)].map((b) => b.toString(16).padStart(2, '0')).join(''),
      g: newGroup(), queuedAt: Date.now(), notConfirmed: false, reportsSince: 0,
      ...(kind === 'TRACK' ? { intervalS: arg > 0 ? arg : 300, durationMin: durationMin > 0 ? durationMin : TRACK_MIN } : {}) };
    this.persist(); this.emit();
    this.publishAnchors(t).catch(() => {});
    return this.publishPending(t);
  }

  /**
   * Publish the entries of the pending command that are not out yet, one at a time and in order
   * (untimed, then ascending `at`): sign it with sk_n of its report (PROTOCOL.md 4.2), publish it, go on.
   * Resolves after the first entry ({ ok, events }), which is when the UI says the command is on its
   * way; the rest follow in the background. If the tab closes, what is left is simply not published:
   * on the next open, after the first sync, this runs again and publishes what is still useful
   * (checks still ahead, the next report counters). A new report while it runs makes it recompute.
   */
  publishPending(t) {
    const pend = t.pending;
    if (!pend) return Promise.resolve({ ok: 0, events: 0 });
    pend.g ||= newGroup();
    const running = this._queues.get(t.id);
    if (running && running.pend === pend) { running.again = true; return running.first; }
    running?.abort.abort();
    const q = { pend, again: false, abort: new AbortController() };
    let resolveFirst;
    q.first = new Promise((r) => { resolveFirst = r; });
    this._queues.set(t.id, q);
    const live = () => t.pending === pend && !q.abort.signal.aborted;
    (async () => {
      let first = null;
      try {
        do {
          q.again = false;
          const entries = notYetOut(await mailboxEntries(t, pend), pend.sent, nowS());
          for (const entry of entries) {
            if (!live() || q.again) break;
            const now = nowS();
            if (entry.at != null && entry.at <= now + 5) continue; // its check has passed
            let ev;
            try { ev = signEntry(entry, t.chain, now); } catch { continue; }
            if (!live()) return;
            const ok = await this.publish([ev]);
            if (ok) {
              pend.sent = [...(pend.sent ?? []), ...sentOf([ev])].slice(-60);
              pend.ys = [...new Set([...(pend.ys ?? []), ...entry.ys])].slice(-400);
              this.persist();
              this._watchClaims();
            }
            if (!first) { first = { ok, events: 1 }; resolveFirst(first); }
            if (!ok) return; // no relay took it: the rest waits for the next report or the next open
          }
        } while (q.again && live());
      } finally {
        if (this._queues.get(t.id) === q) this._queues.delete(t.id);
        if (!first) resolveFirst({ ok: 0, events: 0 });
      }
    })();
    return q.first;
  }

  _stopQueue(id) { this._queues.get(id)?.abort.abort(); this._queues.delete(id); }

  /**
   * Bridges announce that they are about to transmit a command with an ephemeral kind 24244 claim (tag y).
   * Seeing one for a y of ours means it is on its way: from then on it is "sent", and cannot be cancelled.
   */
  _watchClaims() {
    this._claimSub?.close(); this._claimSub = null;
    const ys = [...new Set(this.tags.filter((t) => t.pending && !t.pending.sentAt).flatMap((t) => t.pending.ys ?? []))].slice(-250);
    if (!ys.length) return;
    this._claimSub = this.pool.subscribeMany(this.relays, { kinds: [24244], '#y': ys, since: nowS() - 60 }, {
      onevent: (ev) => { if (this.markSentFromClaim(ev)) this._watchClaims(); },
    });
  }

  /** A claim for one of our command tags: mark that command sent. Returns the tag id or null. */
  markSentFromClaim(ev) {
    const claimed = ev.tags.filter((x) => x[0] === 'y').map((x) => x[1]);
    const t = this.tags.find((x) => x.pending && !x.pending.sentAt && (x.pending.ys ?? []).some((y) => claimed.includes(y)));
    if (!t) return null;
    t.pending.sentAt = Math.max(ev.created_at * 1000, t.pending.queuedAt);
    this.persist(); this.emit();
    return t.id;
  }

  /** Cancel (only while it waits): a NIP-09 kind 5 per mailbox event, signed with that event's key (sk_n), to the same relays. */
  async cancelCommand(id) {
    const t = this.tag(id);
    if (!t?.pending) return 0;
    if (t.pending.sentAt) return 0; // already on its way
    const dels = cancelEvents(t.pending.sent, t.chain);
    t.pending = null;
    this._stopQueue(id);
    this.persist(); this.emit(); this._watchClaims();
    return dels.length ? this.publish(dels) : 0;
  }

  statusOf(t, now = Date.now()) { return statusOf(t, now); }
}
