import { Schema } from 'effect';
import {
  HttpApi,
  HttpApiEndpoint,
  HttpApiGroup,
  HttpApiSchema,
} from 'effect/http-api';
import { Authorization } from './authorization.ts';
import {
  ClaimPayloadSchema,
  ClaimResultSchema,
  DeviceIdSchema,
  DeviceSchema,
  MeSchema,
  PairingSchema,
} from './device.ts';
import {
  ConflictBody,
  ForbiddenBody,
  InvalidRequestBody,
  NotFoundBody,
  RateLimitedBody,
  TooLargeBody,
} from './errors/api-errors.ts';
import { EventSchema, keepAliveSeconds } from './events.ts';
import {
  maxOriginalBytes,
  maxPage,
  maxPreviewBytes,
  NotePatchSchema,
  originalTypes,
  PhotoIdSchema,
  PhotoMetadataSchema,
  PhotoPageSchema,
  PhotoSchema,
  WaitResultSchema,
} from './photo.ts';

/** The longest one `GET /v1/photos/wait` holds the request open. */
export const maxWaitSeconds = 60;

const photoParams = { id: PhotoIdSchema };

const created = 201;

const LimitSchema = Schema.FiniteFromString.check(
  Schema.isInt(),
  Schema.isBetween({ minimum: 1, maximum: maxPage }),
);

const WaitSecondsSchema = Schema.FiniteFromString.check(
  Schema.isInt(),
  Schema.isBetween({ minimum: 0, maximum: maxWaitSeconds }),
);

const TimeSchema = Schema.DateTimeUtcFromString;

export const HealthApi = HttpApiGroup.make('health').add(
  HttpApiEndpoint.get('health', '/healthz', {
    success: Schema.Struct({ status: Schema.Literal('ok') }),
  }),
);

/** Claiming a code is how a phone gets its token, so it needs none. */
export const PairingApi = HttpApiGroup.make('pairing')
  .add(
    HttpApiEndpoint.post('claim', '/pairings/claim', {
      payload: ClaimPayloadSchema,
      success: ClaimResultSchema.pipe(HttpApiSchema.status(created)),
      error: [InvalidRequestBody, NotFoundBody, RateLimitedBody],
    }),
  )
  .prefix('/v1');

export const AccountApi = HttpApiGroup.make('account')
  .add(HttpApiEndpoint.get('me', '/me', { success: MeSchema }))
  .middleware(Authorization)
  .prefix('/v1');

/** Pairing phones and removing them is for the owner only. */
export const DevicesApi = HttpApiGroup.make('devices')
  .add(
    HttpApiEndpoint.post('createPairing', '/pairings', {
      success: PairingSchema.pipe(HttpApiSchema.status(created)),
      error: ForbiddenBody,
    }),
    HttpApiEndpoint.get('list', '/devices', {
      success: Schema.Array(DeviceSchema),
      error: ForbiddenBody,
    }),
    HttpApiEndpoint.delete('remove', '/devices/:id', {
      params: { id: DeviceIdSchema },
      success: HttpApiSchema.NoContent,
      error: [ForbiddenBody, NotFoundBody],
    }),
  )
  .middleware(Authorization)
  .prefix('/v1');

const imageStream = (contentType: string | ReadonlyArray<string>) =>
  HttpApiSchema.StreamUint8Array({ contentType });

export const PhotosApi = HttpApiGroup.make('photos')
  .add(
    /**
     * Registers a photo before its images. Sending the same metadata again
     * answers 200, so a phone can retry; different metadata is a conflict.
     * The server answers 201 the first time.
     */
    HttpApiEndpoint.put('register', '/photos/:id', {
      params: photoParams,
      payload: PhotoMetadataSchema,
      success: PhotoSchema,
      error: [ForbiddenBody, ConflictBody, InvalidRequestBody],
    }),
    /** A JPEG of at most 2048 px on its longest edge. It makes the photo ready. */
    HttpApiEndpoint.put('putPreview', '/photos/:id/preview', {
      params: photoParams,
      payload: HttpApiSchema.StreamUint8Array({
        contentType: 'image/jpeg',
        maxBytes: maxPreviewBytes,
      }),
      success: PhotoSchema,
      error: [ForbiddenBody, NotFoundBody, InvalidRequestBody, TooLargeBody],
    }),
    /** The full image, sent with the `originalType` registered for it. */
    HttpApiEndpoint.put('putOriginal', '/photos/:id/original', {
      params: photoParams,
      payload: HttpApiSchema.StreamUint8Array({
        contentType: originalTypes,
        maxBytes: maxOriginalBytes,
      }),
      success: PhotoSchema,
      error: [ForbiddenBody, NotFoundBody, InvalidRequestBody, TooLargeBody],
    }),
    HttpApiEndpoint.patch('setNote', '/photos/:id', {
      params: photoParams,
      payload: NotePatchSchema,
      success: PhotoSchema,
      error: [NotFoundBody, InvalidRequestBody],
    }),
    /** Ready photos, the newest to arrive first. */
    HttpApiEndpoint.get('list', '/photos', {
      query: {
        limit: Schema.optionalKey(LimitSchema),
        before: Schema.optionalKey(Schema.String),
        since: Schema.optionalKey(TimeSchema),
      },
      success: PhotoPageSchema,
      error: InvalidRequestBody,
    }),
    /**
     * Answers as soon as a photo nobody has looked at arrives after `after`,
     * or with no photos once `timeoutSeconds` pass. Pass the same `waiter`
     * on every request of one wait, so the phone counts it once.
     */
    HttpApiEndpoint.get('wait', '/photos/wait', {
      query: {
        after: TimeSchema,
        timeoutSeconds: Schema.optionalKey(WaitSecondsSchema),
        waiter: Schema.optionalKey(
          Schema.String.check(Schema.isPattern(/^[A-Za-z0-9_-]{1,64}$/u)),
        ),
      },
      success: WaitResultSchema,
      error: ForbiddenBody,
    }),
    HttpApiEndpoint.get('get', '/photos/:id', {
      params: photoParams,
      success: PhotoSchema,
      error: NotFoundBody,
    }),
    HttpApiEndpoint.get('getPreview', '/photos/:id/preview', {
      params: photoParams,
      success: imageStream('image/jpeg'),
      error: NotFoundBody,
    }),
    HttpApiEndpoint.get('getOriginal', '/photos/:id/original', {
      params: photoParams,
      success: imageStream(originalTypes),
      error: NotFoundBody,
    }),
    HttpApiEndpoint.delete('remove', '/photos/:id', {
      params: photoParams,
      success: HttpApiSchema.NoContent,
      error: NotFoundBody,
    }),
  )
  .middleware(Authorization)
  .prefix('/v1');

export const EventsApi = HttpApiGroup.make('events')
  .add(
    HttpApiEndpoint.get('events', '/events', {
      success: HttpApiSchema.StreamSse({
        events: EventSchema,
        keepAlive: `${keepAliveSeconds} seconds`,
      }),
    }),
  )
  .middleware(Authorization)
  .prefix('/v1');

export const GlimpseApi = HttpApi.make('glimpse').add(
  HealthApi,
  PairingApi,
  AccountApi,
  DevicesApi,
  PhotosApi,
  EventsApi,
);
