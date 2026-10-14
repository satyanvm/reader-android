export class HttpError extends Error {
  /** `code` is a stable machine-readable reason the apps can switch on. */
  constructor(status, message, code = undefined) {
    super(message);
    this.status = status;
    this.code = code;
  }
}
