import assert from 'node:assert/strict';
import { after, before, beforeEach, describe, test } from 'node:test';
import { Auth } from '../src/auth.js';
import { hashPassword } from '../src/crypto.js';
import { nodeSqlite } from '../src/db.js';
import { Store } from '../src/store.js';
import { client, startServer } from './support.js';

// EMAIL_VERIFICATION=off: no code emails, email and password alone sign in.
let server, call;
let clock = 1_760_000_000_000;
const post = (path, body, token) => call('POST', path, { body, token, now: clock });
const get = (path, token) => call('GET', path, { token, now: clock });

before(async () => {
  server = await startServer({ EMAIL_VERIFICATION: 'off' });
  call = client(server.base);
});

after(() => server.stop());

beforeEach(async () => {
  await call('POST', '/__test/reset', { body: {} });
  clock += 24 * 3600 * 1000;
});

describe('email codes off', () => {
  test('providers say codes are off', async () => {
    assert.equal((await get('/v1/auth/providers')).body.emailCodes, false);
  });

  test('sign-up signs straight in, sends no email, and the account signs in on another device', async () => {
    const res = await post('/v1/auth/signup', { email: ' Reader@Example.com ', password: 'correct horse' });
    assert.equal(res.status, 200);
    assert.equal(res.body.account.email, 'reader@example.com');
    assert.equal((await get('/v1/me', res.body.token)).body.email, 'reader@example.com');
    assert.deepEqual((await call('GET', '/__test/mail?to=reader%40example.com')).body, []);

    const other = await post('/v1/auth/signin', { email: 'reader@example.com', password: 'correct horse' });
    assert.equal(other.status, 200);
    assert.notEqual(other.body.token, res.body.token);
  });

  test('a second sign-up for the same email is refused', async () => {
    await post('/v1/auth/signup', { email: 'a@example.com', password: 'correct horse' });
    const again = await post('/v1/auth/signup', { email: 'a@example.com', password: 'other password' });
    assert.equal(again.status, 409);
    assert.equal(again.body.code, 'account_exists');
  });

  test('an account left unconfirmed while codes were on signs in with its password', async () => {
    const db = await nodeSqlite(':memory:');
    const store = new Store(db);
    await store.migrate();
    await store.createEmailAccount('b@example.com', await hashPassword('correct horse', 'pepper')); // unverified
    const sent = [];
    const auth = new Auth(store, { send: async (m) => sent.push(m) }, { pepper: 'pepper', emailCodes: false });
    const session = await auth.signIn('b@example.com', 'correct horse');
    assert.ok(session.token);
    assert.ok((await store.accountByEmail('b@example.com')).email_verified_at);
    assert.equal(sent.length, 0);
    db.close();
  });

  test('a wrong password is still refused', async () => {
    await post('/v1/auth/signup', { email: 'c@example.com', password: 'correct horse' });
    const res = await post('/v1/auth/signin', { email: 'c@example.com', password: 'wrong horse' });
    assert.equal(res.status, 401);
    assert.equal(res.body.code, 'invalid_credentials');
  });
});
