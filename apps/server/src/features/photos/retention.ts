import { Effect, Layer, Schedule } from 'effect';
import { PhotoStore } from './photo-store.ts';

/**
 * Deletes expired photos, and photos whose preview never arrived, at start-up
 * and every hour after.
 */
export const RetentionLive = Layer.effectDiscard(
  Effect.gen(function* () {
    const store = yield* PhotoStore;
    const sweep = store.expire.pipe(
      Effect.tap((count) =>
        count === 0
          ? Effect.void
          : Effect.logInfo(`Deleted ${count} expired photos`),
      ),
    );
    yield* sweep.pipe(
      Effect.repeat(Schedule.spaced('1 hour')),
      Effect.forkScoped,
    );
  }),
);
