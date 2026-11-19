import { Effect, Option } from 'effect';
import { Command } from 'effect/cli';
import { savePreview } from '../features/photos/files.ts';
import { photoJson } from '../features/photos/photos.ts';
import { waitForPhotos } from '../features/photos/waiting.ts';
import { bounded, optionalInt, optionalString } from './command-inputs.ts';
import { timeHelp } from './photo-commands.ts';
import { runGlimpse } from './root.ts';
import { parseTime } from './time-inputs.ts';

// Long enough for the user to pick up the phone and take the photo, and short
// enough to finish inside the two minutes an agent's shell command may take.
const defaultTimeout = 100;
const maxTimeout = 600;
const defaultSettle = 5;
const maxSettle = 30;
const defaultSince = '5m';
const previewConcurrency = 4;

export const wait = Command.make(
  'wait',
  {
    since: optionalString(
      'since',
      `Also return photos nobody looked at that arrived after this: ${timeHelp}. Defaults to ${defaultSince}, so a photo taken just before the wait counts.`,
    ),
    timeout: optionalInt(
      'timeout',
      `How many seconds to wait, from 0 to ${maxTimeout}. Defaults to ${defaultTimeout}.`,
    ),
    settle: optionalInt(
      'settle',
      `After a photo arrives, how many seconds to listen for more, such as the next page of a scan, from 0 to ${maxSettle}. Defaults to ${defaultSettle}.`,
    ),
  },
  ({ since, timeout, settle }) =>
    runGlimpse(
      Effect.all({
        after: parseTime(
          'since',
          Option.getOrElse(since, () => defaultSince),
          'start',
        ),
        timeoutSeconds: bounded('timeout', timeout, {
          fallback: defaultTimeout,
          min: 0,
          max: maxTimeout,
        }),
        settleSeconds: bounded('settle', settle, {
          fallback: defaultSettle,
          min: 0,
          max: maxSettle,
        }),
      }),
      (options) =>
        Effect.gen(function* () {
          const photos = yield* waitForPhotos(options);
          if (photos.length === 0) {
            return {
              photos: [],
              timedOut: true,
              next: `No photo arrived in ${options.timeoutSeconds} s. To keep waiting without missing one, run: glimpse wait --since ${options.after}`,
            };
          }
          const withPreviews = yield* Effect.forEach(
            photos,
            (photo) =>
              savePreview(photo.id).pipe(
                Effect.map((preview) => ({ ...photoJson(photo), preview })),
              ),
            { concurrency: previewConcurrency },
          );
          return { photos: withPreviews, timedOut: false };
        }),
    ),
).pipe(
  Command.withDescription(
    'Wait until the phone sends a photo nobody has looked at yet, then save its preview and print it with the path, as `preview` does. While it waits, the Glimpse app shows that an agent is waiting.',
  ),
);
