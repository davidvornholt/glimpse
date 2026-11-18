import { GlimpseApi } from '@glimpse/api/api';
import { CurrentCaller } from '@glimpse/api/caller';
import type { Event } from '@glimpse/api/events';
import { Effect, PubSub, Stream } from 'effect';
import { HttpApiBuilder } from 'effect/http-api';
import { DeviceRemovals } from '../../shared/device-removals.ts';
import { EventBus } from './event-bus.ts';
import { Waiters } from './waiters.ts';

export const EventsLive = HttpApiBuilder.group(
  GlimpseApi,
  'events',
  Effect.fn(function* (handlers) {
    const bus = yield* EventBus;
    const waiters = yield* Waiters;
    const removals = yield* DeviceRemovals;
    return handlers.handle('events', () =>
      Effect.gen(function* () {
        const caller = yield* CurrentCaller;
        // The token was checked once, when the stream opened, so a phone's
        // stream ends when the owner removes the phone.
        const unpaired =
          caller.kind === 'device'
            ? removals.untilRemoved(caller.device.id)
            : Effect.never;
        return Stream.unwrap(
          Effect.gen(function* () {
            // Subscribed before the count is read, so no change slips by.
            const subscription = yield* PubSub.subscribe(bus.pubsub);
            const count = yield* waiters.count;
            const first: Event = { event: 'waiting', data: { count } };
            return Stream.concat(
              Stream.make(first),
              Stream.fromSubscription(subscription),
            );
          }),
        ).pipe(Stream.interruptWhen(unpaired));
      }),
    );
  }),
);
