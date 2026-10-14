import assert from 'node:assert/strict';
import { sign } from 'node:crypto';
import { after, before, beforeEach, describe, test } from 'node:test';
import { APPLE_BUNDLE, client, GOOGLE_CLIENT, otherKey, signingKey, startServer } from './support.js';

let server, call;
let clock = 1_760_000_000_000;

function idToken(claims, { key = signingKey.privateKey, kid = 'test-key' } = {}) {
  const enc = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
  const head = enc({ alg: 'RS256', kid, typ: 'JWT' });
  const body = enc({ iat: clock / 1000, exp: clock / 1000 + 3600, ...claims });
  return `${head}.${body}.${sign('RSA-SHA256', Buffer.from(`${head}.${body}`), key).toString('base64url')}`;
}
const google = (sub, email, extra = {}) => idToken({ iss: 'https://accounts.google.com', aud: GOOGLE_CLIENT, sub, email, email_verified: true, ...extra });
const apple = (sub, email, extra = {}) => idToken({ iss: 'https://appleid.apple.com', aud: APPLE_BUNDLE, sub, email, email_verified: 'true', ...extra });

const post = (path, body, token) => call('POST', path, { body, token, now: clock });
const get = (path, token) => call('GET', path, { token, now: clock });

async function mail(to) {
  return (await call('GET', `/__test/mail?to=${encodeURIComponent(to)}`)).body;
}

async function lastCode(to) {
  const all = await mail(to);
  return all.at(-1)?.text.match(/\b(\d{6})\b/)?.[1] ?? null;
}

const position = (page, hlc, device) => ({
  documentId: 'book', pageIndex: page, pageOffset: 0.5, horizontalOffset: 0, zoom: 1,
  pageCount: 500, hlc, deviceId: device, schemaVersion: 1,
});
const putPosition = async (token, p) => (await call('PUT', '/v1/reading-positions/book', { token, body: p, now: clock })).body;

async function signUpAndVerify(email, password = 'correct horse') {
  assert.equal((await post('/v1/auth/signup', { email, password })).status, 202);
  const verified = await post('/v1/auth/verify', { email, code: await lastCode(email.toLowerCase()) });
  assert.equal(verified.status, 200);
  return verified.body.token;
}

before(async () => {
  server = await startServer();
  call = client(server.base);
});

after(() => server.stop());

beforeEach(async () => {
  await call('POST', '/__test/reset', { body: {} });
  clock += 24 * 3600 * 1000;
});

