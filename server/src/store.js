import { randomToken, sha256Hex } from './crypto.js';

/**
 * Accounts, sessions, reading positions and each account's library, over the async `Db` interface (D1 or node:sqlite).
 *
 * D1 has no interactive transactions, so the position compare-and-store is a compare-and-swap:
 * read, decide in JavaScript (UTF-16 device-id order, like the clients), then write only if the
 * row is still what was read, retrying if another write got there first. The outcome is the
 * same as doing it in one transaction.
 */
export class Store {
  /** @param {import('./db.js').Db} db */
  constructor(db) {
    this.db = db;
  }

  /** Brings the schema up to date. Safe to run on every start. */
  async migrate() {
    const db = this.db;
    await db.batch([
      ['CREATE TABLE IF NOT EXISTS accounts (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL)'],
      [`CREATE TABLE IF NOT EXISTS tokens (
          token_hash TEXT PRIMARY KEY,
          account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
          label TEXT NOT NULL DEFAULT '',
          created_at INTEGER NOT NULL)`],
      [`CREATE TABLE IF NOT EXISTS reading_positions (
          account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
          document_id TEXT NOT NULL,
          page_index INTEGER NOT NULL,
          page_offset REAL NOT NULL,
          horizontal_offset REAL NOT NULL,
          zoom REAL NOT NULL,
          page_count INTEGER NOT NULL,
          hlc INTEGER NOT NULL,
          device_id TEXT NOT NULL,
          schema_version INTEGER NOT NULL,
          updated_at INTEGER NOT NULL,
          PRIMARY KEY (account_id, document_id))`],
      [`CREATE TABLE IF NOT EXISTS verification_codes (
          email TEXT NOT NULL,
          purpose TEXT NOT NULL,
          code_hash TEXT NOT NULL,
          expires_at INTEGER NOT NULL,
          attempts INTEGER NOT NULL DEFAULT 0,
          created_at INTEGER NOT NULL,
          PRIMARY KEY (email, purpose))`],
      [`CREATE TABLE IF NOT EXISTS identities (
          provider TEXT NOT NULL,
          subject TEXT NOT NULL,
          account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
          email TEXT,
          created_at INTEGER NOT NULL,
          PRIMARY KEY (provider, subject))`],
      [`CREATE TABLE IF NOT EXISTS library_books (
          account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
          document_id TEXT NOT NULL,
          title TEXT NOT NULL,
          format TEXT NOT NULL,
          size_bytes INTEGER NOT NULL,
          page_count INTEGER NOT NULL,
          has_file INTEGER NOT NULL DEFAULT 0,
          added_at INTEGER NOT NULL,
          removed_at INTEGER,
          PRIMARY KEY (account_id, document_id))`],
      // Book files stored in the database (blobs.js dbBlobs), keyed "<account>/<documentId>".
      ['CREATE TABLE IF NOT EXISTS book_files (key TEXT PRIMARY KEY, size INTEGER NOT NULL, chunks INTEGER NOT NULL)'],
      ['CREATE TABLE IF NOT EXISTS book_chunks (key TEXT NOT NULL, idx INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY (key, idx))'],
      ['CREATE TABLE IF NOT EXISTS rate_events (key TEXT NOT NULL, at INTEGER NOT NULL)'],
      ['CREATE INDEX IF NOT EXISTS rate_events_key ON rate_events(key, at)'],
      [`CREATE TABLE IF NOT EXISTS dev_mail (
          id INTEGER PRIMARY KEY, to_addr TEXT NOT NULL, subject TEXT NOT NULL, text TEXT NOT NULL, created_at INTEGER NOT NULL)`],
    ]);
    // Accounts from before email sign-in have no email columns.
    try {
      await db.first('SELECT email, password_hash, email_verified_at FROM accounts LIMIT 0');
    } catch {
      await db.batch([
        ['ALTER TABLE accounts ADD COLUMN email TEXT'],
        ['ALTER TABLE accounts ADD COLUMN password_hash TEXT'],
        ['ALTER TABLE accounts ADD COLUMN email_verified_at INTEGER'],
      ]);
    }
    await db.run('CREATE UNIQUE INDEX IF NOT EXISTS accounts_email ON accounts(email) WHERE email IS NOT NULL');
  }

  // Accounts

  async createAccount(name) {
    return (await this.db.run('INSERT INTO accounts (name, created_at) VALUES (?, ?)', name, Date.now())).lastRowId;
  }

  async accountByName(name) {
    return this.db.first('SELECT * FROM accounts WHERE name = ?', name);
  }

  async accountByEmail(email) {
    return this.db.first('SELECT * FROM accounts WHERE email = ?', email);
  }

  async accountById(id) {
    return this.db.first('SELECT * FROM accounts WHERE id = ?', id);
  }

