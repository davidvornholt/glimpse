import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';

/** The SHA-256 digest of a token in hex, which is all the server stores. */
export const tokenDigest = (token: string) =>
  createHash('sha256').update(token, 'utf8').digest('hex');

/** Compares two hex digests in constant time. */
export const sameDigest = (left: string, right: string) => {
  const a = Buffer.from(left, 'hex');
  const b = Buffer.from(right, 'hex');
  return a.length === b.length && timingSafeEqual(a, b);
};

const tokenBytes = 32;
const idBytes = 12;
const codeLength = 24;
const codeAlphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';

/** A device token: `gd_` and 256 random bits. */
export const newDeviceToken = () =>
  `gd_${randomBytes(tokenBytes).toString('base64url')}`;

/**
 * A pairing code: 24 characters of base32, 120 random bits, short enough for
 * a QR code a phone reads at a glance. 32 divides 256, so taking each random
 * byte modulo 32 favours no character.
 */
export const newPairingCode = () =>
  Array.from(
    randomBytes(codeLength),
    (byte) => codeAlphabet[byte % codeAlphabet.length],
  ).join('');

export const newDeviceId = () => randomBytes(idBytes).toString('base64url');
