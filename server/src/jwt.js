import { fromBase64url } from './crypto.js';

/**
 * Verifies OpenID Connect ID tokens from Apple and Google with WebCrypto: RS256 signature
 * against the provider's published keys (cached), issuer, audience (our app's client ids) and
 * expiry.
 */
export const PROVIDERS = {
  google: {
    jwks: 'https://www.googleapis.com/oauth2/v3/certs',
    issuers: ['https://accounts.google.com', 'accounts.google.com'],
  },
  apple: {
    jwks: 'https://appleid.apple.com/auth/keys',
    issuers: ['https://appleid.apple.com'],
  },
};

export class KeySet {
  /** `fetchKeys(url)` returns `{ keys: [...] }`; injectable for tests. */
  constructor(fetchKeys = defaultFetch, ttlMs = 60 * 60 * 1000) {
    this.fetchKeys = fetchKeys;
    this.ttlMs = ttlMs;
    this.cache = new Map();
  }

  async key(url, kid) {
    let entry = this.cache.get(url);
    if (!entry || Date.now() - entry.at > this.ttlMs || !entry.keys.some((k) => k.kid === kid)) {
      // Unknown kid: the provider rotated keys, so refetch.
      entry = { at: Date.now(), keys: (await this.fetchKeys(url)).keys ?? [] };
      this.cache.set(url, entry);
    }
    const jwk = entry.keys.find((k) => k.kid === kid);
    if (!jwk) return null;
    return crypto.subtle.importKey('jwk', { kty: jwk.kty, n: jwk.n, e: jwk.e, alg: 'RS256', ext: true },
      { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
  }
}

async function defaultFetch(url) {
  const res = await fetch(url, { signal: AbortSignal.timeout(10_000) });
  if (!res.ok) throw new Error(`fetching ${url}: HTTP ${res.status}`);
  return res.json();
}

export class TokenError extends Error {}

/** Returns the verified claims, or throws TokenError. */
export async function verifyIdToken(provider, idToken, { audiences, keys, now = Date.now() }) {
  const config = PROVIDERS[provider];
  if (!config) throw new TokenError('unknown provider');
  if (!audiences?.length) throw new TokenError(`${provider} sign-in isn't configured on this server`);
  const parts = typeof idToken === 'string' ? idToken.split('.') : [];
  if (parts.length !== 3) throw new TokenError('malformed token');
  let header, claims;
  try {
    const text = (s) => new TextDecoder().decode(fromBase64url(s));
    header = JSON.parse(text(parts[0]));
    claims = JSON.parse(text(parts[1]));
  } catch {
    throw new TokenError('malformed token');
  }
  if (header.alg !== 'RS256') throw new TokenError('unexpected signing algorithm');
  const key = await keys.key(config.jwks, header.kid);
  if (!key) throw new TokenError('unknown signing key');
  let signature;
  try {
    signature = fromBase64url(parts[2]);
  } catch {
    throw new TokenError('malformed token');
  }
  const valid = await crypto.subtle.verify('RSASSA-PKCS1-v1_5', key, signature, new TextEncoder().encode(`${parts[0]}.${parts[1]}`));
  if (!valid) throw new TokenError('bad signature');
  if (!config.issuers.includes(claims.iss)) throw new TokenError('wrong issuer');
  const aud = Array.isArray(claims.aud) ? claims.aud : [claims.aud];
  if (!aud.some((a) => audiences.includes(a))) throw new TokenError('token is for a different app');
  const nowSec = now / 1000;
  if (typeof claims.exp !== 'number' || claims.exp < nowSec - 60) throw new TokenError('token expired');
  if (typeof claims.iat === 'number' && claims.iat > nowSec + 300) throw new TokenError('token issued in the future');
  if (typeof claims.sub !== 'string' || !claims.sub) throw new TokenError('token has no subject');
  return claims;
}
