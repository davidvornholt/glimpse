import { BunHttpServer, BunRuntime } from '@effect/platform-bun';
import { Layer } from 'effect';
import { listenOptions, ServerConfig } from '../shared/config.ts';
import { serve } from './server.ts';

serve(ServerConfig.fromEnvironment).pipe(
  Layer.provide(BunHttpServer.layerConfig(listenOptions)),
  Layer.launch,
  BunRuntime.runMain,
);
