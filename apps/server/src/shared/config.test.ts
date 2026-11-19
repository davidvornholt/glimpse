import { describe, expect, it } from 'bun:test';
import { ConfigProvider, Effect, Exit, Layer } from 'effect';
import { ServerConfig } from './config.ts';

const digest = 'a'.repeat(64);

const configFor = (publicUrl: string) =>
  Effect.runPromiseExit(
    Effect.gen(function* () {
      return yield* ServerConfig;
    }).pipe(
      Effect.provide(
        ServerConfig.fromEnvironment.pipe(
          Layer.provide(
            ConfigProvider.layer(
              ConfigProvider.fromEnv({
                env: Object.fromEntries([
                  ['GLIMPSE_DATA_DIR', '/data'],
                  ['GLIMPSE_PUBLIC_URL', publicUrl],
                  ['GLIMPSE_OWNER_TOKEN_SHA256', digest],
                ]),
              }),
            ),
          ),
        ),
      ),
    ),
  );

describe('the public address', () => {
  it('is the origin, with or without a trailing slash', async () => {
    const exits = await Promise.all(
      ['https://glimpse.example.com', 'https://glimpse.example.com/'].map(
        configFor,
      ),
    );
    for (const exit of exits) {
      expect(Exit.isSuccess(exit) && exit.value.publicUrl).toBe(
        'https://glimpse.example.com',
      );
    }
  });

  it('stops the server when it has a path, which pairing links would lose', async () => {
    const exit = await configFor('https://glimpse.example.com/glimpse');
    expect(Exit.isFailure(exit)).toBe(true);
    expect(String(Exit.isFailure(exit) && exit.cause)).toContain(
      'without a path',
    );
  });
});
