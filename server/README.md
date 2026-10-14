# Reader sync server

Stores each account's reading position per book, so the Android and Mac apps open a book where you left off on any device. It implements the contract in [../docs/position-sync.md](../docs/position-sync.md).

It runs on **Cloudflare Workers with a D1 database** (deployed at https://reader-sync.satyanarayanv2050.workers.dev), and the same code runs locally on Node 22.13+ with SQLite for development. No runtime dependencies; `wrangler` is a dev dependency for deploying.

## Deploy (Cloudflare)

```bash
npx wrangler login
```

```bash
npm run deploy
```

`wrangler.toml` has the D1 binding (database `reader`) and the non-secret settings. Secrets are set once:

```bash
npx wrangler secret put PASSWORD_PEPPER
```

```bash
npx wrangler secret put RESEND_API_KEY
```

`PASSWORD_PEPPER` is mixed into every password hash and must never change; a backup is in `~/.config/reader-sync/password-pepper` on the Mac that set it up. Until `RESEND_API_KEY` is set, code emails aren't sent: they appear in the live log (`npx wrangler tail reader-sync`). The schema is created and upgraded by the Worker itself on first use.

Inspect the database with `npx wrangler d1 execute reader --remote --command "SELECT email, email_verified_at FROM accounts"`.

Passwords are PBKDF2-SHA256 (40,000 iterations) over an HMAC with the pepper: the free plan allows about 10 ms of CPU per request, which rules out today's recommended 600,000 iterations. A copy of the database can't be cracked offline without the pepper, and the iteration count is stored in each hash, so it can be raised (on the paid plan) and old hashes upgrade at the next sign-in.

## Run locally

```bash
npm start
```

| Variable | Default | |
|---|---|---|
| `READER_DB` | `data/reader-sync.sqlite` | Database file |
| `PORT` | `8787` | |
| `HOST` | `127.0.0.1` | `0.0.0.0` to accept other machines |

It speaks plain HTTP. On anything but localhost, put it behind a TLS reverse proxy (Caddy, nginx, a platform load balancer): tokens are bearer tokens.

## Accounts

People create their own account in the app, and every device signed in to it syncs:

- **Email and password.** With `EMAIL_CODES` off (the default for now, until mail sending is set up), creating an account signs in at once with no code, an email that already has an account can't be signed up again, and "Forgot password?" is hidden in the apps. With `EMAIL_CODES = "1"`: creating an account emails a 6-digit code; the account works only once the code is entered (so the address is proven to be theirs). Signing in to an unconfirmed account sends a new code. "Forgot password?" emails a code that sets a new password and signs out other devices.
- **Sign in with Apple / Google.** The app sends the provider's ID token; the server checks its signature against Apple's or Google's published keys, the issuer, the audience (our app) and expiry. The provider's verified email joins the account with that email, so a person who signed up with a password and later uses Google lands in the same library. An unconfirmed password someone else set on that address is discarded.

Passwords are hashed (see above); codes expire after 10 minutes, are single-use, and lock after 5 wrong tries; code requests and failed sign-ins are rate limited. Each sign-in gets its own session token (only its SHA-256 is stored); signing out ends that device's session.

| Variable | |
|---|---|
| `EMAIL_CODES` | `1` makes new email + password accounts confirm their address with an emailed code and turns on password reset. Anything else: no codes. Turn it on once `RESEND_API_KEY` sends mail |
| `RESEND_API_KEY`, `MAIL_FROM` | Send the code emails with [Resend](https://resend.com). Without a key the server is in development mode: emails are only printed to its log |
| `READER_DEV_MAIL_DIR` | Development: also write each email to this folder as JSON |
| `GOOGLE_CLIENT_IDS` | Comma-separated Google OAuth client ids allowed to sign in (the Mac app's iOS/macOS client, Android's, ...). Empty turns Google off |
| `APPLE_AUDIENCES` | Comma-separated bundle ids / Services ids for Sign in with Apple (e.g. `io.github.satyanvm.reader`). Empty turns Apple off |

`bin/admin.js` still makes token-only accounts for scripts (`add-account`, `issue-token`, `revoke-tokens`, `list`).

## API

```
GET /v1/reading-positions/{documentId}   200 position | 404
PUT /v1/reading-positions/{documentId}   body: position → 200 the stored winner
GET /healthz                             200
```

Every `/v1/reading-positions` request needs `Authorization: Bearer <token>` (otherwise 401). Positions are per account.

```
POST /v1/auth/signup           { email, password }        202, code emailed (EMAIL_CODES off: session)
POST /v1/auth/verify           { email, code }            session
POST /v1/auth/resend           { email }
POST /v1/auth/signin           { email, password }        session | 403 verification_required
POST /v1/auth/password/forgot  { email }                  code emailed
POST /v1/auth/password/reset   { email, code, password }  session
POST /v1/auth/apple | google   { idToken }                session
GET  /v1/auth/providers        { apple, google, emailCodes }
GET  /v1/me                    { email }
POST /v1/auth/signout          ends this session
```

A session is `{ token, account: { email } }`. Errors are `{ error, code }`, where `code` is stable (`verification_required`, `invalid_code`, `invalid_credentials`, `account_exists`, `rate_limited`, ...).

`PUT` is one compare-and-store in a transaction: the incoming position is kept only if its `(hlc, deviceId)` is greater than the stored one, and the response is whatever is stored afterwards. Device ids compare by UTF-16 code units (JavaScript's `<`, Kotlin's `compareTo`, the Mac app's `Stamp`). That makes `PUT` idempotent and order-independent: retries, late responses and concurrent writes converge on the newest position.

Rejected with 422, nothing stored: a `documentId` that doesn't match the URL, values out of range (page beyond `pageCount`, fractions outside 0–1, zoom outside 1–4), an empty `deviceId`, an unknown `schemaVersion`, or an `hlc` more than a day ahead of the server's clock. That last one is a broken device clock, which would otherwise win every conflict for as long as it's ahead. Malformed JSON is 400, bodies over 8 KB are 413.

Fractions are stored as the doubles the clients send and returned unchanged, so a client's `Float` round-trips bit for bit.

## Test

```bash
npm test
```

```bash
npm run test:worker
```

The first runs the handler on Node; the second runs the same tests on Cloudflare's runtime (`wrangler dev`, local D1). The tests run the real server on a random port: the position contract, ordering and tie-breaks, out-of-order and concurrent writes, validation; sign-up and email codes (wrong, reused, expired, brute-forced), sign-in, password reset, rate limits, sessions; Apple and Google tokens (signed with a local test key standing in for theirs: valid, forged, foreign, expired) and account linking. `../../reader-mac/scripts/sync-e2e.sh <book>` (in the separate Mac project) runs this server with two Mac libraries as two devices: one creates an account with the emailed code, the other signs in to it, then resume, offline reading and reconnect.
