import { afterEach, beforeEach, describe, expect, it } from 'bun:test';
import { parsePairingLink } from '@glimpse/api/pairing-link';
import { Option } from 'effect';
import { maxJsonBodyBytes } from './server.ts';
import {
  bearer,
  newPairingCode,
  openEvents,
  pairPhone,
  sendPhoto,
  sentEvents,
  startTestServer,
  type TestServer,
  testOrigin,
  testOwnerToken,
  testPhotoId,
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

  it('takes a good code however many wrong ones came before it', async () => {
    const code = await newPairingCode(server);
    const wrong = await Promise.all(
      Array.from({ length: 20 }, () => claim('WRONGWRONGWRONGWRONGWRON')),
    );
    expect(wrong.map((answer) => answer.status)).toEqual(
      Array.from({ length: 20 }, () => 404),
    );
    expect((await claim(code)).status).toBe(201);
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

  it('remembers paired phones across a restart', async () => {
    const { token } = await pairPhone(server);
    await server.stop();
    server = await startTestServer({ dataDirectory: server.dataDirectory });
    const me = await server.fetch('/v1/me', { headers: bearer(token) });
    expect(me.status).toBe(200);
  });
});

describe('an oversized claim', () => {
  it('refuses an oversized claim that states its length', async () => {
    const body = JSON.stringify({
      code: 'x'.repeat(maxJsonBodyBytes),
      deviceName: 'Pixel',
    });
    const response = await server.fetch('/v1/pairings/claim', {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'content-length': String(body.length),
      },
      body,
    });
    expect(response.status).toBe(413);
    expect(await response.json()).toMatchObject({ error: { tag: 'TooLarge' } });
  });

  it('stops reading an oversized claim that does not state its length', async () => {
    // Far more than the limit, so a server that reads it all is caught.
    const offered = 64 * maxJsonBodyBytes;
    let sent = 0;
    const chunk = new TextEncoder().encode('x'.repeat(4096));
    const body = new ReadableStream<Uint8Array>({
      start: (controller) => {
        controller.enqueue(new TextEncoder().encode('{"code":"'));
      },
      pull: (controller) => {
        sent += chunk.byteLength;
        if (sent > offered) {
          controller.close();
          return;
        }
        controller.enqueue(chunk);
      },
    });
    const response = await server.fetch('/v1/pairings/claim', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body,
      duplex: 'half',
    } as RequestInit);
    expect(response.status).toBe(413);
    expect(sent).toBeLessThan(offered);
  });
});

describe('removing a phone', () => {
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

  it('ends the event stream of a phone the owner removed, and no other', async () => {
    const removed = await pairPhone(server, 'Pixel');
    const kept = await pairPhone(server, 'Tablet');
    const [phoneEvents, keptEvents, ownerEvents] = await Promise.all([
      openEvents(server, removed.token),
      openEvents(server, kept.token),
      openEvents(server, testOwnerToken),
    ]);
    const opened = (text: string) => text.includes('event: waiting');
    await Promise.all(
      [phoneEvents, keptEvents, ownerEvents].map((events) =>
        events.readUntil(opened),
      ),
    );
    const response = await server.fetch(`/v1/devices/${removed.device.id}`, {
      method: 'DELETE',
      headers: owner,
    });
    expect(response.status).toBe(204);

    await sendPhoto(server, kept.token, testPhotoId(1));
    const photoSent = (text: string) =>
      sentEvents(text).some(({ event }) => event === 'photo');
    await Promise.all(
      [phoneEvents, keptEvents, ownerEvents].map((events) =>
        events.readUntil(photoSent),
      ),
    );
    expect(phoneEvents.ended()).toBe(true);
    expect(photoSent(phoneEvents.text())).toBe(false);
    for (const events of [keptEvents, ownerEvents]) {
      expect(events.ended()).toBe(false);
      expect(photoSent(events.text())).toBe(true);
    }
    await Promise.all([keptEvents.close(), ownerEvents.close()]);
  });
});
