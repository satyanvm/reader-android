import { Auth } from './auth.js';
import { PLANS, billingStatus, checkoutUrl, subscriptionFromEvent, validSignature } from './billing.js';
import { DigestMismatch, dbBlobs, r2Blobs } from './blobs.js';
import { d1 } from './db.js';
import { HttpError } from './errors.js';
import { KeySet } from './jwt.js';
import { createMailer } from './mailer.js';
import { Store } from './store.js';

/** Largest request body accepted; a position is ~300 bytes. */
const MAX_BODY = 8 * 1024;
/** Largest billing webhook accepted; a subscription event is a few kilobytes. */
const MAX_WEBHOOK_BODY = 256 * 1024;
/** A stamp this far beyond the server's clock is a broken device clock, not reading. */
const MAX_CLOCK_LEAD_MS = 24 * 60 * 60 * 1000;
const DOCUMENT_ID = /^[A-Za-z0-9._-]{1,128}$/;
const POSITION_ROUTE = /^\/v1\/reading-positions\/([^/]+)$/;
const LIBRARY_ROUTE = /^\/v1\/library(?:\/([^/]+)(\/file)?)?$/;
/** Book ids are the SHA-256 of the file. */
const BOOK_ID = /^[0-9a-f]{64}$/;
/** Largest book file accepted (Cloudflare caps request bodies at 100 MB). */
const MAX_BOOK_BYTES = 100 * 1024 * 1024;
const BOOK_FORMATS = new Set(['PDF', 'EPUB']);

/**
 * The Reader sync API, as a fetch handler. Deployed as a Cloudflare Worker (D1 database); the
 * same handler also runs under Node (bin/server.js) with node:sqlite.
 *
 * Reading positions (docs/position-sync.md), with `Authorization: Bearer <session token>`:
 *
 *   GET /v1/reading-positions/{documentId}  -> 200 position | 404
 *   PUT /v1/reading-positions/{documentId}  body: position -> 200 the stored winner
 *
 * Accounts (see auth.js), JSON bodies, each success returning `{ token, account }`:
 *
 *   POST /v1/auth/signup           { email, password }          -> 202, a code is emailed
 *                                                                  (EMAIL_CODES off: session)
 *   POST /v1/auth/verify           { email, code }              -> session
 *   POST /v1/auth/resend           { email }
 *   POST /v1/auth/signin           { email, password }          -> session | 403 verification_required
 *   POST /v1/auth/password/forgot  { email }                    -> a code is emailed
 *   POST /v1/auth/password/reset   { email, code, password }    -> session
 *   POST /v1/auth/apple | /google  { idToken }                  -> session
 *   GET  /v1/auth/providers        -> { apple, google, emailCodes }  (which sign-in options to show)
 *   POST /v1/auth/app/code         { challenge }  (signed in)   -> { code }  (a website sign-in,
 *                                                                  handed to an app; see auth.js)
 *   POST /v1/auth/app/token        { code, verifier }           -> session
 *   GET  /v1/me                    -> { email }
 *   POST /v1/auth/signout          -> ends this session
 *   GET  /healthz
 *
 * The library (which books the account has; a book added on one device appears on the others):
 *
 *   GET    /v1/library                 -> { books: [book] }, removed books included (removedAt set)
 *   PUT    /v1/library/{id}            { title, format, sizeBytes, pageCount } -> 200 book (adds, or brings back)
 *   DELETE /v1/library/{id}            -> 200 { removed }  (the file goes too)
 *   PUT    /v1/library/{id}/file       the book's bytes (SHA-256 = {id}, sizeBytes long) -> 200 book
 *   GET    /v1/library/{id}/file       -> the bytes | 404
 *
 * Paid plans (see billing.js):
 *
 *   GET  /v1/billing                -> { pro, plan, status, renewsAt, endsAt, manageUrl, plans }
 *   POST /v1/billing/checkout       { plan: "monthly" | "yearly" } -> { url }  (open in a browser)
 *   POST /v1/billing/lemonsqueezy   Lemon Squeezy's webhook, signed with X-Signature
 */
