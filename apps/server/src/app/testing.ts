import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { BunHttpServer } from '@effect/platform-bun';
import { ClaimResultSchema, PairingSchema } from '@glimpse/api/device';
import { parsePairingLink } from '@glimpse/api/pairing-link';
import { defaultRetentionDays, PhotoSchema } from '@glimpse/api/photo';
import {
  type Context,
  type Duration,
  Effect,
  Layer,
  Option,
  Schema,
} from 'effect';
import { HttpRouter } from 'effect/http';
import { TestClock } from 'effect/testing';
import { ServerConfig } from '../shared/config.ts';
import { tokenDigest } from '../shared/tokens.ts';
import { appLayer } from './server.ts';

/** The address a test server says it has. Nothing listens there. */
export const testOrigin = 'https://glimpse.example.com';
export const testOwnerToken = 'test-owner-token';

export type TestServer = {
  readonly dataDirectory: string;
  /** Calls the server in this process, the way `fetch` would over a network. */
  readonly fetch: (
    input: string | URL | Request,
    init?: RequestInit,
  ) => Promise<Response>;
  /** Stops the server and keeps its data, so another can start on it. */
  readonly stop: () => Promise<void>;
  /** Stops the server and deletes its data. */
  readonly close: () => Promise<void>;
  /**
   * Moves the server's clock on and wakes what was due by then. Only for a
   * server started with `testClock`.
   */
  readonly adjustClock: (duration: Duration.Input) => Promise<void>;
};

export type TestServerOptions = {
  /** Starts on the data an earlier server left, as after a restart. */
  readonly dataDirectory?: string;
  readonly retentionDays?: number;
  /**
   * Runs the server on a clock that starts at the current time and moves only
   * with `adjustClock`, so a test can let hours pass at once.
   */
  readonly testClock?: boolean;
};

/** The whole server, without a socket, on a fresh data directory. */
export const startTestServer = async (
  options: TestServerOptions = {},
): Promise<TestServer> => {
  const dataDirectory =
    options.dataDirectory ??
    (await mkdtemp(join(tmpdir(), 'glimpse-server-test-')));
  const config = ServerConfig.layer({
    dataDirectory,
    publicUrl: testOrigin,
    ownerTokenDigests: [tokenDigest(testOwnerToken)],
    retentionDays: options.retentionDays ?? defaultRetentionDays,
  });
  let clock: Context.Context<TestClock.TestClock> | undefined;
  const testClock = TestClock.layer().pipe(
    Layer.tap((context) => {
      clock = context;
      return TestClock.setTime(Date.now()).pipe(Effect.provideContext(context));
    }),
  );
  const app = appLayer(config).pipe(
    Layer.provide(BunHttpServer.layerHttpServices),
  );
  const { handler, dispose } = HttpRouter.toWebHandler(
    options.testClock === true ? app.pipe(Layer.provideMerge(testClock)) : app,
    { disableLogger: true },
  );
  const fetch = (input: string | URL | Request, init?: RequestInit) =>
    handler(
      input instanceof Request
        ? input
        : new Request(new URL(input, testOrigin), init),
    );
  return {
    dataDirectory,
    fetch,
    stop: dispose,
    close: async () => {
      await dispose();
      await rm(dataDirectory, { recursive: true, force: true });
    },
    adjustClock: (duration) => {
      if (clock === undefined) {
        throw new Error(
          'Start the server with testClock, and call it once, to move its clock.',
        );
      }
      return Effect.runPromise(
        TestClock.adjust(duration).pipe(Effect.provideContext(clock)),
      );
    },
  };
};

// A JPEG needs no more than its start, a frame header with the size, and its
// end for the server to accept it as a preview. The bytes are written as
// Latin-1 text, one character per byte.
const startOfImage = '\xff\xd8';
// The marker, the segment's length, and eight-bit samples.
const frameHeader = '\xff\xc0\x00\x11\x08';
const threeComponents = '\x03\x01\x22\x00\x02\x11\x01\x03\x11\x01';
const endOfImage = '\xff\xd9';
const byteValues = 256;

const uint16 = (value: number) =>
  String.fromCharCode(Math.floor(value / byteValues), value % byteValues);

/** The smallest JPEG the server reads as `width`×`height` pixels. */
export const tinyJpeg = (width: number, height: number) =>
  Uint8Array.from(
    `${startOfImage}${frameHeader}${uint16(height)}${uint16(width)}${threeComponents}${endOfImage}`,
    (character) => character.charCodeAt(0),
  );

export const bearer = (token: string) => ({
  authorization: `Bearer ${token}`,
});

const decodeClaim = Schema.decodeUnknownSync(
  Schema.toCodecJson(ClaimResultSchema),
);
const decodePairing = Schema.decodeUnknownSync(
  Schema.toCodecJson(PairingSchema),
);
export const decodePhoto = Schema.decodeUnknownSync(
  Schema.toCodecJson(PhotoSchema),
);

