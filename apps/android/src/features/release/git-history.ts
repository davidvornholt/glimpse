import type { ReleaseHistory } from './release-plan';

// The paths whose changes reach the APK. Unit tests, this release tooling, the
// release smoke test and the Bun workspace files don't, so changing only those
// releases nothing.
export const apkPaths = [
  ':(top)apps/android',
  ':(top,exclude)apps/android/app/src/test',
  ':(top,exclude)apps/android/scripts',
  ':(top,exclude)apps/android/src',
  ':(top,exclude)apps/android/package.json',
  ':(top,exclude)apps/android/tsconfig.json',
] as const;

const git = (cwd: string, args: ReadonlyArray<string>) => {
  const result = Bun.spawnSync(['git', ...args], { cwd });
  return {
    status: result.exitCode,
    stdout: result.stdout.toString(),
    stderr: result.stderr.toString(),
  };
};

const gitOutput = (cwd: string, args: ReadonlyArray<string>): string => {
  const result = git(cwd, args);
  if (result.status !== 0) {
    throw new Error(`git ${args.join(' ')} failed: ${result.stderr.trim()}`);
  }
  return result.stdout;
};

const lines = (text: string): Array<string> =>
  text.split('\n').filter((line) => line.length > 0);

export const gitHistory = (cwd: string, head = 'HEAD'): ReleaseHistory => ({
  tags: lines(gitOutput(cwd, ['tag', '--list', 'android-v*'])),
  isAncestor: (tag) => {
    const { status, stderr } = git(cwd, [
      'merge-base',
      '--is-ancestor',
      tag,
      head,
    ]);
    if (status === 0 || status === 1) {
      return status === 0;
    }
    throw new Error(`git merge-base failed: ${stderr.trim()}`);
  },
  changedSince: (tag) => {
    // --quiet exits 1 when the paths differ and 0 when they don't.
    const { status, stderr } = git(cwd, [
      'diff',
      '--quiet',
      tag,
      head,
      '--',
      ...apkPaths,
    ]);
    if (status === 0 || status === 1) {
      return status === 1;
    }
    throw new Error(`git diff failed: ${stderr.trim()}`);
  },
});

// The subjects of the commits that changed the app since `tag`, newest first.
export const changeSubjects = (
  cwd: string,
  tag: string | null,
  head = 'HEAD',
): Array<string> => {
  const range = tag === null ? head : `${tag}..${head}`;
  return lines(
    gitOutput(cwd, ['log', '--format=%s', range, '--', ...apkPaths]),
  );
};
