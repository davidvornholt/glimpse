import { Option } from 'effect';

/**
 * The link a phone scans to pair: `glimpse://pair?server=<base URL>&code=<code>`.
 * `server` is percent-encoded, the code is opaque to the phone.
 */
export const pairingLink = (server: string, code: string) =>
  `glimpse://pair?server=${encodeURIComponent(server)}&code=${encodeURIComponent(code)}`;

const leadingSlashes = /^\/+/u;

export const parsePairingLink = (link: string) => {
  const url = URL.parse(link);
  if (url === null || url.protocol !== 'glimpse:') {
    return Option.none();
  }
  // `glimpse://pair` has `pair` as its host; some parsers put it in the path.
  const target =
    url.host === '' ? url.pathname.replace(leadingSlashes, '') : url.host;
  const server = url.searchParams.get('server');
  const code = url.searchParams.get('code');
  if (target !== 'pair' || server === null || code === null || code === '') {
    return Option.none();
  }
  return Option.some({ server, code });
};
