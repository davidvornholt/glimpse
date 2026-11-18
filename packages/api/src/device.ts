import { Schema } from 'effect';

export const deviceNameLength = 64;
/** The longest pairing code a server may hand out. */
export const pairingCodeLength = 128;

export const DeviceIdSchema = Schema.String.check(
  Schema.isPattern(/^[A-Za-z0-9_-]{1,64}$/u),
);

/** A phone paired with the server. */
export const DeviceSchema = Schema.Struct({
  id: DeviceIdSchema,
  name: Schema.String,
  createdAt: Schema.DateTimeUtc,
  lastSeenAt: Schema.NullOr(Schema.DateTimeUtc),
});
export type Device = typeof DeviceSchema.Type;

export const DeviceNameSchema = Schema.String.check(
  Schema.isMinLength(1),
  Schema.isMaxLength(deviceNameLength),
);

/** A single-use code that pairs one phone, valid for ten minutes. */
export const PairingSchema = Schema.Struct({
  /** `glimpse://pair?server=…&code=…`, for the QR code the phone scans. */
  link: Schema.String,
  expiresAt: Schema.DateTimeUtc,
});

export const ClaimPayloadSchema = Schema.Struct({
  code: Schema.String.check(
    Schema.isMinLength(1),
    Schema.isMaxLength(pairingCodeLength),
  ),
  deviceName: DeviceNameSchema,
});

/** The token is shown once; the server keeps only its hash. */
export const ClaimResultSchema = Schema.Struct({
  token: Schema.String,
  device: DeviceSchema,
});

/** Who a token belongs to: the owner, which is the CLI, or a paired phone. */
export const MeSchema = Schema.Union([
  Schema.Struct({ kind: Schema.Literal('owner') }),
  Schema.Struct({ kind: Schema.Literal('device'), device: DeviceSchema }),
]);
export type Me = typeof MeSchema.Type;
