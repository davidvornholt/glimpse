import type { Caller } from '@glimpse/api/caller';
import type { Device } from '@glimpse/api/device';
import {
  type Conflict,
  InvalidRequest,
  NotFound,
  type TooLarge,
} from '@glimpse/api/errors';
import type { OriginalType, Photo, PhotoMetadata } from '@glimpse/api/photo';
import { Context, DateTime, Effect, Layer, Option } from 'effect';
import { ServerConfig } from '../../shared/config.ts';
import {
  cursorOf,
  isOlderThanCursor,
  newestFirst,
  parseCursor,
  publicPhoto,
  type StoredPhoto,
} from './photo-records.ts';
import { makePhotoState } from './photo-state.ts';
import { makeUploads, type Upload } from './photo-uploads.ts';

/** How long a photo whose preview never arrived is kept: a day. */
const pendingMillis = 86_400_000;

export type PageQuery = {
  readonly limit: number;
  readonly before: string | undefined;
  readonly since: DateTime.Utc | undefined;
};

type UploadError = NotFound | InvalidRequest | TooLarge;

export type PhotoImage = {
  readonly file: string;
  readonly contentType: OriginalType;
  readonly bytes: number;
};

const badCursor = new InvalidRequest({
  message:
    'The cursor is not one this server gave out. Pass nextCursor as it came.',
});

/** The ready photos, newest first, that a page query asks for. */
const pageOf = (photos: ReadonlyArray<StoredPhoto>, query: PageQuery) => {
  const cursor =
    query.before === undefined ? undefined : parseCursor(query.before);
  if (cursor !== undefined && Option.isNone(cursor)) {
    return Effect.fail(badCursor);
  }
  const since =
    query.since === undefined ? 0 : DateTime.toEpochMillis(query.since);
  const matching = photos
    .filter(
      (photo) =>
        photo.readyAt !== null &&
        DateTime.toEpochMillis(photo.readyAt) >= since &&
        (cursor === undefined || isOlderThanCursor(photo, cursor.value)),
    )
    .sort(newestFirst);
  const chosen = matching.slice(0, query.limit);
  const last = chosen.at(-1);
  return Effect.succeed({
    photos: chosen.map(publicPhoto),
    nextCursor:
      matching.length > query.limit && last !== undefined
        ? cursorOf(last)
        : null,
  });
};

/** A photo's preview or original file, if the phone has sent it. */
const imageOf = (
  photo: StoredPhoto,
  which: 'preview' | 'original',
  paths: { readonly preview: string; readonly original: string },
): Option.Option<PhotoImage> => {
  if (which === 'preview') {
    return photo.readyAt === null
      ? Option.none()
      : Option.some({
          file: paths.preview,
          contentType: 'image/jpeg',
          bytes: photo.previewBytes ?? 0,
        });
  }
  return photo.hasOriginal
    ? Option.some({
        file: paths.original,
        contentType: photo.originalType,
        bytes: photo.originalBytes ?? 0,
      })
    : Option.none();
};

const missingImage = (id: string, which: 'preview' | 'original') =>
  new NotFound({
    message:
      which === 'preview'
        ? `Photo ${id} is still being sent.`
        : `The phone has not sent the original of photo ${id}.`,
  });

const isDue = (photo: StoredPhoto, now: number) =>
  DateTime.toEpochMillis(photo.expiresAt) <= now ||
  (photo.readyAt === null &&
    DateTime.toEpochMillis(photo.createdAt) + pendingMillis <= now);

