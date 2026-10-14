import { constantTimeEqual, hashPassword, randomCode, sha256Hex, verifyPassword } from './crypto.js';
import { HttpError } from './errors.js';
import { TokenError, verifyIdToken } from './jwt.js';

const CODE_TTL_MS = 10 * 60 * 1000;
const CODE_MAX_ATTEMPTS = 5;
const RESEND_MIN_INTERVAL_MS = 30 * 1000;
const MAX_CODES_PER_HOUR = 6;
const MAX_LOGIN_FAILURES = 10;
const LOGIN_WINDOW_MS = 15 * 60 * 1000;
const MIN_PASSWORD = 8;
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/**
 * Accounts: email + password (the email proven with a 6-digit code), and Sign in with Apple /
 * Google. Every successful sign-in returns a session token for the sync API. One email is one
 * account however the person signs in. All state, including rate limits, lives in the database.
 */
export class Auth {
  constructor(store, mailer, { appleAudiences = [], googleAudiences = [], keys, pepper = '', now = Date.now } = {}) {
    this.store = store;
    this.mailer = mailer;
    this.audiences = { apple: appleAudiences, google: googleAudiences };
    this.keys = keys;
    this.pepper = pepper;
    this.now = now;
  }

  get providers() {
    return { apple: this.audiences.apple.length > 0, google: this.audiences.google.length > 0 };
  }

  /** New account, or a fresh password and code for one whose email was never verified. */
  async signUp(rawEmail, password) {
    const email = normalizeEmail(rawEmail);
    checkPassword(password);
    const existing = await this.store.accountByEmail(email);
    if (existing?.email_verified_at) {
      throw new HttpError(409, 'An account with this email already exists. Sign in instead.', 'account_exists');
    }
    const hash = await hashPassword(password, this.pepper);
    // Unverified: whoever proves the address owns the account, so the latest sign-up wins.
    if (existing) {
      await this.store.setPassword(existing.id, hash);
    } else if ((await this.store.createEmailAccount(email, hash)) === null) {
      const raced = await this.store.accountByEmail(email); // created by a concurrent sign-up
      if (raced?.email_verified_at) throw new HttpError(409, 'An account with this email already exists. Sign in instead.', 'account_exists');
      await this.store.setPassword(raced.id, hash);
    }
    await this.sendCode(email, 'verify');
    return { status: 'verification_required', email };
  }

  async signIn(rawEmail, password) {
    const email = normalizeEmail(rawEmail);
    const failures = `login:${email}`;
    if ((await this.store.rateEvents(failures, this.now(), LOGIN_WINDOW_MS)).count >= MAX_LOGIN_FAILURES) {
      throw new HttpError(429, 'Too many attempts. Try again in a few minutes, or reset your password.', 'rate_limited');
    }
    const account = await this.store.accountByEmail(email);
    const check = account?.password_hash ? await verifyPassword(password ?? '', account.password_hash, this.pepper) : { ok: false };
    if (!check.ok) {
      await this.store.recordRateEvent(failures, this.now());
      throw new HttpError(401, 'Wrong email or password.', 'invalid_credentials');
    }
    if (!account.email_verified_at) {
      await this.sendCode(email, 'verify').catch(() => {});
      throw new HttpError(403, 'Confirm your email first. We sent you a code.', 'verification_required');
    }
    if (check.needsRehash) await this.store.setPassword(account.id, await hashPassword(password, this.pepper));
    await this.store.clearRateEvents(failures);
    return this.session(account);
  }

  async verifyEmail(rawEmail, code) {
    const email = normalizeEmail(rawEmail);
    await this.checkCode(email, 'verify', code);
    const account = await this.store.accountByEmail(email);
    if (!account) throw new HttpError(400, 'No account for this email.', 'no_account');
    await this.store.markVerified(account.id, this.now());
    return this.session(account);
  }

  async resendCode(rawEmail) {
    const email = normalizeEmail(rawEmail);
    const account = await this.store.accountByEmail(email);
    // Same answer whether or not the account exists, so this can't be used to probe emails.
    if (account && !account.email_verified_at) await this.sendCode(email, 'verify');
    return { status: 'sent' };
  }

  async requestPasswordReset(rawEmail) {
    const email = normalizeEmail(rawEmail);
    if (await this.store.accountByEmail(email)) await this.sendCode(email, 'reset');
    return { status: 'sent' };
  }

  /** A reset code proves the email too, so it also verifies the account. */
  async resetPassword(rawEmail, code, password) {
    const email = normalizeEmail(rawEmail);
    checkPassword(password);
    await this.checkCode(email, 'reset', code);
    const account = await this.store.accountByEmail(email);
    if (!account) throw new HttpError(400, 'No account for this email.', 'no_account');
    await this.store.setPassword(account.id, await hashPassword(password, this.pepper));
    await this.store.markVerified(account.id, this.now());
    await this.store.revokeTokens(account.id); // signed out everywhere else
    await this.store.clearRateEvents(`login:${email}`);
    return this.session(account);
  }

