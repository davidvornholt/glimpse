import { BunHttpServer, BunRuntime } from '@effect/platform-bun';
import { Cause, Exit, Layer, Runtime } from 'effect';
import { listenOptions, ServerConfig } from '../shared/config.ts';
import { serve } from './server.ts';

// A stop signal interrupts the server, which then closes its connections and
// ends. That is how it is meant to stop, so it exits 0 rather than the 130
// the default reports for an interruption, which a release counts as a crash.
const teardown: Runtime.Teardown = (exit, onExit) =>
  Exit.isFailure(exit) && Cause.hasInterruptsOnly(exit.cause)
    ? onExit(0)
    : Runtime.defaultTeardown(exit, onExit);

serve(ServerConfig.fromEnvironment).pipe(
  Layer.provide(BunHttpServer.layerConfig(listenOptions)),
  Layer.launch,
  (program) => BunRuntime.runMain(program, { teardown }),
);
