import type { OriginalType } from '@glimpse/api/photo';
import { Option } from 'effect';

/** Bytes as Latin-1 text, one character per byte, to compare with signatures. */
const latin1 = (bytes: Uint8Array, start: number, length: number) =>
  String.fromCharCode(...bytes.subarray(start, start + length));

const startsWith = (bytes: Uint8Array, signature: string) =>
  latin1(bytes, 0, signature.length) === signature;

const jpegSignature = '\xff\xd8\xff';
const pngSignature = '\x89PNG\r\n\x1a\n';

// A RIFF file names its form after the 4-byte size: `RIFF....WEBP`.
const riffFormOffset = 8;

// An ISO media file, which HEIF and AVIF are, opens with an `ftyp` box: its
// 4-byte size and type, the major brand, a 4-byte version, and then the
// compatible brands, each a four-character code.
const fourCc = 4;
const majorBrandOffset = 8;
const compatibleBrandsOffset = 16;

const heifBrands = new Set([
  'heic',
  'heix',
  'hevc',
  'hevx',
  'heim',
  'heis',
  'hevm',
  'hevs',
  'mif1',
  'msf1',
]);
const avifBrands = new Set(['avif', 'avis']);

const isoBrands = (head: Uint8Array) => {
  if (
    head.length < compatibleBrandsOffset ||
    latin1(head, fourCc, fourCc) !== 'ftyp'
  ) {
    return [];
  }
  const view = new DataView(head.buffer, head.byteOffset, head.byteLength);
  const end = Math.min(view.getUint32(0), head.length);
  const brands = [latin1(head, majorBrandOffset, fourCc)];
  for (
    let offset = compatibleBrandsOffset;
    offset + fourCc <= end;
    offset += fourCc
  ) {
    brands.push(latin1(head, offset, fourCc));
  }
  return brands;
};

/** How many leading bytes {@link looksLike} needs. */
export const headBytes = 64;

const checks: Record<OriginalType, (head: Uint8Array) => boolean> = {
  'image/jpeg': (head) => startsWith(head, jpegSignature),
  'image/png': (head) => startsWith(head, pngSignature),
  'image/webp': (head) =>
    startsWith(head, 'RIFF') && latin1(head, riffFormOffset, fourCc) === 'WEBP',
  'image/heic': (head) =>
    isoBrands(head).some((brand) => heifBrands.has(brand)),
  'image/heif': (head) =>
    isoBrands(head).some((brand) => heifBrands.has(brand)),
  'image/avif': (head) =>
    isoBrands(head).some((brand) => avifBrands.has(brand)),
};

/** Whether the first bytes of a file fit the image type it was sent as. */
export const looksLike = (type: OriginalType, head: Uint8Array) =>
  checks[type](head);

// A JPEG is a run of segments, each a 0xFF byte, a marker, and, for most
// markers, a 2-byte length that counts itself and what follows.
const markerByte = 0xff;
const markerBytes = 2;
const lengthBytes = 2;
const startOfScan = 0xda;
// A start-of-frame segment holds the precision (1 byte), then the height and
// the width (2 bytes each).
const frameHeightOffset = 5;
const frameWidthOffset = 7;
const frameBytes = 9;

// Start-of-frame markers, which carry the size: all of 0xC0 to 0xCF but the
// Huffman (C4), JPEG-extension (C8) and arithmetic coding (CC) tables.
const firstFrame = 0xc0;
const lastFrame = 0xcf;
const huffmanTables = 0xc4;
const jpegExtension = 0xc8;
const arithmeticTables = 0xcc;
const notFrames = new Set([huffmanTables, jpegExtension, arithmeticTables]);
const isStartOfFrame = (marker: number) =>
  marker >= firstFrame && marker <= lastFrame && !notFrames.has(marker);

// Markers without a length: the restart markers, start of image, and TEM.
const firstRestart = 0xd0;
const startOfImage = 0xd8;
const temporary = 0x01;
const standsAlone = (marker: number) =>
  (marker >= firstRestart && marker <= startOfImage) || marker === temporary;

type Size = { readonly width: number; readonly height: number };
type Step = { readonly next: number } | { readonly size: Option.Option<Size> };

const frameSize = (view: DataView, offset: number): Option.Option<Size> => {
  if (offset + frameBytes > view.byteLength) {
    return Option.none();
  }
  const height = view.getUint16(offset + frameHeightOffset);
  const width = view.getUint16(offset + frameWidthOffset);
  return width > 0 && height > 0
    ? Option.some({ width, height })
    : Option.none();
};

/** Reads the segment at `offset`: the size, the end, or where the next one starts. */
const step = (view: DataView, offset: number): Step => {
  if (view.getUint8(offset) !== markerByte) {
    return { size: Option.none() };
  }
  const marker = view.getUint8(offset + 1);
  if (marker === markerByte) {
    // A fill byte before the marker.
    return { next: offset + 1 };
  }
  if (standsAlone(marker)) {
    return { next: offset + markerBytes };
  }
  if (isStartOfFrame(marker)) {
    return { size: frameSize(view, offset) };
  }
  const length = view.getUint16(offset + markerBytes);
  return marker === startOfScan || length < lengthBytes
    ? { size: Option.none() }
    : { next: offset + markerBytes + length };
};

/** The width and height of a JPEG, read from its start-of-frame segment. */
export const jpegSize = (bytes: Uint8Array): Option.Option<Size> => {
  if (!startsWith(bytes, jpegSignature)) {
    return Option.none();
  }
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let offset = markerBytes;
  while (offset + markerBytes + lengthBytes <= view.byteLength) {
    const result = step(view, offset);
    if ('size' in result) {
      return result.size;
    }
    offset = result.next;
  }
  return Option.none();
};
