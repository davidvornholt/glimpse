import { afterEach, beforeEach, describe, expect, it } from 'bun:test';
import { chmod, mkdir, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { DateTime } from 'effect';
import {
  bearer,
  decodePhoto,
  openEvents,
  pairPhone,
  registerPhoto,
  sendPhoto,
  sentEvents,
  startTestServer,
  type TestServer,
  testOwnerToken,
  testPhotoId,
} from './testing.ts';

let server: TestServer;
let phone: string;

beforeEach(async () => {
  server = await startTestServer();
  phone = (await pairPhone(server)).token;
});

afterEach(async () => {
  await server.close();
});

const owner = bearer(testOwnerToken);

const get = (path: string) => server.fetch(path, { headers: owner });

describe('listing photos', () => {
  it('pages through them newest first', async () => {
    // One after another, so they arrive in this order.
    await [1, 2, 3].reduce<Promise<unknown>>(
      (previous, n) =>
        previous.then(() => sendPhoto(server, phone, testPhotoId(n))),
      Promise.resolve(),
    );
    const first = (await (await get('/v1/photos?limit=2')).json()) as {
      photos: Array<{ id: string }>;
      nextCursor: string;
    };
    expect(first.photos.map((photo) => photo.id)).toEqual([
      testPhotoId(3),
      testPhotoId(2),
    ]);
    const second = await get(
      `/v1/photos?limit=2&before=${encodeURIComponent(first.nextCursor)}`,
    );
    expect(await second.json()).toMatchObject({
      photos: [{ id: testPhotoId(1) }],
      nextCursor: null,
    });
  });

  it('marks a photo viewed when the owner first fetches it', async () => {
    const id = testPhotoId(1);
    await sendPhoto(server, phone, id);
    const phoneFetch = await server.fetch(`/v1/photos/${id}/preview`, {
      headers: bearer(phone),
    });
    expect(phoneFetch.status).toBe(200);
    expect(
      decodePhoto(await (await get(`/v1/photos/${id}`)).json()).viewedAt,
    ).toBeNull();

    const preview = await get(`/v1/photos/${id}/preview`);
    expect(preview.headers.get('content-type')).toBe('image/jpeg');
    const viewed = decodePhoto(await (await get(`/v1/photos/${id}`)).json());
    expect(viewed.viewedAt).not.toBeNull();
  });
});

describe('waiting for a photo', () => {
  it('answers as soon as a new photo arrives', async () => {
    const after = new Date().toISOString();
    const waiting = get(`/v1/photos/wait?after=${after}&timeoutSeconds=10`);
    await Bun.sleep(50);
    await sendPhoto(server, phone, testPhotoId(1));
    const response = await waiting;
    expect(await response.json()).toMatchObject({
      photos: [{ id: testPhotoId(1) }],
    });
  });

  it('answers at once with a photo that came in the meantime', async () => {
    const after = new Date(Date.now() - 1000).toISOString();
    await sendPhoto(server, phone, testPhotoId(1));
    const response = await get(`/v1/photos/wait?after=${after}`);
    expect(await response.json()).toMatchObject({
      photos: [{ id: testPhotoId(1) }],
    });
  });

  it('answers with no photos when the time is up', async () => {
    const after = new Date().toISOString();
    const response = await get(
      `/v1/photos/wait?after=${after}&timeoutSeconds=0`,
    );
    expect(await response.json()).toEqual({ photos: [] });
  });

  it('is for the owner, not a phone', async () => {
    const after = new Date().toISOString();
    const response = await server.fetch(`/v1/photos/wait?after=${after}`, {
      headers: bearer(phone),
    });
    expect(response.status).toBe(403);
  });

  it('tells the event stream how many agents wait', async () => {
    const events = await server.fetch('/v1/events', { headers: bearer(phone) });
    expect(events.headers.get('content-type')).toContain('text/event-stream');
    const reader = events.body?.getReader();
    const first = await reader?.read();
    const text = new TextDecoder().decode(first?.value);
    expect(text).toContain('event: waiting');
    expect(text).toContain('{"count":0}');
    await reader?.cancel();
  });
});

describe('counting the agents that wait', () => {
  it('shows one wait as one until it is cancelled and its grace passes', async () => {
    await server.close();
    server = await startTestServer({ testClock: true });
    phone = (await pairPhone(server)).token;
    // A photo still being sent, whose deletion the stream reports after every
    // event before it.
    const marker = testPhotoId(1);
    await registerPhoto(server, phone, marker);
    const events = await openEvents(server, phone);
    const counts = () =>
      sentEvents(events.text()).flatMap(({ event, data }) =>
        event === 'waiting' ? [(data as { count: number }).count] : [],
      );
    const after = new Date().toISOString();
    const wait = (timeoutSeconds: number, signal?: AbortSignal) =>
      server.fetch(
        `/v1/photos/wait?after=${after}&timeoutSeconds=${timeoutSeconds}&waiter=agent-1`,
        { headers: owner, signal },
      );
    await events.readUntil(() => counts().length === 1);

    const first = wait(1);
    await events.readUntil(() => counts().length === 2);
    expect(counts()).toEqual([0, 1]);

    // The first request times out, and the agent asks again within the grace.
    await server.adjustClock('1 second');
    expect(await (await first).json()).toEqual({ photos: [] });
    const cancel = new AbortController();
    const second = wait(60, cancel.signal);
    await Bun.sleep(50);
    await server.adjustClock('5 seconds');
    await server.fetch(`/v1/photos/${marker}`, {
      method: 'DELETE',
      headers: owner,
    });
    await events.readUntil((text) => text.includes('event: photo-deleted'));
    expect(counts()).toEqual([0, 1]);

    cancel.abort();
    await second;
    // Lets the grace the cancelled wait started begin before the clock moves.
    await Bun.sleep(50);
    await server.adjustClock('5 seconds');
    await events.readUntil(() => counts().length === 3);
    expect(counts()).toEqual([0, 1, 0]);
    await events.close();
  });
});

describe('changing photos', () => {
  it('sets and clears the note', async () => {
    const id = testPhotoId(1);
    await sendPhoto(server, phone, id, { note: 'the receipt' });
    const patch = (note: string | null) =>
      server.fetch(`/v1/photos/${id}`, {
        method: 'PATCH',
        headers: { ...owner, 'content-type': 'application/json' },
        body: JSON.stringify({ note }),
      });
    expect(decodePhoto(await (await patch('page two')).json()).note).toBe(
      'page two',
    );
    expect(decodePhoto(await (await patch(null)).json()).note).toBeNull();
  });

  it('deletes a photo and its files', async () => {
    const id = testPhotoId(1);
    await sendPhoto(server, phone, id);
    const removed = await server.fetch(`/v1/photos/${id}`, {
      method: 'DELETE',
      headers: owner,
    });
    expect(removed.status).toBe(204);
    expect((await get(`/v1/photos/${id}`)).status).toBe(404);
    expect((await get(`/v1/photos/${id}/preview`)).status).toBe(404);
  });
});

const restart = async () => {
  await server.stop();
  server = await startTestServer({ dataDirectory: server.dataDirectory });
};

const backdate = async (id: string, field: string) => {
  const file = join(server.dataDirectory, 'photos', id, 'meta.json');
  const record = JSON.parse(await readFile(file, 'utf8'));
  record[field] = DateTime.formatIso(
    DateTime.subtract(DateTime.nowUnsafe(), { days: 2 }),
  );
  await writeFile(file, JSON.stringify(record));
};

const exists = (file: string) =>
  stat(file).then(
    () => true,
    () => false,
  );

const onDisk = (id: string) => exists(join(server.dataDirectory, 'photos', id));

/** Waits up to two seconds for the photo's folder to go. */
const goneFromDisk = async (id: string, deadline = Date.now() + 2000) => {
  if (!(await onDisk(id)) || Date.now() > deadline) {
    return !(await onDisk(id));
  }
  await Bun.sleep(10);
  return goneFromDisk(id, deadline);
};

/**
 * Puts a folder that nothing may be deleted from in the photo's folder, which
 * makes deleting the photo fail until the returned `unlock` is called.
 */
const lock = async (id: string) => {
  const locked = join(server.dataDirectory, 'photos', id, 'locked');
  await mkdir(locked);
  await writeFile(join(locked, 'file'), '');
  await chmod(locked, 0o500);
  return {
    locked,
    unlock: () => chmod(locked, 0o700).catch(() => undefined),
  };
};

describe('retention', () => {
  it('keeps photos across a restart', async () => {
    await sendPhoto(server, phone, testPhotoId(1));
    await restart();
    expect((await get(`/v1/photos/${testPhotoId(1)}`)).status).toBe(200);
  });

  it('deletes a photo once it expires', async () => {
    const id = testPhotoId(1);
    const photo = await sendPhoto(server, phone, id);
    const days =
      (DateTime.toEpochMillis(photo.expiresAt) -
        DateTime.toEpochMillis(photo.createdAt)) /
      86_400_000;
    expect(days).toBe(30);
    await backdate(id, 'expiresAt');
    await restart();
    expect((await get(`/v1/photos/${id}`)).status).toBe(404);
  });

  it('deletes a photo whose preview never came after a day', async () => {
    const id = testPhotoId(1);
    await registerPhoto(server, phone, id);
    await backdate(id, 'createdAt');
    await restart();
    expect((await get(`/v1/photos/${id}`)).status).toBe(404);
  });

  it('deletes the other photos when one fails, and that one an hour later', async () => {
    await server.close();
    server = await startTestServer({ testClock: true, retentionDays: 1 });
    phone = (await pairPhone(server)).token;
    const stuck = testPhotoId(1);
    const other = testPhotoId(2);
    await sendPhoto(server, phone, stuck);
    await sendPhoto(server, phone, other);
    const { locked, unlock } = await lock(stuck);
    try {
      await expect(rm(join(locked, 'file'))).rejects.toThrow('EACCES');

      await server.adjustClock('1 day');
      expect(await goneFromDisk(other)).toBe(true);
      expect(await onDisk(stuck)).toBe(true);
      expect((await get(`/v1/photos/${stuck}`)).status).toBe(200);

      await unlock();
      await server.adjustClock('1 hour');
      expect(await goneFromDisk(stuck)).toBe(true);
      expect((await get(`/v1/photos/${stuck}`)).status).toBe(404);
    } finally {
      // Lets the test's data be deleted, however the test went.
      await unlock();
    }
  });

  it('keeps a photo it could not delete across a restart, and deletes it at a later start', async () => {
    const id = testPhotoId(1);
    await sendPhoto(server, phone, id);
    await backdate(id, 'expiresAt');
    const meta = join(server.dataDirectory, 'photos', id, 'meta.json');
    const { locked, unlock } = await lock(id);
    try {
      await expect(rm(join(locked, 'file'))).rejects.toThrow('EACCES');

      await restart();
      expect((await get(`/v1/photos/${id}`)).status).toBe(200);
      expect(await exists(meta)).toBe(true);
      await restart();
      expect((await get(`/v1/photos/${id}`)).status).toBe(200);

      await unlock();
      await restart();
      expect((await get(`/v1/photos/${id}`)).status).toBe(404);
      expect(await onDisk(id)).toBe(false);
    } finally {
      // Lets the test's data be deleted, however the test went.
      await unlock();
    }
  });
});

describe('starting up', () => {
  it('serves the photos it can read beside a folder it cannot', async () => {
    const healthy = testPhotoId(1);
    const unreadable = testPhotoId(2);
    await sendPhoto(server, phone, healthy);
    await sendPhoto(server, phone, unreadable);
    const folder = join(server.dataDirectory, 'photos', unreadable);
    await chmod(folder, 0o000);
    try {
      await restart();
      expect((await server.fetch('/healthz')).status).toBe(200);
      expect((await get(`/v1/photos/${healthy}`)).status).toBe(200);
      expect((await get(`/v1/photos/${unreadable}`)).status).toBe(404);
    } finally {
      await chmod(folder, 0o700);
    }
  });

  it('serves a photo whose cut-off upload it cannot clear away', async () => {
    const id = testPhotoId(1);
    await sendPhoto(server, phone, id);
    const folder = join(server.dataDirectory, 'photos', id);
    await writeFile(join(folder, 'original.tmp'), '');
    await chmod(folder, 0o500);
    try {
      await restart();
      expect((await get(`/v1/photos/${id}`)).status).toBe(200);
    } finally {
      await chmod(folder, 0o700);
    }
  });
});
