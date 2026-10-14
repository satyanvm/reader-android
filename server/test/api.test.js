import assert from 'node:assert/strict';
import { after, before, beforeEach, describe, test } from 'node:test';
import { client, startServer } from './support.js';

const DOC = '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08';
const NOW = 1_760_000_000_000;

let server, call, alice, bob;

function pos(page, { hlc = NOW - 1000, device = 'phone', offset = 0.4000000059604645, doc = DOC } = {}) {
  return {
    documentId: doc, pageIndex: page, pageOffset: offset, horizontalOffset: 0, zoom: 1,
    pageCount: 500, hlc, deviceId: device, schemaVersion: 1,
  };
}

const position = (method, token, body, doc = DOC) =>
  call(method, `/v1/reading-positions/${encodeURIComponent(doc)}`, { token, body, now: NOW });

before(async () => {
  server = await startServer();
  call = client(server.base);
});

after(() => server.stop());

beforeEach(async () => {
  await call('POST', '/__test/reset', { body: {} });
  alice = (await call('POST', '/__test/token', { body: { name: 'alice' } })).body.token;
  bob = (await call('POST', '/__test/token', { body: { name: 'bob' } })).body.token;
});

describe('contract', () => {
  test('GET with no position is 404', async () => {
    assert.equal((await position('GET', alice)).status, 404);
  });

  test('PUT stores and returns the position; GET returns it unchanged', async () => {
    const put = await position('PUT', alice, pos(186));
    assert.equal(put.status, 200);
    assert.deepEqual(put.body, pos(186));
    const get = await position('GET', alice);
    assert.deepEqual(get.body, pos(186));
    // 0.4f widened to Double comes back as the same digits, so the client's Float is identical.
    assert.match(get.raw, /"pageOffset":0\.4000000059604645/);
  });

  test('an older position loses and the server answers with the newer one', async () => {
    await position('PUT', alice, pos(250, { hlc: NOW }));
    const stale = await position('PUT', alice, pos(40, { hlc: NOW - 60_000, device: 'laptop' }));
    assert.equal(stale.status, 200);
    assert.equal(stale.body.pageIndex, 250);
    assert.equal((await position('GET', alice)).body.pageIndex, 250);
  });

  test('restarting from page one is just a newer write, so it wins', async () => {
    await position('PUT', alice, pos(300, { hlc: NOW - 5000 }));
    assert.equal((await position('PUT', alice, pos(0, { hlc: NOW - 4000, offset: 0 }))).body.pageIndex, 0);
  });

  test('equal clocks are ordered by device id, by UTF-16 code units like Kotlin', async () => {
    await position('PUT', alice, pos(10, { hlc: NOW, device: 'b' }));
    assert.equal((await position('PUT', alice, pos(20, { hlc: NOW, device: 'a' }))).body.pageIndex, 10);
    assert.equal((await position('PUT', alice, pos(30, { hlc: NOW, device: 'c' }))).body.pageIndex, 30);
    // U+1F600 is D83D DE00 in UTF-16, which sorts before U+FFFD (SQLite's byte order would disagree).
    await position('PUT', alice, pos(40, { hlc: NOW + 1, device: '\u{FFFD}' }));
    assert.equal((await position('PUT', alice, pos(50, { hlc: NOW + 1, device: '\u{1F600}' }))).body.pageIndex, 40);
  });

  test('a retried PUT is idempotent', async () => {
    const first = await position('PUT', alice, pos(77));
    const again = await position('PUT', alice, pos(77));
    assert.deepEqual(first.body, again.body);
  });

  test('out-of-order and concurrent PUTs converge on the newest position', async () => {
    const writes = Array.from({ length: 30 }, (_, i) => pos(i, { hlc: NOW - 100_000 + i * 10, device: `d${i % 3}` }));
    const shuffled = writes.map((w) => [Math.random(), w]).sort((a, b) => a[0] - b[0]).map(([, w]) => w);
    await Promise.all(shuffled.map((w) => position('PUT', alice, w)));
    assert.equal((await position('GET', alice)).body.pageIndex, 29);
  });
});

describe('accounts', () => {
  test('requests need a valid token', async () => {
    assert.equal((await position('GET', null)).status, 401);
    assert.equal((await position('PUT', 'not-a-token', pos(1))).status, 401);
  });

  test("accounts never see each other's positions", async () => {
    await position('PUT', alice, pos(186));
    assert.equal((await position('GET', bob)).status, 404);
    await position('PUT', bob, pos(3, { hlc: NOW - 999_999 }));
    assert.equal((await position('GET', alice)).body.pageIndex, 186, 'older but separate: no interference');
  });

  test('revoked tokens stop working; a new token sees the same positions', async () => {
    await position('PUT', alice, pos(9));
    await call('POST', '/__test/revoke', { body: { name: 'alice' } });
    assert.equal((await position('GET', alice)).status, 401);
    const fresh = (await call('POST', '/__test/token', { body: { name: 'alice' } })).body.token;
    assert.equal((await position('GET', fresh)).body.pageIndex, 9);
  });

  test('tokens are stored hashed', async () => {
    const hashes = (await call('GET', '/__test/token-hashes')).body;
    assert.ok(hashes.length >= 2 && hashes.every((h) => h !== alice && /^[0-9a-f]{64}$/.test(h)));
  });
});

describe('validation', () => {
  const cases = {
    'documentId differs from the URL': pos(1, { doc: 'other' }),
    'page beyond the page count': { ...pos(1), pageIndex: 500 },
    'fraction above 1': { ...pos(1), pageOffset: 1.5 },
    'zoom out of range': { ...pos(1), zoom: 9 },
    'missing deviceId': { ...pos(1), deviceId: '' },
    'non-integer hlc': { ...pos(1), hlc: 1.5 },
    'unknown schema': { ...pos(1), schemaVersion: 2 },
    'clock far in the future': pos(1, { hlc: NOW + 2 * 24 * 3600 * 1000 }),
  };
  for (const [name, body] of Object.entries(cases)) {
    test(`rejects ${name} with 422`, async () => {
      assert.equal((await position('PUT', alice, body)).status, 422);
      assert.equal((await position('GET', alice)).status, 404, 'nothing stored');
    });
  }

  test('optional fields default like the clients', async () => {
    const { horizontalOffset, zoom, schemaVersion, ...minimal } = pos(5);
    const res = await position('PUT', alice, minimal);
    assert.equal(res.status, 200);
    assert.equal(res.body.zoom, 1);
    assert.equal(res.body.horizontalOffset, 0);
    assert.equal(res.body.schemaVersion, 1);
  });

  test('bad JSON is 400, oversized bodies are 413, other methods 405', async () => {
    const path = `/v1/reading-positions/${DOC}`;
    assert.equal((await call('PUT', path, { token: alice, body: '{nope', raw: true })).status, 400);
    assert.equal((await call('PUT', path, { token: alice, body: { ...pos(1), pad: 'x'.repeat(10_000) } })).status, 413);
    assert.equal((await call('DELETE', path, { token: alice })).status, 405);
  });

  test('health check', async () => {
    assert.equal((await call('GET', '/healthz')).status, 200);
  });
});
