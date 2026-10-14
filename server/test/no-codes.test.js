import assert from 'node:assert/strict';
import { sign } from 'node:crypto';
import { after, before, beforeEach, describe, test } from 'node:test';
import { client, GOOGLE_CLIENT, signingKey, startServer } from './support.js';

// EMAIL_CODES off: email + password works with no emailed code, until mail sending is set up.
let server, call;
let clock = 1_760_000_000_000;

const post = (path, body, token) => call('POST', path, { body, token, now: clock });
const get = (path, token) => call('GET', path, { token, now: clock });

function google(sub, email) {
  const enc = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
  const head = enc({ alg: 'RS256', kid: 'test-key', typ: 'JWT' });
  const body = enc({ iss: 'https://accounts.google.com', aud: GOOGLE_CLIENT, sub, email, email_verified: true, iat: clock / 1000, exp: clock / 1000 + 3600 });
  return `${head}.${body}.${sign('RSA-SHA256', Buffer.from(`${head}.${body}`), signingKey.privateKey).toString('base64url')}`;
}

before(async () => {
  server = await startServer({ EMAIL_CODES: '' });
  call = client(server.base);
});

after(() => server.stop());

beforeEach(async () => {
  await call('POST', '/__test/reset', { body: {} });
  clock += 24 * 3600 * 1000;
});

describe('email and password without codes', () => {
  test('providers tell the apps codes are off', async () => {
    assert.equal((await get('/v1/auth/providers')).body.emailCodes, false);
  });

  test('sign up signs in at once, nothing is emailed, and a second device signs in with the password', async () => {
    const res = await post('/v1/auth/signup', { email: ' Reader@Example.com ', password: 'correct horse' });
    assert.equal(res.status, 200);
    assert.equal(res.body.account.email, 'reader@example.com');
    assert.equal((await get('/v1/me', res.body.token)).body.email, 'reader@example.com');
    assert.deepEqual((await call('GET', '/__test/mail?to=reader%40example.com')).body, []);

    const other = await post('/v1/auth/signin', { email: 'reader@example.com', password: 'correct horse' });
    assert.equal(other.status, 200);
    assert.notEqual(other.body.token, res.body.token);
    assert.equal((await post('/v1/auth/signin', { email: 'reader@example.com', password: 'wrong one' })).status, 401);
  });

  test("an existing account's password can't be replaced by signing up again", async () => {
    await post('/v1/auth/signup', { email: 'a@example.com', password: 'owner pass' });
    const again = await post('/v1/auth/signup', { email: 'a@example.com', password: 'squatter pw' });
    assert.equal(again.status, 409);
    assert.equal(again.body.code, 'account_exists');
    assert.equal((await post('/v1/auth/signin', { email: 'a@example.com', password: 'owner pass' })).status, 200);
    assert.equal((await post('/v1/auth/signin', { email: 'a@example.com', password: 'squatter pw' })).status, 401);
  });

  test("a Google account can't be signed up over", async () => {
    await post('/v1/auth/google', { idToken: google('g-1', 'b@example.com') });
    assert.equal((await post('/v1/auth/signup', { email: 'b@example.com', password: 'squatter pw' })).status, 409);
  });

  test('Google with the same email still discards a password nobody proved', async () => {
    await post('/v1/auth/signup', { email: 'c@example.com', password: 'squatter pw' });
    const owner = await post('/v1/auth/google', { idToken: google('g-2', 'c@example.com') });
    assert.equal(owner.status, 200);
    assert.equal((await post('/v1/auth/signin', { email: 'c@example.com', password: 'squatter pw' })).status, 401);
  });
});
