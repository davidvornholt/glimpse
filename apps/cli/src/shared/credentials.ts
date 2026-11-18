import process from 'node:process';
import { Effect, FileSystem, Option, Path, Schema } from 'effect';
import { CredentialsStoreFailed } from './errors/errors.ts';
import { xdgDirectory } from './xdg.ts';

/**
 * The Glimpse server this machine uses and the owner token for it. The token opens
 * every photo on it, so only the owner can read the file.
 */
const CredentialsSchema = Schema.Struct({
  server: Schema.String,
  token: Schema.String,
});
export type Credentials = typeof CredentialsSchema.Type;
const CredentialsJson = Schema.fromJsonString(CredentialsSchema, { space: 2 });

export const credentialsFile = Effect.gen(function* () {
  const path = yield* Path.Path;
  const configHome = yield* xdgDirectory('XDG_CONFIG_HOME', '.config');
  return path.join(configHome, 'glimpse', 'credentials.json');
});

const storeFailed = (path: string) => (error: { readonly message: string }) =>
  new CredentialsStoreFailed({
    message: `Could not use ${path}: ${error.message}`,
  });

const ownerOnlyFile = 0o600;
const ownerOnlyDirectory = 0o700;
/** A mode modulo this leaves its last three octal digits, the permissions. */
const permissionDigits = 0o1000;

/**
 * Refuses a directory or file that another user owns, since they chose what
 * is in it, and takes other users' access away from one that grants it.
 */
const keepPrivate = (target: string, mode: number) =>
  Effect.gen(function* () {
    const fs = yield* FileSystem.FileSystem;
    const info = yield* fs.stat(target).pipe(
      Effect.map(Option.some),
      Effect.catchReason('PlatformError', 'NotFound', () => Effect.succeedNone),
      Effect.mapError(storeFailed(target)),
    );
    if (Option.isNone(info)) {
      return;
    }
    if (Option.getOrUndefined(info.value.uid) !== process.getuid?.()) {
      return yield* new CredentialsStoreFailed({
        message: `${target} belongs to another user. Remove it, then run \`glimpse login\` again.`,
      });
    }
    if (info.value.mode % permissionDigits !== mode) {
      yield* fs.chmod(target, mode).pipe(Effect.mapError(storeFailed(target)));
    }
  });

export const readCredentials = Effect.gen(function* () {
  const fs = yield* FileSystem.FileSystem;
  const pathService = yield* Path.Path;
  const path = yield* credentialsFile;
  yield* keepPrivate(pathService.dirname(path), ownerOnlyDirectory);
  yield* keepPrivate(path, ownerOnlyFile);
  const text = yield* fs.readFileString(path).pipe(
    Effect.map(Option.some),
    Effect.catchReason('PlatformError', 'NotFound', () => Effect.succeedNone),
    Effect.mapError(storeFailed(path)),
  );
  if (Option.isNone(text)) {
    return Option.none<Credentials>();
  }
  const credentials = yield* Schema.decodeEffect(CredentialsJson)(
    text.value,
  ).pipe(Effect.mapError(storeFailed(path)));
  return Option.some(credentials);
});

export const saveCredentials = (credentials: Credentials) =>
  Effect.gen(function* () {
    const fs = yield* FileSystem.FileSystem;
    const pathService = yield* Path.Path;
    const path = yield* credentialsFile;
    const text = yield* Schema.encodeEffect(CredentialsJson)(credentials).pipe(
      Effect.mapError(storeFailed(path)),
    );
    yield* keepPrivate(pathService.dirname(path), ownerOnlyDirectory);
    // Written beside the old file and renamed over it, so a crash never
    // leaves half a file, and created owner-only before the token is in it.
    const draft = `${path}.${crypto.randomUUID()}.tmp`;
    yield* Effect.gen(function* () {
      yield* fs.makeDirectory(pathService.dirname(path), {
        recursive: true,
        mode: ownerOnlyDirectory,
      });
      yield* fs.writeFileString(draft, `${text}\n`, {
        flag: 'wx',
        mode: ownerOnlyFile,
      });
      yield* fs.chmod(draft, ownerOnlyFile);
      yield* fs.rename(draft, path);
    }).pipe(Effect.mapError(storeFailed(path)));
  });

/** Removes the stored token and tells whether there was one. */
export const forgetCredentials = Effect.gen(function* () {
  const fs = yield* FileSystem.FileSystem;
  const path = yield* credentialsFile;
  const existed = yield* fs
    .exists(path)
    .pipe(Effect.mapError(storeFailed(path)));
  yield* fs
    .remove(path, { force: true })
    .pipe(Effect.mapError(storeFailed(path)));
  return existed;
});
