import { BunHttpServer, BunRuntime } from '@effect/platform-bun';
import { defaultRetentionDays } from '@glimpse/api/photo';
import { Config, Layer } from 'effect';
import { defaultPort, ServerConfig } from '../shared/config.ts';
import { tokenDigest } from '../shared/tokens.ts';
import { serve } from './server.ts';

/**
 * A server for working on the app and the CLI: it keeps its data in
 * `apps/server/.data`, accepts the owner token `dev-owner-token`, and puts the
 * address the Android emulator reaches this machine by into pairing links.
 */
const settings = ServerConfig.layer({
  dataDirectory: new URL('../../.data', import.meta.url).pathname,
  publicUrl: `http://10.0.2.2:${defaultPort}`,
  ownerTokenDigests: [tokenDigest('dev-owner-token')],
  retentionDays: defaultRetentionDays,
});

const listen = Config.all({
  port: Config.Port('PORT').pipe(Config.withDefault(defaultPort)),
  hostname: Config.NonEmptyString('GLIMPSE_HOST').pipe(
    Config.withDefault('127.0.0.1'),
  ),
});

serve(settings).pipe(
  Layer.provide(BunHttpServer.layerConfig(listen)),
  Layer.launch,
  BunRuntime.runMain,
);
