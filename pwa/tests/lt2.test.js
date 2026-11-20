import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { bytesToHex as hex, hexToBytes as unhex } from '../src/lib/bytes.js';
import * as L from '../src/lib/lt2.js';

const V = JSON.parse(readFileSync(new URL('../../host/tests/vectors/lt2.json', import.meta.url)));
const chain = new L.KeyChain(unhex(V.k0));

describe('LT2 keys', () => {
  it('derives id, usb_key, dm_sk and the chains', () => {
    expect(chain.idHex).toBe(V.id);
    expect(hex(chain.usbKey)).toBe(V.usb_key);
    expect(chain.dmSk).toBe(V.dm_sk);
    expect(hex(chain.view(0))).toBe(V.view0);
    expect(hex(chain.view(2))).toBe(V.view2);
    expect(hex(chain.cmd(0))).toBe(V.cmd0);
    expect(hex(chain.cmd(2))).toBe(V.cmd2);
  });
});

describe('report sealing v2: mailbox keys and history records', () => {
  for (const m of V.mailbox_keys) {
    it(`sk_n and pk_n for n=${m.n}`, () => {
      expect(hex(L.mailboxSk(chain.cmd(L.epoch(m.n)), m.n))).toBe(m.sk);
      expect(L.mailboxPk(chain.cmd(L.epoch(m.n)), m.n)).toBe(m.pk);
    });
  }
  for (const h of V.history) {
    it(`history j=${h.j} keeps the plain seal`, async () => {
      expect(hex(await L.sealReport(chain.view(0), h.n, h.j, unhex(h.pt)))).toBe(h.p);
      expect(await L.openReport(chain.view(0), h.n, h.j, unhex(h.p))).not.toBeNull();
    });
  }
  it('a view share cannot derive the mailbox key', () => {
    const view = L.KeyChain.fromShare(0, chain.view(0));
    expect(view.cmd(0)).toBeNull();
  });
});

describe('LT2 reports', () => {
  for (const r of V.reports) {
    it(`seals and opens n=${r.n} j=${r.j}`, async () => {
      const ve = chain.view(L.epoch(r.n));
      expect(hex(L.reportTag(ve, r.n, r.j))).toBe(r.tag);
      const p = await L.sealReportChain(chain, r.n, r.j, unhex(r.pt));
      expect(hex(p)).toBe(r.p);
      expect(hex(await L.sealReportChain(chain, r.n, r.j, unhex(r.pt), true))).toBe(r.p_stealth);
      expect(L.mailboxPk(chain.cmd(L.epoch(r.n)), r.n)).toBe(r.pk);
      expect(hex(L.reportCommit(unhex(r.pk), unhex(r.p)))).toBe(r.p.slice(8, 14));
      const moved = unhex(r.p); moved[5] ^= 1; // c is not under the mac
      expect(await L.openReport(ve, r.n, r.j, moved)).not.toBeNull();
      expect(await L.openReport(ve, r.n, r.j, unhex(r.p))).not.toBeNull();
      expect(await L.openReport(ve, r.n, r.j, unhex(r.p_stealth))).not.toBeNull();
      const bad = unhex(r.p); bad[10] ^= 1;
      expect(await L.openReport(ve, r.n, r.j, bad)).toBeNull();
    });
  }

  it('decodes fields of the first vector (fix at the synthetic spot)', async () => {
    const r = V.reports[0];
    const rep = await L.openReport(chain.view(0), r.n, r.j, unhex(r.p));
    expect(rep.hasFix).toBe(true);
    expect(rep.fixTime).toBeGreaterThan(0);
    expect(rep.lat).toBeCloseTo(48.7, 0);
    expect(rep.battery).toBe(0x58);
  });

  it('the all-unknown report decodes without a position', async () => {
    const r = V.reports.find((x) => x.n === 64);
    const rep = await L.openReport(chain.view(1), 64, 0, unhex(r.p));
    expect(rep.fixTime).toBe(0);
    expect(rep.battery).toBeNull();
    expect(rep.hasFix).toBe(false);
  });
});

