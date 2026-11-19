import { HttpApiMiddleware, HttpApiSecurity } from 'effect/http-api';
import type { CurrentCaller } from './caller.ts';
import { UnauthorizedBody } from './errors/api-errors.ts';

/** Every endpoint but claiming a pairing code needs a bearer token. */
export class Authorization extends HttpApiMiddleware.Service<
  Authorization,
  { provides: CurrentCaller }
>()('@glimpse/api/Authorization', {
  requiredForClient: true,
  security: { bearer: HttpApiSecurity.bearer },
  error: UnauthorizedBody,
}) {}
