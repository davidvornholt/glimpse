import {
  type Device,
  DeviceSchema,
  MeSchema,
  PairingSchema,
} from '@glimpse/api/device';
import { DateTime, Schema } from 'effect';
import { requestJson, requestNothing } from '../../shared/glimpse.ts';

const DeviceJson = Schema.toCodecJson(DeviceSchema);

/** A phone as the server lists it, with its times as ISO text. */
export const deviceJson = (device: Device) => ({
  ...device,
  createdAt: DateTime.formatIso(device.createdAt),
  lastSeenAt:
    device.lastSeenAt === null ? null : DateTime.formatIso(device.lastSeenAt),
});

/** Who the stored token belongs to: the owner, or by mistake a phone. */
export const whoAmI = requestJson(
  { method: 'GET', path: '/v1/me' },
  Schema.toCodecJson(MeSchema),
);

export const listDevices = requestJson(
  { method: 'GET', path: '/v1/devices' },
  Schema.Array(DeviceJson),
);

/** A single-use link that pairs one phone, valid for ten minutes. */
export const createPairing = requestJson(
  { method: 'POST', path: '/v1/pairings' },
  Schema.toCodecJson(PairingSchema),
);

export const removeDevice = (id: string) =>
  requestNothing({ method: 'DELETE', path: `/v1/devices/${id}` });
