import { lstat } from 'node:fs/promises';
import process from 'node:process';
import { Config, Effect, FileSystem, Option, Path } from 'effect';
import { FileNotSaved } from './errors/errors.ts';

export const ownerOnlyFile = 0o600;
const ownerOnlyDirectory = 0o700;
const permissionDigits = 0o1000;

export const notSaved =
  (target: string) => (error: { readonly message: string }) =>
    new FileNotSaved({ message: `Could not save ${target}: ${error.message}` });

const isMissing = (error: unknown) =>
  error instanceof Error && 'code' in error && error.code === 'ENOENT';

/** What is at a path, without following a link, or nothing. */
export const entryAt = (target: string) =>
  Effect.tryPromise({ try: () => lstat(target), catch: (error) => error }).pipe(
    Effect.map(Option.some),
    Effect.catchIf(isMissing, () => Effect.succeedNone),
    Effect.mapError(
      (error) =>
        new FileNotSaved({
          message: `Could not use ${target}: ${error instanceof Error ? error.message : String(error)}`,
        }),
    ),
  );

/**
 * Makes sure a directory is a plain one of this user's that nobody else can
 * enter, creating it if needed. Whoever controls a directory decides where a
 * file written into it lands, and previews of private photos sit there.
 */
const privateDirectory = (target: string) =>
  Effect.gen(function* () {
    const fs = yield* FileSystem.FileSystem;
    // Two commands saving at once both try to create it; the loser checks the
    // directory the winner made.
    const created = yield* fs
      .makeDirectory(target, { mode: ownerOnlyDirectory })
      .pipe(
        Effect.as(true),
        Effect.catchReason('PlatformError', 'AlreadyExists', () =>
          Effect.succeed(false),
        ),
        Effect.mapError(notSaved(target)),
      );
    if (created) {
      return;
    }
    const entry = yield* entryAt(target);
    if (
      Option.isNone(entry) ||
      !entry.value.isDirectory() ||
      entry.value.uid !== process.getuid?.()
    ) {
      return yield* new FileNotSaved({
        message: `${target} is not a plain directory of yours. Remove it, then try again.`,
      });
    }
    if (entry.value.mode % permissionDigits !== ownerOnlyDirectory) {
      yield* fs
        .chmod(target, ownerOnlyDirectory)
        .pipe(Effect.mapError(notSaved(target)));
    }
  });

/**
 * Where glimpse keeps files for the session: the per-user runtime directory,
 * which lives in memory and is emptied at logout, or else a private directory
 * under /tmp.
 */
const runtimeBase = Effect.gen(function* () {
  const path = yield* Path.Path;
  const runtime = yield* Config.option(
    Config.NonEmptyString('XDG_RUNTIME_DIR'),
  ).pipe(Effect.orElseSucceed(() => Option.none<string>()));
  return Option.match(runtime, {
    onSome: (directory) => path.join(directory, 'glimpse'),
    onNone: () => `/tmp/glimpse-${process.getuid?.() ?? 'user'}`,
  });
});

/** A private directory for the session's files, such as `previews`. */
export const runtimeDirectory = (name: string) =>
  Effect.gen(function* () {
    const path = yield* Path.Path;
    const base = yield* runtimeBase;
    const directory = path.join(base, name);
    yield* privateDirectory(base);
    yield* privateDirectory(directory);
    return directory;
  });

/** Deletes every file glimpse saved for the session and says whether there were any. */
export const removeRuntimeFiles = Effect.gen(function* () {
  const fs = yield* FileSystem.FileSystem;
  const base = yield* runtimeBase;
  const entry = yield* entryAt(base);
  if (Option.isNone(entry)) {
    return false;
  }
  // A link is removed itself; what it points to is left alone.
  yield* fs
    .remove(base, { recursive: true, force: true })
    .pipe(Effect.mapError(notSaved(base)));
  return true;
});

/**
 * Saves bytes as `file`, owner-only. They are written as a new file beside
 * the final name and renamed, so a broken write never poses as the file, and
 * a link at the final name is replaced rather than followed.
 */
export const replaceFile = (file: string, bytes: Uint8Array) =>
  Effect.gen(function* () {
    const fs = yield* FileSystem.FileSystem;
    const draft = `${file}.${crypto.randomUUID()}.tmp`;
    yield* Effect.gen(function* () {
      yield* fs.writeFile(draft, bytes, { flag: 'wx', mode: ownerOnlyFile });
      yield* fs.rename(draft, file);
    }).pipe(
      Effect.onError(() =>
        fs.remove(draft, { force: true }).pipe(Effect.ignore),
      ),
      Effect.mapError(notSaved(file)),
    );
    return file;
  });
