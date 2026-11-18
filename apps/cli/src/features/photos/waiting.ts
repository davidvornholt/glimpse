import { maxWaitSeconds } from '@glimpse/api/api';
import type { Photo } from '@glimpse/api/photo';
import { Clock, DateTime, Effect } from 'effect';
import { waitOnce } from './photos.ts';

const millisPerSecond = 1000;

export type WaitOptions = {
  /** An ISO time; photos that became ready after it and nobody looked at count. */
  readonly after: string;
  readonly timeoutSeconds: number;
  /**
   * How long to keep listening once a photo arrived, so the pages of one scan
   * come back together. Zero returns the first arrival alone.
   */
  readonly settleSeconds: number;
};

type Waited = Effect.Effect<
  ReadonlyArray<Photo>,
  Effect.Error<ReturnType<typeof waitOnce>>,
  Effect.Services<ReturnType<typeof waitOnce>>
>;

type Poll = {
  readonly after: string;
  readonly waiter: string;
  readonly until: number;
};

/**
 * Asks the server again and again, each time for at most as long as it holds
 * a request open, until a photo arrives or `until` passes.
 */
const firstArrival = (poll: Poll): Waited =>
  Effect.gen(function* () {
    const remaining = poll.until - (yield* Clock.currentTimeMillis);
    const timeoutSeconds = Math.max(
      0,
      Math.min(maxWaitSeconds, Math.ceil(remaining / millisPerSecond)),
    );
    const { photos } = yield* waitOnce({ ...poll, timeoutSeconds });
    if (photos.length > 0 || remaining <= 0) {
      return photos;
    }
    return yield* firstArrival(poll);
  });

const latestArrival = (photos: ReadonlyArray<Photo>, fallback: string) =>
  photos.reduce(
    (latest, photo) =>
      photo.readyAt !== null && DateTime.formatIso(photo.readyAt) > latest
        ? DateTime.formatIso(photo.readyAt)
        : latest,
    fallback,
  );

/**
 * Keeps listening while photos keep coming, each within `settleSeconds` of
 * the one before, and stops once `until` has passed.
 */
const moreArrivals = (
  poll: Poll & { readonly settleSeconds: number },
  found: ReadonlyArray<Photo>,
): Waited =>
  Effect.gen(function* () {
    if (poll.settleSeconds === 0) {
      return found;
    }
    const after = latestArrival(found, poll.after);
    const { photos } = yield* waitOnce({
      after,
      waiter: poll.waiter,
      timeoutSeconds: poll.settleSeconds,
    });
    const known = new Set(found.map((photo) => photo.id));
    const fresh = photos.filter((photo) => !known.has(photo.id));
    const now = yield* Clock.currentTimeMillis;
    if (fresh.length === 0 || now >= poll.until) {
      return [...found, ...fresh];
    }
    return yield* moreArrivals({ ...poll, after }, [...found, ...fresh]);
  });

const oldestFirst = (photos: ReadonlyArray<Photo>) =>
  [...photos].sort((left, right) =>
    DateTime.formatIso(left.readyAt ?? left.createdAt).localeCompare(
      DateTime.formatIso(right.readyAt ?? right.createdAt),
    ),
  );

/**
 * Waits for the phone to send photos nobody has looked at yet, and returns
 * them in the order they arrived, or none once `timeoutSeconds` pass. One
 * waiter id for the whole wait lets the phone show one waiting agent, however
 * many requests the wait takes.
 */
export const waitForPhotos = (options: WaitOptions) =>
  Effect.gen(function* () {
    const waiter = crypto.randomUUID();
    const start = yield* Clock.currentTimeMillis;
    const until = start + options.timeoutSeconds * millisPerSecond;
    const poll = { after: options.after, waiter, until };
    const first = yield* firstArrival(poll);
    if (first.length === 0) {
      return [];
    }
    const all = yield* moreArrivals(
      { ...poll, settleSeconds: options.settleSeconds },
      first,
    );
    return oldestFirst(all);
  });
