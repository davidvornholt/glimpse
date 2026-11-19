// Decides whether a push to main releases the Android app, and as which
// version. Releases are numbered 1, 2, 3, …: the number is the versionCode
// Android compares on update, and `1.<number>` is the version name the app
// and the GitHub release show. Obtainium reads the version from the tag.

const tagPattern = /^android-v1\.(?<number>[1-9]\d*)$/u;

export type ReleaseTag = {
  readonly name: string;
  readonly number: number;
};

export type Release = {
  readonly tag: string;
  readonly title: string;
  readonly versionCode: number;
  readonly versionName: string;
};

export type ReleasePlan =
  | {
      readonly kind: 'release';
      readonly release: Release;
      readonly previous: ReleaseTag | null;
    }
  | { readonly kind: 'skip'; readonly reason: string };

// What the plan needs to know about the repository, answered for the commit
// being released.
export type ReleaseHistory = {
  readonly tags: ReadonlyArray<string>;
  readonly isAncestor: (tag: string) => boolean;
  readonly changedSince: (tag: string) => boolean;
};

export const releaseFor = (number: number): Release => ({
  tag: `android-v1.${number}`,
  title: `Glimpse for Android 1.${number}`,
  versionCode: number,
  versionName: `1.${number}`,
});

export const parseReleaseTags = (
  names: ReadonlyArray<string>,
): Array<ReleaseTag> =>
  names
    .flatMap((name) => {
      const number = tagPattern.exec(name.trim())?.groups?.number;
      return number === undefined
        ? []
        : [{ name: name.trim(), number: Number(number) }];
    })
    .sort((left, right) => right.number - left.number);

export const planRelease = (history: ReleaseHistory): ReleasePlan => {
  const [latest] = parseReleaseTags(history.tags);
  if (latest === undefined) {
    return { kind: 'release', release: releaseFor(1), previous: null };
  }
  // Release runs queue one after another. A run for an older commit that
  // starts after a newer one was released would otherwise ship older code
  // with a higher versionCode.
  if (!history.isAncestor(latest.name)) {
    return {
      kind: 'skip',
      reason: `${latest.name} is not an ancestor of this commit, so a newer commit was released already`,
    };
  }
  if (!history.changedSince(latest.name)) {
    return {
      kind: 'skip',
      reason: `the app has not changed since ${latest.name}`,
    };
  }
  return {
    kind: 'release',
    release: releaseFor(latest.number + 1),
    previous: latest,
  };
};

export const releaseNotes = (
  subjects: ReadonlyArray<string>,
  previous: ReleaseTag | null,
): string => {
  const changes = subjects
    .map((subject) => subject.trim())
    .filter((subject) => subject.length > 0)
    .map((subject) => `- ${subject}`);
  const heading =
    previous === null
      ? 'The first release.'
      : `Changes since ${previous.name}:`;
  return [heading, '', ...changes, ''].join('\n');
};

// The lines the release workflow appends to $GITHUB_OUTPUT.
export const planOutputs = (plan: ReleasePlan): string => {
  if (plan.kind === 'skip') {
    return 'release=false\n';
  }
  const { release } = plan;
  return [
    'release=true',
    `tag=${release.tag}`,
    `title=${release.title}`,
    `version-code=${release.versionCode}`,
    `version-name=${release.versionName}`,
    '',
  ].join('\n');
};
