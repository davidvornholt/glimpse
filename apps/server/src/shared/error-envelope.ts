import { Cause, Effect } from 'effect';
import { HttpServerError, HttpServerResponse } from 'effect/http';
import { HttpApiError } from 'effect/http-api';

type Envelope = { readonly tag: string; readonly message: string };

const defaults: Record<number, Envelope> = {
  400: { tag: 'InvalidRequest', message: 'The request is not valid.' },
  401: { tag: 'Unauthorized', message: 'A valid bearer token is needed.' },
  404: { tag: 'NotFound', message: 'There is nothing at this address.' },
  405: { tag: 'NotFound', message: 'This address does not take this method.' },
  413: { tag: 'TooLarge', message: 'The request body is too large.' },
  415: {
    tag: 'InvalidRequest',
    message: 'The Content-Type of this request is not one this address takes.',
  },
  500: { tag: 'ServerError', message: 'Something went wrong on the server.' },
};

const firstError = 400;
const firstServerError = 500;

const fallback = (status: number): Envelope =>
  defaults[status] ?? {
    tag: status >= firstServerError ? 'ServerError' : 'InvalidRequest',
    message: `The server answered ${status}.`,
  };

const whitespaceAroundNewlines = /\s*\n\s*/gu;

const failureOf = (reason: Cause.Reason<unknown>) => {
  if (reason._tag === 'Fail') {
    return reason.error;
  }
  return reason._tag === 'Die' ? reason.defect : undefined;
};

const isJson = (response: HttpServerResponse.HttpServerResponse) =>
  response.body._tag !== 'Empty' &&
  (response.body.contentType ?? '').includes('json');

const envelope = (status: number, error: Envelope) =>
  HttpServerResponse.jsonUnsafe({ error }, { status });

/** A clearer message for the errors Effect raises itself, when there is one. */
const frameworkMessage = (cause: Cause.Cause<unknown>) => {
  for (const reason of cause.reasons) {
    const error = failureOf(reason);
    if (HttpApiError.HttpApiSchemaError.is(error)) {
      // Such as `Query: Expected a value between 1 and 100 at ["limit"]`.
      return `${error.kind}: ${error.cause.message.replace(whitespaceAroundNewlines, ' ')}`;
    }
    if (HttpApiError.HttpApiPayloadTooLarge.is(error)) {
      return `The body is larger than the ${error.size} bytes this address takes.`;
    }
  }
};

/**
 * Gives every error response the same JSON body as the API's own errors,
 * `{"error":{"tag","message"}}`, including those Effect makes itself: a request
 * the schemas refused, an unknown route, a body too large, or a defect.
 *
 * It runs as global router middleware, inside the server's own handling, so
 * it sees the failure before the server turns it into an empty response.
 */
export const errorEnvelope = <E, R>(
  app: Effect.Effect<HttpServerResponse.HttpServerResponse, E, R>,
): Effect.Effect<HttpServerResponse.HttpServerResponse, E, R> =>
  app.pipe(
    Effect.catchCause((cause) =>
      Cause.hasInterruptsOnly(cause)
        ? Effect.failCause(cause)
        : HttpServerError.causeResponse(cause).pipe(
            Effect.tap(([response, reported]) =>
              response.status >= firstServerError
                ? Effect.logError('Request failed', reported)
                : Effect.void,
            ),
            Effect.map(([response]) => {
              const message = frameworkMessage(cause);
              return message === undefined || isJson(response)
                ? response
                : envelope(response.status, {
                    ...fallback(response.status),
                    message,
                  });
            }),
          ),
    ),
    Effect.map((response) =>
      response.status < firstError || isJson(response)
        ? response
        : envelope(response.status, fallback(response.status)),
    ),
  );
