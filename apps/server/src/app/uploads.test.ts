import { afterEach, beforeEach, describe, expect, it } from 'bun:test';
import {
  bearer,
  decodePhoto,
  pairPhone,
  putPreview,
  registerPhoto,
  startTestServer,
  type TestServer,
  testOwnerToken,
  testPhotoId,
  tinyJpeg,
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

const id = testPhotoId(1);

const putOriginal = (body: Uint8Array<ArrayBuffer>, contentType: string) =>
  server.fetch(`/v1/photos/${id}/original`, {
    method: 'PUT',
    headers: { ...bearer(phone), 'content-type': contentType },
    body,
  });

describe('registering a photo', () => {
  it('answers 201 the first time and 200 for the same details', async () => {
    const first = await registerPhoto(server, phone, id);
    expect(first.status).toBe(201);
    const photo = decodePhoto(await first.json());
    expect(photo.readyAt).toBeNull();
    expect(photo.deviceName).toBe('Pixel');
    expect((await registerPhoto(server, phone, id)).status).toBe(200);
  });

  it('refuses other details under the same id', async () => {
    await registerPhoto(server, phone, id);
    const response = await registerPhoto(server, phone, id, { width: 10 });
    expect(response.status).toBe(409);
    expect(await response.json()).toMatchObject({ error: { tag: 'Conflict' } });
  });

  it('refuses an id that is not a ULID', async () => {
    const response = await registerPhoto(server, phone, 'photo-1');
    expect(response.status).toBe(400);
    expect(await response.text()).toContain('ULID');
  });

  it('is for phones, not the owner', async () => {
    const response = await registerPhoto(server, testOwnerToken, id);
    expect(response.status).toBe(403);
  });

  it('keeps a photo from the list until its preview arrives', async () => {
    await registerPhoto(server, phone, id);
    const list = await server.fetch('/v1/photos', {
      headers: bearer(testOwnerToken),
    });
    expect(await list.json()).toEqual({ photos: [], nextCursor: null });
  });
});

describe('sending the preview', () => {
  it('makes the photo ready', async () => {
    await registerPhoto(server, phone, id);
    const response = await putPreview(server, phone, id);
    expect(response.status).toBe(200);
    const photo = decodePhoto(await response.json());
    expect(photo.readyAt).not.toBeNull();
    expect(photo.previewBytes).toBe(tinyJpeg(2048, 1536).length);
  });

  it('keeps it without camera metadata', async () => {
    await registerPhoto(server, phone, id);
    const clean = tinyJpeg(2048, 1536);
    const exif = Uint8Array.from(
      '\xff\xe1\x00\x0cExif\x00\x00GPS!',
      (character) => character.charCodeAt(0),
    );
    const tagged = new Uint8Array(clean.length + exif.length);
    tagged.set(clean.subarray(0, 2));
    tagged.set(exif, 2);
    tagged.set(clean.subarray(2), 2 + exif.length);
    const response = await putPreview(server, phone, id, tagged);
    expect(decodePhoto(await response.json()).previewBytes).toBe(clean.length);
    const kept = await server.fetch(`/v1/photos/${id}/preview`, {
      headers: bearer(testOwnerToken),
    });
    expect(new Uint8Array(await kept.arrayBuffer())).toEqual(clean);
  });

  it('needs the photo registered first', async () => {
    expect((await putPreview(server, phone, id)).status).toBe(404);
  });

  it('may come only from the phone that registered the photo', async () => {
    await registerPhoto(server, phone, id);
    const other = (await pairPhone(server, 'Tablet')).token;
    const response = await putPreview(server, other, id);
    expect(response.status).toBe(403);
  });

  it('refuses what is not a JPEG', async () => {
    await registerPhoto(server, phone, id);
    const response = await putPreview(
      server,
      phone,
      id,
      new TextEncoder().encode('not an image'),
    );
    expect(response.status).toBe(400);
    expect(await response.json()).toEqual({
      error: {
        tag: 'InvalidRequest',
        message: 'The preview is not a whole JPEG.',
      },
    });
  });

  it('refuses a preview larger than 2048 px', async () => {
    await registerPhoto(server, phone, id);
    const response = await putPreview(server, phone, id, tinyJpeg(4032, 3024));
    expect(response.status).toBe(400);
    expect(await response.text()).toContain('4032×3024');
  });

  it('refuses a body larger than 4 MiB', async () => {
    await registerPhoto(server, phone, id);
    const huge = new Uint8Array(4 * 1024 * 1024 + 1);
    huge.set(tinyJpeg(100, 100));
    const response = await putPreview(server, phone, id, huge);
    expect(response.status).toBe(413);
    expect(await response.json()).toMatchObject({ error: { tag: 'TooLarge' } });
  });

  it('refuses another content type', async () => {
    await registerPhoto(server, phone, id);
    const response = await server.fetch(`/v1/photos/${id}/preview`, {
      method: 'PUT',
      headers: { ...bearer(phone), 'content-type': 'image/png' },
      body: tinyJpeg(100, 100),
    });
    expect(response.status).toBe(415);
    expect(await response.json()).toMatchObject({
      error: { tag: 'InvalidRequest' },
    });
  });
});

describe('sending the original', () => {
  const original = tinyJpeg(4032, 3024);

  it('keeps it for download', async () => {
    await registerPhoto(server, phone, id);
    await putPreview(server, phone, id);
    const response = await putOriginal(original, 'image/jpeg');
    expect(response.status).toBe(200);
    const photo = decodePhoto(await response.json());
    expect(photo.hasOriginal).toBe(true);
    expect(photo.originalBytes).toBe(original.length);

    const download = await server.fetch(`/v1/photos/${id}/original`, {
      headers: bearer(testOwnerToken),
    });
    expect(download.status).toBe(200);
    expect(download.headers.get('content-type')).toBe('image/jpeg');
    expect(download.headers.get('cache-control')).toContain('private');
    expect(new Uint8Array(await download.arrayBuffer())).toEqual(original);
  });

  it('must have the type the photo was registered with', async () => {
    await registerPhoto(server, phone, id);
    const response = await putOriginal(original, 'image/png');
    expect(response.status).toBe(400);
    expect(await response.text()).toContain('registered as image/jpeg');
  });

  it('must look like that type', async () => {
    await registerPhoto(server, phone, id);
    const response = await putOriginal(
      new TextEncoder().encode('just some text'),
      'image/jpeg',
    );
    expect(response.status).toBe(400);
    const photo = await server.fetch(`/v1/photos/${id}`, {
      headers: bearer(testOwnerToken),
    });
    expect(decodePhoto(await photo.json()).hasOriginal).toBe(false);
  });

  it('is not there before the phone sends it', async () => {
    await registerPhoto(server, phone, id);
    await putPreview(server, phone, id);
    const download = await server.fetch(`/v1/photos/${id}/original`, {
      headers: bearer(testOwnerToken),
    });
    expect(download.status).toBe(404);
  });
});