export function createHandler({ store, blobs, env = {}, keys = new KeySet(), log = console.log, devSink }) {
  const list = (v) => String(v ?? '').split(',').map((s) => s.trim()).filter(Boolean);
  const testMode = env.TEST_MODE === '1';

  return async function handle(request) {
    // Tests can set the clock per request; never in production (TEST_MODE is only set locally).
    const testNow = testMode ? Number(request.headers.get('x-test-now')) : NaN;
    const now = () => (Number.isFinite(testNow) && testNow > 0 ? testNow : Date.now());
    const mailer = createMailer({ env, store, log, devSink, now });
    const auth = new Auth(store, mailer, {
      googleAudiences: list(env.GOOGLE_CLIENT_IDS),
      appleAudiences: list(env.APPLE_AUDIENCES),
      keys,
      pepper: env.PASSWORD_PEPPER ?? '',
      emailCodes: env.EMAIL_CODES === '1',
      now,
    });
    const url = new URL(request.url);
    const { pathname } = url;
    const method = request.method;
    try {
      if (pathname === '/healthz' && method === 'GET') return json(200, { ok: true });
      if (pathname === '/v1/auth/providers' && method === 'GET') return json(200, auth.providers);

      const authRoutes = {
        'POST /v1/auth/signup': async (b) => {
          const result = await auth.signUp(b.email, b.password);
          return [result.token ? 200 : 202, result];
        },
        'POST /v1/auth/verify': async (b) => [200, await auth.verifyEmail(b.email, b.code)],
        'POST /v1/auth/resend': async (b) => [200, await auth.resendCode(b.email)],
        'POST /v1/auth/signin': async (b) => [200, await auth.signIn(b.email, b.password)],
        'POST /v1/auth/password/forgot': async (b) => [200, await auth.requestPasswordReset(b.email)],
        'POST /v1/auth/password/reset': async (b) => [200, await auth.resetPassword(b.email, b.code, b.password)],
        'POST /v1/auth/apple': async (b) => [200, await auth.signInWithProvider('apple', b.idToken)],
        'POST /v1/auth/google': async (b) => [200, await auth.signInWithProvider('google', b.idToken)],
        'POST /v1/auth/app/token': async (b) => [200, await auth.exchangeAppCode(b.code, b.verifier)],
      };
      const authRoute = authRoutes[`${method} ${pathname}`];
      if (authRoute) {
        const body = await readJson(request);
        if (body === null || typeof body !== 'object' || Array.isArray(body)) throw new HttpError(400, 'body must be a JSON object');
        const [status, result] = await authRoute(body);
        return json(status, result);
      }

      if (pathname === '/v1/billing/lemonsqueezy') {
        if (method !== 'POST') throw new HttpError(405, 'method not allowed');
        return await lemonSqueezyWebhook({ store, env, request, now: now(), log });
      }

      if (testMode && pathname.startsWith('/__test/')) return testRoute(store, pathname, method, request, url);

      const token = bearer(request);
      if (LIBRARY_ROUTE.test(pathname)) {
        const accountId = await store.accountForToken(token);
        if (accountId === null) throw new HttpError(401, 'missing or invalid token', 'unauthorized');
        return await libraryRoute({ store, blobs, accountId, request, now: now() });
      }

      if (pathname === '/v1/billing' || pathname === '/v1/billing/checkout') {
        const accountId = await store.accountForToken(token);
        if (accountId === null) throw new HttpError(401, 'missing or invalid token', 'unauthorized');
        if (pathname === '/v1/billing') {
          if (method !== 'GET') throw new HttpError(405, 'method not allowed');
          return json(200, billingStatus(await store.subscriptions(accountId), now(), env));
        }
        if (method !== 'POST') throw new HttpError(405, 'method not allowed');
        const plan = (await readJson(request))?.plan;
        if (!PLANS.includes(plan)) throw new HttpError(422, `plan must be one of ${PLANS.join(', ')}`);
        const url = checkoutUrl(env, plan, await store.accountById(accountId));
        if (!url) throw new HttpError(503, 'this plan cannot be bought yet', 'billing_unavailable');
        return json(200, { url });
      }

      if (pathname === '/v1/auth/app/code') {
        const accountId = await store.accountForToken(token);
        if (accountId === null) throw new HttpError(401, 'missing or invalid token', 'unauthorized');
        if (method !== 'POST') throw new HttpError(405, 'method not allowed');
        const body = await readJson(request);
        return json(200, await auth.createAppCode(accountId, body?.challenge));
      }

      if (pathname === '/v1/me' || pathname === '/v1/auth/signout' || POSITION_ROUTE.test(pathname)) {
        const accountId = await store.accountForToken(token);
        if (accountId === null) throw new HttpError(401, 'missing or invalid token', 'unauthorized');
        if (pathname === '/v1/me') {
          if (method !== 'GET') throw new HttpError(405, 'method not allowed');
          return json(200, { email: (await store.accountById(accountId))?.email ?? null });
        }
        if (pathname === '/v1/auth/signout') {
          if (method !== 'POST') throw new HttpError(405, 'method not allowed');
          await store.revokeToken(token);
          return json(200, { status: 'signed_out' });
        }
        let documentId;
        try {
          documentId = decodeURIComponent(POSITION_ROUTE.exec(pathname)[1]);
        } catch {
          throw new HttpError(400, 'bad document id');
        }
        if (!DOCUMENT_ID.test(documentId)) throw new HttpError(400, 'bad document id');
        if (method === 'GET') {
          const position = await store.get(accountId, documentId);
          if (!position) throw new HttpError(404, 'no position for this document');
          return json(200, position);
        }
        if (method === 'PUT') {
          const incoming = validate(await readJson(request), documentId, now());
          return json(200, await store.compareAndStore(accountId, incoming));
        }
        return json(405, { error: 'method not allowed' }, { Allow: 'GET, PUT' });
      }
      throw new HttpError(404, 'not found');
    } catch (e) {
      if (e instanceof HttpError) return json(e.status, { error: e.message, ...(e.code ? { code: e.code } : {}) });
      log(`error: ${e?.stack ?? e}`);
      return json(500, { error: 'internal error' });
    }
  };
}