  /** Returns the new account's id, or null if the email was taken meanwhile. */
  async createEmailAccount(email, passwordHash, verifiedAt = null) {
    try {
      return (await this.db.run(
        'INSERT INTO accounts (name, email, password_hash, email_verified_at, created_at) VALUES (?, ?, ?, ?, ?)',
        email, email, passwordHash, verifiedAt, Date.now())).lastRowId;
    } catch (e) {
      if (/UNIQUE/i.test(String(e?.message ?? e))) return null;
      throw e;
    }
  }

  async setPassword(accountId, passwordHash) {
    await this.db.run('UPDATE accounts SET password_hash = ? WHERE id = ?', passwordHash, accountId);
  }

  async markVerified(accountId, now) {
    await this.db.run('UPDATE accounts SET email_verified_at = COALESCE(email_verified_at, ?) WHERE id = ?', now, accountId);
  }

  async listAccounts() {
    return this.db.all(`
      SELECT a.id, a.name, a.email, a.email_verified_at IS NOT NULL AS verified,
        (SELECT COUNT(*) FROM tokens t WHERE t.account_id = a.id) AS tokens,
        (SELECT COUNT(*) FROM reading_positions p WHERE p.account_id = a.id) AS positions,
        (SELECT COUNT(*) FROM library_books b WHERE b.account_id = a.id AND b.removed_at IS NULL) AS books
      FROM accounts a ORDER BY a.id`);
  }

  // Sessions (only token hashes are stored)

  async issueToken(accountId, label = '') {
    const token = randomToken();
    await this.db.run('INSERT INTO tokens (token_hash, account_id, label, created_at) VALUES (?, ?, ?, ?)',
      await sha256Hex(token), accountId, label, Date.now());
    return token;
  }

  async accountForToken(token) {
    if (!token) return null;
    const row = await this.db.first('SELECT account_id FROM tokens WHERE token_hash = ?', await sha256Hex(token));
    return row ? Number(row.account_id) : null;
  }

  async revokeToken(token) {
    await this.db.run('DELETE FROM tokens WHERE token_hash = ?', await sha256Hex(token));
  }

  async revokeTokens(accountId) {
    return (await this.db.run('DELETE FROM tokens WHERE account_id = ?', accountId)).changes;
  }

  // Verification codes (stored hashed)

  async putCode(email, purpose, codeHash, expiresAt, now) {
    await this.db.run(`INSERT OR REPLACE INTO verification_codes (email, purpose, code_hash, expires_at, attempts, created_at)
      VALUES (?, ?, ?, ?, 0, ?)`, email, purpose, codeHash, expiresAt, now);
  }

  async getCode(email, purpose) {
    return this.db.first('SELECT * FROM verification_codes WHERE email = ? AND purpose = ?', email, purpose);
  }

  async countFailedAttempt(email, purpose) {
    await this.db.run('UPDATE verification_codes SET attempts = attempts + 1 WHERE email = ? AND purpose = ?', email, purpose);
  }

  /** Deletes the code if it's still the one checked; false if another request used it first. */
  async consumeCode(email, purpose, codeHash) {
    return (await this.db.run('DELETE FROM verification_codes WHERE email = ? AND purpose = ? AND code_hash = ?',
      email, purpose, codeHash)).changes === 1;
  }

  // Apple / Google identities

  async accountForIdentity(provider, subject) {
    const row = await this.db.first('SELECT account_id FROM identities WHERE provider = ? AND subject = ?', provider, subject);
    return row ? Number(row.account_id) : null;
  }

  async linkIdentity(provider, subject, accountId, email, now) {
    await this.db.run('INSERT OR IGNORE INTO identities (provider, subject, account_id, email, created_at) VALUES (?, ?, ?, ?, ?)',
      provider, subject, accountId, email, now);
  }

  // Rate limiting (in the database, because Workers keep no memory between requests)

  /** How many events for `key` happened within `windowMs` before `now`, and the latest. */
  async rateEvents(key, now, windowMs) {
    const recent = await this.db.first('SELECT COUNT(*) AS n, MAX(at) AS last FROM rate_events WHERE key = ? AND at > ?', key, now - windowMs);
    return { count: Number(recent?.n ?? 0), last: recent?.last ?? null };
  }

  async recordRateEvent(key, now) {
    await this.db.run('INSERT INTO rate_events (key, at) VALUES (?, ?)', key, now);
    // Keep the table small: forget anything older than a day.
    if (Math.random() < 0.05) await this.db.run('DELETE FROM rate_events WHERE at < ?', now - 24 * 3600 * 1000);
  }

  async clearRateEvents(key) {
    await this.db.run('DELETE FROM rate_events WHERE key = ?', key);
  }

  // Development mail (when no email provider is configured)

  async saveDevMail(mail, now) {
    await this.db.run('INSERT INTO dev_mail (to_addr, subject, text, created_at) VALUES (?, ?, ?, ?)', mail.to, mail.subject, mail.text, now);
  }