export class PhotoStore extends Context.Service<
  PhotoStore,
  {
    readonly register: (
      id: string,
      metadata: PhotoMetadata,
      device: Device,
    ) => Effect.Effect<
      { readonly photo: Photo; readonly created: boolean },
      Conflict
    >;
    readonly putPreview: (
      id: string,
      device: Device,
      body: Upload,
    ) => Effect.Effect<Photo, UploadError>;
    readonly putOriginal: (
      id: string,
      device: Device,
      contentType: string,
      body: Upload,
    ) => Effect.Effect<Photo, UploadError>;
    readonly setNote: (
      id: string,
      note: string | null,
    ) => Effect.Effect<Photo, NotFound>;
    readonly page: (query: PageQuery) => Effect.Effect<
      {
        readonly photos: ReadonlyArray<Photo>;
        readonly nextCursor: string | null;
      },
      InvalidRequest
    >;
    /** Ready photos nobody has looked at that arrived after `after`, oldest first. */
    readonly unviewedAfter: (
      after: DateTime.Utc,
    ) => Effect.Effect<ReadonlyArray<Photo>>;
    readonly get: (id: string) => Effect.Effect<Photo, NotFound>;
    /** A photo's image file. The owner's first fetch marks the photo viewed. */
    readonly image: (
      id: string,
      which: 'preview' | 'original',
      caller: Caller,
    ) => Effect.Effect<PhotoImage, NotFound>;
    readonly remove: (id: string) => Effect.Effect<void, NotFound>;
    /** Deletes expired photos and abandoned uploads, and says how many. */
    readonly expire: Effect.Effect<number>;
  }
>()('@glimpse/server/PhotoStore') {
  static readonly layer = Layer.effect(
    PhotoStore,
    Effect.gen(function* () {
      const { retentionDays } = yield* ServerConfig;
      const state = yield* makePhotoState;
      const { files, find, store, forget, all, lock } = state;

      const markViewed = (id: string) =>
        Effect.gen(function* () {
          const current = yield* find(id);
          if (current.viewedAt === null) {
            yield* store({ ...current, viewedAt: yield* DateTime.now });
          }
        }).pipe(lock.withPermit);

      const image = (
        id: string,
        which: 'preview' | 'original',
        caller: Caller,
      ) =>
        Effect.gen(function* () {
          const photo = yield* find(id);
          const found = imageOf(photo, which, files(photo));
          if (Option.isNone(found)) {
            return yield* missingImage(id, which);
          }
          if (caller.kind === 'owner' && photo.viewedAt === null) {
            yield* markViewed(id);
          }
          return found.value;
        });

      // A photo that can't be deleted holds up no other, and stays due, so the
      // next sweep tries it again.
      const expire = Effect.gen(function* () {
        const now = DateTime.toEpochMillis(yield* DateTime.now);
        const due = (yield* all).filter((photo) => isDue(photo, now));
        const deleted = yield* Effect.forEach(due, (photo) =>
          forget(photo).pipe(
            Effect.as(true),
            Effect.catch((error) =>
              Effect.logWarning(
                `Could not delete photo ${photo.id}, so the next sweep tries again: ${error.message}`,
              ).pipe(Effect.as(false)),
            ),
          ),
        );
        return deleted.filter(Boolean).length;
      }).pipe(lock.withPermit);

      return {
        ...makeUploads(state, retentionDays),
        setNote: (id, note) =>
          find(id).pipe(
            Effect.flatMap((photo) => store({ ...photo, note })),
            Effect.map(publicPhoto),
            lock.withPermit,
          ),
        page: (query) =>
          all.pipe(Effect.flatMap((photos) => pageOf(photos, query))),
        unviewedAfter: (after) =>
          all.pipe(
            Effect.map((photos) =>
              photos
                .filter(
                  (photo) =>
                    photo.viewedAt === null &&
                    photo.readyAt !== null &&
                    DateTime.isGreaterThan(photo.readyAt, after),
                )
                .sort((a, b) => newestFirst(b, a))
                .map(publicPhoto),
            ),
          ),
        get: (id) => find(id).pipe(Effect.map(publicPhoto)),
        image,
        remove: (id) =>
          find(id).pipe(
            Effect.flatMap((photo) => forget(photo).pipe(Effect.orDie)),
            lock.withPermit,
          ),
        expire,
      };
    }),
  );
}
