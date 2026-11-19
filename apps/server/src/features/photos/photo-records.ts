import {
  originalExtensions,
  type Photo,
  type PhotoMetadata,
  PhotoSchema,
} from '@glimpse/api/photo';
import { DateTime, Effect, FileSystem, Option, Path, Schema } from 'effect';

/** A photo as kept in `photos/<id>/meta.json`, with the phone that sent it. */
export const StoredPhotoSchema = Schema.Struct({
  ...PhotoSchema.fields,
  deviceId: Schema.NullOr(Schema.String),
});
export type StoredPhoto = typeof StoredPhotoSchema.Type;

export const MetaFileSchema = Schema.fromJsonString(
  Schema.toCodecJson(StoredPhotoSchema),
  { space: 2 },
);

export const publicPhoto = ({ deviceId: _, ...photo }: StoredPhoto): Photo =>
  photo;

export type PhotoFiles = {
  readonly directory: string;
  readonly meta: string;
  readonly preview: string;
  readonly original: string;
};

export const photoFiles = (
  path: Path.Path,
  root: string,
  photo: Pick<StoredPhoto, 'id' | 'originalType'>,
): PhotoFiles => {
  const directory = path.join(root, photo.id);
  return {
    directory,
    meta: path.join(directory, 'meta.json'),
    preview: path.join(directory, 'preview.jpg'),
    original: path.join(
      directory,
      `original.${originalExtensions[photo.originalType]}`,
    ),
  };
};

/**
 * Whether a repeated registration describes the same photo. The note is left
 * out, since it may have been changed since.
 */
export const sameRegistration = (
  stored: StoredPhoto,
  metadata: PhotoMetadata,
) =>
  DateTime.toEpochMillis(stored.takenAt) ===
    DateTime.toEpochMillis(metadata.takenAt) &&
  stored.source === metadata.source &&
  stored.width === metadata.width &&
  stored.height === metadata.height &&
  stored.originalType === metadata.originalType &&
  stored.batchId === (metadata.batchId ?? null);

const millis = (time: DateTime.Utc | null) =>
  time === null ? 0 : DateTime.toEpochMillis(time);

/** Newest to arrive first; the id breaks ties. */
export const newestFirst = (a: StoredPhoto, b: StoredPhoto) =>
  millis(b.readyAt) - millis(a.readyAt) || (a.id < b.id ? 1 : -1);

/** A page cursor names the last photo of the page: `<readyAt ms>.<id>`. */
export const cursorOf = (photo: StoredPhoto) =>
  `${millis(photo.readyAt)}.${photo.id}`;

const cursorPattern = /^(?<readyAt>\d{1,16})\.(?<id>[0-9A-Z]{26})$/u;

export const parseCursor = (cursor: string) => {
  const groups = cursorPattern.exec(cursor)?.groups;
  return groups?.readyAt === undefined || groups.id === undefined
    ? Option.none()
    : Option.some({ readyAt: Number(groups.readyAt), id: groups.id });
};

export const isOlderThanCursor = (
  photo: StoredPhoto,
  cursor: { readonly readyAt: number; readonly id: string },
) => {
  const readyAt = millis(photo.readyAt);
  return (
    readyAt < cursor.readyAt ||
    (readyAt === cursor.readyAt && photo.id < cursor.id)
  );
};

/**
 * Reads every photo's record at start-up and clears out uploads that were cut
 * off. A record that can't be read is skipped and logged, not deleted: it may
 * come from a newer server.
 */
export const loadPhotos = Effect.fn('loadPhotos')(function* (root: string) {
  const fs = yield* FileSystem.FileSystem;
  const path = yield* Path.Path;
  yield* fs.makeDirectory(root, { recursive: true });
  const decode = Schema.decodeEffect(MetaFileSchema);

  const loadOne = (name: string) => {
    const directory = path.join(root, name);
    return Effect.gen(function* () {
      if ((yield* fs.stat(directory)).type !== 'Directory') {
        return Option.none<StoredPhoto>();
      }
      const drafts = (yield* fs.readDirectory(directory)).filter((entry) =>
        entry.endsWith('.tmp'),
      );
      yield* Effect.forEach(
        drafts,
        (draft) => fs.remove(path.join(directory, draft), { force: true }),
        { discard: true },
      );
      const photo = yield* fs
        .readFileString(path.join(directory, 'meta.json'))
        .pipe(Effect.flatMap(decode));
      return photo.id === name ? Option.some(photo) : Option.none();
    }).pipe(
      // Anything wrong with one photo, such as a folder restored with the
      // wrong owner, skips that photo rather than stopping the server.
      Effect.catch((error) =>
        Effect.logWarning(`Skipping ${directory}: ${error.message}`).pipe(
          Effect.as(Option.none<StoredPhoto>()),
        ),
      ),
    );
  };

  const loaded = yield* Effect.forEach(yield* fs.readDirectory(root), loadOne);
  return new Map(
    loaded.filter(Option.isSome).map(({ value }) => [value.id, value]),
  );
});
