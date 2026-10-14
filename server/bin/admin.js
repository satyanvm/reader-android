#!/usr/bin/env node
// Administration of the local (Node) server's database. For Cloudflare D1, use
// `npx wrangler d1 execute reader-sync --remote --command "<SQL>"`.
//   admin.js list                       accounts, sessions and positions
//   admin.js add-account <name>         a token-only account for scripts; prints its token
//   admin.js issue-token <name>         another token for that account
//   admin.js revoke-tokens <name|email> sign the account out everywhere
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
  default:
    fail('usage: admin.js list | add-account <name> | issue-token <name> | revoke-tokens <name|email>');
}
db.close();
