import { Effect } from 'effect';
import { InvalidInput } from './errors/errors.ts';

export const exampleServer = 'https://glimpse.example.com';

const loopbackHosts = new Set(['localhost', '127.0.0.1', '[::1]']);

/**
 * The origin of a Glimpse server. Only a server on this machine may use plain
 * HTTP, because the owner token travels in every request.
 */
export const serverOrigin = (value: string) =>
  Effect.gen(function* () {
    const url = URL.parse(value.trim());
    if (
      url === null ||
      (url.protocol !== 'https:' && url.protocol !== 'http:')
    ) {
      return yield* new InvalidInput({
        message: `${value} is not a web address. Pass the address of your Glimpse server, such as ${exampleServer}.`,
      });
    }
    if (url.protocol === 'http:' && !loopbackHosts.has(url.hostname)) {
      return yield* new InvalidInput({
        message: `Use https for ${url.host}. Plain http only works for a server on this machine.`,
      });
    }
    return url.origin;
  });
