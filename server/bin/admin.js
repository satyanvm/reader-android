#!/usr/bin/env node
// Administration of the local (Node) server's database. For Cloudflare D1, use
// `npx wrangler d1 execute reader-sync --remote --command "<SQL>"`.
//   admin.js list                       accounts, sessions and positions
//   admin.js add-account <name>         a token-only account for scripts; prints its token
//   admin.js issue-token <name>         another token for that account
//   admin.js revoke-tokens <name|email> sign the account out everywhere
//   admin.js grant-pro <email>          Pro without payment (owner, testers); never expires
//   admin.js revoke-pro <email>         takes that back (paid subscriptions are untouched)
//   admin.js grant-pro-sql <email>      prints the same grant as a D1 command for the live server
import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { nodeSqlite } from '../src/db.js';
import { Store } from '../src/store.js';

const dbPath = process.env.READER_DB ?? 'data/reader-sync.sqlite';
mkdirSync(dirname(dbPath), { recursive: true });
const db = await nodeSqlite(dbPath);
const store = new Store(db);
await store.migrate();
const [command, name] = process.argv.slice(2);
const fail = (message) => { console.error(message); process.exit(1); };
const account = async () => (await store.accountByName(name)) ?? (await store.accountByEmail(name)) ?? fail(`no account ${name}`);

switch (command) {
  case 'list':
    console.table(await store.listAccounts());
    break;
  case 'add-account':
    if (!name) fail('usage: add-account <name>');
    if (await store.accountByName(name)) fail(`account ${name} already exists`);
    console.log(await store.issueToken(await store.createAccount(name), 'script'));
    break;
  case 'issue-token':
    console.log(await store.issueToken((await account()).id, 'script'));
    break;
  case 'revoke-tokens':
    console.log(`revoked ${await store.revokeTokens((await account()).id)} session(s)`);
    break;
  case 'grant-pro':
    await store.grantPro((await account()).id, Date.now());
    console.log(`${name} has Pro`);
    break;
  case 'revoke-pro':
    console.log(`removed ${await store.revokePro((await account()).id)} manual grant(s)`);
    break;
  case 'grant-pro-sql': {
    if (!/^[^\s'@]+@[^\s'@]+$/.test(name ?? '')) fail('usage: grant-pro-sql <email>');
    const email = name.toLowerCase();
    const sql = `INSERT INTO subscriptions (provider, subscription_id, account_id, status, variant_name, provider_updated_at, updated_at) `
      + `SELECT 'manual', 'admin-' || id, id, 'active', NULL, unixepoch() * 1000, unixepoch() * 1000 FROM accounts WHERE email = '${email}' `
      + `ON CONFLICT (provider, subscription_id) DO UPDATE SET status = 'active', updated_at = excluded.updated_at;`;
    console.log(`npx wrangler d1 execute reader --remote --command "${sql}"`);
    break;
  }
  default:
    fail('usage: admin.js list | add-account <name> | issue-token <name> | revoke-tokens <name|email> | grant-pro <email> | revoke-pro <email> | grant-pro-sql <email>');
}
db.close();
