import { afterEach, beforeEach, describe, expect, it, spyOn } from 'bun:test';
import { parsePairingLink } from '@glimpse/api/pairing-link';
import { pairPhone, testOrigin } from '@glimpse/server/testing';
import { Option } from 'effect';
import {
  allPrinted,
  failureOf,
  fixture,
  runOk,
  setUp,
  tearDown,
} from './command-fixtures.ts';

beforeEach(() => setUp(spyOn));
afterEach(tearDown);

const pngSignature = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

describe('glimpse pair', () => {
  it('prints a link a phone can pair with, and its QR code as a PNG', async () => {
    const output = await runOk('pair');
    const link = Option.getOrThrow(parsePairingLink(output.link));
    expect(link.server).toBe(testOrigin);
    expect(Date.parse(output.expiresAt)).toBeGreaterThan(Date.now());

    const png = new Uint8Array(await Bun.file(output.qr).arrayBuffer());
    expect([...png.slice(0, pngSignature.length)]).toEqual(pngSignature);
    const header = new DataView(png.buffer, 16, 8);
    expect(header.getUint32(0)).toBe(header.getUint32(4));
    expect(header.getUint32(0) % 10).toBe(0);

    const claim = await fixture.server.fetch('/v1/pairings/claim', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ code: link.code, deviceName: 'Pixel' }),
    });
    expect(claim.status).toBe(201);
  });
});

describe('glimpse devices', () => {
  it('lists the paired phones without their tokens', async () => {
    const { token, device } = await pairPhone(fixture.server, 'Pixel 6');
    const output = await runOk('devices', 'list');
    expect(output).toMatchObject({
      phones: [{ id: device.id, name: 'Pixel 6' }],
    });
    expect(allPrinted()).not.toContain(token);
  });

  it('unpairs a phone', async () => {
    const { token, device } = await pairPhone(fixture.server);
    expect(await runOk('devices', 'remove', device.id)).toEqual({
      removed: device.id,
    });
    const me = await fixture.server.fetch('/v1/me', {
      headers: { authorization: `Bearer ${token}` },
    });
    expect(me.status).toBe(401);
    const again = await failureOf('devices', 'remove', device.id);
    expect(again.tag).toBe('NotFound');
  });

  it('refuses what is not a device id', async () => {
    const failure = await failureOf('devices', 'remove', '../photos');
    expect(failure._tag).toBe('InvalidInput');
    expect(fixture.sent).toEqual([]);
  });
});
