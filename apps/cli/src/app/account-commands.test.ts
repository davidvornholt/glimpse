import { afterEach, beforeEach, describe, expect, it, spyOn } from 'bun:test';
import { stat } from 'node:fs/promises';
import { join } from 'node:path';
import { pairPhone, testOrigin, testOwnerToken } from '@glimpse/server/testing';
import {
  allPrinted,
  credentialsPath,
  failureOf,
  fixture,
  runOk,
  runtimeDirectory,
  setUp,
  tearDown,
  writeCredentials,
} from './command-fixtures.ts';

beforeEach(() => setUp(spyOn));
afterEach(tearDown);

const stored = async () => JSON.parse(await Bun.file(credentialsPath()).text());

describe('glimpse login', () => {
  it('checks the token with the server and stores it owner-only', async () => {
    await writeCredentials({ server: testOrigin, token: 'old-token' });
    fixture.stdin = `${testOwnerToken}\n`;
    const output = await runOk('login', '--server', `${testOrigin}/`);
    expect(output).toEqual({ signedIn: true, server: testOrigin, phones: [] });
    expect(await stored()).toEqual({
      server: testOrigin,
      token: testOwnerToken,
    });
    expect((await stat(credentialsPath())).mode % 0o1000).toBe(0o600);
    expect(allPrinted()).not.toContain(testOwnerToken);
    expect(fixture.sent.every((sent) => sent.redirect === 'manual')).toBe(true);
  });

  it('keeps the stored token when the server refuses the new one', async () => {
    fixture.stdin = 'not-the-token';
    const failure = await failureOf('login', '--server', testOrigin);
    expect(failure._tag).toBe('NotSignedIn');
    expect(failure.message).not.toContain('not-the-token');
    expect((await stored()).token).toBe(testOwnerToken);
  });

  it("refuses a phone's token", async () => {
    const { token } = await pairPhone(fixture.server);
    fixture.stdin = token;
    const failure = await failureOf('login', '--server', testOrigin);
    expect(failure._tag).toBe('InvalidInput');
    expect(failure.message).toContain('Pixel');
    expect((await stored()).token).toBe(testOwnerToken);
  });

  it('refuses plain http to another machine before reading a token', async () => {
    const failure = await failureOf(
      'login',
      '--server',
      'http://glimpse.example.com',
    );
    expect(failure._tag).toBe('InvalidInput');
    expect(fixture.sent).toEqual([]);
  });

  it('refuses a token with spaces in it', async () => {
    fixture.stdin = 'two words';
    const failure = await failureOf('login', '--server', testOrigin);
    expect(failure._tag).toBe('InvalidInput');
  });
});

describe('glimpse status', () => {
  it('shows the server and the paired phones', async () => {
    await pairPhone(fixture.server, 'Pixel 6');
    const output = await runOk('status');
    expect(output).toMatchObject({
      signedIn: true,
      server: testOrigin,
      phones: [{ name: 'Pixel 6' }],
    });
  });

  it('says so when the server no longer takes the token', async () => {
    await writeCredentials({ server: testOrigin, token: 'revoked' });
    const output = await runOk('status');
    expect(output).toMatchObject({ signedIn: false, server: testOrigin });
  });
});

describe('glimpse logout', () => {
  it('removes the token and the saved files', async () => {
    const previews = join(runtimeDirectory(), 'glimpse', 'previews');
    await Bun.write(join(previews, 'old.jpg'), 'x');
    const output = await runOk('logout');
    expect(output).toEqual({
      signedIn: false,
      removedToken: true,
      removedPreviews: true,
    });
    expect(await Bun.file(credentialsPath()).exists()).toBe(false);
    expect(await Bun.file(join(previews, 'old.jpg')).exists()).toBe(false);
    expect(await runOk('status')).toEqual({ signedIn: false, server: null });
  });
});
