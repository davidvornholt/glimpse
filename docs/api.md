# Glimpse API

The schemas in `packages/api/src` are the contract; the server and the CLI both build on them, and the Android app mirrors them. This page explains what the schemas don't say.

## Callers

Every route but `/healthz` and `POST /v1/pairings/claim` takes `Authorization: Bearer <token>`. A token is either the owner's, which the CLI holds and the server knows only as a SHA-256 digest from `GLIMPSE_OWNER_TOKEN_SHA256`, or a phone's, which the phone got by claiming a pairing code. `GET /v1/me` says which.

- Only the owner pairs and removes phones and waits for photos.
- Only a phone registers photos and uploads their images, and only for photos it registered itself.
- Both list, get, fetch, note, and delete photos, and both listen on `GET /v1/events`, so the phone can show what it sent and whether an agent looked.

## Pairing

1. `POST /v1/pairings` (owner) returns `glimpse://pair?server=<base URL>&code=<code>`, the link the phone scans as a QR code. The base URL is `GLIMPSE_PUBLIC_URL`.
2. The phone sends the code with a device name to `POST /v1/pairings/claim` and gets its token, which the server shows only this once and keeps as a digest.

A code works once, for ten minutes, and lives only in memory, so a restart forgets it. Wrong codes aren't rate limited, because a code's 120 random bits can't be guessed in ten minutes, and a shared limit would let anyone who can reach the server lock every phone out of pairing.

## Sending a photo

The phone makes a ULID for the photo and keeps it across retries.

1. `PUT /v1/photos/{id}` with the metadata registers it and answers 201. Registering it again answers 200 when the fixed details (when and how it was taken, its size, its type and its batch) are the same, so a retry is safe, and `Conflict` when they differ. The note isn't compared, and a repeat doesn't change it: `PATCH /v1/photos/{id}` does.
2. `PUT /v1/photos/{id}/preview` with a JPEG of at most 2048 px on the longest edge makes the photo ready: it gets `readyAt` and from then on is listed, announced, and returned by waits. The server drops the camera metadata (EXIF, XMP, IPTC, comments) and whatever follows the image's end, such as a gain map, so a preview never says where it was taken even if the phone left that in.
3. `PUT /v1/photos/{id}/original` with the `originalType` registered for it adds the full file, metadata and all. The phone sends it after the preview, so the agent can look sooner.

A photo whose preview hasn't arrived after a day is deleted. Every photo is deleted `GLIMPSE_RETENTION_DAYS` (30) days after it was registered, which `expiresAt` says. The server sweeps at start-up, before it answers, and every hour, and a photo it couldn't delete is tried again at the next sweep.

## Looking at photos

- `GET /v1/photos` pages through ready photos, the newest `readyAt` first. `readyAt` is unique, so the `nextCursor` cursor never skips or repeats a photo.
- `viewedAt` is set when the owner first fetches the preview or the original. A phone fetching them doesn't count, because the phone wants to know whether an agent looked.
- `GET /v1/photos/wait?after=<time>&timeoutSeconds=<0–60>&waiter=<id>` (owner) answers at once with the ready photos nobody has viewed that became ready after `after`, oldest first, or waits for the first such photo and then answers with all of them, or answers with none after the timeout (30 s by default, 60 s at most). The CLI repeats it until its own deadline and sends the same `waiter` each time.

## Events

`GET /v1/events` is a server-sent event stream with a comment every 20 seconds to keep proxies from closing it. A phone's stream ends when the owner removes the phone.

- `photo`: a ready photo arrived or changed, such as a new note, its original, or its first view. Photos still waiting for their preview aren't announced.
- `photo-deleted`: a photo was deleted or expired.
- `waiting`: how many agents are waiting with `glimpse wait`, sent when the stream opens and whenever it changes. A wait still counts for 5 seconds after its request ends, so the CLI's back-to-back requests look like one steady wait.

A listener that falls more than 1024 events behind loses the oldest ones; it should reload the photo list when it reconnects.

## Errors

Every error, including the ones the framework raises for a bad query, an unknown route, or a body too large, has the body `{"error":{"tag","message"}}`. The message is meant for the user, and clients show it as it is. Branch on the status or the tag:

| Status | Tag |
| --- | --- |
| 400 | `InvalidRequest`, such as a bad id or a preview that isn't a JPEG |
| 401 | `Unauthorized` |
| 403 | `Forbidden`, such as a phone pairing another phone |
| 404 | `NotFound`, also for a wrong method |
| 409 | `Conflict` |
| 413 | `TooLarge`: a preview over 4 MiB, an original over 50 MiB, or any other body over 64 KiB |
| 415 | `InvalidRequest`: a Content-Type the route doesn't take |
| 500 | `ServerError` |
