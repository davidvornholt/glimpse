import { afterEach, describe, expect, it } from 'bun:test';
import { changeSubjects, gitHistory } from './git-history';
import { planRelease } from './release-plan';

const repositories: Array<string> = [];

// Settings that keep the developer's own git configuration, such as commit
// signing or hooks, out of the test repositories.
const isolated = [
  '-c',
  'user.name=Test',
  '-c',
  'user.email=test@glimpse.example.com',
  '-c',
  'commit.gpgSign=false',
  '-c',
  'core.hooksPath=/dev/null',
];

const run = (cwd: string, args: Array<string>) => {
  const result = Bun.spawnSync(['git', ...isolated, ...args], { cwd });
  if (result.exitCode !== 0) {
    throw new Error(`git ${args.join(' ')}: ${result.stderr.toString()}`);
  }
  return result.stdout.toString().trim();
};

// A repository with the monorepo's layout, so the path filters see real
// paths. `git init` creates the directory.
const repository = () => {
  const root = `/tmp/glimpse-release-${crypto.randomUUID()}`;
  repositories.push(root);
  run('/', ['init', '--quiet', '--initial-branch=main', root]);
  return root;
};

const commit = async (root: string, path: string, subject: string) => {
  // Bun.write creates the missing directories.
  await Bun.write(`${root}/${path}`, `${subject}\n`);
  run(root, ['add', '--all']);
  run(root, ['commit', '--quiet', '--message', subject]);
  return run(root, ['rev-parse', 'HEAD']);
};

afterEach(() => {
  for (const root of repositories.splice(0)) {
    Bun.spawnSync(['rm', '-rf', '--', root]);
  }
});

describe('gitHistory', () => {
  it('releases first, then only when the app itself changed', async () => {
    const root = repository();
    await commit(root, 'apps/android/app/src/main/Main.kt', 'feat: the app');
    await commit(root, 'apps/server/index.ts', 'feat: the server');
    const android = `${root}/apps/android`;

    const first = planRelease(gitHistory(android));
    expect(first.kind === 'release' && first.release.tag).toBe('android-v1.1');
    expect(changeSubjects(android, null)).toEqual(['feat: the app']);
    run(root, ['tag', 'android-v1.1']);

    await commit(root, 'apps/server/index.ts', 'fix: the server');
    await commit(
      root,
      'apps/android/app/src/test/MainTest.kt',
      'test: the app',
    );
    await commit(root, 'apps/android/src/plan-release.ts', 'chore: tooling');
    expect(planRelease(gitHistory(android))).toEqual({
      kind: 'skip',
      reason: 'the app has not changed since android-v1.1',
    });

    await commit(root, 'apps/android/app/build.gradle.kts', 'fix: sign it');
    const second = planRelease(gitHistory(android));
    expect(second.kind === 'release' && second.release.versionCode).toBe(2);
    expect(changeSubjects(android, 'android-v1.1')).toEqual(['fix: sign it']);
  });

  it('releases nothing when only the smoke test changed', async () => {
    const root = repository();
    await commit(root, 'apps/android/app/src/main/Main.kt', 'feat: the app');
    run(root, ['tag', 'android-v1.1']);
    await commit(
      root,
      'apps/android/scripts/release-smoke.sh',
      'ci: the smoke test',
    );
    const android = `${root}/apps/android`;

    expect(planRelease(gitHistory(android))).toEqual({
      kind: 'skip',
      reason: 'the app has not changed since android-v1.1',
    });
    expect(changeSubjects(android, 'android-v1.1')).toEqual([]);
  });

  it('skips a commit older than the newest release', async () => {
    const root = repository();
    const older = await commit(root, 'apps/android/app/A.kt', 'feat: a');
    await commit(root, 'apps/android/app/B.kt', 'feat: b');
    run(root, ['tag', 'android-v1.1']);

    expect(planRelease(gitHistory(root, older)).kind).toBe('skip');
  });
});