describe('0.7 report flags in the vectors', () => {
  it('every vector report decodes and re-reads the CHECKS / quiet bits like the plaintext says', () => {
    for (const r of V.reports) {
      const pt = unhex(r.pt);
      const d = L.decodeReport(pt);
      expect(d).not.toBeNull();
      expect(d.checks).toBe(!!(pt[1] & 0x80));
      expect(d.quiet).toBe(!!(pt[1] & 0x10));
      if (d.quiet && !(pt[1] & 0x20) && pt[2] & 0x08) expect(d.quietReason).toBe('parked');
      expect(d.nextInS).toBe(L.nextInDecode(pt[31]));
    }
  });
});

describe('next_in and energy', () => {
  it('decode and encode follow the vectors', () => {
    for (const [sec, v, back] of V.next_in) {
      if (v) expect(L.nextInEncode(sec)).toBe(v);
      expect(L.nextInDecode(v)).toBe(back);
    }
    for (const [mah, v] of V.energy) expect(mah === undefined ? 0 : L.energyDecode(v)).toBeDefined();
  });
});

describe('LT2 commands', () => {
  for (const c of V.commands) {
    it(`seals and opens the command for n=${c.n}`, async () => {
      const ce = chain.cmd(L.epoch(c.n));
      expect(hex(L.commandTag(ce, c.n))).toBe(c.tag);
      expect(hex(await L.sealCommand(ce, c.n, unhex(c.pt)))).toBe(c.p);
      const opened = await L.openCommand(ce, c.n, unhex(c.p));
      expect(hex(L.encodeCommand(opened.seq, opened.op, opened.args))).toBe(c.pt);
    });
  }
  it('builds the app commands byte for byte (Lost, Beep, Idle)', () => {
    expect(hex(L.Command.setMode(1, L.Mode.LOST, 600, 1440))).toBe('220101035802a005000000000000000000');
    expect(hex(L.Command.buzz(2, 10))).toBe('2202040a00000000000000000000000000');
    expect(hex(L.Command.setIdle(3, 30, 60))).toBe('2203031e003c0000000000000000000000');
  });
});

describe('0.7 checks', () => {
  for (const c of V.checks) {
    it(`schedule for n=${c.n} base ${c.base_s} s`, () => {
      const ce = chain.cmd(L.epoch(c.n));
      const offs = L.checkOffsets(ce, c.n, c.base_s, 6);
      expect(offs).toEqual(c.offsets);
      for (let i = 0; i < 6; i++) {
        expect(L.checkJitter(ce, c.n, i + 1, c.base_s)).toBe(c.jitter[i]);
        expect(L.checkGuardMs(c.offsets[i])).toBe(c.guard_ms[i]);
      }
      expect(L.checkOffsets(ce, c.n, c.base_s, 64, c.heartbeat_s)).toEqual(c.before_heartbeat);
    });
  }
  for (const c of V.check_commands) {
    it(`command n=${c.n} at check ${c.k}`, async () => {
      const ce = chain.cmd(L.epoch(c.n));
      expect(hex(L.commandTag(ce, c.n, c.k))).toBe(c.tag);
      expect(hex(await L.sealCommand(ce, c.n, unhex(c.pt), c.k))).toBe(c.p);
      expect(hex(L.commandTag(ce, c.n, 0))).toBe(c.tag_k0);
      expect(await L.openCommand(ce, c.n, unhex(c.p), c.k)).not.toBeNull();
      expect(await L.openCommand(ce, c.n, unhex(c.p), 0)).toBeNull();
    });
  }
});

describe('ReportWindow', () => {
  it('finds a report by its tag, slides the window and offers tags ahead', async () => {
    const w = new L.ReportWindow(chain);
    const r = V.reports.find((x) => x.n === 200);
    const hit = await w.open(unhex(r.p));
    expect(hit.n).toBe(200);
    expect(w.lastN).toBe(200);
    const ahead = w.tagsAhead(8);
    expect(ahead.length).toBe(8);
    expect(ahead[0]).toBe(hex(L.reportTag(chain.view(L.epoch(201)), 201, 0)));
    expect(await w.open(unhex(r.p))).toBeNull(); // seen
  });
  it('ignores a payload of another tracker', async () => {
    const other = new L.KeyChain(new Uint8Array(32).fill(7));
    const w = new L.ReportWindow(other);
    expect(await w.open(unhex(V.reports[0].p))).toBeNull();
  });
});

