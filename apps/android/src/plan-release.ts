#!/usr/bin/env bun
import { changeSubjects, gitHistory } from './features/release/git-history';
import {
  planOutputs,
  planRelease,
  releaseNotes,
} from './features/release/release-plan';

// Usage: bun src/plan-release.ts <notes-file>
//
// Prints the release workflow's step outputs for the checked-out commit and,
// when it releases, writes the release notes to <notes-file>. Tags must be
// fetched.

const [notesFile] = Bun.argv.slice(2);
if (notesFile === undefined) {
  throw new Error('usage: bun src/plan-release.ts <notes-file>');
}

const cwd = import.meta.dir;
const plan = planRelease(gitHistory(cwd));
if (plan.kind === 'release') {
  const subjects = changeSubjects(cwd, plan.previous?.name ?? null);
  await Bun.write(notesFile, releaseNotes(subjects, plan.previous));
  await Bun.write(Bun.stderr, `Releasing ${plan.release.tag}\n`);
} else {
  await Bun.write(Bun.stderr, `Not releasing: ${plan.reason}\n`);
}
await Bun.write(Bun.stdout, planOutputs(plan));
