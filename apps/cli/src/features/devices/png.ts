import { crc32, deflateSync } from 'node:zlib';

// The bytes of a PNG's fixed parts, written as Latin-1 text, one character
// per byte.
const signature = '\x89PNG\r\n\x1a\n';
// Eight bits per sample, one gray sample per pixel, the standard compression
// and filters, and no interlacing.
const grayscaleHeader = '\x08\x00\x00\x00\x00';
const noFilter = 0;
const black = 0;
const white = 255;
const uint32Bytes = 4;

const latin1 = (text: string) =>
  Uint8Array.from(text, (character) => character.charCodeAt(0));

const uint32 = (value: number) => {
  const bytes = new Uint8Array(uint32Bytes);
  new DataView(bytes.buffer).setUint32(0, value);
  return bytes;
};

const concat = (parts: ReadonlyArray<Uint8Array>) => {
  const all = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
  parts.reduce((offset, part) => {
    all.set(part, offset);
    return offset + part.length;
  }, 0);
  return all;
};

/** A chunk: its length, its type, its data, and a checksum of type and data. */
const chunk = (type: string, data: Uint8Array) => {
  const body = concat([latin1(type), data]);
  return concat([uint32(data.length), body, uint32(crc32(body))]);
};

export type Bitmap = {
  readonly width: number;
  readonly height: number;
  readonly isDark: (x: number, y: number) => boolean;
};

/** A black and white image as a grayscale PNG. */
export const encodePng = ({ width, height, isDark }: Bitmap) => {
  const rowLength = width + 1;
  const pixels = new Uint8Array(rowLength * height);
  for (let y = 0; y < height; y += 1) {
    pixels[y * rowLength] = noFilter;
    for (let x = 0; x < width; x += 1) {
      pixels[y * rowLength + 1 + x] = isDark(x, y) ? black : white;
    }
  }
  const header = concat([
    uint32(width),
    uint32(height),
    latin1(grayscaleHeader),
  ]);
  return concat([
    latin1(signature),
    chunk('IHDR', header),
    chunk('IDAT', deflateSync(pixels)),
    chunk('IEND', new Uint8Array()),
  ]);
};
