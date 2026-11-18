import { Schema } from 'effect';

/** How long the server keeps a photo, unless it is configured otherwise. */
export const defaultRetentionDays = 30;
export const noteLength = 2000;
/** 4 MiB. */
export const maxPreviewBytes = 4_194_304;
export const maxPreviewEdge = 2048;
/** 50 MiB. */
export const maxOriginalBytes = 52_428_800;
export const maxPage = 100;

/**
 * A photo's id, a ULID the phone makes before the first upload, so a retried
 * upload names the same photo: 26 characters of Crockford's base32, upper case.
 */
export const PhotoIdSchema = Schema.String.check(
  Schema.isPattern(/^[0-7][0-9A-HJKMNP-TV-Z]{25}$/u, {
    message:
      'Expected a ULID in upper case, such as 01JD3V8Q2W5X7Y9Z0A1B2C3D4E',
  }),
);

export const photoSources = ['camera', 'scan', 'share'] as const;
export const PhotoSourceSchema = Schema.Literals(photoSources);
export type PhotoSource = typeof PhotoSourceSchema.Type;

export const originalTypes = [
  'image/jpeg',
  'image/png',
  'image/webp',
  'image/heic',
  'image/heif',
  'image/avif',
] as const;
export const OriginalTypeSchema = Schema.Literals(originalTypes);
export type OriginalType = typeof OriginalTypeSchema.Type;

/** The file extension an original of each type is saved with. */
export const originalExtensions: Readonly<Record<OriginalType, string>> = {
  'image/jpeg': 'jpg',
  'image/png': 'png',
  'image/webp': 'webp',
  'image/heic': 'heic',
  'image/heif': 'heif',
  'image/avif': 'avif',
};

const DimensionSchema = Schema.Int.check(
  Schema.isBetween({ minimum: 1, maximum: 100_000 }),
);

export const NoteSchema = Schema.String.check(Schema.isMaxLength(noteLength));

export const BatchIdSchema = Schema.String.check(
  Schema.isPattern(/^[A-Za-z0-9_-]{1,64}$/u, {
    message: 'Expected 1 to 64 letters, digits, - or _',
  }),
);

/**
 * What the phone says about a photo before it sends the image: `PUT
 * /v1/photos/{id}`. Photos taken together, such as the pages of one scan,
 * share a `batchId`.
 */
export const PhotoMetadataSchema = Schema.Struct({
  takenAt: Schema.DateTimeUtc,
  source: PhotoSourceSchema,
  note: Schema.optionalKey(NoteSchema),
  width: DimensionSchema,
  height: DimensionSchema,
  originalType: OriginalTypeSchema,
  batchId: Schema.optionalKey(BatchIdSchema),
});
export type PhotoMetadata = typeof PhotoMetadataSchema.Type;

export const NotePatchSchema = Schema.Struct({
  note: Schema.NullOr(NoteSchema),
});

/**
 * A photo as the server describes it. It is listed once `readyAt` is set,
 * which happens when its preview arrives. `viewedAt` is when the owner, which
 * means an agent through the CLI, first fetched its preview or original.
 */
export const PhotoSchema = Schema.Struct({
  id: PhotoIdSchema,
  createdAt: Schema.DateTimeUtc,
  readyAt: Schema.NullOr(Schema.DateTimeUtc),
  takenAt: Schema.DateTimeUtc,
  expiresAt: Schema.DateTimeUtc,
  source: PhotoSourceSchema,
  note: Schema.NullOr(NoteSchema),
  width: DimensionSchema,
  height: DimensionSchema,
  originalType: OriginalTypeSchema,
  hasOriginal: Schema.Boolean,
  originalBytes: Schema.NullOr(Schema.Int),
  previewBytes: Schema.NullOr(Schema.Int),
  deviceName: Schema.NullOr(Schema.String),
  viewedAt: Schema.NullOr(Schema.DateTimeUtc),
  batchId: Schema.NullOr(BatchIdSchema),
});
export type Photo = typeof PhotoSchema.Type;

export const PhotoPageSchema = Schema.Struct({
  photos: Schema.Array(PhotoSchema),
  /** Pass it as `before` for the next, older page; null on the last page. */
  nextCursor: Schema.NullOr(Schema.String),
});

export const WaitResultSchema = Schema.Struct({
  photos: Schema.Array(PhotoSchema),
});
