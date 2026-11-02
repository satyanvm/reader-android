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

/** Which plans can be bought on this server (through Razorpay when it's set up, else Lemon Squeezy). */
export function availablePlans(env) {
  if (razorpayEnabled(env)) return PLANS.filter((plan) => razorpayPlanId(env, plan));
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
 * Whether a subscription gives Pro now. Past-due (Lemon Squeezy) and pending (Razorpay) keep it
 * while the card is retried; a cancelled one keeps it until the end of the period that was paid for.
 */
export function grantsPro(sub, now) {
  switch (sub.status) {
    case 'active':
    case 'on_trial':
    case 'past_due':
    case 'pending':
      return true;
    case 'cancelled':
      return sub.endsAt !== null && sub.endsAt > now;
    default:
      return false;
  }
}

/** What GET /v1/billing returns for an account's subscriptions. */
export function billingStatus(subs, now, env) {
  return { ...proStatus(subs, now), plans: availablePlans(env) };
}

/** Whether any of an account's subscriptions gives Pro now, and the details of the one that matters. */
export function proStatus(subs, now) {
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
  };
}

/**
 * Razorpay (India: UPI, cards, netbanking, and international cards once enabled on the account).
 * Preferred over Lemon Squeezy when it is configured. Unlike a share link, a Razorpay subscription
 * is created through the API, per purchase:
 *
 *  1. POST /v1/billing/checkout creates a subscription for the plan with the account id in its
 *     notes, and returns its `short_url`, Razorpay's hosted page where the buyer pays.
 *  2. Razorpay calls the webhook (POST /v1/billing/razorpay) on every subscription event, signed
 *     with the webhook secret in X-Razorpay-Signature.
 *
 * Configuration:
 *   RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET   API keys (Dashboard → Account & Settings → API Keys); secret is a wrangler secret
 *   RAZORPAY_PLAN_MONTHLY, RAZORPAY_PLAN_YEARLY   plan ids (plan_...) from Dashboard → Subscriptions → Plans
 *   RAZORPAY_WEBHOOK_SECRET               the secret set on the webhook (wrangler secret)
 */
const RAZORPAY_PLAN_ENV = { monthly: 'RAZORPAY_PLAN_MONTHLY', yearly: 'RAZORPAY_PLAN_YEARLY' };
/** Billing cycles a subscription runs for before it has to be renewed by hand (Razorpay needs a count). */
const RAZORPAY_TOTAL_COUNT = { monthly: 120, yearly: 10 };

const razorpayPlanId = (env, plan) => {
  const id = String(env[RAZORPAY_PLAN_ENV[plan]] ?? '').trim();
  return /^plan_[A-Za-z0-9]+$/.test(id) ? id : null;
};

/** Whether checkout goes through Razorpay on this server. */
export function razorpayEnabled(env) {
  return Boolean(env.RAZORPAY_KEY_ID && env.RAZORPAY_KEY_SECRET && PLANS.some((p) => razorpayPlanId(env, p)));
}

/** Creates a Razorpay subscription for `plan` and returns its hosted payment link; null if that plan isn't set up. */
export async function razorpayCheckoutUrl(env, plan, account, fetchImpl = fetch) {
  const planId = razorpayPlanId(env, plan);
  if (!planId) return null;
  const api = env.RAZORPAY_API || 'https://api.razorpay.com';
  const res = await fetchImpl(`${api}/v1/subscriptions`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Basic ${btoa(`${env.RAZORPAY_KEY_ID}:${env.RAZORPAY_KEY_SECRET}`)}`,
    },
    body: JSON.stringify({
      plan_id: planId,
      total_count: RAZORPAY_TOTAL_COUNT[plan],
      customer_notify: 1,
      notes: { account_id: String(account.id), plan },
      ...(account.email ? { notify_info: { notify_email: account.email } } : {}),
    }),
  });
  const body = await res.json().catch(() => null);
  if (!res.ok || typeof body?.short_url !== 'string') {
    throw new Error(`razorpay: creating a subscription failed (${res.status}): ${body?.error?.description ?? 'no detail'}`);
  }
  return body.short_url;
}

/**
 * The subscription a Razorpay webhook describes, in the same shape as subscriptionFromEvent, or
 * null for events that aren't about one (payments, refunds). Razorpay times are in seconds.
 */
export function subscriptionFromRazorpay(payload, env) {
  const s = payload?.payload?.subscription?.entity;
  if (!String(payload?.event ?? '').startsWith('subscription.') || !s?.id) return null;
  const accountId = Number(s.notes?.account_id);
  const ms = (v) => (Number.isFinite(Number(v)) && Number(v) > 0 ? Number(v) * 1000 : null);
  const plan = PLANS.find((p) => razorpayPlanId(env, p) === s.plan_id) ?? s.notes?.plan;
  return {
    subscriptionId: String(s.id),
    accountId: Number.isSafeInteger(accountId) && accountId > 0 ? accountId : null,
    status: String(s.status ?? ''),
    variantName: plan ? plan[0].toUpperCase() + plan.slice(1) : null,
    renewsAt: s.status === 'cancelled' ? null : ms(s.charge_at) ?? ms(s.current_end),
    // A cancelled subscription keeps Pro to the end of the period paid for, unless it ended at once.
    endsAt: s.status === 'cancelled' || s.status === 'completed' ? ms(s.ended_at) ?? ms(s.current_end) : null,
    manageUrl: null,
    providerUpdatedAt: ms(payload.created_at) ?? 0,
  };
}
