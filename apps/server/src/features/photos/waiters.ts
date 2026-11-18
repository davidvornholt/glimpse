import {
  Context,
  Effect,
  Fiber,
  Layer,
  Ref,
  type Scope,
  Semaphore,
} from 'effect';
import { EventBus } from './event-bus.ts';

/**
 * How long a wait still counts after its request ends. `glimpse wait` asks
 * again at once when a request times out, so the phone sees one steady wait
 * instead of a flicker.
 */
export const waiterGraceMillis = 5000;

type Entry = {
  readonly active: number;
  readonly leaving: Fiber.Fiber<void> | undefined;
};

/** The agents waiting for a photo, which the phone shows as they come and go. */
export class Waiters extends Context.Service<
  Waiters,
  {
    /** Counts `key` as waiting until the scope closes, and a grace period after. */
    readonly hold: (key: string) => Effect.Effect<void, never, Scope.Scope>;
    readonly count: Effect.Effect<number>;
  }
>()('@glimpse/server/Waiters') {
  static readonly layer = Layer.effect(
    Waiters,
    Effect.gen(function* () {
      const bus = yield* EventBus;
      const scope = yield* Effect.scope;
      const entries = yield* Ref.make(new Map<string, Entry>());
      const lock = yield* Semaphore.make(1);

      const publishCount = Ref.get(entries).pipe(
        Effect.flatMap((all) => bus.waitingChanged(all.size)),
      );

      const arrive = (key: string) =>
        Effect.gen(function* () {
          const all = yield* Ref.get(entries);
          const entry = all.get(key);
          if (entry?.leaving !== undefined) {
            yield* Fiber.interrupt(entry.leaving);
          }
          yield* Ref.set(
            entries,
            new Map(all).set(key, {
              active: (entry?.active ?? 0) + 1,
              leaving: undefined,
            }),
          );
          if (entry === undefined) {
            yield* publishCount;
          }
        }).pipe(lock.withPermit);

      const leaveLater = (key: string) =>
        Effect.sleep(waiterGraceMillis).pipe(
          Effect.andThen(
            Effect.gen(function* () {
              const all = yield* Ref.get(entries);
              if (all.get(key)?.active !== 0) {
                return;
              }
              const next = new Map(all);
              next.delete(key);
              yield* Ref.set(entries, next);
              yield* publishCount;
            }).pipe(lock.withPermit),
          ),
        );

      const depart = (key: string) =>
        Effect.gen(function* () {
          const all = yield* Ref.get(entries);
          const entry = all.get(key);
          if (entry === undefined) {
            return;
          }
          const active = entry.active - 1;
          const leaving =
            active === 0
              ? yield* Effect.forkIn(leaveLater(key), scope)
              : undefined;
          yield* Ref.set(entries, new Map(all).set(key, { active, leaving }));
        }).pipe(lock.withPermit);

      return {
        hold: (key) =>
          Effect.acquireRelease(arrive(key), () => depart(key)).pipe(
            Effect.asVoid,
          ),
        count: Ref.get(entries).pipe(Effect.map((all) => all.size)),
      };
    }),
  );
}
