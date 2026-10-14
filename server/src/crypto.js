/**
 * WebCrypto helpers: the same code runs in Cloudflare Workers and in Node.
 */
const enc = new TextEncoder();

export function bytesToHex(bytes) {
  return [...new Uint8Array(bytes)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

export function base64url(bytes) {
  let s = '';
  for (const b of new Uint8Array(bytes)) s += String.fromCharCode(b);
  return btoa(s).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}

export function fromBase64url(text) {
  const b = atob(text.replaceAll('-', '+').replaceAll('_', '/') + '='.repeat((4 - (text.length % 4)) % 4));
  return Uint8Array.from(b, (c) => c.charCodeAt(0));
}

export async function sha256Hex(text) {
  return bytesToHex(await crypto.subtle.digest('SHA-256', enc.encode(text)));
}

/** 256 random bits, URL-safe. */
export function randomToken() {
  return base64url(crypto.getRandomValues(new Uint8Array(32)));
}

/** A uniformly random 6-digit code. */
export function randomCode() {
  const buf = new Uint32Array(1);
  const limit = Math.floor(0x1_0000_0000 / 1_000_000) * 1_000_000; // reject the biased tail
  do crypto.getRandomValues(buf); while (buf[0] >= limit);
  return String(buf[0] % 1_000_000).padStart(6, '0');
}

export function constantTimeEqual(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/**
 * Passwords: PBKDF2-SHA256 over HMAC(pepper, password), with a random salt. The pepper is a
 * server secret (PASSWORD_PEPPER) that isn't in the database, so a stolen copy of the database
 * can't be cracked offline without it. The iteration count fits Cloudflare's free-plan CPU budget
 * (about 10 ms a request); it's stored in each hash, so it can be raised later and old hashes
 * still verify (and are upgraded on the next sign-in).
 *
 * Stored as pbkdf2-sha256$<iterations>$<salt b64url>$<hash b64url>.
 */
export const PASSWORD_ITERATIONS = 40_000;

export async function hashPassword(password, pepper, iterations = PASSWORD_ITERATIONS) {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const key = await derive(password, pepper, salt, iterations);
  return `pbkdf2-sha256$${iterations}$${base64url(salt)}$${base64url(key)}`;
}

/** Returns { ok, needsRehash }. */
export async function verifyPassword(password, stored, pepper) {
  const [scheme, iterations, salt, hash] = String(stored ?? '').split('$');
  if (scheme !== 'pbkdf2-sha256') return { ok: false, needsRehash: false };
  const key = await derive(password, pepper, fromBase64url(salt), Number(iterations));
  const ok = constantTimeEqual(base64url(key), hash);
  return { ok, needsRehash: ok && Number(iterations) < PASSWORD_ITERATIONS };
}

async function derive(password, pepper, salt, iterations) {
  const hmacKey = await crypto.subtle.importKey('raw', enc.encode(pepper || 'no-pepper-configured'), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const peppered = await crypto.subtle.sign('HMAC', hmacKey, enc.encode(password));
  const base = await crypto.subtle.importKey('raw', peppered, 'PBKDF2', false, ['deriveBits']);
  return crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations }, base, 256);
}
