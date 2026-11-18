import type { Device } from '@glimpse/api/device';
import {
  Conflict,
  Forbidden,
  InvalidRequest,
  TooLarge,
} from '@glimpse/api/errors';
import { maxPreviewEdge, type PhotoMetadata } from '@glimpse/api/photo';
import { DateTime, Effect, Option, Stream } from 'effect';
import type { HttpApiError } from 'effect/http-api';
import {
  headBytes,
  jpegSize,
  looksLike,
  withoutMetadata,
} from './image-checks.ts';
import {
  publicPhoto,
  type StoredPhoto,
  sameRegistration,
} from './photo-records.ts';
import { notFound, type PhotoState } from './photo-state.ts';

export type Upload = Stream.Stream<
  Uint8Array,
  HttpApiError.HttpApiPayloadTooLarge
>;

const tooLarge =
  (what: string) => (error: HttpApiError.HttpApiPayloadTooLarge) =>
    new TooLarge({
      message: `The ${what} is larger than ${error.size} bytes.`,
    });

const collect = (body: Upload) =>
  body.pipe(
    Stream.runFold(
      () => [] as Array<Uint8Array>,
      (chunks, chunk) => {
        chunks.push(chunk);
        return chunks;
      },
    ),
    Effect.map((chunks) => Buffer.concat(chunks)),
    Effect.mapError(tooLarge('preview')),
  );

/**
 * A preview fit to keep: a whole JPEG of the allowed size, without the camera
 * metadata the phone should have left out already.
 */
const cleanPreview = (bytes: Uint8Array) =>
  Effect.gen(function* () {
    const clean = withoutMetadata(bytes);
    const size = Option.flatMap(clean, jpegSize);
    if (Option.isNone(clean) || Option.isNone(size)) {
      return yield* new InvalidRequest({
        message: 'The preview is not a whole JPEG.',
      });
    }
    const { width, height } = size.value;
    if (Math.max(width, height) > maxPreviewEdge) {
      return yield* new InvalidRequest({
        message: `The preview is ${width}×${height} px; its longest edge may be ${maxPreviewEdge} px at most.`,
      });
    }
    return clean.value;
  });

type NewPhoto = {
  readonly id: string;
  readonly metadata: PhotoMetadata;
  readonly device: Device;
  readonly createdAt: DateTime.Utc;
  readonly retentionDays: number;
};

const newPhoto = ({
  id,
  metadata,
  device,
  createdAt,
  retentionDays,
}: NewPhoto): StoredPhoto => ({
  id,
  createdAt,
  readyAt: null,
  takenAt: metadata.takenAt,
  expiresAt: DateTime.add(createdAt, { days: retentionDays }),
  source: metadata.source,
  note: metadata.note ?? null,
  width: metadata.width,
  height: metadata.height,
  originalType: metadata.originalType,
  hasOriginal: false,
  originalBytes: null,
  previewBytes: null,
  deviceName: device.name,
  viewedAt: null,
  batchId: metadata.batchId ?? null,
  deviceId: device.id,
});

/**
 * How a phone sends a photo: it registers the photo's details under an id it
 * made, then sends the preview, which makes the photo ready, and then the
 * original. Each step can be repeated after a dropped connection.
 */
export const makeUploads = (state: PhotoState, retentionDays: number) => {
  const { disk, files, find, store, nextReadyAt, lock } = state;

  const uploadable = (id: string, device: Device) =>
    find(id).pipe(
      Effect.filterOrFail(
        (photo) => photo.deviceId === device.id,
        () =>
          new Forbidden({
            message: `Photo ${id} was registered by another phone.`,
          }),
      ),
    );

  const register = (id: string, metadata: PhotoMetadata, device: Device) =>
    Effect.gen(function* () {
      const existing = yield* find(id).pipe(Effect.option);
      if (Option.isSome(existing)) {
        const same =
          existing.value.deviceId === device.id &&
          sameRegistration(existing.value, metadata);
        return same
          ? { photo: publicPhoto(existing.value), created: false }
          : yield* new Conflict({
              message: `Photo ${id} already exists with other details. Use a new id.`,
            });
      }
      const photo = newPhoto({
        id,
        metadata,
        device,
        createdAt: yield* DateTime.now,
        retentionDays,
      });
      yield* disk.fs
        .makeDirectory(files(photo).directory, { recursive: true })
        .pipe(Effect.orDie);
      yield* store(photo);
      return { photo: publicPhoto(photo), created: true };
    }).pipe(lock.withPermit);

  const putPreview = (id: string, device: Device, body: Upload) =>
    Effect.gen(function* () {
      yield* uploadable(id, device);
      const bytes = yield* cleanPreview(yield* collect(body));
      return yield* Effect.gen(function* () {
        const photo = yield* uploadable(id, device);
        yield* disk
          .writeAtomically(files(photo).preview, bytes)
          .pipe(Effect.orDie);
        const stored = yield* store({
          ...photo,
          previewBytes: bytes.length,
          readyAt: photo.readyAt ?? (yield* nextReadyAt),
        });
        return publicPhoto(stored);
      }).pipe(lock.withPermit);
    });

  /** Moves a checked draft into place as the photo's original. */
  const keepOriginal = (id: string, device: Device, draft: string) =>
    Effect.gen(function* () {
      const photo = yield* uploadable(id, device);
      const head = yield* disk.readHead(draft, headBytes).pipe(Effect.orDie);
      if (!looksLike(photo.originalType, head)) {
        return yield* new InvalidRequest({
          message: `The original is not a ${photo.originalType} image.`,
        });
      }
      const info = yield* disk.fs.stat(draft).pipe(Effect.orDie);
      yield* disk.fs.rename(draft, files(photo).original).pipe(Effect.orDie);
      const stored = yield* store({
        ...photo,
        hasOriginal: true,
        originalBytes: Number(info.size),
      });
      return publicPhoto(stored);
    }).pipe(lock.withPermit);

  const putOriginal = (
    id: string,
    device: Device,
    contentType: string,
    body: Upload,
  ) =>
    Effect.gen(function* () {
      const registered = yield* uploadable(id, device);
      if (contentType !== registered.originalType) {
        return yield* new InvalidRequest({
          message: `Photo ${id} was registered as ${registered.originalType}, not ${contentType}.`,
        });
      }
      const draft = yield* disk
        .streamToDraft(files(registered).directory, body)
        .pipe(
          Effect.catchTag('HttpApiPayloadTooLarge', (error) =>
            Effect.fail(tooLarge('original')(error)),
          ),
          Effect.catchTag('PlatformError', () => Effect.fail(notFound(id))),
        );
      return yield* keepOriginal(id, device, draft).pipe(
        Effect.ensuring(
          disk.fs.remove(draft, { force: true }).pipe(Effect.ignore),
        ),
      );
    });

  return { register, putPreview, putOriginal };
};
