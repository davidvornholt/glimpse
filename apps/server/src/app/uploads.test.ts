import { afterEach, beforeEach, describe, expect, it } from 'bun:test';
import { GlimpseApi } from '@glimpse/api/api';
import { Authorization } from '@glimpse/api/authorization';
import { DateTime, Effect } from 'effect';
import { FetchHttpClient, HttpClientRequest } from 'effect/http';
import { HttpApiClient, HttpApiMiddleware } from 'effect/http-api';
import { maxJsonBodyBytes } from './server.ts';
import {
  bearer,
  decodePhoto,
  pairPhone,
  putPreview,
  registerPhoto,
  sampleMetadata,
  startTestServer,
  type TestServer,
  testOrigin,
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

/** A JPEG with segments, written as Latin-1 text, put right after its start. */
const afterStart = (image: Uint8Array<ArrayBuffer>, segments: string) => {
  const extra = Uint8Array.from(segments, (character) =>
    character.charCodeAt(0),
  );
  const joined = new Uint8Array(image.length + extra.length);
  joined.set(image.subarray(0, 2));
  joined.set(extra, 2);
  joined.set(image.subarray(2), 2 + extra.length);
  return joined;
};

const keptPreview = async () => {
  const response = await server.fetch(`/v1/photos/${id}/preview`, {
    headers: bearer(testOwnerToken),
  });
  return new Uint8Array(await response.arrayBuffer());
};

describe('registering a photo', () => {
  it('answers 201 the first time and 200 for the same details', async () => {
    const first = await registerPhoto(server, phone, id);
    expect(first.status).toBe(201);
    const photo = decodePhoto(await first.json());
    expect(photo.readyAt).toBeNull();
    expect(photo.deviceName).toBe('Pixel');
    expect((await registerPhoto(server, phone, id)).status).toBe(200);
  });

  it('answers both times in a way a client made from the contract reads', async () => {
    const client = HttpApiClient.make(GlimpseApi, { baseUrl: testOrigin });
    const authorization = HttpApiMiddleware.layerClient(
      Authorization,
      ({ request, next }) =>
        next(HttpClientRequest.bearerToken(request, phone)),
    );
    const register = Effect.flatMap(client, (api) =>
      api.photos.register({
        params: { id },
        payload: {
          ...sampleMetadata,
          takenAt: DateTime.makeUnsafe(sampleMetadata.takenAt),
        },
      }),
    );
    const [first, again] = await Effect.runPromise(
      Effect.all([register, register]).pipe(
        Effect.provide(FetchHttpClient.layer),
        Effect.provide(authorization),
        Effect.provideService(
          FetchHttpClient.Fetch,
          server.fetch as typeof globalThis.fetch,
        ),
      ),
    );
    expect(first.id).toBe(id);
    expect(again).toEqual(first);
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
    const tagged = afterStart(clean, '\xff\xe1\x00\x0cExif\x00\x00GPS!');
    const response = await putPreview(server, phone, id, tagged);
    expect(decodePhoto(await response.json()).previewBytes).toBe(clean.length);
    expect(await keptPreview()).toEqual(clean);
  });

  it('keeps the JFIF header without its thumbnail, Adobe’s fixed fields, and no other APP0, such as Canon’s with the owner', async () => {
    await registerPhoto(server, phone, id);
    // The JFIF header (version 1.2, square pixels) with a 1×1 thumbnail, one
    // red RGB pixel, after it, and Adobe's segment (version 100, no flags,
    // transform 1) with two more bytes.
    const jfif =
      '\xff\xe0\x00\x13JFIF\x00\x01\x02\x00\x00\x01\x00\x01\x01\x01\xff\x00\x00';
    const adobe = '\xff\xee\x00\x10Adobe\x00\x64\x00\x00\x00\x00\x01\xab\xcd';
    const owner = 'II\x1a\x00\x00\x00HEAPJPGMOwner: Jane Example';
    const ciff = `\xff\xe0\x00${String.fromCharCode(owner.length + 2)}${owner}`;
    // The 18-byte JFIF header, sized for no thumbnail, and Adobe's 16 bytes.
    const cleanJfif =
      '\xff\xe0\x00\x10JFIF\x00\x01\x02\x00\x00\x01\x00\x01\x00\x00';
    const cleanAdobe = '\xff\xee\x00\x0eAdobe\x00\x64\x00\x00\x00\x00\x01';
    const clean = afterStart(tinyJpeg(2048, 1536), `${cleanJfif}${cleanAdobe}`);
    const response = await putPreview(
      server,
      phone,
      id,
      afterStart(tinyJpeg(2048, 1536), `${jfif}${ciff}${adobe}`),
    );
    expect(response.status).toBe(200);
    const photo = decodePhoto(await response.json());
    expect(photo.previewBytes).toBe(clean.length);
    const kept = await keptPreview();
    expect(kept).toEqual(clean);
    expect(new TextDecoder('latin1').decode(kept)).not.toContain('Jane');
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

  it('takes one far larger than a JSON body may be', async () => {
    await registerPhoto(server, phone, id);
    await putPreview(server, phone, id);
    const large = new Uint8Array(16 * maxJsonBodyBytes);
    large.set(original);
    const response = await putOriginal(large, 'image/jpeg');
    expect(response.status).toBe(200);
    expect(decodePhoto(await response.json()).originalBytes).toBe(large.length);
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
