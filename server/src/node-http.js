import { createServer } from 'node:http';

/** Serves a fetch-style handler (worker.js) with Node's HTTP server. */
export function serve(handler, { log = () => {} } = {}) {
  return createServer(async (req, res) => {
    const started = Date.now();
    try {
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      const body = chunks.length ? Buffer.concat(chunks) : undefined;
      const headers = new Headers();
      for (const [k, v] of Object.entries(req.headers)) if (v !== undefined) headers.set(k, Array.isArray(v) ? v.join(', ') : v);
      const request = new Request(`http://${req.headers.host ?? 'localhost'}${req.url}`, {
        method: req.method,
        headers,
        body: req.method === 'GET' || req.method === 'HEAD' ? undefined : body,
      });
      const response = await handler(request);
      const out = Buffer.from(await response.arrayBuffer());
      res.writeHead(response.status, { ...Object.fromEntries(response.headers), 'Content-Length': out.length });
      res.end(out);
      log(`${req.method} ${req.url.split('?')[0]} ${response.status} ${Date.now() - started}ms`);
    } catch (e) {
      log(`error: ${e?.stack ?? e}`);
      if (!res.headersSent) res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end('{"error":"internal error"}');
    }
  });
}
