import { GlimpseApi } from '@glimpse/api/api';
import { CurrentCaller } from '@glimpse/api/caller';
import { pairingLink } from '@glimpse/api/pairing-link';
import { Effect } from 'effect';
import { HttpApiBuilder } from 'effect/http-api';
import { requireOwner } from '../../shared/callers.ts';
import { ServerConfig } from '../../shared/config.ts';
import { DeviceStore } from './device-store.ts';

export const PairingLive = HttpApiBuilder.group(
  GlimpseApi,
  'pairing',
  Effect.fn(function* (handlers) {
    const devices = yield* DeviceStore;
    return handlers.handle('claim', ({ payload }) =>
      devices.claim(payload.code, payload.deviceName),
    );
  }),
);

export const AccountLive = HttpApiBuilder.group(
  GlimpseApi,
  'account',
  Effect.fn(function* (handlers) {
    return handlers.handle('me', () =>
      Effect.gen(function* () {
        return yield* CurrentCaller;
      }),
    );
  }),
);

export const DevicesLive = HttpApiBuilder.group(
  GlimpseApi,
  'devices',
  Effect.fn(function* (handlers) {
    const devices = yield* DeviceStore;
    const { publicUrl } = yield* ServerConfig;
    return handlers.handleAll({
      createPairing: () =>
        requireOwner.pipe(
          Effect.andThen(devices.createPairing),
          Effect.map(({ code, expiresAt }) => ({
            link: pairingLink(publicUrl, code),
            expiresAt,
          })),
        ),
      list: () => requireOwner.pipe(Effect.andThen(devices.list)),
      remove: ({ params }) =>
        requireOwner.pipe(Effect.andThen(devices.remove(params.id))),
    });
  }),
);
