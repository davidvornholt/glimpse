import { DeviceIdSchema } from '@glimpse/api/device';
import { PhotoIdSchema } from '@glimpse/api/photo';
import { Effect, Option, Schema } from 'effect';
import { Argument, Flag } from 'effect/cli';
import { InvalidInput } from '../shared/errors/errors.ts';

// Like `Flag.optional`, but a repeated flag is an error: the parser would keep
// the first value, so `--since 1h --since 2h` would silently mean the first.
export const once = <A>(self: Flag.Flag<A>): Flag.Flag<Option.Option<A>> =>
  self.pipe(
    Flag.atMost(1),
    Flag.map((values) => Option.fromUndefinedOr(values[0])),
  );

export const optionalString = (name: string, description: string) =>
  once(Flag.String(name).pipe(Flag.withDescription(description)));

export const optionalInt = (name: string, description: string) =>
  once(Flag.Int(name).pipe(Flag.withDescription(description)));

/** A switch that is off unless given. */
export const flag = (name: string, description: string) =>
  once(Flag.Boolean(name).pipe(Flag.withDescription(description))).pipe(
    Flag.map(Option.getOrElse(() => false)),
  );

export const fail = (message: string) =>
  Effect.fail(new InvalidInput({ message }));

const isPhotoId = Schema.is(PhotoIdSchema);
const isDeviceId = Schema.is(DeviceIdSchema);

/**
 * A photo id. It goes into request paths, so anything but a ULID is refused
 * here rather than sent. Ids are upper case; a lower-case copy is accepted.
 */
export const validPhotoId = (value: string) => {
  const id = value.trim().toUpperCase();
  return isPhotoId(id)
    ? Effect.succeed(id)
    : fail(
        `${value} is not a photo id. Ids look like 01JD3V8Q2W5X7Y9Z0A1B2C3D4E; \`glimpse recent\` lists them.`,
      );
};

/** Several photo ids, each once, in the order given. */
export const validPhotoIds = (values: ReadonlyArray<string>) =>
  Effect.forEach(values, validPhotoId).pipe(
    Effect.map((ids) => [...new Set(ids)]),
  );

export const validDeviceId = (value: string) => {
  const id = value.trim();
  return isDeviceId(id)
    ? Effect.succeed(id)
    : fail(
        `${value} is not a phone's id. \`glimpse devices list\` lists them.`,
      );
};

export const idsArgument = (name: string, description: string) =>
  Argument.String(name).pipe(
    Argument.withDescription(description),
    Argument.variadic({ min: 1 }),
  );

export const idArgument = (name: string, description: string) =>
  Argument.String(name).pipe(Argument.withDescription(description));

/** A whole number within bounds, or the default when the flag is absent. */
export const bounded = (
  name: string,
  value: Option.Option<number>,
  range: {
    readonly fallback: number;
    readonly min: number;
    readonly max: number;
  },
) => {
  const chosen = Option.getOrElse(value, () => range.fallback);
  return chosen >= range.min && chosen <= range.max
    ? Effect.succeed(chosen)
    : fail(`--${name} must be between ${range.min} and ${range.max}.`);
};
