import { Effect, Layer, Option, Redacted, Stdio, Stream } from 'effect';
import { Command, Prompt } from 'effect/cli';
import {
  deviceJson,
  listDevices,
  whoAmI,
} from '../features/devices/devices.ts';
import {
  forgetCredentials,
  readCredentials,
  saveCredentials,
} from '../shared/credentials.ts';
import { NotSignedIn } from '../shared/errors/errors.ts';
import {
  Glimpse,
  glimpseLayer,
  httpLayer,
  makeGlimpse,
} from '../shared/glimpse.ts';
import { removeRuntimeFiles } from '../shared/runtime-files.ts';
import { exampleServer, serverOrigin } from '../shared/server.ts';
import { fail, optionalString } from './command-inputs.ts';
import { emit } from './output.ts';

// A token is one word; anything with spaces is a paste gone wrong.
const whitespace = /\s/u;

/** The server, and the phones that may send photos to it. */
const account = Effect.gen(function* () {
  const { server } = yield* Glimpse;
  const me = yield* whoAmI;
  if (me.kind !== 'owner') {
    return yield* fail(
      `That is the token of the phone ${me.device.name}, not the owner token. Pass the owner token the server is set up with.`,
    );
  }
  const phones = yield* listDevices;
  return { signedIn: true, server, phones: phones.map(deviceJson) };
});

const noToken = () =>
  fail(
    'No token arrived. Paste it when asked, or pipe it in: glimpse login --server <address> < token-file',
  );

/**
 * The token, typed hidden at a terminal or piped in, so it never appears in
 * the shell history or the process list.
 */
const readToken = Effect.gen(function* () {
  const stdio = yield* Stdio.Stdio;
  if (yield* stdio.stdinIsTerminal) {
    const token = yield* Prompt.run(
      Prompt.Hidden({ message: 'Glimpse owner token' }),
    ).pipe(Effect.catchTag('QuitError', noToken));
    return Redacted.value(token).trim();
  }
  return yield* stdio.stdin.pipe(
    Stream.decodeText(),
    Stream.mkString,
    Effect.map((text) => text.trim()),
    Effect.catchTag('PlatformError', noToken),
  );
});

export const login = Command.make(
  'login',
  {
    server: optionalString(
      'server',
      `The address of your Glimpse server, such as ${exampleServer}. Defaults to the one this machine used before.`,
    ),
  },
  ({ server }) =>
    Effect.gen(function* () {
      const stored = yield* readCredentials;
      const chosen = Option.orElse(server, () =>
        Option.map(stored, (previous) => previous.server),
      );
      if (Option.isNone(chosen)) {
        return yield* fail(
          `Pass --server with the address of your Glimpse server, such as ${exampleServer}.`,
        );
      }
      const origin = yield* serverOrigin(chosen.value);
      const token = yield* readToken;
      if (token === '') {
        return yield* noToken();
      }
      if (whitespace.test(token)) {
        return yield* fail(
          'That is not a token: it contains spaces or line breaks. Paste only the token.',
        );
      }
      const credentials = { server: origin, token };
      // Checked before it is saved, so a typo never replaces a working token.
      const checked = yield* account.pipe(
        Effect.provide(
          Layer.effect(Glimpse, makeGlimpse(credentials)).pipe(
            Layer.provide(httpLayer),
          ),
        ),
        Effect.catchTag('NotSignedIn', () =>
          Effect.fail(
            new NotSignedIn({
              message: `${origin} does not accept this token. Check that the whole owner token was pasted. The token stored before, if any, is unchanged.`,
            }),
          ),
        ),
      );
      yield* saveCredentials(credentials);
      yield* emit(checked);
    }),
).pipe(
  Command.withDescription(
    'Store the owner token for this machine after checking it with the server. Reads the token hidden from the terminal, or from stdin.',
  ),
);

export const logout = Command.make('logout', {}, () =>
  Effect.gen(function* () {
    const removedToken = yield* forgetCredentials;
    const removedPreviews = yield* removeRuntimeFiles;
    yield* emit({ signedIn: false, removedToken, removedPreviews });
  }),
).pipe(
  Command.withDescription(
    'Remove the owner token and the saved previews from this machine. The photos stay on the server until they expire.',
  ),
);

export const status = Command.make('status', {}, () =>
  Effect.gen(function* () {
    const stored = yield* readCredentials;
    if (Option.isNone(stored)) {
      yield* emit({ signedIn: false, server: null });
      return;
    }
    const result = yield* account.pipe(
      Effect.provide(glimpseLayer),
      Effect.catchTag('NotSignedIn', (error) =>
        Effect.succeed({
          signedIn: false,
          server: stored.value.server,
          problem: error.message,
        }),
      ),
    );
    yield* emit(result);
  }),
).pipe(
  Command.withDescription(
    'Show the server this machine signed in to and the phones paired with it',
  ),
);
