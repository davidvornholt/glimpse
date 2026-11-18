import { Effect, Layer } from 'effect';
import { PhotoStore } from './photo-store.ts';

/**
 * Deletes expired photos, and photos whose preview never arrived, at start-up
 * and every hour after. The first sweep finishes before the server answers,
 * so it never serves a photo that has expired while it was down.
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
    yield* sweep;
    yield* Effect.sleep('1 hour').pipe(
      Effect.andThen(sweep),
      Effect.forever,
      Effect.forkScoped,
    );
  }),
);
