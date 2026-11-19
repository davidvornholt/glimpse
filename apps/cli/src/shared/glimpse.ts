import { AnyErrorBody } from '@glimpse/api/errors';
import { Context, Effect, Layer, Option, Schema } from 'effect';
import {
  FetchHttpClient,
  HttpClient,
  type HttpClientError,
  HttpClientRequest,
  type HttpClientResponse,
} from 'effect/http';
import { type Credentials, readCredentials } from './credentials.ts';
import {
  type GlimpseFailure,
  NotSignedIn,
  ServerRejected,
  ServerUnreachable,
  UnexpectedResponse,
} from './errors/errors.ts';

/**
 * HTTP for every request. A redirect comes back as its own answer instead of
 * being followed, because every request carries the owner token, which only
 * the Glimpse server may see.
 */
export const httpLayer = FetchHttpClient.layer.pipe(
  Layer.provide(
    Layer.succeed(FetchHttpClient.RequestInit, { redirect: 'manual' }),
  ),
);

export type GlimpseRequest = {
  readonly method: 'GET' | 'POST' | 'PATCH' | 'DELETE';
  /** Such as `/v1/photos/{id}`. */
  readonly path: string;
  readonly query?: Readonly<Record<string, string | undefined>>;
  /** Sent as JSON. */
  readonly body?: unknown;
};

/** The Glimpse API, signed in with this machine's owner token. */
export class Glimpse extends Context.Service<
  Glimpse,
  {
    readonly server: string;
    /** Sends a request and fails unless the server answers with a 2xx status. */
    readonly execute: (
      request: GlimpseRequest,
    ) => Effect.Effect<HttpClientResponse.HttpClientResponse, GlimpseFailure>;
  }
>()('@glimpse/cli/shared/Glimpse') {}

export const notSignedIn = new NotSignedIn({
  message:
    'This machine has no Glimpse owner token. Run `glimpse login --server <address of your Glimpse server>` and paste the token.',
});

export const unexpected = (server: string, detail: string) =>
  new UnexpectedResponse({
    message: `${server} answered in a way glimpse does not understand (${detail}). Update glimpse or check the server.`,
  });

const unauthorized = 401;
const hundreds = 100;
const statusClass = (status: number) => Math.floor(status / hundreds);
const success = 2;
const redirection = 3;

const decodeErrorBody = Schema.decodeUnknownEffect(AnyErrorBody);

/** The failure the server's answer stands for, in the server's own words. */
const failureFor = (
  server: string,
  response: HttpClientResponse.HttpClientResponse,
) =>
  Effect.gen(function* () {
    const { status, headers } = response;
    if (status === unauthorized) {
      return new NotSignedIn({
        message: `${server} does not accept the owner token on this machine. Run \`glimpse login\` with the token the server is set up with.`,
      });
    }
    if (statusClass(status) === redirection) {
      const { location } = headers;
      return new UnexpectedResponse({
        message: `${server} redirected the request${location === undefined ? '' : ` to ${location}`}. glimpse never follows a redirect, so the owner token stays with the server. Check the server address.`,
      });
    }
    const body = yield* response.json.pipe(
      Effect.flatMap(decodeErrorBody),
      Effect.option,
    );
    return Option.match(body, {
      onNone: (): GlimpseFailure =>
        unexpected(server, `HTTP ${status} without an explanation`),
      onSome: ({ error }): GlimpseFailure =>
        new ServerRejected({ tag: error.tag, message: error.message, status }),
    });
  });

const unreachable =
  (server: string) => (error: HttpClientError.HttpClientError) =>
    error.reason._tag === 'TransportError'
      ? new ServerUnreachable({
          message: `Could not reach ${server}: ${error.message}`,
        })
      : unexpected(server, error.message);

/** The API of one server with one token, for checking a token before it is saved. */
export const makeGlimpse = (credentials: Credentials) =>
  Effect.gen(function* () {
    const { server, token } = credentials;
    const client = (yield* HttpClient.HttpClient).pipe(
      HttpClient.mapRequest((outgoing) =>
        outgoing.pipe(
          HttpClientRequest.prependUrl(server),
          HttpClientRequest.bearerToken(token),
        ),
      ),
    );
    const execute = Effect.fn('Glimpse.execute')(function* (
      request: GlimpseRequest,
    ) {
      const urlParams = Object.fromEntries(
        Object.entries(request.query ?? {}).flatMap(([key, value]) =>
          value === undefined ? [] : [[key, value]],
        ),
      );
      const base = HttpClientRequest.make(request.method)(request.path, {
        urlParams,
      });
      const outgoing =
        request.body === undefined
          ? base
          : HttpClientRequest.bodyJsonUnsafe(base, request.body);
      const response = yield* client
        .execute(outgoing)
        .pipe(Effect.mapError(unreachable(server)));
      if (statusClass(response.status) === success) {
        return response;
      }
      return yield* yield* failureFor(server, response);
    });
    return Glimpse.of({ server, execute });
  });

/** The API of the server this machine signed in to. */
export const glimpseLayer = Layer.effect(
  Glimpse,
  Effect.gen(function* () {
    const credentials = yield* readCredentials;
    if (Option.isNone(credentials)) {
      return yield* notSignedIn;
    }
    return yield* makeGlimpse(credentials.value);
  }),
).pipe(Layer.provide(httpLayer));

/** Sends a request and decodes the server's JSON answer. */
export const requestJson = <A>(
  request: GlimpseRequest,
  schema: Schema.Decoder<A>,
) =>
  Effect.gen(function* () {
    const { server, execute } = yield* Glimpse;
    const response = yield* execute(request);
    const json = yield* response.json.pipe(
      Effect.mapError(() => unexpected(server, `${request.path} sent no JSON`)),
    );
    return yield* Schema.decodeUnknownEffect(schema)(json).pipe(
      Effect.mapError((error) =>
        unexpected(server, `${request.path}: ${error.message}`),
      ),
    );
  });

/** Sends a request whose answer has no body worth reading. */
export const requestNothing = (request: GlimpseRequest) =>
  Effect.gen(function* () {
    const { execute } = yield* Glimpse;
    yield* execute(request);
  });
