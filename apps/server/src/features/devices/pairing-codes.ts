import { NotFound } from '@glimpse/api/errors';
import { DateTime, Effect, Ref } from 'effect';
import { newPairingCode, tokenDigest } from '../../shared/tokens.ts';

const pairingMinutes = 10;
const minuteMillis = 60_000;
const pairingMillis = pairingMinutes * minuteMillis;

/**
 * The codes on offer, which live only in memory: a code is good for one
 * claim within ten minutes, and a restart forgets them all.
 *
 * Wrong claims aren't limited. A code has 120 random bits, so guessing one
 * within its ten minutes is hopeless at any request rate, and a limit shared
 * by every caller would only let one caller lock every phone out of pairing.
 */
export const makePairingCodes = Effect.gen(function* () {
  // Digests of the codes on offer, with when each stops working.
  const offered = yield* Ref.make(new Map<string, number>());
  const nowMillis = DateTime.now.pipe(Effect.map(DateTime.toEpochMillis));

  const offer = Effect.gen(function* () {
    const now = yield* nowMillis;
    const code = newPairingCode();
    yield* Ref.update(offered, (current) => {
      const next = new Map([...current].filter(([, expires]) => expires > now));
      next.set(tokenDigest(code), now + pairingMillis);
      return next;
    });
    return { code, expiresAt: DateTime.makeUnsafe(now + pairingMillis) };
  });

  /** Uses up a code, or fails when it is not on offer. */
  const redeem = (code: string) =>
    Effect.gen(function* () {
      const now = yield* nowMillis;
      const digest = tokenDigest(code.trim().toUpperCase());
      const expires = yield* Ref.modify(offered, (current) => {
        const next = new Map(current);
        next.delete(digest);
        return [current.get(digest), next];
      });
      if (expires === undefined || expires <= now) {
        return yield* new NotFound({
          message:
            'This pairing code is unknown, used, or expired. Make a new one with `glimpse pair`.',
        });
      }
    });

  return { offer, redeem };
});
