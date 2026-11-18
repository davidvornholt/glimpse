# Glimpse

> Built on [davidvornholt/standards](https://github.com/davidvornholt/standards).

Glimpse hands a photo from your Android phone to a coding agent without putting it in your photo library. You take the photo in the Glimpse app, it goes to your own Glimpse server, and the agent looks at it with the `glimpse` command-line tool. The server deletes every photo after 30 days.

## How it fits together

- **The server** (`apps/server`) keeps the photos a phone sends until they expire. It knows two kinds of callers: the owner, which is the CLI with a token you set up, and phones, which pair with a one-time code. It strips camera metadata such as the location from every preview it accepts.
- **The CLI** (`apps/cli`) is how an agent waits for a photo, looks at it, and pairs phones. Its agent skill, in `apps/cli/skills/glimpse`, tells the agent when and how to use it.
- **The API** (`packages/api`) is the contract the server, the CLI, and the phone share. [docs/api.md](docs/api.md) describes it.

A typical exchange: the agent asks you for a photo and runs `glimpse wait`; the app on your phone shows that an agent is waiting; you take the photo, and the agent sees it a moment later.

## Run the server

The image `ghcr.io/davidvornholt/glimpse` runs the server on port 8787 and keeps its data in `/data`. Make an owner token and its digest as [the server's README](apps/server/README.md#configuration-in-production) shows, keep the token for `glimpse login`, and start it with the digest:

```sh
docker run --detach --name glimpse \
  --read-only --cap-drop=ALL --security-opt=no-new-privileges \
  --volume glimpse-data:/data \
  --env GLIMPSE_PUBLIC_URL=https://glimpse.example.com \
  --env GLIMPSE_OWNER_TOKEN_SHA256=<digest> \
  --publish 127.0.0.1:8787:8787 \
  ghcr.io/davidvornholt/glimpse:<commit>
```

Put it behind a reverse proxy that serves HTTPS at `GLIMPSE_PUBLIC_URL`, ideally reachable only from your own devices, such as over a VPN. Phones scan that address from the pairing code, so it must be the one they can reach.

## Install the CLI

With Nix, the flake's default package installs `glimpse` and puts the skill in `share/glimpse/skills`:

```sh
nix profile install github:davidvornholt/glimpse
```

Without Nix, build it with [Bun](https://bun.sh); this puts `glimpse` in `~/.local/bin` and the skill in `~/.agents/skills`:

```sh
bun install
bun run --cwd apps/cli install:user
```

Then sign in, pasting the owner token when asked, and pair your phone by scanning the code it draws:

```sh
glimpse login --server https://glimpse.example.com
glimpse pair
```

## Develop

`nix develop` provides the tools, and `bun run check` runs every check. [apps/server/README.md](apps/server/README.md) shows how to run a development server that an Android emulator can reach.

## License

[MIT](LICENSE) © David Vornholt
