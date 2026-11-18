import { describe, expect, it } from 'bun:test';
import { Option } from 'effect';
import { jpegSize, looksLike, withoutMetadata } from './image-checks.ts';

const bytes = (text: string) =>
  Uint8Array.from(text, (character) => character.charCodeAt(0));

// An `ftyp` box of 24 bytes: size, type, major brand, version, one brand.
const ftyp = (major: string, compatible: string) =>
  bytes(`\x00\x00\x00\x18ftyp${major}\x00\x00\x00\x00${compatible}`);

describe('looksLike', () => {
  it('knows each type by its first bytes', () => {
    expect(looksLike('image/jpeg', bytes('\xff\xd8\xff\xe0'))).toBe(true);
    expect(looksLike('image/png', bytes('\x89PNG\r\n\x1a\n'))).toBe(true);
    expect(looksLike('image/webp', bytes('RIFF\x10\x00\x00\x00WEBPVP8 '))).toBe(
      true,
    );
    expect(looksLike('image/heic', ftyp('heic', 'mif1'))).toBe(true);
    expect(looksLike('image/heif', ftyp('mif1', 'heic'))).toBe(true);
    expect(looksLike('image/avif', ftyp('avif', 'mif1'))).toBe(true);
  });

  it('refuses another type under the name', () => {
    expect(looksLike('image/png', bytes('\xff\xd8\xff\xe0'))).toBe(false);
    expect(looksLike('image/avif', ftyp('heic', 'mif1'))).toBe(false);
    expect(looksLike('image/webp', bytes('RIFF\x10\x00\x00\x00WAVE'))).toBe(
      false,
    );
    expect(looksLike('image/heic', bytes(''))).toBe(false);
  });
});

describe('jpegSize', () => {
  it('reads the size past other segments', () => {
    const app0 = '\xff\xe0\x00\x04\x00\x00';
    const frame = '\xff\xc2\x00\x11\x08\x0b\xd0\x0f\xc0\x03';
    expect(jpegSize(bytes(`\xff\xd8${app0}${frame}`))).toEqual(
      Option.some({ width: 4032, height: 3024 }),
    );
  });

  it('finds no size in a JPEG that ends first, or in what is not a JPEG', () => {
    expect(jpegSize(bytes('\xff\xd8\xff\xda\x00\x02'))).toEqual(Option.none());
    expect(jpegSize(bytes('\x89PNG\r\n\x1a\n'))).toEqual(Option.none());
  });
});

describe('withoutMetadata', () => {
  // Segments as Latin-1 text: the marker, then a length that counts itself.
  const segment = (marker: string, body: string) =>
    `\xff${marker}\x00${String.fromCharCode(body.length + 2)}${body}`;
  const jfif = segment('\xe0', 'JFIF\x00\x01\x02');
  const exif = segment('\xe1', 'Exif\x00\x00GPS 48.1N 11.5E');
  const xmp = segment('\xe1', 'http://ns.adobe.com/xap/1.0/\x00<x/>');
  const icc = segment('\xe2', 'ICC_PROFILE\x00\x01\x01sRGB');
  const multiPicture = segment('\xe2', 'MPF\x00II*\x00');
  const note = segment('\xfe', 'Taken at home');
  const tables = segment('\xdb', '\x00quantization');
  const frame =
    '\xff\xc0\x00\x11\x08\x00\x10\x00\x20\x03\x01\x22\x00\x02\x11\x01\x03\x11\x01';
  // Compressed data may hold 0xFF only as a stuffed zero or a restart marker.
  const scan = `${segment('\xda', '\x03\x01\x00')}\x12\xff\x00\x34\xff\xd0\x56`;
  const end = '\xff\xd9';

  it('drops what describes the photo and keeps what draws it', () => {
    const photo = `\xff\xd8${jfif}${exif}${xmp}${icc}${multiPicture}${note}${tables}${frame}${scan}${end}`;
    const kept = `\xff\xd8${jfif}${icc}${tables}${frame}${scan}${end}`;
    expect(withoutMetadata(bytes(photo))).toEqual(Option.some(bytes(kept)));
  });

  it('drops what follows the end, such as a gain map with its own metadata', () => {
    const photo = `\xff\xd8${frame}${scan}${end}\xff\xd8${exif}${frame}${scan}${end}`;
    expect(withoutMetadata(bytes(photo))).toEqual(
      Option.some(bytes(`\xff\xd8${frame}${scan}${end}`)),
    );
  });

  it('refuses a JPEG that is cut off', () => {
    expect(withoutMetadata(bytes(`\xff\xd8${frame}${scan}`))).toEqual(
      Option.none(),
    );
    expect(withoutMetadata(bytes(`\xff\xd8${exif.slice(0, 8)}`))).toEqual(
      Option.none(),
    );
  });
});