describe('email and password', () => {
  test('sign up, confirm the emailed code, and the same account syncs on a second device', async () => {
    const res = await post('/v1/auth/signup', { email: ' Reader@Example.com ', password: 'correct horse' });
    assert.equal(res.status, 202);
    assert.deepEqual(res.body, { status: 'verification_required', email: 'reader@example.com' });
    const sent = await mail('reader@example.com');
    assert.equal(sent.length, 1);
    assert.match(sent[0].subject, /^\d{6} is your Reader code$/);

    const mac = (await post('/v1/auth/verify', { email: 'reader@example.com', code: await lastCode('reader@example.com') })).body;
    assert.equal(mac.account.email, 'reader@example.com');
    await putPosition(mac.token, position(186, clock, 'mac'));

    // The phone signs in with the same email and password and sees the Mac's place.
    const phone = await post('/v1/auth/signin', { email: 'READER@example.com', password: 'correct horse' });
    assert.equal(phone.status, 200);
    assert.notEqual(phone.body.token, mac.token, 'each device gets its own session');
    assert.equal((await get('/v1/reading-positions/book', phone.body.token)).body.pageIndex, 186);
    assert.deepEqual((await get('/v1/me', phone.body.token)).body, { email: 'reader@example.com' });
  });

  test('no session until the email is confirmed', async () => {
    await post('/v1/auth/signup', { email: 'a@example.com', password: 'correct horse' });
    clock += 60_000; // past the resend interval
    const res = await post('/v1/auth/signin', { email: 'a@example.com', password: 'correct horse' });
    assert.equal(res.status, 403);
    assert.equal(res.body.code, 'verification_required');
    assert.equal((await mail('a@example.com')).length, 2, 'signing in to an unconfirmed account sends a fresh code');
  });

  test('wrong, reused, expired and brute-forced codes are refused', async () => {
    await post('/v1/auth/signup', { email: 'b@example.com', password: 'correct horse' });
    const code = await lastCode('b@example.com');
    const wrong = code === '000000' ? '000001' : '000000';
    assert.equal((await post('/v1/auth/verify', { email: 'b@example.com', code: wrong })).body.code, 'invalid_code');
    assert.equal((await post('/v1/auth/verify', { email: 'b@example.com', code })).status, 200);
    assert.equal((await post('/v1/auth/verify', { email: 'b@example.com', code })).status, 400, 'single use');

    await post('/v1/auth/signup', { email: 'c@example.com', password: 'correct horse' });
    const cCode = await lastCode('c@example.com');
    clock += 11 * 60 * 1000;
    assert.equal((await post('/v1/auth/verify', { email: 'c@example.com', code: cCode })).status, 400, 'expired');

    await post('/v1/auth/signup', { email: 'd@example.com', password: 'correct horse' });
    const real = await lastCode('d@example.com');
    for (let i = 0; i < 5; i++) await post('/v1/auth/verify', { email: 'd@example.com', code: real === '111111' ? '222222' : '111111' });
    assert.equal((await post('/v1/auth/verify', { email: 'd@example.com', code: real })).status, 400, 'locked after 5 wrong tries');
  });

  test('a confirmed email cannot be signed up again; an unconfirmed one can (latest wins)', async () => {
    await signUpAndVerify('e@example.com');
    assert.equal((await post('/v1/auth/signup', { email: 'e@example.com', password: 'different pw' })).status, 409);

    await post('/v1/auth/signup', { email: 'f@example.com', password: 'squatter pw' });
    clock += 60_000;
    await post('/v1/auth/signup', { email: 'f@example.com', password: 'owner pass' });
    await post('/v1/auth/verify', { email: 'f@example.com', code: await lastCode('f@example.com') });
    assert.equal((await post('/v1/auth/signin', { email: 'f@example.com', password: 'squatter pw' })).status, 401);
    assert.equal((await post('/v1/auth/signin', { email: 'f@example.com', password: 'owner pass' })).status, 200);
  });

  test('wrong passwords, short passwords and bad emails', async () => {
    await signUpAndVerify('g@example.com');
    const bad = await post('/v1/auth/signin', { email: 'g@example.com', password: 'nope nope' });
    assert.equal(bad.status, 401);
    assert.equal(bad.body.code, 'invalid_credentials');
    assert.equal((await post('/v1/auth/signin', { email: 'nobody@example.com', password: 'whatever1' })).body.code, 'invalid_credentials',
      'same answer for unknown emails');
    assert.equal((await post('/v1/auth/signup', { email: 'h@example.com', password: 'short' })).body.code, 'weak_password');
    assert.equal((await post('/v1/auth/signup', { email: 'not-an-email', password: 'long enough' })).body.code, 'invalid_email');
  });

  test('repeated wrong passwords are rate limited', async () => {
    await signUpAndVerify('i@example.com');
    for (let i = 0; i < 10; i++) await post('/v1/auth/signin', { email: 'i@example.com', password: 'wrong pass' });
    assert.equal((await post('/v1/auth/signin', { email: 'i@example.com', password: 'correct horse' })).status, 429);
    clock += 16 * 60 * 1000;
    assert.equal((await post('/v1/auth/signin', { email: 'i@example.com', password: 'correct horse' })).status, 200, 'the window passes');
  });

  test('codes cannot be requested in a burst', async () => {
    await post('/v1/auth/signup', { email: 'j@example.com', password: 'correct horse' });
    assert.equal((await post('/v1/auth/resend', { email: 'j@example.com' })).status, 429);
    clock += 31_000;
    assert.equal((await post('/v1/auth/resend', { email: 'j@example.com' })).status, 200);
    assert.equal((await mail('j@example.com')).length, 2);
  });

  test('forgot password: the emailed code sets a new one and signs out other devices', async () => {
    const old = await signUpAndVerify('k@example.com');
    assert.equal((await post('/v1/auth/password/forgot', { email: 'k@example.com' })).status, 200);
    assert.equal((await post('/v1/auth/password/forgot', { email: 'ghost@example.com' })).status, 200, 'no hint whether the account exists');
    const reset = await post('/v1/auth/password/reset', { email: 'k@example.com', code: await lastCode('k@example.com'), password: 'brand new pw' });
    assert.equal(reset.status, 200);
    assert.equal((await get('/v1/me', old)).status, 401, 'old sessions ended');
    assert.equal((await post('/v1/auth/signin', { email: 'k@example.com', password: 'brand new pw' })).status, 200);
  });

  test("signing out ends only that device's session", async () => {
    const mac = await signUpAndVerify('l@example.com');
    const phone = (await post('/v1/auth/signin', { email: 'l@example.com', password: 'correct horse' })).body.token;
    assert.equal((await post('/v1/auth/signout', {}, mac)).status, 200);
    assert.equal((await get('/v1/me', mac)).status, 401);
    assert.equal((await get('/v1/me', phone)).status, 200);
  });
});

