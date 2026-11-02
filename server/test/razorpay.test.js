import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { createServer } from 'node:http';
import { after, before, beforeEach, describe, test } from 'node:test';
import { client, startServer } from './support.js';

const SECRET = 'test-razorpay-secret';
const NOW = Date.parse('2026-10-31T12:00:00Z');
const DAY = 24 * 3600 * 1000;
const sec = (ms) => Math.floor(ms / 1000);

// A stand-in for api.razorpay.com: records subscription requests and answers with a short_url.
const created = [];
const fakeApi = createServer((req, res) => {
  let body = '';
  req.on('data', (c) => (body += c));
  req.on('end', () => {
    created.push({ auth: req.headers.authorization, body: JSON.parse(body) });
    res.setHeader('content-type', 'application/json');
    res.end(JSON.stringify({ id: 'sub_test1', status: 'created', short_url: 'https://rzp.io/i/abc123' }));
  });
});

let server, call, alice, aliceId;

before(async () => {
  await new Promise((r) => fakeApi.listen(0, '127.0.0.1', r));
  server = await startServer({
    RAZORPAY_KEY_ID: 'rzp_test_key',
    RAZORPAY_KEY_SECRET: 'rzp_test_secret',
    RAZORPAY_PLAN_MONTHLY: 'plan_Monthly1',
    RAZORPAY_WEBHOOK_SECRET: SECRET,
    RAZORPAY_API: `http://127.0.0.1:${fakeApi.address().port}`,
    SYNC_REQUIRES_PRO: '1',
  });
  call = client(server.base);
});

after(async () => {
  await server.stop();
  fakeApi.close();
});

beforeEach(async () => {
  created.length = 0;
  await call('POST', '/__test/reset', { body: {} });
  ({ token: alice, accountId: aliceId } = (await call('POST', '/__test/token', { body: { name: 'alice' } })).body);
});

function event(name, entity, { at = NOW } = {}) {
  return {
    event: name,
    created_at: sec(at),
    payload: {
      subscription: {
        entity: {
          id: 'sub_test1',
          plan_id: 'plan_Monthly1',
          status: 'active',
          current_start: sec(NOW),
          current_end: sec(NOW + 30 * DAY),
          charge_at: sec(NOW + 30 * DAY),
          ended_at: null,
          notes: { account_id: String(aliceId), plan: 'monthly' },
          ...entity,
        },
      },
    },
  };
}

function webhook(payload, { secret = SECRET } = {}) {
  const body = JSON.stringify(payload);
  return fetch(`${server.base}/v1/billing/razorpay`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Razorpay-Signature': createHmac('sha256', secret).update(body).digest('hex') },
    body,
  });
}

const status = async (now = NOW) => (await call('GET', '/v1/billing', { token: alice, now })).body;
const library = (now = NOW) => call('GET', '/v1/library', { token: alice, now });

describe('razorpay', () => {
  test('checkout creates a subscription tied to the account', async () => {
    assert.deepEqual((await status()).plans, ['monthly']);
    const res = await call('POST', '/v1/billing/checkout', { token: alice, body: { plan: 'monthly' } });
    assert.equal(res.status, 200);
    assert.equal(res.body.url, 'https://rzp.io/i/abc123');
    assert.equal(created.length, 1);
    assert.equal(created[0].auth, `Basic ${Buffer.from('rzp_test_key:rzp_test_secret').toString('base64')}`);
    assert.equal(created[0].body.plan_id, 'plan_Monthly1');
    assert.equal(created[0].body.notes.account_id, String(aliceId));
    const yearly = await call('POST', '/v1/billing/checkout', { token: alice, body: { plan: 'yearly' } });
    assert.equal(yearly.body.code, 'billing_unavailable');
  });

  test('sync needs Pro; an activated subscription unlocks it', async () => {
    const before = await library();
    assert.equal(before.status, 402);
    assert.equal(before.body.code, 'pro_required');
    assert.equal((await call('GET', '/v1/reading-positions/doc1', { token: alice, now: NOW })).status, 402);
    assert.equal((await call('GET', '/v1/me', { token: alice })).body.pro, false);

    assert.equal((await webhook(event('subscription.activated', {}))).status, 200);
    const s = await status();
    assert.equal(s.pro, true);
    assert.equal(s.plan, 'Monthly');
    assert.equal(s.renewsAt, NOW + 30 * DAY);
    assert.equal((await library()).status, 200);
    assert.equal((await call('GET', '/v1/reading-positions/doc1', { token: alice, now: NOW })).status, 404);
  });

  test('cancelled keeps Pro to the end of the paid period; halted ends it', async () => {
    await webhook(event('subscription.activated', {}));
    await webhook(event('subscription.cancelled', { status: 'cancelled' }, { at: NOW + DAY }));
    assert.equal((await status(NOW + 2 * DAY)).pro, true);
    assert.equal((await status(NOW + 31 * DAY)).pro, false);
    assert.equal((await library(NOW + 31 * DAY)).status, 402);

    await webhook(event('subscription.activated', {}, { at: NOW + 2 * DAY }));
    await webhook(event('subscription.halted', { status: 'halted' }, { at: NOW + 3 * DAY }));
    assert.equal((await status()).pro, false);
  });

  test('an older event never overwrites a newer one', async () => {
    await webhook(event('subscription.halted', { status: 'halted' }, { at: NOW + DAY }));
    await webhook(event('subscription.activated', {}, { at: NOW }));
    assert.equal((await status()).status, 'halted');
  });

  test('rejects bad signatures and ignores other events', async () => {
    assert.equal((await webhook(event('subscription.activated', {}), { secret: 'wrong' })).status, 401);
    assert.equal((await status()).pro, false);
    const res = await webhook({ event: 'payment.captured', payload: {} });
    assert.deepEqual(await res.json(), { ok: true, ignored: 'payment.captured' });
  });
});
