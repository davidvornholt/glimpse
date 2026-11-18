import { afterEach, beforeEach, describe, expect, it } from 'bun:test';
import { parsePairingLink } from '@glimpse/api/pairing-link';
import { Option } from 'effect';
import {
  bearer,
  newPairingCode,
  pairPhone,
  startTestServer,
  type TestServer,
  testOrigin,
  testOwnerToken,
} from './testing.ts';

const pairingCode = /^[A-Z2-7]{24}$/u;

let server: TestServer;

beforeEach(async () => {
  server = await startTestServer();
});

afterEach(async () => {
  await server.close();
});

const owner = bearer(testOwnerToken);

const claim = (code: string, deviceName = 'Pixel') =>
  server.fetch('/v1/pairings/claim', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ code, deviceName }),
  });

describe('pairing a phone', () => {
  it('gives the owner a link to this server that expires', async () => {
    const response = await server.fetch('/v1/pairings', {
      method: 'POST',
      headers: owner,
    });
    expect(response.status).toBe(201);
    const { link, expiresAt } = (await response.json()) as {
      link: string;
      expiresAt: string;
    };
    const parsed = Option.getOrThrow(parsePairingLink(link));
    expect(parsed.server).toBe(testOrigin);
    expect(parsed.code).toMatch(pairingCode);
    const minutes = (Date.parse(expiresAt) - Date.now()) / 60_000;
    expect(minutes).toBeGreaterThan(9);
    expect(minutes).toBeLessThanOrEqual(10);
  });

  it('gives the phone a token that knows the phone', async () => {
    const { token, device } = await pairPhone(server, 'Pixel 6');
    expect(token).toStartWith('gd_');
    expect(device.name).toBe('Pixel 6');
    const me = await server.fetch('/v1/me', { headers: bearer(token) });
    expect(await me.json()).toMatchObject({
      kind: 'device',
      device: { id: device.id, name: 'Pixel 6' },
    });
  });

  it('takes a code typed in lower case with spaces around it', async () => {
    const code = await newPairingCode(server);
    const response = await claim(`  ${code.toLowerCase()} `);
    expect(response.status).toBe(201);
  });

  it('takes each code once', async () => {
    const code = await newPairingCode(server);
    expect((await claim(code)).status).toBe(201);
    const again = await claim(code);
    expect(again.status).toBe(404);
    expect(await again.json()).toMatchObject({ error: { tag: 'NotFound' } });
  });

  it('stops taking codes after ten wrong ones', async () => {
    const code = await newPairingCode(server);
    const wrong = await Promise.all(
      Array.from({ length: 10 }, () => claim('WRONGWRONGWRONGWRONGWRON')),
    );
    expect(wrong.map((answer) => answer.status)).toEqual(
      Array.from({ length: 10 }, () => 404),
    );
    const response = await claim(code);
    expect(response.status).toBe(429);
    expect(await response.json()).toMatchObject({
      error: { tag: 'RateLimited' },
    });
  });

  it('lets only the owner make codes and list phones', async () => {
    const { token } = await pairPhone(server);
    const phone = bearer(token);
    const pairing = await server.fetch('/v1/pairings', {
      method: 'POST',
      headers: phone,
    });
    expect(pairing.status).toBe(403);
    expect((await server.fetch('/v1/devices', { headers: phone })).status).toBe(
      403,
    );
  });

  it('lists phones without their tokens', async () => {
    const { token, device } = await pairPhone(server);
    const response = await server.fetch('/v1/devices', { headers: owner });
    const text = await response.text();
    expect(JSON.parse(text)).toMatchObject([{ id: device.id, name: 'Pixel' }]);
    expect(text).not.toContain(token);
    expect(text).not.toContain('token');
  });

  it('stops taking the token of a phone the owner removed', async () => {
    const { token, device } = await pairPhone(server);
    const removed = await server.fetch(`/v1/devices/${device.id}`, {
      method: 'DELETE',
      headers: owner,
    });
    expect(removed.status).toBe(204);
    const me = await server.fetch('/v1/me', { headers: bearer(token) });
    expect(me.status).toBe(401);
    const again = await server.fetch(`/v1/devices/${device.id}`, {
      method: 'DELETE',
      headers: owner,
    });
    expect(again.status).toBe(404);
  });

  it('remembers paired phones across a restart', async () => {
    const { token } = await pairPhone(server);
    await server.stop();
    server = await startTestServer({ dataDirectory: server.dataDirectory });
    const me = await server.fetch('/v1/me', { headers: bearer(token) });
    expect(me.status).toBe(200);
  });
});
