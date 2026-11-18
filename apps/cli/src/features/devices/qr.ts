import { encode, renderUnicodeCompact } from 'uqr';
import { encodePng } from './png.ts';

// Medium error correction survives a glare on the screen; four modules of
// white around the code is what scanners expect.
const errorCorrection = 'M';
const quietZone = 4;
const pixelsPerModule = 10;

/** A QR code of `text` as a PNG, large enough to scan from a screen. */
export const qrPng = (text: string) => {
  const { size, data } = encode(text, {
    ecc: errorCorrection,
    border: quietZone,
  });
  const pixels = size * pixelsPerModule;
  return encodePng({
    width: pixels,
    height: pixels,
    isDark: (x, y) =>
      data[Math.floor(y / pixelsPerModule)]?.[
        Math.floor(x / pixelsPerModule)
      ] === true,
  });
};

const terminalQuietZone = 2;

/** A QR code of `text` drawn with half blocks, two rows of modules per line. */
export const qrText = (text: string) =>
  renderUnicodeCompact(text, {
    ecc: errorCorrection,
    border: terminalQuietZone,
  });
