import { NotFound } from '@glimpse/api/errors';
import { DateTime, Effect, Ref, Schema, Semaphore } from 'effect';
import { ServerConfig } from '../../shared/config.ts';
import { makeDisk } from '../../shared/files.ts';
import { EventBus } from './event-bus.ts';
import {
  loadPhotos,
  MetaFileSchema,
  photoFiles,
  publicPhoto,
  type StoredPhoto,
} from './photo-records.ts';

export const notFound = (id: string) =>
  new NotFound({
    message: `There is no photo ${id}. It may have expired or been deleted.`,
  });

/**
 * The photos in memory and on disk, and the one lock every change to them
 * takes, shared by the parts of the photo store.
 */
export const makePhotoState = Effect.gen(function* () {
  const { dataDirectory } = yield* ServerConfig;
  const bus = yield* EventBus;
  const disk = yield* makeDisk;
  const root = disk.path.join(dataDirectory, 'photos');
  yield* disk.makeDataDirectory(dataDirectory).pipe(Effect.orDie);
  const photos = yield* Ref.make(yield* loadPhotos(root).pipe(Effect.orDie));
  const lock = yield* Semaphore.make(1);
  const encodeMeta = Schema.encodeEffect(MetaFileSchema);
  let lastReadyAt = 0;

  const files = (photo: Pick<StoredPhoto, 'id' | 'originalType'>) =>
    photoFiles(disk.path, root, photo);

  const find = (id: string) =>
    Ref.get(photos).pipe(
      Effect.map((current) => current.get(id)),
      Effect.flatMap((photo) =>
        photo === undefined ? Effect.fail(notFound(id)) : Effect.succeed(photo),
      ),
    );

  /** Writes a record, keeps it, and tells listeners. Call it holding the lock. */
  const store = (photo: StoredPhoto) =>
    Effect.gen(function* () {
      const text = yield* encodeMeta(photo).pipe(Effect.orDie);
      yield* disk
        .writeAtomically(files(photo).meta, `${text}\n`)
        .pipe(Effect.orDie);
      yield* Ref.update(photos, (current) =>
        new Map(current).set(photo.id, photo),
      );
      if (photo.readyAt !== null) {
        yield* bus.photoChanged(publicPhoto(photo));
      }
      return photo;
    });

  /**
   * Deletes a photo's files and record. Call it holding the lock. The record,
   * `meta.json`, goes last, so when the other files can't all be deleted it
   * stays in memory and on disk, and deleting again or the next start tries
   * again.
   */
  const forget = (photo: StoredPhoto) =>
    Effect.gen(function* () {
      const { directory, meta } = files(photo);
      const entries = yield* disk.fs
        .readDirectory(directory)
        .pipe(
          Effect.catchReason('PlatformError', 'NotFound', () =>
            Effect.succeed<ReadonlyArray<string>>([]),
          ),
        );
      yield* Effect.forEach(
        entries
          .map((entry) => disk.path.join(directory, entry))
          .filter((file) => file !== meta),
        (file) => disk.fs.remove(file, { recursive: true, force: true }),
        { discard: true },
      );
      yield* disk.fs.remove(directory, { recursive: true, force: true });
      yield* Ref.update(photos, (current) => {
        const next = new Map(current);
        next.delete(photo.id);
        return next;
      });
      yield* bus.photoDeleted(photo.id);
    });

  /** A time later than every `readyAt` before it, so cursors are exact. */
  const nextReadyAt = DateTime.now.pipe(
    Effect.map((now) => {
      lastReadyAt = Math.max(DateTime.toEpochMillis(now), lastReadyAt + 1);
      return DateTime.makeUnsafe(lastReadyAt);
    }),
  );

  const all = Ref.get(photos).pipe(Effect.map((map) => [...map.values()]));

  return { disk, files, find, store, forget, nextReadyAt, all, lock };
});

export type PhotoState = Effect.Success<typeof makePhotoState>;
