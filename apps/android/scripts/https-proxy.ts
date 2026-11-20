// Serves HTTPS on PROXY_PORT with the certificate in PROXY_CERT and PROXY_KEY
// and passes every request on to the server at PROXY_TARGET, so a release
// build, which talks to servers only over HTTPS, can pair with a local server.
// release-smoke.sh starts it. Logs each connection and failed handshake, which
// tell a certificate the phone rejects apart from a phone that never connects.
import { readFileSync } from 'node:fs';
import { request } from 'node:http';
import { createServer } from 'node:https';
import process from 'node:process';

const env = (name: string) => {
  const value = process.env[name];
  if (value === undefined || value === '') {
    throw new Error(`${name} is not set`);
  }
  return value;
};

const target = new URL(env('PROXY_TARGET'));
const badGateway = 502;

const server = createServer(
  {
    cert: readFileSync(env('PROXY_CERT')),
    key: readFileSync(env('PROXY_KEY')),
  },
  (incoming, outgoing) => {
    console.log(`${incoming.method} ${incoming.url}`);
    const forwarded = request(
      {
        host: target.hostname,
        port: target.port,
        method: incoming.method,
        path: incoming.url,
        headers: incoming.headers,
      },
      (response) => {
        outgoing.writeHead(response.statusCode ?? badGateway, response.headers);
        response.pipe(outgoing);
      },
    );
    forwarded.on('error', (error) => {
      console.log(`forwarding failed: ${error.message}`);
      outgoing.destroy();
    });
    incoming.pipe(forwarded);
  },
);
server.on('secureConnection', () => console.log('handshake done'));
server.on('tlsClientError', (error) =>
  console.log(`handshake failed: ${error.message}`),
);
// The phone holds GET /v1/events open for as long as it runs.
server.requestTimeout = 0;
server.listen(Number(env('PROXY_PORT')), '127.0.0.1');
