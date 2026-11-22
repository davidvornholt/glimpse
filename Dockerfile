# syntax=docker/dockerfile:1

# The server compiles to one executable, so the image holds only that file
# and the C++ runtime Bun's Alpine build links against.
FROM docker.io/oven/bun:1.4.5-alpine AS build
WORKDIR /app

COPY package.json bun.lock ./
COPY apps/cli/package.json ./apps/cli/package.json
COPY apps/server/package.json ./apps/server/package.json
COPY packages/a11y-testing/package.json ./packages/a11y-testing/package.json
COPY packages/api/package.json ./packages/api/package.json
COPY packages/typescript-config/package.json ./packages/typescript-config/package.json
RUN bun install --frozen-lockfile --production --ignore-scripts

COPY packages/api ./packages/api
COPY apps/server ./apps/server
RUN bun run --cwd apps/server build

FROM docker.io/library/alpine:3.22 AS runner

RUN apk add --no-cache libgcc libstdc++ \
  && install -d -o 65532 -g 65532 -m 0700 /data

COPY --from=build /app/apps/server/dist/glimpse-server /usr/local/bin/glimpse-server

# A new named volume mounted at /data takes this directory's owner, so the
# default user can write to it. Run with another --user only with a data
# directory that user owns.
ENV GLIMPSE_DATA_DIR=/data
ENV PORT=8787
EXPOSE 8787

USER 65532:65532

CMD ["/usr/local/bin/glimpse-server"]
