/**
 * The small async database interface the server uses, with two implementations:
 *
 *  - Cloudflare D1 (production, and `wrangler dev` locally);
 *  - node:sqlite (`npm start` and the Node test run).
 *
 * Both are SQLite, so the SQL is the same. D1 has no interactive transactions; writes that must
 * not race use single statements or compare-and-swap (see store.js), and `batch` runs several
 * statements atomically.
 */

/** @typedef {{ first(sql: string, ...args: any[]): Promise<any>, all(sql: string, ...args: any[]): Promise<any[]>, run(sql: string, ...args: any[]): Promise<{ changes: number, lastRowId: number }>, batch(statements: [string, ...any[]][]): Promise<void> }} Db */

/** @returns {Db} */
export function d1(database) {
  const stmt = (sql, args) => database.prepare(sql).bind(...args.map(nullify));
  return {
    async first(sql, ...args) {
      return (await stmt(sql, args).first()) ?? null;
    },
    async all(sql, ...args) {
      return (await stmt(sql, args).all()).results ?? [];
    },
    async run(sql, ...args) {
      const res = await stmt(sql, args).run();
      return { changes: res.meta?.changes ?? 0, lastRowId: res.meta?.last_row_id ?? 0 };
    },
    async batch(statements) {
      await database.batch(statements.map(([sql, ...args]) => stmt(sql, args)));
    },
  };
}

/** @returns {Promise<Db>} */
export async function nodeSqlite(path) {
  const { DatabaseSync } = await import('node:sqlite');
  const db = new DatabaseSync(path);
  db.exec('PRAGMA journal_mode = WAL');
  db.exec('PRAGMA synchronous = FULL');
  db.exec('PRAGMA foreign_keys = ON');
  db.exec('PRAGMA busy_timeout = 5000');
  const plain = (row) => (row ? Object.fromEntries(Object.entries(row).map(([k, v]) => [k, typeof v === 'bigint' ? Number(v) : v])) : null);
  return {
    raw: db,
    async first(sql, ...args) {
      return plain(db.prepare(sql).get(...args.map(nullify)));
    },
    async all(sql, ...args) {
      return db.prepare(sql).all(...args.map(nullify)).map(plain);
    },
    async run(sql, ...args) {
      const info = db.prepare(sql).run(...args.map(nullify));
      return { changes: Number(info.changes), lastRowId: Number(info.lastInsertRowid) };
    },
    async batch(statements) {
      db.exec('BEGIN IMMEDIATE');
      try {
        for (const [sql, ...args] of statements) db.prepare(sql).run(...args.map(nullify));
        db.exec('COMMIT');
      } catch (e) {
        db.exec('ROLLBACK');
        throw e;
      }
    },
    close() {
      db.close();
    },
  };
}

const nullify = (v) => (v === undefined ? null : v);
