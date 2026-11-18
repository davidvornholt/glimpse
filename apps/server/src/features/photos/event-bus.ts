import type { Event } from '@glimpse/api/events';
import type { Photo } from '@glimpse/api/photo';
import { Context, Effect, Layer, PubSub } from 'effect';

/** How many events a listener may fall behind by. */
const backlog = 1024;

/** What changed, for the phones and agents listening on `GET /v1/events`. */
export class EventBus extends Context.Service<
  EventBus,
  {
    readonly pubsub: PubSub.PubSub<Event>;
    readonly photoChanged: (photo: Photo) => Effect.Effect<void>;
    readonly photoDeleted: (id: string) => Effect.Effect<void>;
    readonly waitingChanged: (count: number) => Effect.Effect<void>;
  }
>()('@glimpse/server/EventBus') {
  static readonly layer = Layer.effect(
    EventBus,
    Effect.gen(function* () {
      // A listener that falls far behind loses the oldest events rather than
      // holding up everyone else; it catches up from the photo list.
      const pubsub = yield* PubSub.sliding<Event>(backlog);
      const publish = (event: Event) =>
        PubSub.publish(pubsub, event).pipe(Effect.asVoid);
      return {
        pubsub,
        photoChanged: (photo) => publish({ event: 'photo', data: photo }),
        photoDeleted: (id) => publish({ event: 'photo-deleted', data: { id } }),
        waitingChanged: (count) =>
          publish({ event: 'waiting', data: { count } }),
      };
    }),
  );
}