  async devMail(to) {
    return this.db.all('SELECT to_addr AS "to", subject, text, created_at FROM dev_mail WHERE to_addr = ? ORDER BY id', to);
  }

  // Library: which books are in the account, so a book added on one device appears on the others.
  // A removed book stays as a tombstone (removed_at), so devices that have it remove it too.

  async library(accountId) {
    return (await this.db.all('SELECT * FROM library_books WHERE account_id = ? ORDER BY added_at', accountId)).map(toBook);
  }

  async book(accountId, documentId) {
    const row = await this.db.first('SELECT * FROM library_books WHERE account_id = ? AND document_id = ?', accountId, documentId);
    return row ? toBook(row) : null;
  }

  /** Adds the book, or brings back a removed one; updates the details of one already there. */
  async putBook(accountId, b, now) {
    await this.db.run(`INSERT INTO library_books (account_id, document_id, title, format, size_bytes, page_count, added_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (account_id, document_id) DO UPDATE SET title = excluded.title, format = excluded.format,
        size_bytes = excluded.size_bytes, page_count = excluded.page_count,
        added_at = CASE WHEN removed_at IS NULL THEN added_at ELSE excluded.added_at END, removed_at = NULL`,
      accountId, b.documentId, b.title, b.format, b.sizeBytes, b.pageCount, now);
    return this.book(accountId, b.documentId);
  }

  async setHasFile(accountId, documentId, hasFile) {
    await this.db.run('UPDATE library_books SET has_file = ? WHERE account_id = ? AND document_id = ?', hasFile ? 1 : 0, accountId, documentId);
  }

  /** Marks the book removed; false if the account never had it. */
  async removeBook(accountId, documentId, now) {
    return (await this.db.run('UPDATE library_books SET removed_at = ?, has_file = 0 WHERE account_id = ? AND document_id = ? AND removed_at IS NULL',
      now, accountId, documentId)).changes === 1;
  }

  // Positions

  async get(accountId, documentId) {
    const row = await this.db.first('SELECT * FROM reading_positions WHERE account_id = ? AND document_id = ?', accountId, documentId);
    return row ? toPosition(row) : null;
  }

  /**
   * The contract's compare-and-store: keep `incoming` only if its (hlc, deviceId) stamp is
   * greater than the stored one, and return whatever is stored afterwards.
   */
  async compareAndStore(accountId, incoming) {
    const p = incoming;
    for (let attempt = 0; attempt < 8; attempt++) {
      const existing = await this.get(accountId, p.documentId);
      if (existing && !isNewer(p, existing)) return existing;
      const values = [p.pageIndex, p.pageOffset, p.horizontalOffset, p.zoom, p.pageCount, p.hlc, p.deviceId, p.schemaVersion, Date.now()];
      let changes;
      if (!existing) {
        ({ changes } = await this.db.run(`INSERT OR IGNORE INTO reading_positions (account_id, document_id, page_index, page_offset,
            horizontal_offset, zoom, page_count, hlc, device_id, schema_version, updated_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, accountId, p.documentId, ...values));
      } else {
        // Only if nobody wrote since we read: otherwise re-read and decide again.
        ({ changes } = await this.db.run(`UPDATE reading_positions SET page_index = ?, page_offset = ?, horizontal_offset = ?,
            zoom = ?, page_count = ?, hlc = ?, device_id = ?, schema_version = ?, updated_at = ?
          WHERE account_id = ? AND document_id = ? AND hlc = ? AND device_id = ?`,
          ...values, accountId, p.documentId, existing.hlc, existing.deviceId));
      }
      if (changes === 1) return p;
    }
    throw new Error('compareAndStore: too much contention');
  }
}

/**
 * Last writer wins by (hlc, deviceId). Device ids compare by UTF-16 code units: JavaScript's
 * string `<` does exactly that, as do Kotlin's String.compareTo and the Mac app's Stamp.
 */
export function isNewer(a, b) {
  if (a.hlc !== b.hlc) return a.hlc > b.hlc;
  return a.deviceId > b.deviceId;
}

function toBook(row) {
  return {
    documentId: row.document_id,
    title: row.title,
    format: row.format,
    sizeBytes: Number(row.size_bytes),
    pageCount: Number(row.page_count),
    hasFile: Boolean(row.has_file),
    addedAt: Number(row.added_at),
    removedAt: row.removed_at === null || row.removed_at === undefined ? null : Number(row.removed_at),
  };
}

function toPosition(row) {
  return {
    documentId: row.document_id,
    pageIndex: Number(row.page_index),
    pageOffset: row.page_offset,
    horizontalOffset: row.horizontal_offset,
    zoom: row.zoom,
    pageCount: Number(row.page_count),
    hlc: Number(row.hlc),
    deviceId: row.device_id,
    schemaVersion: Number(row.schema_version),
  };
}
