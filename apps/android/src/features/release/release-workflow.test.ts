import { describe, expect, it } from 'bun:test';

const workflowFile = new URL(
  '../../../../../.github/workflows/release-android.yml',
  import.meta.url,
);

type Workflow = {
  concurrency?: unknown;
  jobs: Record<string, { concurrency?: unknown }>;
};

const workflow = Bun.YAML.parse(
  await Bun.file(workflowFile).text(),
) as Workflow;

describe('release workflow', () => {
  it('queues runs in the order of their pushes, before their gates', () => {
    expect(workflow.concurrency).toEqual({
      group: 'release-android',
      'cancel-in-progress': false,
    });
    for (const job of Object.values(workflow.jobs)) {
      expect(job.concurrency).toBeUndefined();
    }
  });
});
