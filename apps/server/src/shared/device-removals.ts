import { Context, Effect, Layer, Stream, SubscriptionRef } from 'effect';

/**
 * The paired phones the owner has removed, so what a phone still holds open,
 * such as its event stream, ends with its pairing.
 */
export class DeviceRemovals extends Context.Service<
  DeviceRemovals,
  {
    readonly removed: (id: string) => Effect.Effect<void>;
    /** Succeeds once the phone `id` is removed, at once if it already was. */
    readonly untilRemoved: (id: string) => Effect.Effect<void>;
  }
>()('@glimpse/server/DeviceRemovals') {
  static readonly layer = Layer.effect(
    DeviceRemovals,
    Effect.gen(function* () {
      // Every removed id stays, so a stream that opens just as its phone is
      // removed ends too. Only the owner removes phones, so the set is small.
      const removedIds = yield* SubscriptionRef.make<ReadonlySet<string>>(
        new Set(),
      );
      return {
        removed: (id) =>
          SubscriptionRef.update(removedIds, (ids) => new Set(ids).add(id)),
        untilRemoved: (id) =>
          SubscriptionRef.changes(removedIds).pipe(
            Stream.filter((ids) => ids.has(id)),
            Stream.take(1),
            Stream.runDrain,
          ),
      };
    }),
  );
}
