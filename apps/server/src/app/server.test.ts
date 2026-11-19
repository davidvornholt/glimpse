import { afterEach, beforeEach, describe, expect, it } from 'bun:test';
import {
  bearer,
  startTestServer,
  type TestServer,
  testOwnerToken,
} from './testing.ts';

let server: TestServer;

beforeEach(async () => {
  server = await startTestServer();
});

afterEach(async () => {
  await server.close();
});

const owner = bearer(testOwnerToken);

describe('the server', () => {
  it('answers its health check without a token', async () => {
    const response = await server.fetch('/healthz');
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ status: 'ok' });
  });

  it('wraps an unknown address in the error body', async () => {
    const response = await server.fetch('/nothing-here', { headers: owner });
    expect(response.status).toBe(404);
    expect(await response.json()).toEqual({
      error: { tag: 'NotFound', message: 'There is nothing at this address.' },
    });
  });

  it('refuses a request without a token', async () => {
    const response = await server.fetch('/v1/me');
    expect(response.status).toBe(401);
    expect(await response.json()).toMatchObject({
      error: { tag: 'Unauthorized' },
    });
  });

  it('refuses a token it does not know', async () => {
    const response = await server.fetch('/v1/me', {
      headers: bearer('gd_not-a-token'),
    });
    expect(response.status).toBe(401);
  });

  it('knows the owner token', async () => {
    const response = await server.fetch('/v1/me', { headers: owner });
    expect(await response.json()).toEqual({ kind: 'owner' });
  });

  it('says which query parameter is wrong', async () => {
    const response = await server.fetch('/v1/photos?limit=500', {
      headers: owner,
    });
    expect(response.status).toBe(400);
    const body = await response.json();
    expect(body).toMatchObject({ error: { tag: 'InvalidRequest' } });
    expect(JSON.stringify(body)).toContain('between 1 and 100');
  });

  it('refuses a cursor it did not give out', async () => {
    const response = await server.fetch('/v1/photos?before=nonsense', {
      headers: owner,
    });
    expect(response.status).toBe(400);
    expect(await response.json()).toMatchObject({
      error: { tag: 'InvalidRequest' },
    });
  });
});
