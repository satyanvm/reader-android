import { bytesToHex, constantTimeEqual } from './crypto.js';

/**
 * Paid plans through Lemon Squeezy, which is the merchant of record: it charges the card, handles
 * sales tax and VAT worldwide, and pays out. The server never sees card details.
 *
 *  1. The app asks for a checkout link (POST /v1/billing/checkout). It is the product's share link
 *     with the account id in `checkout[custom]`, and opens in the browser.
 *  2. Lemon Squeezy calls the webhook (POST /v1/billing/lemonsqueezy) on every subscription change,
 *     signed with the webhook secret; the custom data says which account it is for.
 *  3. The apps ask GET /v1/billing whether the account has Pro.
 *
 * Configuration (wrangler secrets / environment):
 *   LEMONSQUEEZY_WEBHOOK_SECRET    the signing secret set on the store's webhook
 *   LEMONSQUEEZY_CHECKOUT_MONTHLY  share link of the monthly variant (https://<store>.lemonsqueezy.com/buy/<uuid>)
 *   LEMONSQUEEZY_CHECKOUT_YEARLY   share link of the yearly variant
 */
export const PLANS = ['monthly', 'yearly'];

const CHECKOUT_ENV = { monthly: 'LEMONSQUEEZY_CHECKOUT_MONTHLY', yearly: 'LEMONSQUEEZY_CHECKOUT_YEARLY' };

/** Which plans can be bought on this server. */
export function availablePlans(env) {
  return PLANS.filter((plan) => validLink(env[CHECKOUT_ENV[plan]]));
}

/** The checkout link for `plan`, tied to the account; null if that plan isn't set up. */
export function checkoutUrl(env, plan, account) {
  const link = validLink(env[CHECKOUT_ENV[plan]]);
  if (!link) return null;
  const url = new URL(link);
  url.searchParams.set('checkout[custom][account_id]', String(account.id));
  if (account.email) url.searchParams.set('checkout[email]', account.email);
  return url.toString();
}

function validLink(value) {
  try {
    const url = new URL(String(value ?? ''));
    return url.protocol === 'https:' ? url.toString() : null;
  } catch {
    return null;
  }
}

/** Lemon Squeezy signs the raw body: X-Signature is its HMAC-SHA256, in hex. */
export async function validSignature(secret, rawBody, signature) {
  if (!secret || !signature) return false;
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const digest = bytesToHex(await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(rawBody)));
  return constantTimeEqual(digest, signature.trim().toLowerCase());
}

/**
 * The subscription a webhook event describes, or null for events that aren't about one (orders,
 * license keys). `accountId` is null when the event carries no custom data; the store then keeps
 * the account the subscription already belongs to.
 */
export function subscriptionFromEvent(payload) {
  const data = payload?.data;
  if (data?.type !== 'subscriptions' || !data.id) return null;
  const a = data.attributes ?? {};
  const accountId = Number(payload.meta?.custom_data?.account_id);
  return {
    subscriptionId: String(data.id),
    accountId: Number.isSafeInteger(accountId) && accountId > 0 ? accountId : null,
    status: String(a.status ?? ''),
    variantName: a.variant_name ? String(a.variant_name) : null,
    renewsAt: time(a.renews_at),
    endsAt: time(a.ends_at),
    manageUrl: a.urls?.customer_portal ? String(a.urls.customer_portal) : null,
    providerUpdatedAt: time(a.updated_at) ?? 0,
  };
}

const time = (v) => {
  const t = v ? Date.parse(v) : NaN;
  return Number.isFinite(t) ? t : null;
};

/**
 * Whether a subscription gives Pro now. Past-due keeps it while Lemon Squeezy retries the card;
 * a cancelled one keeps it until the end of the period that was paid for.
 */
export function grantsPro(sub, now) {
  switch (sub.status) {
    case 'active':
    case 'on_trial':
    case 'past_due':
      return true;
    case 'cancelled':
      return sub.endsAt !== null && sub.endsAt > now;
    default:
      return false;
  }
}

/** What GET /v1/billing returns for an account's subscriptions. */
export function billingStatus(subs, now, env) {
  // The one that matters: one granting Pro if any, otherwise the latest.
  const sorted = [...subs].sort((a, b) => (grantsPro(b, now) - grantsPro(a, now)) || (b.providerUpdatedAt - a.providerUpdatedAt));
  const sub = sorted[0] ?? null;
  return {
    pro: sub ? grantsPro(sub, now) : false,
    plan: sub?.variantName ?? null,
    status: sub?.status ?? null,
    renewsAt: sub?.renewsAt ?? null,
    endsAt: sub?.endsAt ?? null,
    manageUrl: sub?.manageUrl ?? null,
    plans: availablePlans(env),
  };
}
