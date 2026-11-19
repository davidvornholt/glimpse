import { Effect, FileSystem, Option, Path, Stream } from 'effect';

/**
 * Photos, notes and the device list are private, so only the server's own
 * user may read them. A mode given at creation can only lose bits to the
 * umask, never gain them, so these hold under any umask.
 */
export const privateDirectoryMode = 0o700;
export const privateFileMode = 0o600;
// The low six bits of a mode are the group's and everyone else's access.
const groupAndOtherSpan = 0o100;

/**
 * File helpers bound to the platform's file system, so the stores that use
 * them need it only once, when they are built.
 */
export const makeDisk = Effect.gen(function* () {
  const fs = yield* FileSystem.FileSystem;
  const path = yield* Path.Path;

  /**
   * Writes a file beside its final name and renames it into place, so a crash
   * never leaves half a file where a reader expects a whole one.
   */
  const writeAtomically = (target: string, data: Uint8Array | string) => {
    const draft = `${target}.${crypto.randomUUID()}.tmp`;
    return (
      typeof data === 'string'
        ? fs.writeFileString(draft, data, {
            flag: 'wx',
            mode: privateFileMode,
          })
        : fs.writeFile(draft, data, { flag: 'wx', mode: privateFileMode })
    ).pipe(
      Effect.andThen(fs.rename(draft, target)),
      Effect.onError(() =>
        fs.remove(draft, { force: true }).pipe(Effect.ignore),
      ),
    );
  };

  /** Streams a body into a new temporary file in `directory` and returns its path. */
  const streamToDraft = <E>(
    directory: string,
    body: Stream.Stream<Uint8Array, E>,
  ) => {
    const draft = path.join(directory, `upload.${crypto.randomUUID()}.tmp`);
    return Stream.run(
      body,
      fs.sink(draft, { flag: 'wx', mode: privateFileMode }),
    ).pipe(
      Effect.onError(() =>
        fs.remove(draft, { force: true }).pipe(Effect.ignore),
      ),
      Effect.as(draft),
    );
  };

  const makePrivateDirectory = (directory: string) =>
    fs.makeDirectory(directory, {
      recursive: true,
      mode: privateDirectoryMode,
    });

  /**
   * Makes the data directory, or closes one that an older server, which
   * followed the umask, left readable by other users. Closing it hides
   * everything inside, however open those files are themselves.
   */
  const makeDataDirectory = (directory: string) =>
    Effect.gen(function* () {
      yield* makePrivateDirectory(directory);
      const { mode } = yield* fs.stat(directory);
      if (mode % groupAndOtherSpan !== 0) {
        yield* fs.chmod(directory, privateDirectoryMode);
      }
    });

  /** The first bytes of a file, enough to tell its type. */
  const readHead = (file: string, bytes: number) =>
    fs.stream(file, { bytesToRead: bytes }).pipe(
      Stream.runCollect,
      Effect.map((chunks) => {
        const head = new Uint8Array(bytes);
        let offset = 0;
        for (const chunk of chunks) {
          const take = Math.min(chunk.length, bytes - offset);
          head.set(chunk.subarray(0, take), offset);
          offset += take;
        }
        return head.subarray(0, offset);
      }),
    );

  const readTextIfPresent = (file: string) =>
    fs.readFileString(file).pipe(
      Effect.map(Option.some),
      Effect.catchReason('PlatformError', 'NotFound', () =>
        Effect.succeed(Option.none<string>()),
      ),
    );

  return {
    fs,
    path,
    makePrivateDirectory,
    makeDataDirectory,
    writeAtomically,
    streamToDraft,
    readHead,
    readTextIfPresent,
  };
});
