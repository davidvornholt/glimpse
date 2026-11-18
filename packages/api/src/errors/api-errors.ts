import { Schema, SchemaTransformation } from 'effect';

/**
 * Every error the server answers has the same body, which is also what the
 * CLI prints on stderr: `{"error":{"tag":"NotFound","message":"…"}}`. The
 * message is written for the person using a client, so a client may show it
 * as it is. Clients should branch on the status code or the tag.
 */
export const errorBody = <const Tag extends string>(tag: Tag) =>
  Schema.Struct({
    error: Schema.Struct({ tag: Schema.Literal(tag), message: Schema.String }),
  });

/** The body of an error whose tag a client may not know yet. */
export const AnyErrorBody = Schema.Struct({
  error: Schema.Struct({ tag: Schema.String, message: Schema.String }),
});

const onTheWire = <
  const Tag extends string,
  Self extends {
    readonly _tag: Tag;
    readonly message: string;
  },
>(
  tag: Tag,
  error: Schema.Codec<Self, { readonly _tag: Tag; readonly message: string }>,
) =>
  errorBody(tag).pipe(
    Schema.decodeTo(
      error,
      SchemaTransformation.transform({
        decode: (body) => ({ _tag: tag, message: body.error.message }),
        encode: (encoded) => ({ error: { tag, message: encoded.message } }),
      }),
    ),
  );

/** The request breaks a rule of the API, such as a note that is too long. */
export class InvalidRequest extends Schema.TaggedError<InvalidRequest>()(
  'InvalidRequest',
  { message: Schema.String },
  { httpApiStatus: 400 },
) {}

/** No token, or one the server doesn't know. */
export class Unauthorized extends Schema.TaggedError<Unauthorized>()(
  'Unauthorized',
  { message: Schema.String },
  { httpApiStatus: 401 },
) {}

/** The token is valid but may not do this, such as a phone pairing another. */
export class Forbidden extends Schema.TaggedError<Forbidden>()(
  'Forbidden',
  { message: Schema.String },
  { httpApiStatus: 403 },
) {}

export class NotFound extends Schema.TaggedError<NotFound>()(
  'NotFound',
  { message: Schema.String },
  { httpApiStatus: 404 },
) {}

/** The request contradicts what the server already has. */
export class Conflict extends Schema.TaggedError<Conflict>()(
  'Conflict',
  { message: Schema.String },
  { httpApiStatus: 409 },
) {}

export class TooLarge extends Schema.TaggedError<TooLarge>()(
  'TooLarge',
  { message: Schema.String },
  { httpApiStatus: 413 },
) {}

export class RateLimited extends Schema.TaggedError<RateLimited>()(
  'RateLimited',
  { message: Schema.String },
  { httpApiStatus: 429 },
) {}

/** Anything unexpected. The details stay in the server's log. */
export class ServerError extends Schema.TaggedError<ServerError>()(
  'ServerError',
  { message: Schema.String },
  { httpApiStatus: 500 },
) {}

export const InvalidRequestBody = onTheWire('InvalidRequest', InvalidRequest);
export const UnauthorizedBody = onTheWire('Unauthorized', Unauthorized);
export const ForbiddenBody = onTheWire('Forbidden', Forbidden);
export const NotFoundBody = onTheWire('NotFound', NotFound);
export const ConflictBody = onTheWire('Conflict', Conflict);
export const TooLargeBody = onTheWire('TooLarge', TooLarge);
export const RateLimitedBody = onTheWire('RateLimited', RateLimited);
export const ServerErrorBody = onTheWire('ServerError', ServerError);

export type ApiError =
  | InvalidRequest
  | Unauthorized
  | Forbidden
  | NotFound
  | Conflict
  | TooLarge
  | RateLimited
  | ServerError;
