import { describe, expect, it } from 'bun:test';
import {
  parseReleaseTags,
  planOutputs,
  planRelease,
  type ReleaseHistory,
  releaseFor,
  releaseNotes,
} from './release-plan';

const history = (overrides: Partial<ReleaseHistory>): ReleaseHistory => ({
  tags: [],
  isAncestor: () => true,
  changedSince: () => true,
  ...overrides,
});

describe('parseReleaseTags', () => {
  it('keeps only release tags, newest first, comparing numbers rather than text', () => {
    expect(
      parseReleaseTags([
        'android-v1.9',
        'android-v1.10',
        'v1.0.0',
        'android-v1.0',
        'android-v1.02',
        'android-v2.1',
        'android-v1.3-rc',
        ' android-v1.2 ',
      ]),
    ).toEqual([
      { name: 'android-v1.10', number: 10 },
      { name: 'android-v1.9', number: 9 },
      { name: 'android-v1.2', number: 2 },
    ]);
  });
});

describe('planRelease', () => {
  it('releases 1.1 when nothing was released yet', () => {
    expect(planRelease(history({}))).toEqual({
      kind: 'release',
      release: {
        tag: 'android-v1.1',
        title: 'Glimpse for Android 1.1',
        versionCode: 1,
        versionName: '1.1',
      },
      previous: null,
    });
  });

  it('releases the number after the newest release when the app changed', () => {
    const plan = planRelease(
      history({ tags: ['android-v1.4', 'android-v1.11', 'android-v1.7'] }),
    );
    expect(plan).toEqual({
      kind: 'release',
      release: releaseFor(12),
      previous: { name: 'android-v1.11', number: 11 },
    });
  });

  it('compares with the newest release only', () => {
    const asked: Array<string> = [];
    planRelease(
      history({
        tags: ['android-v1.1', 'android-v1.2'],
        changedSince: (tag) => {
          asked.push(tag);
          return false;
        },
      }),
    );
    expect(asked).toEqual(['android-v1.2']);
  });

  it('skips when the app did not change since the newest release', () => {
    const plan = planRelease(
      history({ tags: ['android-v1.3'], changedSince: () => false }),
    );
    expect(plan).toEqual({
      kind: 'skip',
      reason: 'the app has not changed since android-v1.3',
    });
  });

  it('skips a commit that the newest release already moved past', () => {
    const plan = planRelease(
      history({ tags: ['android-v1.3'], isAncestor: () => false }),
    );
    expect(plan.kind).toBe('skip');
  });
});

describe('releaseNotes', () => {
  it('lists the changes since the previous release', () => {
    expect(
      releaseNotes(
        ['feat(android): add a scanner', '  ', 'fix: retry uploads '],
        {
          name: 'android-v1.2',
          number: 2,
        },
      ),
    ).toBe(
      'Changes since android-v1.2:\n\n- feat(android): add a scanner\n- fix: retry uploads\n',
    );
  });

  it('introduces the first release', () => {
    expect(releaseNotes(['feat: the app'], null)).toBe(
      'The first release.\n\n- feat: the app\n',
    );
  });
});

describe('planOutputs', () => {
  it('writes the version for a release', () => {
    expect(
      planOutputs({ kind: 'release', release: releaseFor(3), previous: null }),
    ).toBe(
      'release=true\ntag=android-v1.3\ntitle=Glimpse for Android 1.3\nversion-code=3\nversion-name=1.3\n',
    );
  });

  it('writes only the decision for a skip', () => {
    expect(planOutputs({ kind: 'skip', reason: 'nothing changed' })).toBe(
      'release=false\n',
    );
  });
});
