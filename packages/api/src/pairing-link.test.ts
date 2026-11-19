import { describe, expect, it } from 'bun:test';
import { Option } from 'effect';
import { pairingLink, parsePairingLink } from './pairing-link.ts';

describe('pairing links', () => {
  it('carry the server and the code there and back', () => {
    const link = pairingLink('https://glimpse.example.com:8443', 'ABC234');
    expect(link).toBe(
      'glimpse://pair?server=https%3A%2F%2Fglimpse.example.com%3A8443&code=ABC234',
    );
    expect(parsePairingLink(link)).toEqual(
      Option.some({
        server: 'https://glimpse.example.com:8443',
        code: 'ABC234',
      }),
    );
  });

  it('are read when a parser puts the target in the path', () => {
    expect(
      parsePairingLink('glimpse:///pair?server=https%3A%2F%2Fa.example&code=X'),
    ).toEqual(Option.some({ server: 'https://a.example', code: 'X' }));
  });

  it('are refused when anything is missing or foreign', () => {
    for (const link of [
      'https://glimpse.example.com/pair?server=x&code=y',
      'glimpse://other?server=x&code=y',
      'glimpse://pair?code=y',
      'glimpse://pair?server=x&code=',
      'not a link',
    ]) {
      expect(parsePairingLink(link)).toEqual(Option.none());
    }
  });
});
