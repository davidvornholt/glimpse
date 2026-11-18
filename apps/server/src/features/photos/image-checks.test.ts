import { describe, expect, it } from 'bun:test';
import { Option } from 'effect';
import { jpegSize, looksLike } from './image-checks.ts';

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
