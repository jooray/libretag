import { describe, expect, it } from 'vitest';
import { openCache, sealCache, serializeTag, cacheKey } from '../src/lib/vault.js';

describe('sealed cache', () => {
  it('round-trips and refuses a wrong key', async () => {
    const k = cacheKey(new Uint8Array(64).fill(1)), other = cacheKey(new Uint8Array(64).fill(2));
    const data = { v: 1, tags: [serializeTag({ id: 'ab', name: 'Car', type: 'vehicle', mark: {}, k0: '00', positions: [{ n: 1 }], lastN: 1 })] };
    const blob = await sealCache(k, data);
    expect(blob).not.toContain('Car');
    expect(await openCache(k, blob)).toEqual(data);
    expect(await openCache(other, blob)).toBeNull();
    expect(await openCache(k, blob.slice(0, -4) + 'AAAA')).toBeNull();
  });
});
