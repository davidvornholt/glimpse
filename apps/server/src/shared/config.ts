import { defaultRetentionDays } from '@glimpse/api/photo';
import { Config, Context, Effect, Layer } from 'effect';

export type ServerSettings = {
  /** Where photos and paired devices are kept. */
  readonly dataDirectory: string;
  /** The address phones use, which goes into pairing links. */
  readonly publicUrl: string;
  /** SHA-256 digests, in hex, of the tokens that act as the owner. */
  readonly ownerTokenDigests: ReadonlyArray<string>;
  readonly retentionDays: number;
};

export class ServerConfig extends Context.Service<
  ServerConfig,
  ServerSettings
>()('@glimpse/server/ServerConfig') {
  static readonly layer = (settings: ServerSettings) =>
    Layer.succeed(ServerConfig, settings);

  /**
   * Reads the settings from the environment. Every setting but the retention
   * is required, so a misconfigured server stops at once instead of serving.
   */
  static readonly fromEnvironment = Layer.effect(
    ServerConfig,
    Effect.gen(function* () {
      const dataDirectory = yield* Config.NonEmptyString('GLIMPSE_DATA_DIR');
      const publicUrl = yield* Config.URL('GLIMPSE_PUBLIC_URL');
      const digests = yield* Config.NonEmptyString(
        'GLIMPSE_OWNER_TOKEN_SHA256',
      );
      const retentionDays = yield* Config.Int('GLIMPSE_RETENTION_DAYS').pipe(
        Config.withDefault(defaultRetentionDays),
      );
      const ownerTokenDigests = digests
        .split(',')
        .map((digest) => digest.trim().toLowerCase())
        .filter((digest) => digest !== '');
      if (!ownerTokenDigests.every((digest) => sha256Hex.test(digest))) {
        return yield* Effect.die(
          new Error(
            'GLIMPSE_OWNER_TOKEN_SHA256 must list SHA-256 digests in hex, separated by commas.',
          ),
        );
      }
      if (retentionDays < 1) {
        return yield* Effect.die(
          new Error('GLIMPSE_RETENTION_DAYS must be at least 1.'),
        );
      }
      return {
        dataDirectory,
        publicUrl: publicUrl.origin,
        ownerTokenDigests,
        retentionDays,
      };
    }),
  );
}

const sha256Hex = /^[0-9a-f]{64}$/u;

export const defaultPort = 8787;

export const listenOptions = Config.all({
  port: Config.Port('PORT').pipe(Config.withDefault(defaultPort)),
  hostname: Config.NonEmptyString('GLIMPSE_HOST').pipe(
    Config.withDefault('0.0.0.0'),
  ),
});
