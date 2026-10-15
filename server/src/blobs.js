/**
 * Where book files are kept:
 *
 *  - the database itself (the default, D1 or node:sqlite), in 1 MB chunks: no extra service to
 *    set up, but the files share the database's size limit (500 MB on D1's free plan);
 *  - Cloudflare R2, when a `BOOKS` bucket is bound in wrangler.toml (needs R2 enabled on the
 *    account). R2 also checks each upload against its SHA-256 and throws `DigestMismatch`.
 *
 * The database store doesn't hash uploads (a Worker on the free plan gets ~10 ms of CPU, too
 * little to hash a large book); the server checks the size, and the apps hash every book they
 * download before importing it.
 *
 * @typedef {{
 *   put(key: string, body: ReadableStream | ArrayBuffer | Uint8Array, opts: { sha256: string }): Promise<void>,
 *   get(key: string): Promise<{ body: ReadableStream | Uint8Array, size: number } | null>,
 *   delete(key: string): Promise<void>,
 * }} Blobs
 */

export class DigestMismatch extends Error {}

/** @returns {Blobs} */
export function r2Blobs(bucket) {
  return {
    async put(key, body, { sha256 }) {
      try {
        // R2 checks the checksum itself and rejects the upload if it doesn't match.
        await bucket.put(key, body, { sha256 });
      } catch (e) {
        if (/digest|checksum|sha-?256/i.test(String(e?.message ?? e))) throw new DigestMismatch();
        throw e;
      }
    },
    async get(key) {
      const obj = await bucket.get(key);
      return obj ? { body: obj.body, size: obj.size } : null;
    },
    async delete(key) {
      await bucket.delete(key);
    },
  };
}

/** D1 allows rows up to 2 MB. */
const CHUNK = 1024 * 1024;

/** Book files in the database, split into chunks. @param {import('./db.js').Db} db @returns {Blobs} */
export function dbBlobs(db) {
  return {
    async put(key, body) {
      // Replace any earlier upload; the file row is written last, so a failed upload leaves none.
      await db.batch([['DELETE FROM book_files WHERE key = ?', key], ['DELETE FROM book_chunks WHERE key = ?', key]]);
      let index = 0;
      let size = 0;
      let buffer = new Uint8Array(CHUNK);
      let filled = 0;
      const flush = async () => {
        if (filled === 0) return;
        await db.run('INSERT INTO book_chunks (key, idx, data) VALUES (?, ?, ?)', key, index++, buffer.slice(0, filled));
        size += filled;
        filled = 0;
      };
      const reader = (body instanceof ReadableStream ? body : new Response(body).body).getReader();
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        let offset = 0;
        while (offset < value.length) {
          const n = Math.min(CHUNK - filled, value.length - offset);
          buffer.set(value.subarray(offset, offset + n), filled);
          filled += n;
          offset += n;
          if (filled === CHUNK) {
            await flush();
            buffer = new Uint8Array(CHUNK);
          }
        }
      }
      await flush();
      await db.run('INSERT INTO book_files (key, size, chunks) VALUES (?, ?, ?)', key, size, index);
    },
    async get(key) {
      const file = await db.first('SELECT size, chunks FROM book_files WHERE key = ?', key);
      if (!file) return null;
      const chunks = Number(file.chunks);
      let next = 0;
      // Streamed a chunk at a time, so a large book never sits in memory whole.
      const body = new ReadableStream({
        async pull(controller) {
          if (next >= chunks) return controller.close();
          const row = await db.first('SELECT data FROM book_chunks WHERE key = ? AND idx = ?', key, next++);
          if (!row) return controller.error(new Error(`missing chunk ${next - 1} of ${key}`));
          controller.enqueue(new Uint8Array(row.data));
        },
      });
      return { body, size: Number(file.size) };
    },
    async delete(key) {
      await db.batch([['DELETE FROM book_files WHERE key = ?', key], ['DELETE FROM book_chunks WHERE key = ?', key]]);
    },
  };
}
