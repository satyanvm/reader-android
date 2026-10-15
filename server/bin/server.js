#!/usr/bin/env node
// Runs the sync server locally with Node and SQLite: the same handler that runs on Cloudflare
// (src/worker.js). Configuration (environment):
//   READER_DB            SQLite file (default ./data/reader-sync.sqlite)
//   PORT, HOST           default 8787, 127.0.0.1 (0.0.0.0 to accept other machines; put TLS in front)
//   PASSWORD_PEPPER      secret mixed into password hashes (keep it, or passwords stop working)
//   EMAIL_CODES          1: email + password accounts need an emailed code (otherwise no code)
//   RESEND_API_KEY, MAIL_FROM   send account emails with Resend (otherwise they're only logged)
//   READER_DEV_MAIL_DIR  development: also write each email to this folder
//   GOOGLE_CLIENT_IDS, APPLE_AUDIENCES   comma-separated, turn on Google / Apple sign-in
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { nodeSqlite } from '../src/db.js';
import { dbBlobs } from '../src/blobs.js';
import { serve } from '../src/node-http.js';
import { Store } from '../src/store.js';
import { createHandler } from '../src/worker.js';

const dbPath = process.env.READER_DB ?? 'data/reader-sync.sqlite';
mkdirSync(dirname(dbPath), { recursive: true });
const db = await nodeSqlite(dbPath);
const store = new Store(db);
await store.migrate();

const log = (line) => console.log(new Date().toISOString(), line);
const mailDir = process.env.READER_DEV_MAIL_DIR;
if (mailDir) mkdirSync(mailDir, { recursive: true });
const devSink = mailDir ? async (mail) => writeFileSync(join(mailDir, `${Date.now()}-${mail.to}.json`), JSON.stringify(mail)) : undefined;
const blobs = dbBlobs(db);
const handler = createHandler({ store, blobs, env: process.env, log, devSink });

if (process.env.EMAIL_CODES !== '1') log('email codes: off, email + password sign-in needs no code (EMAIL_CODES=1 turns them on)');
if (!process.env.RESEND_API_KEY) log('email: development mode, codes are only logged (set RESEND_API_KEY to send them)');
if (!process.env.PASSWORD_PEPPER) log('warning: PASSWORD_PEPPER is not set; fine for local testing only');
const on = (v) => (v ? 'on' : 'off');
log(`sign in with Apple: ${on(process.env.APPLE_AUDIENCES)}, Google: ${on(process.env.GOOGLE_CLIENT_IDS)}`);

const port = Number(process.env.PORT ?? 8787);
const host = process.env.HOST ?? '127.0.0.1';
const server = serve(handler, { log });
server.listen(port, host, () => log(`reader sync server on http://${host}:${port} (db ${dbPath})`));
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => server.close(() => { db.close(); process.exit(0); }));
}
