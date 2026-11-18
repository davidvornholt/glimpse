import process from 'node:process';
import { BunServices } from '@effect/platform-bun';
import { Console, DateTime, Effect } from 'effect';
import { CliError, CliOutput, Command } from 'effect/cli';
import packageJson from '../../package.json' with { type: 'json' };
import { login, logout, status } from './account-commands.ts';
import { devices, pair } from './device-commands.ts';
import { type ReportedError, report } from './output.ts';
import {
  deletePhotos,
  download,
  note,
  preview,
  recent,
  show,
} from './photo-commands.ts';
import { root } from './root.ts';
import { wait } from './wait-command.ts';

export const glimpse = root.pipe(
  Command.withSubcommands([
    wait,
    recent,
    show,
    preview,
    download,
    note,
    deletePhotos,
    pair,
    devices,
    login,
    logout,
    status,
  ]),
);

/**
 * What a failure is reported as. The server's refusals carry the server's
 * own tag, such as NotFound, so they read the same as in the API.
 */
export const reported = (error: unknown): ReportedError => {
  if (CliError.isCliError(error)) {
    return { _tag: 'UsageError', message: error.message };
  }
  const failure = error as ReportedError & { readonly tag?: string };
  return failure._tag === 'ServerRejected' && failure.tag !== undefined
    ? { _tag: failure.tag, message: failure.message }
    : failure;
};

// The CLI library prints a command's help to stdout before it fails on a
// parse error. Agents read stdout as data, so the help text is held back and
// printed only when it was asked for: by --help, or by naming a command group
// without one of its commands. Anything else the library prints, such as the
// version, passes straight through. A parse error becomes one JSON error on
// stderr like every other failure, so agents parse a single shape.
const program = Effect.gen(function* () {
  const stdout = yield* Console.Console;
  const formatter = yield* CliOutput.Formatter;
  let help: string | undefined;
  let heldHelp: string | undefined;
  const printHeldHelp = Effect.suspend(() =>
    heldHelp === undefined ? Effect.void : Console.log(heldHelp),
  );
  return yield* Command.runWith(glimpse, {
    version: packageJson.version,
    renderErrors: false,
  })(process.argv.slice(2)).pipe(
    Effect.provideService(CliOutput.Formatter, {
      ...formatter,
      formatHelpDoc: (doc) => {
        help = formatter.formatHelpDoc(doc);
        return help;
      },
    }),
    Effect.provideService(Console.Console, {
      ...stdout,
      log: (...args) => {
        if (args.length === 1 && args[0] === help) {
          heldHelp = help;
        } else {
          stdout.log(...args);
        }
      },
    }),
    Effect.andThen(printHeldHelp),
    Effect.catchTag('ShowHelp', (shown) =>
      shown.errors.length === 0
        ? printHeldHelp
        : report({
            _tag: 'UsageError',
            message: shown.errors.map((error) => error.message).join('\n'),
          }),
    ),
  );
}).pipe(
  Effect.catch((error) => report(reported(error))),
  Effect.catchDefect((defect) =>
    report({ _tag: 'UnexpectedError', message: String(defect) }),
  ),
  // Days and times without an offset, such as `--since 2026-11-14T09:30`, are
  // read in this machine's time zone.
  Effect.provide(DateTime.layerCurrentZoneLocal),
  Effect.provide(BunServices.layer),
);

if (import.meta.main) {
  await Effect.runPromise(program);
}