describe('shared keys (V_e from an epoch)', () => {
  it('reads reports from its epoch on, and refuses the past', async () => {
    const share = L.KeyChain.fromShare(2, chain.view(2), null);
    expect(hex(share.view(3))).toBe(hex(chain.view(3)));
    expect(share.cmd(3)).toBeNull();
    expect(() => share.view(1)).toThrow();
    const w = new L.ReportWindow(share);
    const r = V.reports.find((x) => x.n === 130); // epoch 2
    expect((await w.open(unhex(r.p))).n).toBe(130);
    expect(w.tagsRange(0, 200).length).toBe((200 - 128 + 1) * 2);
  });
});

describe('Wi-Fi layout without a fix time (PROTOCOL.md 2.2)', () => {
  for (const c of V.wifi_nofix) {
    it(`n=${c.n}: a second full BSSID in bytes 8..14, no position, no accuracy`, async () => {
      const rep = await L.openReport(chain.view(L.epoch(c.n)), c.n, 0, unhex(c.p));
      expect(rep).not.toBeNull();
      expect(rep.fixTime).toBe(0);
      expect(rep.hasFix).toBe(false);
      expect(rep.lat).toBe(0);
      expect(rep.lon).toBe(0);
      expect(rep.haccM).toBe(255);
      expect(rep.wifi.seed).toBe(c.seed);
      expect(rep.wifi.seed2).toBe(c.seed2);
      expect(Math.abs(rep.wifi.seed2Rssi - c.seed2_rssi)).toBeLessThanOrEqual(1); // 2 dB steps
      expect(rep.wifi.tail).toBe(c.tail || null);
      if (c.tail) expect(Math.abs(rep.wifi.tailRssi - c.tail_rssi)).toBeLessThanOrEqual(1);
      // the second tail takes the repeater's slot
      expect(rep.wifi.tail2).toBe(c.tail2);
      expect(Math.abs(rep.wifi.tail2Rssi - c.tail2_rssi)).toBeLessThanOrEqual(1);
      expect(rep.neighbours).toEqual([]);
      expect(rep.nbTotal).toBe(2);
    });
  }

  it('two usable APs: one tail, the repeater stays', async () => {
    const f = V.wifi_few;
    const rep = await L.openReport(chain.view(L.epoch(f.n)), f.n, 0, unhex(f.p));
    expect(rep.wifi.tail).toBe(f.tail);
    expect(rep.wifi.tail2).toBeNull();
    expect(rep.neighbours.map((x) => x.hash)).toEqual(['a1b2']);
  });

  it('a Wi-Fi report with a fix time has no second seed', () => {
    const pt = unhex(V.wifi_nofix[0].pt);
    pt[4] = 1; // a fix time: bytes 8..14 are a position again
    const rep = L.decodeReport(pt);
    expect(rep.wifi.seed2).toBeNull();
    expect(rep.haccM).toBe(pt[14]);
  });
});

describe('0.9.4 boot reports', () => {
  for (const b of V.boot_reports) {
    it(`n=${b.n} j=${b.j}`, async () => {
      const r = await L.openReport(chain.view(L.epoch(b.n)), b.n, b.j, unhex(b.p));
      expect(r).not.toBeNull();
      const d = L.decodeReport(unhex(b.pt));
      expect(d.boot).toBe(b.boot);
      expect(d.bootCause).toBe(b.boot_cause);
      expect(d.bootResets).toBe(b.boot_resets);
      expect(d.hasFix).toBe(b.has_fix);
      expect(d.mode).toBe(['IDLE', 'TRACK', 'LISTEN', 'LOST'][b.mode]);
      expect(d.haccM === 255 ? null : d.haccM).toBe(b.hacc_m);
    });
  }
  it('older reports carry no boot bit', () => {
    for (const r of V.reports) expect(L.decodeReport(unhex(r.pt)).boot).toBe(false);
  });
});
