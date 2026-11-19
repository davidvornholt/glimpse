import { afterEach, beforeEach, describe, expect, it, spyOn } from 'bun:test';
import { join } from 'node:path';
import { pairPhone, sendPhoto, testPhotoId } from '@glimpse/server/testing';
import {
  failureOf,
  fixture,
  runOk,
  runtimeDirectory,
  setUp,
  tearDown,
} from './command-fixtures.ts';

let phone: string;

beforeEach(async () => {
  await setUp(spyOn);
  phone = (await pairPhone(fixture.server)).token;
});
afterEach(tearDown);

const first = testPhotoId(1);
const second = testPhotoId(2);

const previewOf = (id: string) =>
  join(runtimeDirectory(), 'glimpse', 'previews', `${id}.jpg`);

const waitRequests = () =>
  fixture.sent.filter((sent) => sent.path.startsWith('/v1/photos/wait'));

describe('glimpse wait', () => {
  it('returns a photo sent while it waits, with its preview', async () => {
    const waiting = runOk('wait', '--settle', '0');
    await Bun.sleep(100);
    await sendPhoto(fixture.server, phone, first, { note: 'the receipt' });
    const output = await waiting;
    expect(output).toMatchObject({
      timedOut: false,
      photos: [{ id: first, note: 'the receipt', preview: previewOf(first) }],
    });
    expect(await Bun.file(previewOf(first)).exists()).toBe(true);
  });

  it('returns at once a photo taken shortly before', async () => {
    await sendPhoto(fixture.server, phone, first);
    const output = await runOk('wait', '--settle', '0');
    expect(output.photos.map((photo: { id: string }) => photo.id)).toEqual([
      first,
    ]);
  });

  it('skips photos an agent already looked at', async () => {
    await sendPhoto(fixture.server, phone, first);
    await runOk('preview', first);
    const output = await runOk('wait', '--timeout', '0');
    expect(output.photos).toEqual([]);
  });

  it('says how to go on when the time is up', async () => {
    const output = await runOk('wait', '--timeout', '0', '--since', '1h');
    expect(output.timedOut).toBe(true);
    expect(output.photos).toEqual([]);
    expect(output.next).toContain('glimpse wait --since 20');
  });

  it('keeps listening for the next page of a scan', async () => {
    const waiting = runOk('wait', '--settle', '2');
    await Bun.sleep(100);
    await sendPhoto(fixture.server, phone, first);
    await Bun.sleep(500);
    await sendPhoto(fixture.server, phone, second);
    const output = await waiting;
    expect(output.photos.map((photo: { id: string }) => photo.id)).toEqual([
      first,
      second,
    ]);
  });

  it('uses one waiter for all its requests', async () => {
    await runOk('wait', '--timeout', '0');
    const waiters = new Set(
      waitRequests().map((sent) =>
        new URL(sent.path, 'https://x').searchParams.get('waiter'),
      ),
    );
    expect(waiters.size).toBe(1);
  });

  it('refuses a timeout over ten minutes', async () => {
    const failure = await failureOf('wait', '--timeout', '601');
    expect(failure._tag).toBe('InvalidInput');
  });
});
