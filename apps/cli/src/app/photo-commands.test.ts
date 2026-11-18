import { afterEach, beforeEach, describe, expect, it, spyOn } from 'bun:test';
import { mkdtemp, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  pairPhone,
  registerPhoto,
  sendPhoto,
  testPhotoId,
  tinyJpeg,
} from '@glimpse/server/testing';
import { reported } from './cli.ts';
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

const sendOriginal = (id: string, body: Uint8Array<ArrayBuffer>) =>
  fixture.server.fetch(`/v1/photos/${id}/original`, {
    method: 'PUT',
    headers: { authorization: `Bearer ${phone}`, 'content-type': 'image/jpeg' },
    body,
  });

describe('glimpse recent', () => {
  it('lists the photos that arrived, the newest first', async () => {
    await sendPhoto(fixture.server, phone, first);
    await sendPhoto(fixture.server, phone, second, { note: 'page two' });
    const output = await runOk('recent', '--since', '1h');
    expect(output.nextCursor).toBeNull();
    expect(output.photos).toMatchObject([
      { id: second, note: 'page two', viewedAt: null, deviceName: 'Pixel' },
      { id: first, note: null },
    ]);
    expect(output.photos[0].takenAt).toBe('2026-11-18T09:30:00.000Z');
  });

  it('pages with the cursor it printed', async () => {
    await sendPhoto(fixture.server, phone, first);
    await sendPhoto(fixture.server, phone, second);
    const page = await runOk('recent', '--limit', '1');
    expect(page.photos.map((photo: { id: string }) => photo.id)).toEqual([
      second,
    ]);
    const next = await runOk(
      'recent',
      '--limit',
      '1',
      '--cursor',
      page.nextCursor,
    );
    expect(next.photos.map((photo: { id: string }) => photo.id)).toEqual([
      first,
    ]);
  });

  it('refuses a limit out of range without asking the server', async () => {
    const failure = await failureOf('recent', '--limit', '0');
    expect(failure._tag).toBe('InvalidInput');
    expect(fixture.sent).toEqual([]);
  });
});

describe('glimpse show', () => {
  it('shows photos, accepting ids in lower case', async () => {
    await sendPhoto(fixture.server, phone, first);
    const output = await runOk('show', first.toLowerCase());
    expect(output).toMatchObject({
      photos: [{ id: first, hasOriginal: false }],
    });
  });

  it('refuses what is not a photo id', async () => {
    const failure = await failureOf('show', '../devices');
    expect(failure._tag).toBe('InvalidInput');
    expect(fixture.sent).toEqual([]);
  });

  it("reports the server's refusal under the server's tag", async () => {
    const failure = await failureOf('show', first);
    expect(failure).toMatchObject({ _tag: 'ServerRejected', tag: 'NotFound' });
    expect(reported(failure)).toEqual({
      _tag: 'NotFound',
      message: failure.message,
    });
  });
});

describe('glimpse preview', () => {
  it('saves the preview privately and marks the photo viewed', async () => {
    await sendPhoto(fixture.server, phone, first);
    const output = await runOk('preview', first);
    const file = join(
      runtimeDirectory(),
      'glimpse',
      'previews',
      `${first}.jpg`,
    );
    expect(output).toEqual({ previews: [{ id: first, path: file }] });
    expect(new Uint8Array(await Bun.file(file).arrayBuffer())).toEqual(
      tinyJpeg(2048, 1536),
    );
    expect((await stat(file)).mode % 0o1000).toBe(0o600);
    const shown = await runOk('show', first);
    expect(shown.photos[0].viewedAt).not.toBeNull();
  });
});

describe('glimpse download', () => {
  let directory: string;

  beforeEach(async () => {
    directory = await mkdtemp(join(tmpdir(), 'glimpse-download-'));
  });
  afterEach(() => rm(directory, { recursive: true, force: true }));

  it('saves the original under its id', async () => {
    const original = tinyJpeg(4032, 3024);
    await sendPhoto(fixture.server, phone, first);
    await sendOriginal(first, original);
    const output = await runOk('download', first, '--to', directory);
    const file = join(directory, `${first}.jpg`);
    expect(output).toEqual({
      files: [{ id: first, path: file, bytes: original.length }],
    });
    expect(new Uint8Array(await Bun.file(file).arrayBuffer())).toEqual(
      original,
    );
  });

  it('never replaces a file', async () => {
    await sendPhoto(fixture.server, phone, first);
    await sendOriginal(first, tinyJpeg(4032, 3024));
    await Bun.write(join(directory, `${first}.jpg`), 'mine');
    const failure = await failureOf('download', first, '--to', directory);
    expect(failure._tag).toBe('FileNotSaved');
    expect(await Bun.file(join(directory, `${first}.jpg`)).text()).toBe('mine');
  });

  it('says when the phone has not sent the original yet', async () => {
    await sendPhoto(fixture.server, phone, first);
    const failure = await failureOf('download', first, '--to', directory);
    expect(failure._tag).toBe('FileNotSaved');
    expect(failure.message).toContain('has not sent the original');
  });

  it('needs an existing directory', async () => {
    const failure = await failureOf(
      'download',
      first,
      '--to',
      join(directory, 'missing'),
    );
    expect(failure._tag).toBe('InvalidInput');
  });
});

describe('glimpse note', () => {
  it('sets and clears the note', async () => {
    await sendPhoto(fixture.server, phone, first);
    expect((await runOk('note', first, 'the receipt')).note).toBe(
      'the receipt',
    );
    expect((await runOk('note', first, '--clear')).note).toBeNull();
  });

  it('needs either a note or --clear', async () => {
    expect((await failureOf('note', first))._tag).toBe('InvalidInput');
    expect((await failureOf('note', first, 'text', '--clear'))._tag).toBe(
      'InvalidInput',
    );
  });
});

describe('glimpse delete', () => {
  it('deletes photos from the server', async () => {
    await sendPhoto(fixture.server, phone, first);
    await registerPhoto(fixture.server, phone, second);
    expect(await runOk('delete', first, second)).toEqual({
      deleted: [first, second],
    });
    expect((await failureOf('show', first)).tag).toBe('NotFound');
  });
});
