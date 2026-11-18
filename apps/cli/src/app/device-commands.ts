import process from 'node:process';
import { DateTime, Effect, Path } from 'effect';
import { Command } from 'effect/cli';
import {
  createPairing,
  deviceJson,
  listDevices,
  removeDevice,
} from '../features/devices/devices.ts';
import { qrPng, qrText } from '../features/devices/qr.ts';
import { replaceFile, runtimeDirectory } from '../shared/runtime-files.ts';
import { idArgument, validDeviceId } from './command-inputs.ts';
import { runGlimpse } from './root.ts';

export const pair = Command.make('pair', {}, () =>
  runGlimpse(Effect.void, () =>
    Effect.gen(function* () {
      const path = yield* Path.Path;
      const pairing = yield* createPairing;
      const directory = yield* runtimeDirectory('pairing');
      const qr = yield* replaceFile(
        path.join(directory, 'pairing-qr.png'),
        qrPng(pairing.link),
      );
      // Someone at a terminal scans the code from there; an agent shows the
      // PNG instead, since stdout is for JSON.
      if (process.stderr.isTTY === true) {
        process.stderr.write(
          `\n${qrText(pairing.link)}\nScan this with the Glimpse app.\n\n`,
        );
      }
      return {
        link: pairing.link,
        expiresAt: DateTime.formatIso(pairing.expiresAt),
        qr,
      };
    }),
  ),
).pipe(
  Command.withDescription(
    'Make a single-use link that pairs a phone, valid for ten minutes, and save it as a QR code to scan with the Glimpse app. Prints the PNG path; at a terminal, also draws the code.',
  ),
);

const list = Command.make('list', {}, () =>
  runGlimpse(Effect.void, () =>
    listDevices.pipe(
      Effect.map((phones) => ({ phones: phones.map(deviceJson) })),
    ),
  ),
).pipe(
  Command.withDescription('List the paired phones and when each was last seen'),
);

const remove = Command.make(
  'remove',
  { id: idArgument('id', "A phone's id from `glimpse devices list`") },
  ({ id }) =>
    runGlimpse(validDeviceId(id), (valid) =>
      removeDevice(valid).pipe(Effect.as({ removed: valid })),
    ),
).pipe(
  Command.withDescription(
    'Unpair a phone. Its token stops working at once; the photos it sent stay until they expire.',
  ),
);

export const devices = Command.make('devices').pipe(
  Command.withDescription('List and unpair the phones that send photos'),
  Command.withSubcommands([list, remove]),
);
