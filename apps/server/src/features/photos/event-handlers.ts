import { GlimpseApi } from '@glimpse/api/api';
import type { Event } from '@glimpse/api/events';
import { Effect, PubSub, Stream } from 'effect';
import { HttpApiBuilder } from 'effect/http-api';
import { EventBus } from './event-bus.ts';
import { Waiters } from './waiters.ts';

export const EventsLive = HttpApiBuilder.group(
  GlimpseApi,
  'events',
  Effect.fn(function* (handlers) {
    const bus = yield* EventBus;
    const waiters = yield* Waiters;
    return handlers.handle('events', () =>
      Effect.succeed(
        Stream.unwrap(
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
        ),
      ),
    );
  }),
);
