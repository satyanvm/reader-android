# Reader sync server

Stores each account's library (the books, with their files) and reading position per book, so the Android and Mac apps open a book where you left off on any device. It implements the contract in [../docs/position-sync.md](../docs/position-sync.md).

It runs on **Cloudflare Workers with a D1 database** (book files are kept in it too, in 1 MB chunks) (deployed at https://reader-sync.satyanarayanv2050.workers.dev), and the same code runs locally on Node 22.13+ with SQLite for development. No runtime dependencies; `wrangler` is a dev dependency for deploying.

## Deploy (Cloudflare)

```bash
npx wrangler login
```

```bash
npm run deploy
```

`wrangler.toml` has the D1 binding (database `reader`) and the non-secret settings. Book files live in the same D1 database, so nothing else needs setting up. D1's free plan allows 500 MB per database, shared with everything else in it. To keep files in R2 instead (R2 must be enabled on the account, which asks for a payment method), create a bucket with `npx wrangler r2 bucket create reader-books` and uncomment the `[[r2_buckets]]` block in `wrangler.toml`; the Worker uses R2 whenever a `BOOKS` bucket is bound.

Secrets are set once:

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

## Payments (Lemon Squeezy)

Pro is sold through [Lemon Squeezy](https://www.lemonsqueezy.com), the merchant of record: it takes the card, PayPal or wallet payment in the buyer's currency, collects and pays sales tax and VAT worldwide, and pays you out. No monthly fee; it takes a cut of each sale. The server never sees payment details. It only learns from signed webhooks which accounts have Pro, so the Android and Mac apps share one purchase.

Setup (test mode first, then the same steps in live mode):

1. Create a store at [app.lemonsqueezy.com](https://app.lemonsqueezy.com) and add a **subscription** product, "Reader Pro", with two variants: Monthly ($2.99/month) and Yearly ($24.99/year). Keep the prices equal to the ones in the app's `PricingScreen.kt`.
2. For each variant, copy its **share link** (`https://<store>.lemonsqueezy.com/buy/<uuid>`) and put it in `wrangler.toml` under `[vars]`:
   ```toml
   LEMONSQUEEZY_CHECKOUT_MONTHLY = "https://<store>.lemonsqueezy.com/buy/<uuid>"
   LEMONSQUEEZY_CHECKOUT_YEARLY = "https://<store>.lemonsqueezy.com/buy/<uuid>"
   ```
   A plan without a link shows "Coming soon" in the app.
3. Settings → Webhooks → add `https://<your worker>/v1/billing/lemonsqueezy`, choose a signing secret, and tick the `subscription_*` events (created, updated, cancelled, resumed, expired, paused, unpaused, payment_success, payment_failed).
4. Store the signing secret: `npx wrangler secret put LEMONSQUEEZY_WEBHOOK_SECRET`, then `npm run deploy`.
5. Buy Pro in the app with a [test card](https://docs.lemonsqueezy.com/help/getting-started/test-mode) (`4242 4242 4242 4242`); the Pricing screen switches to "You have Pro" within a few seconds.
6. To go live: activate the store (Lemon Squeezy reviews it), then repeat steps 2–4 with the live-mode share links and webhook. Test-mode and live-mode links and secrets differ.

What counts as Pro: `active`, `on_trial` and `past_due` (while the card is retried) subscriptions, and `cancelled` ones until the end of the period paid for. Webhooks that arrive out of order are applied by Lemon Squeezy's `updated_at`, so an old event never overwrites a newer one.

## API

```
GET /v1/reading-positions/{documentId}   200 position | 404
PUT /v1/reading-positions/{documentId}   body: position → 200 the stored winner
GET /healthz                             200
```

```
GET    /v1/library             { books: [...] }, removed books included (removedAt set)
PUT    /v1/library/{id}        { title, format, sizeBytes, pageCount } → the book (adds, or brings back)
DELETE /v1/library/{id}        removes it (and its file) on every device
PUT    /v1/library/{id}/file   the bytes (sizeBytes long, SHA-256 = {id}); up to 100 MB
GET    /v1/library/{id}/file   the bytes | 404
```

The server checks an upload's size; the apps check each downloaded book's SHA-256 before importing it (hashing a large book doesn't fit the free plan's CPU budget).

Every `/v1/reading-positions` and `/v1/library` request needs `Authorization: Bearer <token>` (otherwise 401). Positions are per account.

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

```
GET  /v1/billing               { pro, plan, status, renewsAt, endsAt, manageUrl, plans }
POST /v1/billing/checkout      { plan: "monthly" | "yearly" } → { url }   503 billing_unavailable if not set up
POST /v1/billing/lemonsqueezy  Lemon Squeezy's webhook; X-Signature = HMAC-SHA256(secret, body), hex
```

The first two need a session; `manageUrl` is Lemon Squeezy's page to change or cancel the subscription.

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
