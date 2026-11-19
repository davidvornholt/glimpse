import { afterEach, beforeEach, describe, expect, it } from 'bun:test';
import { chmod, mkdir, mkdtemp, readdir, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import process from 'node:process';
import {
  bearer,
  pairPhone,
  putPreview,
  registerPhoto,
  startTestServer,
  type TestServer,
  testPhotoId,
  tinyJpeg,
} from './testing.ts';

// The low nine bits of a mode are the owner's, the group's and everyone's access.
const permissionSpan = 0o1000;
const privateDirectory = 0o700;
const privateFile = 0o600;
// The usual umask, which leaves new files readable by every local user.
const usualUmask = 0o022;

let parent: string;
let previousUmask: number;
let server: TestServer | undefined;

beforeEach(async () => {
  parent = await mkdtemp(join(tmpdir(), 'glimpse-storage-test-'));
  previousUmask = process.umask(usualUmask);
});

afterEach(async () => {
  process.umask(previousUmask);
  await server?.stop();
  server = undefined;
  await rm(parent, { recursive: true, force: true });
});

const modeOf = async (path: string) => (await stat(path)).mode % permissionSpan;

/** Every path under `root` with its permission bits, the root included. */
const modesUnder = async (root: string) => {
  const entries = await readdir(root, { recursive: true });
  return Promise.all(
    [root, ...entries.map((entry) => join(root, entry))].map(async (path) => {
      const info = await stat(path);
      return {
        path,
        directory: info.isDirectory(),
        mode: info.mode % permissionSpan,
      };
    }),
  );
};

/** Sends an original that stops after its first bytes until `finish` runs. */
const startSlowOriginal = (target: TestServer, token: string, id: string) => {
  const { promise: finished, resolve: finish } = Promise.withResolvers<void>();
  const image = tinyJpeg(4032, 3024);
  const body = new ReadableStream<Uint8Array>({
    start: async (controller) => {
      controller.enqueue(image.subarray(0, 2));
      await finished;
      controller.enqueue(image.subarray(2));
      controller.close();
    },
  });
  const response = target.fetch(`/v1/photos/${id}/original`, {
    method: 'PUT',
    headers: { ...bearer(token), 'content-type': 'image/jpeg' },
    body,
    duplex: 'half',
  } as RequestInit);
  return { finish, response };
};

const draftPollMillis = 5;

const waitForDraft = async (directory: string): Promise<string> => {
  const draft = (await readdir(directory)).find((entry) =>
    entry.endsWith('.tmp'),
  );
  if (draft !== undefined) {
    return join(directory, draft);
  }
  await Bun.sleep(draftPollMillis);
  return waitForDraft(directory);
};

describe('the data directory', () => {
  it('keeps photos, notes and phones from other local users whatever the umask', async () => {
    const dataDirectory = join(parent, 'data');
    server = await startTestServer({ dataDirectory });
    const { token } = await pairPhone(server);
    const id = testPhotoId(1);
    await registerPhoto(server, token, id, { note: 'A private note' });
    expect((await putPreview(server, token, id)).status).toBe(200);

    const upload = startSlowOriginal(server, token, id);
    const draft = await waitForDraft(join(dataDirectory, 'photos', id));
    expect(await modeOf(draft)).toBe(privateFile);
    upload.finish();
    expect((await upload.response).status).toBe(200);

    const modes = await modesUnder(dataDirectory);
    expect(modes.map(({ path }) => path)).toContain(
      join(dataDirectory, 'photos', id, 'preview.jpg'),
    );
    for (const { path, directory, mode } of modes) {
      expect({ path, mode }).toEqual({
        path,
        mode: directory ? privateDirectory : privateFile,
      });
    }
  });

  it('closes a data directory that an older server left open', async () => {
    const dataDirectory = join(parent, 'data');
    await mkdir(dataDirectory);
    await chmod(dataDirectory, 0o755);
    server = await startTestServer({ dataDirectory });
    // The server starts on its first request.
    expect((await server.fetch('/healthz')).status).toBe(200);
    expect(await modeOf(dataDirectory)).toBe(privateDirectory);
  });
});
