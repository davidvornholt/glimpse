import { Context } from 'effect';
import type { Device } from './device.ts';

/**
 * Who is calling. The owner token belongs to the CLI on the owner's
 * computers; each paired phone has a device token of its own.
 */
export type Caller =
  | { readonly kind: 'owner' }
  | { readonly kind: 'device'; readonly device: Device };

export class CurrentCaller extends Context.Service<CurrentCaller, Caller>()(
  '@glimpse/api/CurrentCaller',
) {}