async function lemonSqueezyWebhook({ store, env, request, now, log }) {
  const secret = env.LEMONSQUEEZY_WEBHOOK_SECRET;
  if (!secret) throw new HttpError(503, 'billing is not set up on this server', 'billing_unavailable');
  if (Number(request.headers.get('content-length') ?? 0) > MAX_WEBHOOK_BODY) throw new HttpError(413, 'body too large');
  const raw = await request.text();
  if (raw.length > MAX_WEBHOOK_BODY) throw new HttpError(413, 'body too large');
  if (!(await validSignature(secret, raw, request.headers.get('x-signature')))) throw new HttpError(401, 'bad signature');
  let payload;
  try {
    payload = JSON.parse(raw);
  } catch {
    throw new HttpError(400, 'body is not JSON');
  }
  const sub = subscriptionFromEvent(payload);
  const event = String(payload?.meta?.event_name ?? '');
  // Anything else (orders, refunds, license keys) needs nothing here; say so, so it isn't retried.
  if (!sub) return json(200, { ok: true, ignored: event });
  const stored = await store.putSubscription('lemonsqueezy', sub, now);
  // Acknowledged either way: retrying wouldn't tie it to an account.
  if (!stored) log(`billing: ${event} for subscription ${sub.subscriptionId} has no known account`);
  return json(200, { ok: true, stored });
}