  /**
   * Sign in with Apple or Google. The provider's verified email joins the account with that
   * email; an unverified password set by someone else on that address is discarded, since the
   * provider has just proven who owns it.
   */
  async signInWithProvider(provider, idToken) {
    let claims;
    try {
      claims = await verifyIdToken(provider, idToken, { audiences: this.audiences[provider], keys: this.keys, now: this.now() });
    } catch (e) {
      if (e instanceof TokenError) throw new HttpError(401, `Couldn't sign in with ${title(provider)}: ${e.message}.`, 'invalid_identity');
      throw new HttpError(502, `Couldn't reach ${title(provider)} to check the sign-in.`, 'provider_unreachable');
    }
    const linked = await this.store.accountForIdentity(provider, claims.sub);
    if (linked !== null) return this.session(await this.store.accountById(linked));

    const verified = claims.email_verified === true || claims.email_verified === 'true';
    if (!claims.email || !verified) throw new HttpError(400, `${title(provider)} didn't share a verified email.`, 'email_required');
    const email = normalizeEmail(claims.email);
    let account = await this.store.accountByEmail(email);
    if (!account) {
      await this.store.createEmailAccount(email, null, this.now()); // null if a concurrent request created it
      account = await this.store.accountByEmail(email);
    } else if (!account.email_verified_at) {
      await this.store.setPassword(account.id, null);
    }
    await this.store.markVerified(account.id, this.now());
    await this.store.linkIdentity(provider, claims.sub, account.id, email, this.now());
    // If two first sign-ins raced, use whichever link won.
    const accountId = await this.store.accountForIdentity(provider, claims.sub);
    return this.session(await this.store.accountById(accountId));
  }

  async session(account) {
    return { token: await this.store.issueToken(Number(account.id), 'session'), account: { email: account.email } };
  }

  async sendCode(email, purpose) {
    const now = this.now();
    const key = `code:${purpose}:${email}`; // a reset right after confirming the email is fine
    const recent = await this.store.rateEvents(key, now, 60 * 60 * 1000);
    if (recent.last !== null && now - recent.last < RESEND_MIN_INTERVAL_MS) {
      throw new HttpError(429, 'A code was just sent. Wait a moment before asking for another.', 'rate_limited');
    }
    if (recent.count >= MAX_CODES_PER_HOUR) throw new HttpError(429, 'Too many codes requested. Try again in an hour.', 'rate_limited');
    const code = randomCode();
    await this.store.putCode(email, purpose, await codeHash(email, purpose, code), now + CODE_TTL_MS, now);
    await this.store.recordRateEvent(key, now);
    const what = purpose === 'verify' ? 'confirm your email for Reader' : 'reset your Reader password';
    await this.mailer.send({
      to: email,
      subject: purpose === 'verify' ? `${code} is your Reader code` : `${code} is your Reader password reset code`,
      text: `Your code is ${code}\n\nEnter it in the app to ${what}. It expires in 10 minutes.\n\nIf you didn't ask for this, you can ignore this email.`,
    });
  }

  async checkCode(email, purpose, code) {
    const row = await this.store.getCode(email, purpose);
    const invalid = new HttpError(400, 'That code is wrong or has expired. Ask for a new one.', 'invalid_code');
    if (!row || row.expires_at < this.now() || row.attempts >= CODE_MAX_ATTEMPTS) throw invalid;
    const given = await codeHash(email, purpose, String(code ?? '').trim());
    if (!constantTimeEqual(row.code_hash, given)) {
      await this.store.countFailedAttempt(email, purpose);
      throw invalid;
    }
    // Single use, even if two requests check the same code at once.
    if (!(await this.store.consumeCode(email, purpose, row.code_hash))) throw invalid;
  }
}

export function normalizeEmail(raw) {
  const email = String(raw ?? '').trim().toLowerCase();
  if (!EMAIL.test(email) || email.length > 254) throw new HttpError(422, 'Enter a valid email address.', 'invalid_email');
  return email;
}

function checkPassword(password) {
  if (typeof password !== 'string' || password.length < MIN_PASSWORD) {
    throw new HttpError(422, `Use a password of at least ${MIN_PASSWORD} characters.`, 'weak_password');
  }
  if (password.length > 1024) throw new HttpError(422, 'That password is too long.', 'weak_password');
}

function codeHash(email, purpose, code) {
  return sha256Hex(`${purpose}:${email}:${code}`);
}

function title(provider) {
  return provider === 'apple' ? 'Apple' : 'Google';
}
