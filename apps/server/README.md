# Glimpse server

The server keeps the photos a phone sends until an agent fetches them, and deletes each one after the retention period, 30 days unless configured otherwise. The API is defined in `packages/api` and described in [docs/api.md](../../docs/api.md).

## Run it for development

```sh
cd apps/server
bun run dev
```

It listens on `127.0.0.1:8787`, keeps its data in `apps/server/.data`, and accepts the owner token `dev-owner-token`. Pairing links point at `http://10.0.2.2:8787`, the address under which an Android emulator reaches the host's loopback, so a phone in the emulator can scan a code made with:

```sh
curl -s -X POST -H 'Authorization: Bearer dev-owner-token' http://127.0.0.1:8787/v1/pairings
```

`PORT` and `GLIMPSE_HOST` change where it listens. Delete `apps/server/.data` to start over.

## Configuration in production

`bun run build` compiles `dist/glimpse-server`, which reads:

| Variable | Meaning |
| --- | --- |
| `GLIMPSE_DATA_DIR` | Where photos and paired phones are kept. Required. |
| `GLIMPSE_PUBLIC_URL` | The address phones use, which goes into pairing links. Required. |
| `GLIMPSE_OWNER_TOKEN_SHA256` | SHA-256 digests, in hex and separated by commas, of the tokens that act as the owner. Required. The server never sees an owner token it doesn't need. |
| `GLIMPSE_RETENTION_DAYS` | How long a photo is kept. Defaults to 30. |
| `PORT`, `GLIMPSE_HOST` | Where it listens. Default `0.0.0.0:8787`. |

Make an owner token and its digest with:

```sh
token="go_$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')"
printf %s "$token" | sha256sum | cut -d' ' -f1
```

## Storage

Each photo is a directory under `photos/<id>/` holding `meta.json`, `preview.jpg`, and the original. Paired phones are in `devices.json`, which keeps only the SHA-256 of each phone's token. Every file is written to a temporary name and renamed, so a crash never leaves half a file in place. At start-up the server loads every photo, skips records it can't read with a warning, and deletes leftover drafts.