async function libraryRoute({ store, blobs, accountId, request, now }) {
  const [, rawId, file] = LIBRARY_ROUTE.exec(new URL(request.url).pathname);
  const method = request.method;
  if (rawId === undefined) {
    if (method !== 'GET') return json(405, { error: 'method not allowed' }, { Allow: 'GET' });
    return json(200, { books: await store.library(accountId) });
  }
  const documentId = rawId.toLowerCase();
  if (!BOOK_ID.test(documentId)) throw new HttpError(400, 'bad document id');
  const key = `${accountId}/${documentId}`;
  if (!file) {
    if (method === 'PUT') return json(200, await store.putBook(accountId, validateBook(await readJson(request), documentId), now));
    if (method === 'DELETE') {
      const removed = await store.removeBook(accountId, documentId, now);
      if (blobs) await blobs.delete(key);
      return json(200, { removed });
    }
    return json(405, { error: 'method not allowed' }, { Allow: 'PUT, DELETE' });
  }
  if (!blobs) throw new HttpError(503, 'book files are not stored on this server', 'no_storage');
  const book = await store.book(accountId, documentId);
  if (method === 'GET') {
    const blob = book && book.removedAt === null && book.hasFile ? await blobs.get(key) : null;
    if (!blob) throw new HttpError(404, 'no file for this book');
    return new Response(blob.body, {
      status: 200,
      headers: { 'Content-Type': 'application/octet-stream', 'Content-Length': String(blob.size), 'Cache-Control': 'no-store' },
    });
  }
  if (method === 'PUT') {
    if (!book || book.removedAt !== null) throw new HttpError(409, 'add the book to the library first', 'not_in_library');
    const length = Number(request.headers.get('content-length') ?? NaN);
    if (!Number.isSafeInteger(length) || length <= 0) throw new HttpError(411, 'Content-Length is required');
    if (length > MAX_BOOK_BYTES) throw new HttpError(413, 'book is too large to sync', 'too_large');
    if (length !== book.sizeBytes) throw new HttpError(422, 'the file is not the size the library says', 'size_mismatch');
    try {
      await blobs.put(key, request.body, { sha256: documentId });
    } catch (e) {
      if (e instanceof DigestMismatch) throw new HttpError(422, 'the file does not match its id', 'digest_mismatch');
      throw e;
    }
    await store.setHasFile(accountId, documentId, true);
    return json(200, await store.book(accountId, documentId));
  }
  return json(405, { error: 'method not allowed' }, { Allow: 'GET, PUT' });
}

function validateBook(b, documentId) {
  const bad = (msg) => new HttpError(422, msg);
  if (b === null || typeof b !== 'object' || Array.isArray(b)) throw bad('book must be an object');
  const title = typeof b.title === 'string' ? b.title.trim().slice(0, 500) : '';
  if (!title) throw bad('title must be a non-empty string');
  if (!BOOK_FORMATS.has(b.format)) throw bad('format must be PDF or EPUB');
  if (!Number.isSafeInteger(b.sizeBytes) || b.sizeBytes < 1) throw bad('sizeBytes must be a positive integer');
  if (!Number.isSafeInteger(b.pageCount) || b.pageCount < 1 || b.pageCount > 1_000_000) throw bad('pageCount must be an integer in [1, 1000000]');
  return { documentId, title, format: b.format, sizeBytes: b.sizeBytes, pageCount: b.pageCount };
}

