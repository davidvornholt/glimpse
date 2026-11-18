import { Schema } from 'effect';

// The command's own arguments are wrong, before anything reaches the server.
export class InvalidInput extends Schema.TaggedError<InvalidInput>()(
  'InvalidInput',
  { message: Schema.String },
) {}

// No owner token is stored on this machine, or the server no longer takes it.
export class NotSignedIn extends Schema.TaggedError<NotSignedIn>()(
  'NotSignedIn',
  { message: Schema.String },
) {}

// The server refused the request and said why. `tag` is the server's own,
// such as NotFound, and is what the CLI reports.
export class ServerRejected extends Schema.TaggedError<ServerRejected>()(
  'ServerRejected',
  { tag: Schema.String, message: Schema.String, status: Schema.Number },
) {}

// Nothing answered: the machine is offline or the server is down.
export class ServerUnreachable extends Schema.TaggedError<ServerUnreachable>()(
  'ServerUnreachable',
  { message: Schema.String },
) {}

// The server answered in a way this CLI does not understand, usually because
// one of them is older than the other.
export class UnexpectedResponse extends Schema.TaggedError<UnexpectedResponse>()(
  'UnexpectedResponse',
  { message: Schema.String },
) {}

// The credentials file exists but cannot be read or written.
export class CredentialsStoreFailed extends Schema.TaggedError<CredentialsStoreFailed>()(
  'CredentialsStoreFailed',
  { message: Schema.String },
) {}

// A preview, an original or a QR code could not be saved on this machine.
export class FileNotSaved extends Schema.TaggedError<FileNotSaved>()(
  'FileNotSaved',
  { message: Schema.String },
) {}

export type GlimpseFailure =
  | NotSignedIn
  | ServerRejected
  | ServerUnreachable
  | UnexpectedResponse;
