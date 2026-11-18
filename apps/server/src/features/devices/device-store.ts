import { type Device, DeviceSchema } from '@glimpse/api/device';
import type { RateLimited } from '@glimpse/api/errors';
import { NotFound } from '@glimpse/api/errors';
import {
  Context,
  DateTime,
  Effect,
  Layer,
  Option,
  Ref,
  Schema,
  Semaphore,
} from 'effect';
import { ServerConfig } from '../../shared/config.ts';
import { DeviceRemovals } from '../../shared/device-removals.ts';
import { makeDisk } from '../../shared/files.ts';
import {
  newDeviceId,
  newDeviceToken,
  tokenDigest,
} from '../../shared/tokens.ts';
import { makePairingCodes } from './pairing-codes.ts';

const StoredDeviceSchema = Schema.Struct({
  ...DeviceSchema.fields,
  tokenSha256: Schema.String,
});
type StoredDevice = typeof StoredDeviceSchema.Type;

const DevicesFileSchema = Schema.fromJsonString(
  Schema.toCodecJson(
    Schema.Struct({ devices: Schema.Array(StoredDeviceSchema) }),
  ),
  { space: 2 },
);

/** How often `lastSeenAt` is written at most, so busy phones don't thrash the disk. */
const seenWriteMillis = 60_000;

const publicDevice = ({ tokenSha256: _, ...device }: StoredDevice): Device =>
  device;

export class DeviceStore extends Context.Service<
  DeviceStore,
  {
    readonly list: Effect.Effect<ReadonlyArray<Device>>;
    /** The device a token belongs to, which is then marked as seen. */
    readonly authenticate: (
      token: string,
    ) => Effect.Effect<Option.Option<Device>>;
    readonly createPairing: Effect.Effect<{
      readonly code: string;
      readonly expiresAt: DateTime.Utc;
    }>;
    readonly claim: (
      code: string,
      name: string,
    ) => Effect.Effect<
      { readonly token: string; readonly device: Device },
      NotFound | RateLimited
    >;
    readonly remove: (id: string) => Effect.Effect<void, NotFound>;
  }
>()('@glimpse/server/DeviceStore') {
  static readonly layer = Layer.effect(
    DeviceStore,
    Effect.gen(function* () {
      const { dataDirectory } = yield* ServerConfig;
      const removals = yield* DeviceRemovals;
      const disk = yield* makeDisk;
      yield* disk.fs
        .makeDirectory(dataDirectory, { recursive: true })
        .pipe(Effect.orDie);
      const file = disk.path.join(dataDirectory, 'devices.json');
      const decode = Schema.decodeEffect(DevicesFileSchema);
      const encode = Schema.encodeEffect(DevicesFileSchema);
      const initial = yield* disk.readTextIfPresent(file).pipe(
        Effect.flatMap((text) =>
          Option.isNone(text)
            ? Effect.succeed<ReadonlyArray<StoredDevice>>([])
            : decode(text.value).pipe(Effect.map((saved) => saved.devices)),
        ),
        Effect.orDie,
      );
      const devices = yield* Ref.make(initial);
      const codes = yield* makePairingCodes;
      const writing = yield* Semaphore.make(1);

      const save = (next: ReadonlyArray<StoredDevice>) =>
        encode({ devices: next }).pipe(
          Effect.flatMap((text) => disk.writeAtomically(file, `${text}\n`)),
          Effect.orDie,
        );

      /** Changes the device list and writes it, one change at a time. */
      const update = <A, E>(
        change: (
          current: ReadonlyArray<StoredDevice>,
        ) => Effect.Effect<readonly [A, ReadonlyArray<StoredDevice>], E>,
      ) =>
        Effect.gen(function* () {
          const [result, next] = yield* change(yield* Ref.get(devices));
          yield* save(next);
          yield* Ref.set(devices, next);
          return result;
        }).pipe(writing.withPermit);

      const authenticate = (token: string) =>
        Effect.gen(function* () {
          const digest = tokenDigest(token);
          const found = (yield* Ref.get(devices)).find(
            (device) => device.tokenSha256 === digest,
          );
          if (found === undefined) {
            return Option.none<Device>();
          }
          const now = yield* DateTime.now;
          const lastSeen = found.lastSeenAt;
          const stale =
            lastSeen === null ||
            DateTime.toEpochMillis(now) - DateTime.toEpochMillis(lastSeen) >
              seenWriteMillis;
          if (!stale) {
            return Option.some(publicDevice(found));
          }
          const seen = yield* update((current) =>
            Effect.succeed([
              { ...found, lastSeenAt: now },
              current.map((device) =>
                device.id === found.id
                  ? { ...device, lastSeenAt: now }
                  : device,
              ),
            ] as const),
          );
          return Option.some(publicDevice(seen));
        });

      const claim = (code: string, name: string) =>
        Effect.gen(function* () {
          yield* codes.redeem(code);
          const token = newDeviceToken();
          const createdAt = yield* DateTime.now;
          const stored: StoredDevice = {
            id: newDeviceId(),
            name: name.trim(),
            createdAt,
            lastSeenAt: createdAt,
            tokenSha256: tokenDigest(token),
          };
          yield* update((current) =>
            Effect.succeed([undefined, [...current, stored]] as const),
          );
          return { token, device: publicDevice(stored) };
        });

      const remove = (id: string) =>
        update((current) =>
          current.some((device) => device.id === id)
            ? Effect.succeed([
                undefined,
                current.filter((device) => device.id !== id),
              ] as const)
            : Effect.fail(
                new NotFound({ message: `No paired phone has the id ${id}.` }),
              ),
        ).pipe(Effect.andThen(removals.removed(id)));

      return {
        list: Ref.get(devices).pipe(Effect.map((all) => all.map(publicDevice))),
        authenticate,
        createPairing: codes.offer,
        claim,
        remove,
      };
    }),
  );
}
