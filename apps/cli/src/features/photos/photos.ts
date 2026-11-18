import {
  type Photo,
  PhotoPageSchema,
  PhotoSchema,
  WaitResultSchema,
} from '@glimpse/api/photo';
import { DateTime, Schema } from 'effect';
import { requestJson, requestNothing } from '../../shared/glimpse.ts';

const PhotoJson = Schema.toCodecJson(PhotoSchema);
const PageJson = Schema.toCodecJson(PhotoPageSchema);
const WaitJson = Schema.toCodecJson(WaitResultSchema);

const isoOrNull = (time: DateTime.Utc | null) =>
  time === null ? null : DateTime.formatIso(time);

/** A photo as the server sends it, with its times as ISO text. */
export const photoJson = (photo: Photo) => ({
  ...photo,
  createdAt: DateTime.formatIso(photo.createdAt),
  readyAt: isoOrNull(photo.readyAt),
  takenAt: DateTime.formatIso(photo.takenAt),
  expiresAt: DateTime.formatIso(photo.expiresAt),
  viewedAt: isoOrNull(photo.viewedAt),
});

export type ListOptions = {
  readonly limit: number;
  readonly before: string | undefined;
  readonly since: string | undefined;
};

/** Ready photos, the newest to arrive first. */
export const listPhotos = ({ limit, before, since }: ListOptions) =>
  requestJson(
    {
      method: 'GET',
      path: '/v1/photos',
      query: { limit: String(limit), before, since },
    },
    PageJson,
  );

export const getPhoto = (id: string) =>
  requestJson({ method: 'GET', path: `/v1/photos/${id}` }, PhotoJson);

export const setNote = (id: string, note: string | null) =>
  requestJson(
    { method: 'PATCH', path: `/v1/photos/${id}`, body: { note } },
    PhotoJson,
  );

export const removePhoto = (id: string) =>
  requestNothing({ method: 'DELETE', path: `/v1/photos/${id}` });

export type WaitRequest = {
  /** An ISO time; only photos that became ready after it count. */
  readonly after: string;
  readonly timeoutSeconds: number;
  /** The same for every request of one `glimpse wait`. */
  readonly waiter: string;
};

/**
 * One long poll: the photos nobody has looked at that arrived after `after`,
 * as soon as there is one, or none once the time is up.
 */
export const waitOnce = ({ after, timeoutSeconds, waiter }: WaitRequest) =>
  requestJson(
    {
      method: 'GET',
      path: '/v1/photos/wait',
      query: { after, timeoutSeconds: String(timeoutSeconds), waiter },
    },
    WaitJson,
  );
