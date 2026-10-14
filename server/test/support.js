import { spawn } from 'node:child_process';
import { generateKeyPairSync } from 'node:crypto';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { nodeSqlite } from '../src/db.js';
import { KeySet } from '../src/jwt.js';
import { serve } from '../src/node-http.js';
import { Store } from '../src/store.js';
import { createHandler } from '../src/worker.js';

export const GOOGLE_CLIENT = 'mac-client.apps.googleusercontent.com';
export const APPLE_BUNDLE = 'io.github.satyanvm.reader';

// Stand-ins for Apple's and Google's signing keys.
export const signingKey = generateKeyPairSync('rsa', { modulusLength: 2048 });
export const otherKey = generateKeyPairSync('rsa', { modulusLength: 2048 });
export const jwks = { keys: [{ ...signingKey.publicKey.export({ format: 'jwk' }), kid: 'test-key', alg: 'RS256', use: 'sig' }] };

const env = {
  TEST_MODE: '1',
  DEV_MAIL: '1',
  GOOGLE_CLIENT_IDS: GOOGLE_CLIENT,
  APPLE_AUDIENCES: APPLE_BUNDLE,
  PASSWORD_PEPPER: 'test-pepper',
};

/**
 * Starts the server under test and returns its base URL. TEST_TARGET=worker runs the real
 * Workers runtime with a local D1 (`wrangler dev`); otherwise the same handler runs in-process
 * on Node with an in-memory SQLite database.
 */
export async function startServer(extraEnv = {}) {
  if (process.env.TEST_TARGET === 'worker') return startWorker(extraEnv);
  const db = await nodeSqlite(':memory:');
  const store = new Store(db);
  await store.migrate();
  const handler = createHandler({ store, env: { ...env, ...extraEnv }, keys: new KeySet(async () => jwks), log: () => {} });
  const server = serve(handler);
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  return {
    base: `http://127.0.0.1:${server.address().port}`,
    stop: () => new Promise((r) => server.close(() => { db.close(); r(); })),
  };
}

async function startWorker(extraEnv) {
  const root = join(dirname(fileURLToPath(import.meta.url)), '..');
  const work = mkdtempSync(join(tmpdir(), 'reader-worker-test-'));
  const port = 8900 + Math.floor(Math.random() * 90);
  const vars = Object.entries({ ...env, ...extraEnv, TEST_JWKS: JSON.stringify(jwks) })
    .map(([k, v]) => `${k} = ${JSON.stringify(v)}`).join('\n');
  writeFileSync(join(work, 'wrangler.toml'), `name = "reader-sync-test"
main = ${JSON.stringify(join(root, 'src/worker.js'))}
compatibility_date = "2025-09-01"

[vars]
${vars}

[[d1_databases]]
binding = "DB"
database_name = "reader-sync-test"
database_id = "00000000-0000-0000-0000-000000000000"
`);
  const child = spawn(join(root, 'node_modules/.bin/wrangler'),
    ['dev', '-c', join(work, 'wrangler.toml'), '--local', '--port', String(port), '--persist-to', join(work, 'state'), '--log-level', 'error'],
    { cwd: work, stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, WRANGLER_SEND_METRICS: 'false' } });
  let output = '';
  child.stdout.on('data', (d) => (output += d));
  child.stderr.on('data', (d) => (output += d));
  const base = `http://127.0.0.1:${port}`;
  for (let i = 0; i < 300; i++) {
    try {
      if ((await fetch(`${base}/healthz`)).ok) break;
    } catch {}
    if (child.exitCode !== null) throw new Error(`wrangler dev exited:\n${output}`);
    await new Promise((r) => setTimeout(r, 100));
    if (i === 299) throw new Error(`wrangler dev didn't start:\n${output}`);
  }
  return {
    base,
    stop: () => new Promise((r) => { child.once('exit', r); child.kill('SIGTERM'); }),
  };
}

/** A small HTTP client: JSON in and out, optional bearer token and test clock. */
export function client(base) {
  return async function call(method, path, { token, body, now, raw } = {}) {
    const res = await fetch(base + path, {
      method,
      headers: {
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(now ? { 'x-test-now': String(now) } : {}),
      },
      body: body === undefined ? undefined : raw ? body : JSON.stringify(body),
    });
    const text = await res.text();
    let json = null;
    try {
      json = text ? JSON.parse(text) : null;
    } catch {}
    return { status: res.status, body: json, raw: text };
  };
}
