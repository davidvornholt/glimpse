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

// Segments that describe the photo rather than draw it are dropped: EXIF and
// XMP (APP1), IPTC (APP13), the maker notes other application segments hold,
// such as Canon's owner name or a JFXX thumbnail in APP0, and comments. Only
// the application segments that change how the image looks stay, each known
// by the name it opens with: the colour profile (APP2) whole, and the fixed
// fields of the JFIF header (APP0) and of Adobe's colour transform (APP14)
// without what follows them. The JFIF header loses its thumbnail, which can
// show what a crop removed. Either is dropped when shorter than its fields.
const firstApplication = 0xe0;
const lastApplication = 0xef;
const jfifHeader = 0xe0;
const colourSegment = 0xe2;
const adobeTransform = 0xee;
const comment = 0xfe;
const endOfImage = 0xd9;
const lastRestart = 0xd7;
const stuffedZero = 0x00;
// The JFIF header's length counts `JFIF\0`, the version (2 bytes), the units,
// the X and Y density (2 bytes each), and the thumbnail's width and height (1
// byte each), which are set to zero. Adobe's counts `Adobe`, the version and
// two sets of flags (2 bytes each), and the transform.
const jfifLength = 16;
const thumbnailSizeBytes = 2;
const adobeLength = 14;

/**
 * A segment cut to the first `length` bytes its length field counts, with the
 * field set to `length`. Nothing when the segment is shorter.
 */
const fixedPart = (segment: Uint8Array, length: number) => {
  if (segment.length < markerBytes + length) {
    return Option.none<Uint8Array>();
  }
  // A copy, since a `Buffer`'s `slice` would share the upload's bytes.
  const kept = new Uint8Array(segment.subarray(0, markerBytes + length));
  new DataView(kept.buffer).setUint16(markerBytes, length);
  return Option.some(kept);
};

const keptApplications = new Map<
  number,
  {
    readonly name: string;
    readonly keep: (segment: Uint8Array) => Option.Option<Uint8Array>;
  }
>([
  [
    jfifHeader,
    {
      name: 'JFIF\x00',
      keep: (segment) =>
        Option.map(fixedPart(segment, jfifLength), (kept) =>
          kept.fill(0, -thumbnailSizeBytes),
        ),
    },
  ],
  [colourSegment, { name: 'ICC_PROFILE\x00', keep: Option.some }],
  [
    adobeTransform,
    { name: 'Adobe', keep: (segment) => fixedPart(segment, adobeLength) },
  ],
]);

/** What stays of a segment, from its marker to where the next one starts. */
const keptPart = (segment: Uint8Array, marker: number) => {
  if (marker === comment) {
    return Option.none<Uint8Array>();
  }
  if (marker < firstApplication || marker > lastApplication) {
    return Option.some(segment);
  }
  const kept = keptApplications.get(marker);
  return kept !== undefined &&
    latin1(segment, markerBytes + lengthBytes, kept.name.length) === kept.name
    ? kept.keep(segment)
    : Option.none<Uint8Array>();
};

/**
 * Where the compressed image data after a start-of-scan header ends: at the
 * first marker that is neither a stuffed zero, a restart, nor a fill byte.
 */
const scanEnd = (bytes: Uint8Array, start: number) => {
  for (let at = start; at + 1 < bytes.length; at += 1) {
    const next = bytes[at + 1] ?? stuffedZero;
    const inData =
      next === stuffedZero ||
      next === markerByte ||
      (next >= firstRestart && next <= lastRestart);
    if (bytes[at] === markerByte && !inData) {
      return at;
    }
  }
  return bytes.length;
};

type Segment =
  | { readonly next: number; readonly kept: Option.Option<Uint8Array> }
  | 'end'
  | 'broken';

/** The segment at `offset`, with where the next one starts. */
const segmentAt = (bytes: Uint8Array, view: DataView, offset: number) => {
  const marker = bytes[offset + 1];
  if (bytes[offset] !== markerByte || marker === undefined) {
    return 'broken';
  }
  if (marker === endOfImage) {
    return 'end';
  }
  if (marker === markerByte) {
    return { next: offset + 1, kept: Option.none() };
  }
  if (standsAlone(marker)) {
    return {
      next: offset + markerBytes,
      kept: Option.some(bytes.subarray(offset, offset + markerBytes)),
    };
  }
  if (offset + markerBytes + lengthBytes > bytes.length) {
    return 'broken';
  }
  const end = offset + markerBytes + view.getUint16(offset + markerBytes);
  if (end < offset + markerBytes + lengthBytes || end > bytes.length) {
    return 'broken';
  }
  const next = marker === startOfScan ? scanEnd(bytes, end) : end;
  return {
    next,
    kept: keptPart(bytes.subarray(offset, next), marker),
  } satisfies Segment;
};

/**
 * A JPEG without the metadata a camera writes, such as where and when it was
 * taken, and without anything after its end, such as a gain map or a motion
 * photo with metadata of its own. Nothing when it is not a whole JPEG.
 */
export const withoutMetadata = (
  bytes: Uint8Array,
): Option.Option<Uint8Array> => {
  if (!startsWith(bytes, jpegSignature)) {
    return Option.none();
  }
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const kept = [bytes.subarray(0, markerBytes)];
  let offset = markerBytes;
  while (offset < bytes.length) {
    const segment: Segment = segmentAt(bytes, view, offset);
    if (segment === 'broken') {
      return Option.none();
    }
    if (segment === 'end') {
      kept.push(bytes.subarray(offset, offset + markerBytes));
      return Option.some(Buffer.concat(kept));
    }
    if (Option.isSome(segment.kept)) {
      kept.push(segment.kept.value);
    }
    offset = segment.next;
  }
  return Option.none();
};