/** Test-only helpers (TEST_MODE=1): reset the database, make a token, read dev mail. */
async function testRoute(store, pathname, method, request, url) {
  if (pathname === '/__test/reset' && method === 'POST') {
    await store.db.batch(['subscriptions', 'reading_positions', 'library_books', 'book_files', 'book_chunks', 'tokens', 'identities', 'verification_codes', 'app_codes', 'rate_events', 'dev_mail', 'accounts']
      .map((t) => [`DELETE FROM ${t}`]));
    return json(200, { ok: true });
  }
  if (pathname === '/__test/token' && method === 'POST') {
    const { name } = await readJson(request);
    const id = (await store.accountByName(name))?.id ?? (await store.createAccount(name));
    return json(200, { token: await store.issueToken(id), accountId: id });
  }
  if (pathname === '/__test/mail' && method === 'GET') {
    return json(200, await store.devMail(String(url.searchParams.get('to') ?? '').toLowerCase()));
  }
  if (pathname === '/__test/revoke' && method === 'POST') {
    const { name } = await readJson(request);
    const account = await store.accountByName(name);
    return json(200, { revoked: account ? await store.revokeTokens(account.id) : 0 });
  }
  if (pathname === '/__test/token-hashes' && method === 'GET') {
    return json(200, (await store.db.all('SELECT token_hash FROM tokens')).map((r) => r.token_hash));
  }
  throw new HttpError(404, 'not found');
}

function json(status, body, headers = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...headers },
  });
}

function bearer(request) {
  const m = /^Bearer\s+(\S+)$/i.exec(request.headers.get('authorization') ?? '');
  return m ? m[1] : null;
}

async function readJson(request) {
  const declared = Number(request.headers.get('content-length') ?? 0);
  if (declared > MAX_BODY) throw new HttpError(413, 'body too large');
  const text = await request.text();
  if (text.length > MAX_BODY) throw new HttpError(413, 'body too large');
  try {
    return JSON.parse(text);
  } catch {
    throw new HttpError(400, 'body is not JSON');
  }
}

/**
 * Checks a position against the contract. Field names and meanings are those in the doc's
 * table. Fractions are kept as sent, so a client gets back bit-identical values.
 */
export function validate(p, documentId, nowMs) {
  const bad = (msg) => new HttpError(422, msg);
  if (p === null || typeof p !== 'object' || Array.isArray(p)) throw bad('position must be an object');
  if (p.documentId !== documentId) throw bad('documentId must match the URL');
  const int = (k, min, max) => {
    if (!Number.isSafeInteger(p[k]) || p[k] < min || p[k] > max) throw bad(`${k} must be an integer in [${min}, ${max}]`);
    return p[k];
  };
  const num = (k, min, max, fallback) => {
    const v = p[k] ?? fallback;
    if (typeof v !== 'number' || !Number.isFinite(v) || v < min || v > max) throw bad(`${k} must be a number in [${min}, ${max}]`);
    return v;
  };
  const pageCount = int('pageCount', 1, 1_000_000);
  const position = {
    documentId,
    pageIndex: int('pageIndex', 0, pageCount - 1),
    pageOffset: num('pageOffset', 0, 1),
    horizontalOffset: num('horizontalOffset', 0, 1, 0),
    zoom: num('zoom', 1, 4, 1),
    pageCount,
    hlc: int('hlc', 0, Number.MAX_SAFE_INTEGER),
    deviceId: p.deviceId,
    schemaVersion: p.schemaVersion ?? 1,
  };
  if (typeof position.deviceId !== 'string' || position.deviceId.length < 1 || position.deviceId.length > 128) {
    throw bad('deviceId must be a non-empty string');
  }
  if (position.schemaVersion !== 1) throw bad('unsupported schemaVersion');
  if (position.hlc > nowMs + MAX_CLOCK_LEAD_MS) {
    throw bad('hlc is more than a day ahead of the server clock; check the device clock');
  }
  return position;
}

// The Cloudflare Worker entry point.
let ready; // one migration per isolate
let keys;
export default {
  async fetch(request, env) {
    const db = d1(env.DB);
    const store = new Store(db);
    ready ??= store.migrate().catch((e) => {
      ready = undefined;
      throw e;
    });
    await ready;
    keys ??= env.TEST_MODE === '1' && env.TEST_JWKS ? new KeySet(async () => JSON.parse(env.TEST_JWKS)) : new KeySet();
    return createHandler({ store, blobs: env.BOOKS ? r2Blobs(env.BOOKS) : dbBlobs(db), env, keys })(request);
  },
};
