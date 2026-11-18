import { originalExtensions, type Photo } from '@glimpse/api/photo';
import { Effect, FileSystem, Option, Path, Stream } from 'effect';
import { FileNotSaved } from '../../shared/errors/errors.ts';
import { Glimpse } from '../../shared/glimpse.ts';
import {
  entryAt,
  notSaved,
  ownerOnlyFile,
  replaceFile,
  runtimeDirectory,
} from '../../shared/runtime-files.ts';

/**
 * Saves the preview of a photo, a JPEG of at most 2048 px that the phone made
 * without the camera's metadata, and returns its absolute path. Fetching it
 * marks the photo viewed, so `glimpse wait` no longer returns it.
 */
export const savePreview = Effect.fn('savePreview')(function* (id: string) {
  const { execute, server } = yield* Glimpse;
  const path = yield* Path.Path;
  const response = yield* execute({
    method: 'GET',
    path: `/v1/photos/${id}/preview`,
  });
  const bytes = yield* response.arrayBuffer.pipe(
    Effect.mapError(
      (error) =>
        new FileNotSaved({
          message: `${server} broke off the preview of ${id}: ${error.message}`,
        }),
    ),
  );
  const directory = yield* runtimeDirectory('previews');
  return yield* replaceFile(
    path.join(directory, `${id}.jpg`),
    new Uint8Array(bytes),
  );
});

/** The file name of a photo's original: its id, which sorts by arrival. */
export const originalName = (photo: Photo) =>
  `${photo.id}.${originalExtensions[photo.originalType]}`;

const alreadyThere = (file: string) =>
  new FileNotSaved({
    message: `${file} already exists. glimpse never replaces a file; choose another directory or remove it.`,
  });

/** Refuses a destination that already holds any of the files. */
export const checkFree = (files: ReadonlyArray<string>) =>
  Effect.forEach(files, (file) =>
    Effect.gen(function* () {
      const entry = yield* entryAt(file);
      if (Option.isSome(entry)) {
        return yield* alreadyThere(file);
      }
    }),
  );

/**
 * Streams the original of a photo into a new file. It is written under a
 * draft name first, so a broken download never passes for the photo, and
 * then linked to its name, which fails instead of replacing a file that
 * appeared there meanwhile.
 */
export const saveOriginal = Effect.fn('saveOriginal')(function* (
  id: string,
  file: string,
) {
  const { execute, server } = yield* Glimpse;
  const fs = yield* FileSystem.FileSystem;
  const path = yield* Path.Path;
  const response = yield* execute({
    method: 'GET',
    path: `/v1/photos/${id}/original`,
  });
  const draft = path.join(
    path.dirname(file),
    `.${path.basename(file)}.${crypto.randomUUID()}.part`,
  );
  yield* Effect.gen(function* () {
    yield* response.stream.pipe(
      Stream.mapError(
        (error) =>
          new FileNotSaved({
            message: `${server} broke off the original of ${id}: ${error.message}`,
          }),
      ),
      Stream.run(fs.sink(draft, { flag: 'wx', mode: ownerOnlyFile })),
    );
    yield* fs.link(draft, file).pipe(
      Effect.catchReason('PlatformError', 'AlreadyExists', () =>
        Effect.fail(alreadyThere(file)),
      ),
      // Some file systems, such as exFAT, have no hard links.
      Effect.catchTag('PlatformError', () =>
        Effect.gen(function* () {
          const entry = yield* entryAt(file);
          if (Option.isSome(entry)) {
            return yield* alreadyThere(file);
          }
          yield* fs.rename(draft, file);
        }),
      ),
    );
  }).pipe(
    Effect.mapError((error) =>
      error._tag === 'FileNotSaved' ? error : notSaved(file)(error),
    ),
    Effect.ensuring(fs.remove(draft, { force: true }).pipe(Effect.ignore)),
  );
  const info = yield* fs.stat(file).pipe(Effect.mapError(notSaved(file)));
  return { path: file, bytes: Number(info.size) };
});
