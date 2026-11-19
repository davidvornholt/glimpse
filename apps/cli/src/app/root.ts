import { Effect } from 'effect';
import { Command } from 'effect/cli';
import { glimpseLayer } from '../shared/glimpse.ts';
import { emit } from './output.ts';

export const root = Command.make('glimpse').pipe(
  Command.withDescription(
    'Wait for, look at and download the photos you take with the Glimpse app on your phone to show an agent. Prints JSON. The server deletes each photo after 30 days.',
  ),
);

// Checks a command's input, then runs its requests with this machine's owner
// token and prints the result. The token is read only after the input passes,
// so a mistake in the input is reported as one even when no token is stored.
export const runGlimpse = <I, A, E1, E2, R1, R2>(
  input: Effect.Effect<I, E1, R1>,
  request: (input: I) => Effect.Effect<A, E2, R2>,
) =>
  Effect.gen(function* () {
    const valid = yield* input;
    const data = yield* request(valid).pipe(Effect.provide(glimpseLayer));
    yield* emit(data);
  });
