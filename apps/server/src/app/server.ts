import { GlimpseApi } from '@glimpse/api/api';
import { ByteSize, Effect, Layer } from 'effect';
import { HttpRouter, HttpServerRequest } from 'effect/http';
import { HttpApiBuilder } from 'effect/http-api';
import { AuthorizationLive } from '../features/devices/authorization.ts';
import { DeviceStore } from '../features/devices/device-store.ts';
import {
  AccountLive,
  DevicesLive,
  PairingLive,
} from '../features/devices/handlers.ts';
import { EventBus } from '../features/photos/event-bus.ts';
import { EventsLive } from '../features/photos/event-handlers.ts';
import { PhotosLive } from '../features/photos/handlers.ts';
import { PhotoStore } from '../features/photos/photo-store.ts';
import { RetentionLive } from '../features/photos/retention.ts';
import { Waiters } from '../features/photos/waiters.ts';
import type { ServerConfig } from '../shared/config.ts';
import { DeviceRemovals } from '../shared/device-removals.ts';
import { errorEnvelope } from '../shared/error-envelope.ts';

const HealthLive = HttpApiBuilder.group(GlimpseApi, 'health', (handlers) =>
  handlers.handle('health', () => Effect.succeed({ status: 'ok' as const })),
);

const Handlers = Layer.mergeAll(
  HealthLive,
  PairingLive,
  AccountLive,
  DevicesLive,
  PhotosLive,
  EventsLive,
).pipe(Layer.provide(AuthorizationLive));

const Stores = Layer.mergeAll(
  PhotoStore.layer,
  DeviceStore.layer,
  Waiters.layer,
).pipe(
  Layer.provideMerge(EventBus.layer),
  Layer.provideMerge(DeviceRemovals.layer),
);

/**
 * The whole server but the socket: the API, its stores, and the hourly
 * retention sweep. `main.ts` serves it with Bun; the tests call it directly.
 */
const ErrorEnvelopeLive = HttpRouter.middleware((app) => errorEnvelope(app), {
  global: true,
});

/**
 * The largest body a JSON route reads, well above the longest note. Without
 * it Effect reads any body whole, up to Bun's 128 MiB, before it decodes it.
 * The image routes stream their bodies under their own limits instead.
 */
export const maxJsonBodyBytes = 65_536;

const JsonBodyLimitLive = HttpRouter.middleware(
  (app) =>
    Effect.provideService(
      app,
      HttpServerRequest.MaxBodySize,
      ByteSize.bytes(maxJsonBodyBytes),
    ),
  { global: true },
);

export const appLayer = <E>(config: Layer.Layer<ServerConfig, E>) =>
  Layer.mergeAll(
    HttpApiBuilder.layer(GlimpseApi),
    ErrorEnvelopeLive,
    JsonBodyLimitLive,
    RetentionLive,
  ).pipe(Layer.provide(Handlers), Layer.provide(Stores), Layer.provide(config));

export const serve = <E>(config: Layer.Layer<ServerConfig, E>) =>
  HttpRouter.serve(appLayer(config));
