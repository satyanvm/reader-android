import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { after, before, beforeEach, describe, test } from 'node:test';
import { client, startServer } from './support.js';

const SECRET = 'test-webhook-secret';
const MONTHLY = 'https://reader.lemonsqueezy.com/buy/monthly-uuid';
const DAY = 24 * 3600 * 1000;
const NOW = Date.parse('2026-10-26T12:00:00Z');

let server, call, alice, aliceId;

before(async () => {
  server = await startServer({ LEMONSQUEEZY_WEBHOOK_SECRET: SECRET, LEMONSQUEEZY_CHECKOUT_MONTHLY: MONTHLY });
  call = client(server.base);
});

after(() => server.stop());

beforeEach(async () => {
  await call('POST', '/__test/reset', { body: {} });
  ({ token: alice, accountId: aliceId } = (await call('POST', '/__test/token', { body: { name: 'alice' } })).body);
});

/** A subscription webhook as Lemon Squeezy sends it (only the fields the server reads). */
function event(name, attributes, { id = '1001', accountId = aliceId } = {}) {
  return {
    meta: { event_name: name, ...(accountId ? { custom_data: { account_id: String(accountId) } } : {}) },
    data: {
      type: 'subscriptions',
      id,
      attributes: {
        status: 'active',
        variant_name: 'Monthly',
        renews_at: new Date(NOW + 30 * DAY).toISOString(),
        ends_at: null,
        updated_at: new Date(NOW).toISOString(),
        urls: { customer_portal: 'https://reader.lemonsqueezy.com/billing?expires=1' },
        ...attributes,
      },
    },
  };
}

function webhook(payload, { secret = SECRET } = {}) {
  const body = JSON.stringify(payload);
  return fetch(`${server.base}/v1/billing/lemonsqueezy`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Signature': createHmac('sha256', secret).update(body).digest('hex') },
    body,
  });
}

const status = async (now = NOW) => (await call('GET', '/v1/billing', { token: alice, now })).body;

describe('billing', () => {
  test('needs a token', async () => {
    assert.equal((await call('GET', '/v1/billing')).status, 401);
    assert.equal((await call('POST', '/v1/billing/checkout', { body: { plan: 'monthly' } })).status, 401);
  });

  test('a new account is free, and lists the plans that can be bought', async () => {
    assert.deepEqual(await status(), {
      pro: false, plan: null, status: null, renewsAt: null, endsAt: null, manageUrl: null, plans: ['monthly'],
    });
  });

  test('the checkout link carries the account', async () => {
    const res = await call('POST', '/v1/billing/checkout', { token: alice, body: { plan: 'monthly' } });
    assert.equal(res.status, 200);
    const url = new URL(res.body.url);
    assert.equal(url.origin + url.pathname, MONTHLY);
    assert.equal(url.searchParams.get('checkout[custom][account_id]'), String(aliceId));
  });

  test('a plan that is not set up, or not a plan, is refused', async () => {
    const yearly = await call('POST', '/v1/billing/checkout', { token: alice, body: { plan: 'yearly' } });
    assert.equal(yearly.status, 503);
    assert.equal(yearly.body.code, 'billing_unavailable');
    assert.equal((await call('POST', '/v1/billing/checkout', { token: alice, body: { plan: 'lifetime' } })).status, 422);
  });

  test('a paid subscription gives Pro', async () => {
    assert.equal((await webhook(event('subscription_created', {}))).status, 200);
    const s = await status();
    assert.equal(s.pro, true);
    assert.equal(s.plan, 'Monthly');
    assert.equal(s.renewsAt, NOW + 30 * DAY);
    assert.match(s.manageUrl, /billing/);
  });

  test('a webhook with a bad signature changes nothing', async () => {
    assert.equal((await webhook(event('subscription_created', {}), { secret: 'wrong' })).status, 401);
    assert.equal((await status()).pro, false);
  });

  test('cancelled keeps Pro until the paid period ends; expired ends it', async () => {
    await webhook(event('subscription_created', {}));
    const endsAt = new Date(NOW + 10 * DAY).toISOString();
    await webhook(event('subscription_cancelled', { status: 'cancelled', ends_at: endsAt, updated_at: new Date(NOW + DAY).toISOString() }));
    assert.equal((await status(NOW + 2 * DAY)).pro, true);
    assert.equal((await status(NOW + 11 * DAY)).pro, false);
    await webhook(event('subscription_expired', { status: 'expired', ends_at: endsAt, updated_at: new Date(NOW + 10 * DAY).toISOString() }));
    assert.equal((await status(NOW + 2 * DAY)).pro, false);
  });

  test('an older event arriving late is ignored', async () => {
    await webhook(event('subscription_expired', { status: 'expired', updated_at: new Date(NOW + DAY).toISOString() }));
    await webhook(event('subscription_created', { status: 'active' }));
    assert.equal((await status()).status, 'expired');
  });

  test('later events without custom data still reach the account', async () => {
    await webhook(event('subscription_created', {}));
    await webhook(event('subscription_updated', { status: 'expired', updated_at: new Date(NOW + DAY).toISOString() }, { accountId: null }));
    assert.equal((await status()).status, 'expired');
  });

  test('events for unknown accounts and other event types are acknowledged and ignored', async () => {
    const unknown = await webhook(event('subscription_created', {}, { accountId: 999999 }));
    assert.equal(unknown.status, 200);
    assert.equal((await unknown.json()).stored, false);
    const order = await webhook({ meta: { event_name: 'order_created' }, data: { type: 'orders', id: '5' } });
    assert.equal(order.status, 200);
    assert.equal((await status()).pro, false);
  });
});
