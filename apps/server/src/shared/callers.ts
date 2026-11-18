import { CurrentCaller } from '@glimpse/api/caller';
import type { Device } from '@glimpse/api/device';
import { Forbidden } from '@glimpse/api/errors';
import { Effect } from 'effect';

export const requireOwner = Effect.gen(function* () {
  const caller = yield* CurrentCaller;
  if (caller.kind !== 'owner') {
    return yield* new Forbidden({
      message: 'Only the owner token may do this, not a phone.',
    });
  }
});

export const requireDevice = Effect.gen(function* () {
  const caller = yield* CurrentCaller;
  if (caller.kind !== 'device') {
    return yield* new Forbidden({
      message: 'Only a paired phone sends photos.',
    });
  }
  return caller.device satisfies Device;
});
