import { GlimpseApi } from '@glimpse/api/api';
import { CurrentCaller } from '@glimpse/api/caller';
import { PhotoSchema } from '@glimpse/api/photo';
import { DateTime, Effect, FileSystem, PubSub, Schema } from 'effect';
import { HttpServerResponse } from 'effect/http';
import { HttpApiBuilder } from 'effect/http-api';
import { requireDevice, requireOwner } from '../../shared/callers.ts';
import { EventBus } from './event-bus.ts';
import { PhotoStore } from './photo-store.ts';
import { Waiters } from './waiters.ts';

const defaultPage = 30;
const createdStatus = 201;
const okStatus = 200;
const defaultWaitSeconds = 30;

// A photo's images never change under its id, and only its owner sees them.
const imageCaching = 'private, max-age=2592000, immutable';

export const PhotosLive = HttpApiBuilder.group(
  GlimpseApi,
  'photos',
  Effect.fn(function* (handlers) {
    const store = yield* PhotoStore;
    const waiters = yield* Waiters;
    const bus = yield* EventBus;
    const fs = yield* FileSystem.FileSystem;
    const encodePhoto = Schema.encodeEffect(Schema.toCodecJson(PhotoSchema));

    const image = (id: string, which: 'preview' | 'original') =>
      Effect.gen(function* () {
        const caller = yield* CurrentCaller;
        const { file, contentType, bytes } = yield* store.image(
          id,
          which,
          caller,
        );
        return HttpServerResponse.stream(fs.stream(file), {
          contentType,
          contentLength: bytes,
          headers: { 'cache-control': imageCaching },
        });
      });

    return handlers.handleAll({
      register: ({ params, payload }) =>
        Effect.gen(function* () {
          const device = yield* requireDevice;
          const { photo, created } = yield* store.register(
            params.id,
            payload,
            device,
          );
          const body = yield* encodePhoto(photo).pipe(Effect.orDie);
          return HttpServerResponse.jsonUnsafe(body, {
            status: created ? createdStatus : okStatus,
          });
        }),
      putPreview: ({ params, payload }) =>
        requireDevice.pipe(
          Effect.flatMap((device) =>
            store.putPreview(params.id, device, payload),
          ),
        ),
      putOriginal: ({ params, payload, request }) =>
        requireDevice.pipe(
          Effect.flatMap((device) =>
            store.putOriginal(
              params.id,
              device,
              (request.headers['content-type'] ?? '')
                .split(';')[0]
                ?.trim()
                .toLowerCase() ?? '',
              payload,
            ),
          ),
        ),
      setNote: ({ params, payload }) => store.setNote(params.id, payload.note),
      list: ({ query }) =>
        store.page({
          limit: query.limit ?? defaultPage,
          before: query.before,
          since: query.since,
        }),
      wait: ({ query }) =>
        Effect.gen(function* () {
          yield* requireOwner;
          yield* waiters.hold(query.waiter ?? crypto.randomUUID());
          // Subscribed before looking, so a photo arriving in between is seen.
          const arrivals = yield* PubSub.subscribe(bus.pubsub);
          const waiting = yield* store.unviewedAfter(query.after);
          if (waiting.length > 0) {
            return { photos: waiting };
          }
          const fresh = PubSub.take(arrivals).pipe(
            Effect.repeat({
              until: (event) =>
                event.event === 'photo' &&
                event.data.viewedAt === null &&
                event.data.readyAt !== null &&
                DateTime.isGreaterThan(event.data.readyAt, query.after),
            }),
          );
          yield* fresh.pipe(
            Effect.timeoutOption(
              `${query.timeoutSeconds ?? defaultWaitSeconds} seconds`,
            ),
          );
          return { photos: yield* store.unviewedAfter(query.after) };
        }).pipe(Effect.scoped),
      get: ({ params }) => store.get(params.id),
      getPreview: ({ params }) => image(params.id, 'preview'),
      getOriginal: ({ params }) => image(params.id, 'original'),
      remove: ({ params }) => store.remove(params.id),
    });
  }),
);
