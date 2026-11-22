import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { App } from '../src/lib/app.js';
import { makeTag, parseKeyInput } from '../src/lib/keys.js';
import { serializeTag } from '../src/lib/vault.js';

const V = JSON.parse(readFileSync(new URL('../../host/tests/vectors/share.json', import.meta.url)));
const quiet = () => { const a = new App(); a.syncTag = () => {}; a.persist = () => {}; return a; };

describe('importing a share', () => {
  it('keeps the console key and id of a full-access link through a save and a load', () => {
    const app = quiet();
    const [t] = app.addFromParsed({ ...parseKeyInput(V.full), sharedBy: 'Anna' });
    expect(t.share).toMatchObject({ id: 'bebef88c94a515c6' });
    expect(t.sharedBy).toBe('Anna');
    const saved = JSON.parse(JSON.stringify([serializeTag(t)]));
    const app2 = quiet();
    app2._loadSaved(saved);
    expect(app2.tags[0].share.uk).toBe(t.share.uk);
    expect(app2.tags[0].sharedBy).toBe('Anna');
    expect(app2.tags[0].chain.canCommand).toBe(true);
  });

  it('the same tag shared again replaces the old level instead of adding a second tag', () => {
    const app = quiet();
    app.addFromParsed(parseKeyInput(V.view));
    expect(app.tags[0].chain.canCommand).toBe(false);
    const out = app.addFromParsed(parseKeyInput(V.full));
    expect(app.tags).toHaveLength(1);
    expect(out[0].share.uk).toBeDefined();
    expect(app.tags[0].chain.canCommand).toBe(true);
  });

  it('a tag the browser already holds with its own K0 is not added as a share', () => {
    const app = quiet();
    const own = makeTag({ k0: V.k0, name: 'Mine', type: 'thing', mark: { color: 'red', symbol: 'stripe' } });
    app.addTags([own]);
    expect(app.addFromParsed(parseKeyInput(V.full))).toEqual([]);
    expect(app.tags).toHaveLength(1);
  });

  it('remembers who a link went to and forgets an entry on request', () => {
    const app = quiet();
    const [t] = app.addFromParsed(parseKeyInput(V.control));
    app.noteShare(t.id, 'Someone (link)', 'control', 3);
    expect(t.shares).toHaveLength(1);
    app.stopShare(t.id, t.shares[0].since);
    expect(t.shares).toEqual([]);
  });
});