/** Makes a pairing code as the owner and returns the code alone. */
export const newPairingCode = async (server: TestServer) => {
  const response = await server.fetch('/v1/pairings', {
    method: 'POST',
    headers: bearer(testOwnerToken),
  });
  const pairing = decodePairing(await response.json());
  return Option.getOrThrow(parsePairingLink(pairing.link)).code;
};

/** Pairs a phone the way the app does and returns its token and record. */
export const pairPhone = async (server: TestServer, deviceName = 'Pixel') => {
  const response = await server.fetch('/v1/pairings/claim', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ code: await newPairingCode(server), deviceName }),
  });
  return decodeClaim(await response.json());
};

const sampleWidth = 4032;
const sampleHeight = 3024;
const previewWidth = 2048;
const previewHeight = 1536;

/** What a phone registers for a camera photo, before its own changes. */
export const sampleMetadata = {
  takenAt: '2026-11-18T09:30:00.000Z',
  source: 'camera',
  width: sampleWidth,
  height: sampleHeight,
  originalType: 'image/jpeg',
} as const;

const idSuffixLength = 2;

/** A photo id, a ULID, that differs from the others by `n`. */
export const testPhotoId = (n: number) =>
  `01JD3V8Q2W5X7Y9Z0A1B2C3D${String(n).padStart(idSuffixLength, '0')}`;

export const registerPhoto = (
  server: TestServer,
  token: string,
  id: string,
  metadata: Record<string, unknown> = {},
) =>
  server.fetch(`/v1/photos/${id}`, {
    method: 'PUT',
    headers: { ...bearer(token), 'content-type': 'application/json' },
    body: JSON.stringify({ ...sampleMetadata, ...metadata }),
  });

export const putPreview = (
  server: TestServer,
  token: string,
  id: string,
  body: Uint8Array<ArrayBuffer> = tinyJpeg(previewWidth, previewHeight),
) =>
  server.fetch(`/v1/photos/${id}/preview`, {
    method: 'PUT',
    headers: { ...bearer(token), 'content-type': 'image/jpeg' },
    body,
  });

const readMillis = 2000;

/** A `GET /v1/events` held open, and what it has sent so far. */
export type EventStream = {
  readonly text: () => string;
  /** Whether the server has ended the stream. */
  readonly ended: () => boolean;
  /**
   * Reads until `done` holds for the text so far, the stream ends, or two
   * seconds pass, whichever comes first.
   */
  readonly readUntil: (done: (text: string) => boolean) => Promise<void>;
  readonly close: () => Promise<void>;
};

export const openEvents = async (
  server: TestServer,
  token: string,
): Promise<EventStream> => {
  const response = await server.fetch('/v1/events', { headers: bearer(token) });
  const reader = response.body?.getReader();
  if (reader === undefined) {
    throw new Error(
      `The event stream answered ${response.status} and no body.`,
    );
  }
  const decoder = new TextDecoder();
  let text = '';
  let ended = false;
  // One read at a time: a read the time ran out on is kept for the next call,
  // so no chunk is lost.
  let pending: ReturnType<typeof reader.read> | undefined;
  const readUntil = async (
    done: (text: string) => boolean,
    deadline: number,
  ): Promise<void> => {
    if (ended || done(text)) {
      return;
    }
    pending ??= reader.read();
    const result = await Promise.race([
      pending,
      Bun.sleep(Math.max(0, deadline - Date.now())).then(() => undefined),
    ]);
    if (result === undefined) {
      return;
    }
    pending = undefined;
    if (result.done) {
      ended = true;
      return;
    }
    text += decoder.decode(result.value, { stream: true });
    return readUntil(done, deadline);
  };
  return {
    text: () => text,
    ended: () => ended,
    readUntil: (done) => readUntil(done, Date.now() + readMillis),
    close: () => reader.cancel(),
  };
};

/** The events in what an event stream sent, each with its data parsed. */
export const sentEvents = (text: string) =>
  text.split('\n\n').flatMap((block) => {
    const lines = block.split('\n');
    const field = (name: string) => {
      const prefix = `${name}: `;
      return lines
        .find((line) => line.startsWith(prefix))
        ?.slice(prefix.length);
    };
    const event = field('event');
    const data = field('data');
    return event === undefined || data === undefined
      ? []
      : [{ event, data: JSON.parse(data) as unknown }];
  });

/** Registers a photo and sends its preview, which makes it ready. */
export const sendPhoto = async (
  server: TestServer,
  token: string,
  id: string,
  metadata: Record<string, unknown> = {},
) => {
  await registerPhoto(server, token, id, metadata);
  const response = await putPreview(server, token, id);
  return decodePhoto(await response.json());
};
