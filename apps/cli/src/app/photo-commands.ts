import { maxPage, noteLength } from '@glimpse/api/photo';
import { Effect, FileSystem, Option, Path } from 'effect';
import { Argument, Command, Flag } from 'effect/cli';
import {
  checkFree,
  originalName,
  saveOriginal,
  savePreview,
} from '../features/photos/files.ts';
import {
  getPhoto,
  listPhotos,
  photoJson,
  removePhoto,
  setNote,
} from '../features/photos/photos.ts';
import { FileNotSaved, InvalidInput } from '../shared/errors/errors.ts';
import {
  bounded,
  fail,
  flag,
  idArgument,
  idsArgument,
  optionalInt,
  optionalString,
  validPhotoId,
  validPhotoIds,
} from './command-inputs.ts';
import { runGlimpse } from './root.ts';
import { optionalTime } from './time-inputs.ts';

const recentDefault = 10;
// Enough to look at a handful of photos quickly without crowding the server.
const lookupConcurrency = 4;

export const timeHelp =
  'an age such as 30m, 2h or 3d; a day as YYYY-MM-DD; or a time';

export const recent = Command.make(
  'recent',
  {
    since: optionalString(
      'since',
      `Only photos that arrived after this: ${timeHelp}`,
    ),
    limit: optionalInt(
      'limit',
      `How many photos to list, from 1 to ${maxPage}. Defaults to ${recentDefault}.`,
    ),
    cursor: optionalString(
      'cursor',
      'The nextCursor an earlier page printed, for the page after it',
    ),
  },
  ({ since, limit, cursor }) =>
    runGlimpse(
      Effect.all({
        since: optionalTime('since', since, 'start'),
        limit: bounded('limit', limit, {
          fallback: recentDefault,
          min: 1,
          max: maxPage,
        }),
      }),
      (valid) =>
        listPhotos({ ...valid, before: Option.getOrUndefined(cursor) }).pipe(
          Effect.map((page) => ({
            photos: page.photos.map(photoJson),
            nextCursor: page.nextCursor,
          })),
        ),
    ),
).pipe(
  Command.withDescription(
    'List the photos the phone sent, the newest to arrive first, with their notes and whether anyone looked at them yet',
  ),
);

export const show = Command.make(
  'show',
  { ids: idsArgument('id', 'Photo ids') },
  ({ ids }) =>
    runGlimpse(validPhotoIds(ids), (valid) =>
      Effect.forEach(valid, getPhoto, { concurrency: lookupConcurrency }).pipe(
        Effect.map((photos) => ({ photos: photos.map(photoJson) })),
      ),
    ),
).pipe(
  Command.withDescription(
    'Show what the server knows about photos: when they were taken and arrived, their note, size, and whether the original is there',
  ),
);

export const preview = Command.make(
  'preview',
  { ids: idsArgument('id', 'Photo ids') },
  ({ ids }) =>
    runGlimpse(validPhotoIds(ids), (valid) =>
      Effect.forEach(
        valid,
        (id) => savePreview(id).pipe(Effect.map((path) => ({ id, path }))),
        { concurrency: lookupConcurrency },
      ).pipe(Effect.map((previews) => ({ previews }))),
    ),
).pipe(
  Command.withDescription(
    'Save the preview of each photo, at most 2048 px and without camera metadata, in a private directory for this session, and print its path. Marks the photos viewed.',
  ),
);

/** An existing directory, as an absolute path. */
const existingDirectory = (to: string) =>
  Effect.gen(function* () {
    const path = yield* Path.Path;
    const fs = yield* FileSystem.FileSystem;
    const directory = path.resolve(to);
    const info = yield* fs.stat(directory).pipe(
      Effect.mapError(
        () =>
          new InvalidInput({
            message: `--to ${to} is not a directory. Create it first.`,
          }),
      ),
    );
    if (info.type !== 'Directory') {
      return yield* new InvalidInput({
        message: `--to ${to} is not a directory.`,
      });
    }
    return directory;
  });

export const download = Command.make(
  'download',
  {
    ids: idsArgument('id', 'Photo ids'),
    to: Flag.String('to').pipe(
      Flag.withDescription('An existing directory to save the originals in'),
    ),
  },
  ({ ids, to }) =>
    runGlimpse(
      Effect.all({
        directory: existingDirectory(to),
        ids: validPhotoIds(ids),
      }),
      ({ directory, ids: valid }) =>
        Effect.gen(function* () {
          const path = yield* Path.Path;
          const photos = yield* Effect.forEach(valid, getPhoto, {
            concurrency: lookupConcurrency,
          });
          const missing = photos.filter((photo) => !photo.hasOriginal);
          if (missing.length > 0) {
            return yield* new FileNotSaved({
              message: `The phone has not sent the original of ${missing.map((photo) => photo.id).join(', ')} yet. It sends originals after the preview; try again in a minute, or use \`glimpse preview\`.`,
            });
          }
          const files = photos.map((photo) =>
            path.join(directory, originalName(photo)),
          );
          yield* checkFree(files);
          return {
            files: yield* Effect.forEach(photos, (photo, index) =>
              saveOriginal(photo.id, files[index] ?? originalName(photo)).pipe(
                Effect.map((saved) => ({ id: photo.id, ...saved })),
              ),
            ),
          };
        }),
    ),
).pipe(
  Command.withDescription(
    'Save the original files, with all their metadata, such as where they were taken, into a directory. Never replaces a file that is already there.',
  ),
);

export const note = Command.make(
  'note',
  {
    id: idArgument('id', 'A photo id'),
    text: Argument.String('text').pipe(
      Argument.withDescription('The new note'),
      Argument.optional,
    ),
    clear: flag('clear', 'Remove the note instead'),
  },
  ({ id, text, clear }) =>
    runGlimpse(
      Effect.gen(function* () {
        const valid = yield* validPhotoId(id);
        if (clear === Option.isSome(text)) {
          return yield* fail('Pass either the new note or --clear.');
        }
        const chosen = Option.map(text, (value) => value.trim());
        if (Option.exists(chosen, (value) => value.length > noteLength)) {
          return yield* fail(`A note holds at most ${noteLength} characters.`);
        }
        return {
          id: valid,
          note: Option.getOrNull(
            Option.filter(chosen, (value) => value !== ''),
          ),
        };
      }),
      (valid) => setNote(valid.id, valid.note).pipe(Effect.map(photoJson)),
    ),
).pipe(
  Command.withDescription(
    'Set or clear the note of a photo, which the phone shows beside it',
  ),
);

export const deletePhotos = Command.make(
  'delete',
  { ids: idsArgument('id', 'Photo ids') },
  ({ ids }) =>
    runGlimpse(validPhotoIds(ids), (valid) =>
      Effect.forEach(valid, removePhoto, {
        concurrency: lookupConcurrency,
      }).pipe(Effect.as({ deleted: valid })),
    ),
).pipe(
  Command.withDescription(
    'Delete photos from the server for good, before they expire',
  ),
);
