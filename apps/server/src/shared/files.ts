import { Effect, FileSystem, Option, Path, Stream } from 'effect';

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
        ? fs.writeFileString(draft, data, { flag: 'wx' })
        : fs.writeFile(draft, data, { flag: 'wx' })
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
    return Stream.run(body, fs.sink(draft, { flag: 'wx' })).pipe(
      Effect.onError(() =>
        fs.remove(draft, { force: true }).pipe(Effect.ignore),
      ),
      Effect.as(draft),
    );
  };

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
    writeAtomically,
    streamToDraft,
    readHead,
    readTextIfPresent,
  };
});
