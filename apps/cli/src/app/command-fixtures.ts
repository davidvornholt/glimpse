import type { spyOn as SpyOn } from 'bun:test';
import { mkdir, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import process from 'node:process';
import { BunServices } from '@effect/platform-bun';
import {
  startTestServer,
  type TestServer,
  testOrigin,
  testOwnerToken,
} from '@glimpse/server/testing';
import {
  ConfigProvider,
  DateTime,
  Effect,
  FileSystem,
  Stdio,
  Stream,
} from 'effect';
import { Command } from 'effect/cli';
import { FetchHttpClient } from 'effect/http';
import { glimpse } from './cli.ts';

export type Sent = {
  readonly method: string;
  readonly path: string;
  readonly authorization: string | null;
  readonly redirect: RequestRedirect | undefined;
};

/**
 * The state of one test: a real Glimpse server in this process, a fresh home
 * directory, and what the CLI sent and printed.
 */
export const fixture = {
  server: undefined as unknown as TestServer,
  home: '',
  stdin: '',
  sent: [] as Array<Sent>,
  /** Changes the file system the commands see, such as to take away hard links. */
  fileSystem: (fs: FileSystem.FileSystem) => fs,
};

const unknownServer = 599;

// Every request goes to the test server, and only one addressed to it is
// answered, so no test reaches a real network.
const fetchFromTestServer = (async (
  input: RequestInfo | URL,
  init?: RequestInit,
) => {
  const request = new Request(input, init);
  const url = new URL(request.url);
  fixture.sent.push({
    method: request.method,
    path: `${url.pathname}${url.search}`,
    authorization: request.headers.get('authorization'),
    redirect: init?.redirect,
  });
  if (url.origin !== testOrigin) {
    return new Response('unknown server', { status: unknownServer });
  }
  return await fixture.server.fetch(request);
}) as typeof globalThis.fetch;

let stdout: ReturnType<typeof SpyOn> | undefined;

/** The JSON the last command printed on stdout. */
export const printed = () =>
  JSON.parse(String(stdout?.mock.calls.at(-1)?.[0] ?? 'null'));

/** Everything the commands printed on stdout, as one text. */
export const allPrinted = () =>
  (stdout?.mock.calls ?? [])
    .map((call: ReadonlyArray<unknown>) => String(call[0]))
    .join('');

export const runtimeDirectory = () => join(fixture.home, 'runtime');

export const run = (...args: ReadonlyArray<string>) =>
  Effect.runPromise(
    Command.runWith(glimpse, { version: 'test' })(args).pipe(
      // The token arrives piped in, as from `glimpse login < token-file`.
      Effect.provide(
        Stdio.layerTest({
          stdin: Stream.suspend(() =>
            Stream.make(new TextEncoder().encode(fixture.stdin)),
          ),
          stdinIsTerminal: Effect.succeed(false),
        }),
      ),
      Effect.updateService(FileSystem.FileSystem, (fs) =>
        fixture.fileSystem(fs),
      ),
      Effect.provide(BunServices.layer),
      Effect.provide(DateTime.layerCurrentZoneNamed('Europe/Berlin')),
      Effect.provide(
        ConfigProvider.layer(
          ConfigProvider.fromDotEnvContents(
            [
              `HOME=${fixture.home}`,
              `XDG_CONFIG_HOME=${join(fixture.home, 'config')}`,
              `XDG_RUNTIME_DIR=${runtimeDirectory()}`,
            ].join('\n'),
          ),
        ),
      ),
      Effect.provideService(FetchHttpClient.Fetch, fetchFromTestServer),
      Effect.result,
    ),
  );

export const runOk = async (...args: ReadonlyArray<string>) => {
  const result = await run(...args);
  if (result._tag === 'Failure') {
    throw new Error(
      `glimpse ${args.join(' ')} failed: ${JSON.stringify(result.failure)}`,
    );
  }
  return printed();
};

export type Failure = {
  readonly _tag: string;
  readonly message: string;
  readonly tag?: string;
};

export const failureOf = async (...args: ReadonlyArray<string>) => {
  const result = await run(...args);
  if (result._tag === 'Success') {
    throw new Error(`glimpse ${args.join(' ')} succeeded`);
  }
  return result.failure as Failure;
};

export const credentialsPath = () =>
  join(fixture.home, 'config', 'glimpse', 'credentials.json');

export const writeCredentials = (credentials: object) =>
  Bun.write(credentialsPath(), JSON.stringify(credentials));

const ownerOnlyDirectory = 0o700;

/** A fresh server and home, signed in as the owner; call it in `beforeEach`. */
export const setUp = async (spyOn: typeof SpyOn) => {
  fixture.sent = [];
  fixture.stdin = '';
  fixture.fileSystem = (fs) => fs;
  fixture.server = await startTestServer();
  fixture.home = await mkdtemp(join(tmpdir(), 'glimpse-cli-'));
  await writeCredentials({ server: testOrigin, token: testOwnerToken });
  // The session's runtime directory exists before any program runs.
  await mkdir(runtimeDirectory(), { mode: ownerOnlyDirectory });
  stdout = spyOn(process.stdout, 'write').mockImplementation(() => true);
};

export const tearDown = async () => {
  stdout?.mockRestore();
  await fixture.server.close();
  await rm(fixture.home, { recursive: true, force: true });
};
