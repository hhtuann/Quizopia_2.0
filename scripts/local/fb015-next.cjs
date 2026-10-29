// Production Next server with a streaming, same-origin public-edge proxy.
// Every API response comes from the actual disposable Gateway; no response doubles.
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const frontend = path.resolve(__dirname, '../../frontend');

function checkBuild() {
  if (!fs.existsSync(path.join(frontend, '.next/BUILD_ID'))) throw new Error('Build frontend first');
  let helpers = 0;
  function scan(directory) {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const file = path.join(directory, entry.name);
      if (entry.isDirectory()) { scan(file); continue; }
      if (!entry.name.endsWith('.js')) continue;
      const source = fs.readFileSync(file, 'utf8');
      const index = source.indexOf('Public API path must start');
      if (index < 0) continue;
      helpers++;
      const start = source.lastIndexOf('function', index);
      const helper = source.slice(Math.max(start, index - 400), index + 250);
      if (process.argv.includes('--check')) console.log(helper);
      // NEXT_PUBLIC_* is baked at build time, not reconfigured by next start.
      if (/https?:\/\//.test(helper)) throw new Error('FB015 UI requires a relative-API frontend build; do not target an existing runtime');
    }
  }
  scan(path.join(frontend, '.next/static/chunks'));
  if (!helpers) throw new Error('Cannot verify compiled public API target');
}

async function main() {
  checkBuild();
  if (process.argv.includes('--check')) return;
  const gateway = new URL(process.env.FB015_GATEWAY_URL);
  const port = Number(process.env.FB015_UI_PORT);
  if (gateway.hostname !== '127.0.0.1' || gateway.protocol !== 'http:' || !port) throw new Error('Disposable loopback origins required');
  const next = require(path.join(frontend, 'node_modules/next'));
  const app = next({ dev: false, dir: frontend, hostname: '127.0.0.1', port });
  await app.prepare();
  const handle = app.getRequestHandler();
  const server = http.createServer((request, response) => {
    const pathname = new URL(request.url, 'http://127.0.0.1').pathname;
    if (!pathname.startsWith('/api/') && pathname !== '/classrooms' && !pathname.startsWith('/classrooms/')) {
      handle(request, response);
      return;
    }
    // Preserve Origin, cookies, authorization and Idempotency-Key. Never log them.
    const upstream = http.request(new URL(request.url, gateway), {
      method: request.method, headers: { ...request.headers, host: gateway.host }, timeout: 20000,
    }, incoming => {
      console.log(`FB015 edge ${request.method} ${pathname} ${incoming.statusCode}`);
      response.writeHead(incoming.statusCode, incoming.headers);
      incoming.pipe(response);
    });
    upstream.on('timeout', () => upstream.destroy());
    upstream.on('error', () => { if (!response.headersSent) response.writeHead(502); response.end(); });
    request.on('aborted', () => upstream.destroy());
    request.pipe(upstream);
  });
  server.listen(port, '127.0.0.1', () => console.log('FB015 Next UI ready'));
  async function close() { server.close(); await app.close(); process.exit(0); }
  process.once('SIGTERM', close);
  process.once('SIGINT', close);
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
