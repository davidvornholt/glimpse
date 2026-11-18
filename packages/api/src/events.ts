import { Schema } from 'effect';
import { PhotoIdSchema, PhotoSchema } from './photo.ts';

/** How often `GET /v1/events` sends a comment so proxies keep it open. */
export const keepAliveSeconds = 20;

const json = <S extends Schema.Top>(schema: S) =>
  Schema.fromJsonString(Schema.toCodecJson(schema));

/**
 * A ready photo arrived or changed, such as its note, its original, or its
 * first view. Photos still waiting for their preview are not announced.
 */
export const PhotoEventSchema = Schema.Struct({
  event: Schema.Literal('photo'),
  data: json(PhotoSchema),
});

export const PhotoDeletedEventSchema = Schema.Struct({
  event: Schema.Literal('photo-deleted'),
  data: json(Schema.Struct({ id: PhotoIdSchema })),
});

/**
 * How many agents are waiting for a photo with `glimpse wait`. Sent when the
 * stream opens and whenever the number changes.
 */
export const WaitingEventSchema = Schema.Struct({
  event: Schema.Literal('waiting'),
  data: json(Schema.Struct({ count: Schema.Int })),
});

export const EventSchema = Schema.Union([
  PhotoEventSchema,
  PhotoDeletedEventSchema,
  WaitingEventSchema,
]);
export type Event = typeof EventSchema.Type;
