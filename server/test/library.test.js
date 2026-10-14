import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { after, before, beforeEach, describe, test } from 'node:test';
import { client, startServer } from './support.js';

const BYTES = new TextEncoder().encode('%PDF-1.4 a tiny book');
const ID = createHash('sha256').update(BYTES).digest('hex');
const META = { title: 'A Tiny Book', format: 'PDF', sizeBytes: BYTES.length, pageCount: 3 };

let server, call, alice, bob;

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

const putFile = (token, bytes = BYTES, id = ID) =>
  fetch(`${server.base}/v1/library/${id}/file`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/octet-stream' },
    body: bytes,
  });

describe('library', () => {
  test('needs a token', async () => {
    assert.equal((await call('GET', '/v1/library')).status, 401);
  });

  test('a book added on one device is listed, with its file, for the same account only', async () => {
    const put = await call('PUT', `/v1/library/${ID}`, { token: alice, body: META });
    assert.equal(put.status, 200);
    assert.equal(put.body.hasFile, false);
    assert.equal((await putFile(alice)).status, 200);

    const list = (await call('GET', '/v1/library', { token: alice })).body.books;
    assert.equal(list.length, 1);
    assert.deepEqual({ ...list[0], addedAt: 0 }, { documentId: ID, ...META, hasFile: true, addedAt: 0, removedAt: null });

    const file = await fetch(`${server.base}/v1/library/${ID}/file`, { headers: { Authorization: `Bearer ${alice}` } });
    assert.equal(file.status, 200);
    assert.deepEqual(new Uint8Array(await file.arrayBuffer()), BYTES);

    assert.deepEqual((await call('GET', '/v1/library', { token: bob })).body.books, []);
    const other = await fetch(`${server.base}/v1/library/${ID}/file`, { headers: { Authorization: `Bearer ${bob}` } });
    assert.equal(other.status, 404);
  });

  test('a file that is not the size the library says is refused', async () => {
    await call('PUT', `/v1/library/${ID}`, { token: alice, body: META });
    const res = await putFile(alice, new TextEncoder().encode('something else'));
    assert.equal(res.status, 422);
    assert.equal((await call('GET', '/v1/library', { token: alice })).body.books[0].hasFile, false);
  });

  test('a book larger than one chunk comes back byte for byte', async () => {
    const big = new Uint8Array(2.5 * 1024 * 1024).map((_, i) => (i * 31 + 7) % 251);
    const id = createHash('sha256').update(big).digest('hex');
    await call('PUT', `/v1/library/${id}`, { token: alice, body: { ...META, sizeBytes: big.length } });
    assert.equal((await putFile(alice, big, id)).status, 200);
    const file = await fetch(`${server.base}/v1/library/${id}/file`, { headers: { Authorization: `Bearer ${alice}` } });
    assert.equal(file.status, 200);
    assert.deepEqual(new Uint8Array(await file.arrayBuffer()), big);
  });

  test('a file needs the book in the library first', async () => {
    assert.equal((await putFile(alice)).status, 409);
  });

  test('removing leaves a tombstone; adding again brings it back', async () => {
    await call('PUT', `/v1/library/${ID}`, { token: alice, body: META });
    await putFile(alice);
    assert.deepEqual((await call('DELETE', `/v1/library/${ID}`, { token: alice })).body, { removed: true });
    const [gone] = (await call('GET', '/v1/library', { token: alice })).body.books;
    assert.notEqual(gone.removedAt, null);
    assert.equal(gone.hasFile, false);
    const file = await fetch(`${server.base}/v1/library/${ID}/file`, { headers: { Authorization: `Bearer ${alice}` } });
    assert.equal(file.status, 404);

    const back = await call('PUT', `/v1/library/${ID}`, { token: alice, body: META });
    assert.equal(back.body.removedAt, null);
  });

  test('ids and details are checked', async () => {
    assert.equal((await call('PUT', '/v1/library/not-a-hash', { token: alice, body: META })).status, 400);
    assert.equal((await call('PUT', `/v1/library/${ID}`, { token: alice, body: { ...META, format: 'DOCX' } })).status, 422);
    assert.equal((await call('PUT', `/v1/library/${ID}`, { token: alice, body: { ...META, title: ' ' } })).status, 422);
  });
});
