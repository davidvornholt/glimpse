import { describe, expect, it } from 'bun:test';
import { Schema } from 'effect';
import { AnyErrorBody, NotFound, NotFoundBody } from './api-errors.ts';

describe('error bodies', () => {
  it('put the tag and the message on the wire', () => {
    const encoded = Schema.encodeSync(NotFoundBody)(
      new NotFound({ message: 'There is no photo X.' }),
    );
    expect(encoded).toEqual({
      error: { tag: 'NotFound', message: 'There is no photo X.' },
    });
  });

  it('come back as the error they were', () => {
    const decoded = Schema.decodeUnknownSync(NotFoundBody)({
      error: { tag: 'NotFound', message: 'Gone.' },
    });
    expect(decoded).toBeInstanceOf(NotFound);
    expect(decoded.message).toBe('Gone.');
  });

  it('refuse a body with another tag, which any body still reads', () => {
    const body = { error: { tag: 'Conflict', message: 'Used.' } };
    expect(() => Schema.decodeUnknownSync(NotFoundBody)(body)).toThrow();
    expect(Schema.decodeUnknownSync(AnyErrorBody)(body)).toEqual(body);
  });
});
