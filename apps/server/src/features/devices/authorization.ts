import { Authorization } from '@glimpse/api/authorization';
import { type Caller, CurrentCaller } from '@glimpse/api/caller';
import { Unauthorized } from '@glimpse/api/errors';
import { Effect, Layer, Option, Redacted } from 'effect';
import { ServerConfig } from '../../shared/config.ts';
import { sameDigest, tokenDigest } from '../../shared/tokens.ts';
import { DeviceStore } from './device-store.ts';

const unauthorized = new Unauthorized({
  message:
    'This token is not valid here. Pair the phone again, or run `glimpse login` with the owner token.',
});

/**
 * Resolves the bearer token to the owner, when its digest is one of the
 * configured ones, or to the paired phone it was issued to.
 */
export const AuthorizationLive = Layer.effect(
  Authorization,
  Effect.gen(function* () {
    const { ownerTokenDigests } = yield* ServerConfig;
    const devices = yield* DeviceStore;
    return Authorization.of({
      bearer: (httpEffect, { credential }) =>
        Effect.gen(function* () {
          const token = Redacted.value(credential);
          if (token === '') {
            return yield* unauthorized;
          }
          const digest = tokenDigest(token);
          // Every digest is compared, so the time taken says nothing about
          // which one matched.
          const owner = ownerTokenDigests
            .map((expected) => sameDigest(expected, digest))
            .some(Boolean);
          const caller: Option.Option<Caller> = owner
            ? Option.some({ kind: 'owner' })
            : Option.map(
                yield* devices.authenticate(token),
                (device) => ({ kind: 'device', device }) as const,
              );
          if (Option.isNone(caller)) {
            return yield* unauthorized;
          }
          return yield* Effect.provideService(
            httpEffect,
            CurrentCaller,
            caller.value,
          );
        }),
    });
  }),
);