describe('Sign in with Apple and Google', () => {
  test('the providers the app should offer', async () => {
    assert.deepEqual((await get('/v1/auth/providers')).body, { apple: true, google: true });
  });

  test('Google creates a verified account; signing in again returns the same account', async () => {
    const first = await post('/v1/auth/google', { idToken: google('g-123', 'Pat@Gmail.com') });
    assert.equal(first.status, 200);
    assert.equal(first.body.account.email, 'pat@gmail.com');
    await putPosition(first.body.token, position(42, clock, 'phone'));
    const again = await post('/v1/auth/google', { idToken: google('g-123', 'pat@gmail.com') });
    assert.equal((await get('/v1/reading-positions/book', again.body.token)).body.pageIndex, 42);
  });

  test('one person, three ways in, one library', async () => {
    const mac = await signUpAndVerify('sam@example.com');
    await putPosition(mac, position(100, clock, 'mac'));
    const viaGoogle = await post('/v1/auth/google', { idToken: google('g-sam', 'sam@example.com') });
    const viaApple = await post('/v1/auth/apple', { idToken: apple('a-sam', 'sam@example.com') });
    for (const r of [viaGoogle, viaApple]) {
      assert.equal(r.status, 200);
      assert.equal((await get('/v1/reading-positions/book', r.body.token)).body.pageIndex, 100);
    }
  });

  test('an unconfirmed password account cannot capture a Google sign-in for that email', async () => {
    await post('/v1/auth/signup', { email: 'victim@example.com', password: 'attacker pw' });
    const owner = await post('/v1/auth/google', { idToken: google('g-victim', 'victim@example.com') });
    assert.equal(owner.status, 200);
    assert.equal((await post('/v1/auth/signin', { email: 'victim@example.com', password: 'attacker pw' })).status, 401,
      'the unproven password was discarded');
  });

  test('Apple private-relay emails and later sign-ins without an email claim', async () => {
    const first = await post('/v1/auth/apple', { idToken: apple('a-1', 'x7f@privaterelay.appleid.com') });
    assert.equal(first.body.account.email, 'x7f@privaterelay.appleid.com');
    const later = await post('/v1/auth/apple', { idToken: apple('a-1', undefined) });
    assert.equal(later.status, 200, 'the identity is already linked');
    assert.equal(later.body.account.email, 'x7f@privaterelay.appleid.com');
  });

  test('forged, foreign, expired and unverified tokens are refused', async () => {
    const cases = {
      'signed by someone else': idToken({ iss: 'https://accounts.google.com', aud: GOOGLE_CLIENT, sub: 'x', email: 'x@x.com', email_verified: true }, { key: otherKey.privateKey }),
      'issued for another app': google('x', 'x@x.com', { aud: 'someone-elses.apps.googleusercontent.com' }),
      expired: google('x', 'x@x.com', { exp: clock / 1000 - 3600 }),
      'wrong issuer': google('x', 'x@x.com', { iss: 'https://evil.example' }),
      'unknown key': idToken({ iss: 'https://accounts.google.com', aud: GOOGLE_CLIENT, sub: 'x' }, { kid: 'nope' }),
      garbage: 'not.a.token',
    };
    for (const [name, token] of Object.entries(cases)) {
      const res = await post('/v1/auth/google', { idToken: token });
      assert.equal(res.status, 401, name);
      assert.equal(res.body.code, 'invalid_identity', name);
    }
    const unverified = await post('/v1/auth/google', { idToken: google('y', 'y@y.com', { email_verified: false }) });
    assert.equal(unverified.body.code, 'email_required');
  });
});
