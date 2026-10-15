'use strict';

/*
 * QRIS Alert server
 * - Menerima data pembayaran dari aplikasi Android (POST /api/payment, butuh App token)
 * - Meneruskannya secara realtime ke widget OBS (GET /api/stream, Server-Sent Events, butuh widget key)
 * - Membalas pencarian dari tombol "Cari" di aplikasi (UDP 39300, hanya jaringan lokal)
 * - Memberi App token ke HP setelah disetujui di dashboard (POST /api/pair)
 * Tanpa dependensi npm. Cukup Node.js 18+.
 */

if (Number(process.versions.node.split('.')[0]) < 18) {
  console.error(`QRIS Alert butuh Node.js versi 18 atau lebih baru. Versi terpasang: ${process.version}`);
  process.exit(1);
}

const http = require('http');
const dgram = require('dgram');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const os = require('os');
const { spawn } = require('child_process');

// node server.js           jalankan server
// node server.js --open    jalankan server lalu buka dashboard di browser
// node server.js --stop    hentikan server yang sedang berjalan
const ARGS = new Set(process.argv.slice(2));
const OPEN_DASHBOARD = ARGS.has('--open');
const STOP = ARGS.has('--stop');

const DATA_DIR = path.join(__dirname, 'data');
const CONFIG_FILE = path.join(DATA_DIR, 'config.json');
const LOG_FILE = path.join(DATA_DIR, 'payments.jsonl');
const PUBLIC_DIR = path.join(__dirname, 'public');

fs.mkdirSync(DATA_DIR, { recursive: true });

// ---------- Konfigurasi & token ----------

function loadConfig() {
  let cfg = {};
  if (fs.existsSync(CONFIG_FILE)) {
    try { cfg = JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8')); } catch { cfg = {}; }
  }
  let changed = false;
  if (!cfg.appToken) { cfg.appToken = crypto.randomBytes(18).toString('base64url'); changed = true; }
  if (!cfg.widgetKey) { cfg.widgetKey = crypto.randomBytes(9).toString('base64url'); changed = true; }
  if (changed) fs.writeFileSync(CONFIG_FILE, JSON.stringify(cfg, null, 2));
  return {
    appToken: process.env.APP_TOKEN || cfg.appToken,   // rahasia: hanya untuk aplikasi HP
    widgetKey: process.env.WIDGET_KEY || cfg.widgetKey, // hanya bisa membaca, untuk URL widget
    port: Number(process.env.PORT) || Number(cfg.port) || 3000,
  };
}

const config = loadConfig();
const PORT = config.port;
const DASHBOARD_URL = `http://localhost:${PORT}/#key=${config.widgetKey}`;

// Port UDP tetap untuk tombol "Cari" di aplikasi, tidak ikut berubah bila port HTTP diganti
const DISCOVERY_PORT = 39300;
const DISCOVERY_ASK = 'qris-alert?';

// ---------- Penyimpanan pembayaran hari ini ----------

function dayKey(ts) {
  const d = new Date(ts);
  return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`;
}

let payments = [];             // pembayaran hari ini, terlama di depan
const seenIds = new Map();     // id -> waktu diterima, untuk mencegah dobel

function loadToday() {
  if (!fs.existsSync(LOG_FILE)) return;
  const today = dayKey(Date.now());
  for (const line of fs.readFileSync(LOG_FILE, 'utf8').split('\n')) {
    if (!line.trim()) continue;
    try {
      const p = JSON.parse(line);
      seenIds.set(p.id, p.receivedAt);
      if (dayKey(p.receivedAt) === today) payments.push(p);
    } catch { /* abaikan baris rusak */ }
  }
}
loadToday();

function todayPayments() {
  const today = dayKey(Date.now());
  if (payments.length && dayKey(payments[0].receivedAt) !== today) {
    payments = payments.filter((p) => dayKey(p.receivedAt) === today);
  }
  return payments;
}

function publicView(p) {
  // Teks notifikasi asli (bisa berisi nama pembayar) TIDAK dikirim ke widget.
  return { id: p.id, amount: p.amount, source: p.source, receivedAt: p.receivedAt, test: !!p.test };
}

function summary() {
  const list = todayPayments();
  return {
    total: list.reduce((sum, p) => sum + p.amount, 0),
    count: list.length,
    recent: list.slice(-15).reverse().map(publicView),
  };
}

// Bersihkan id lama setiap jam
setInterval(() => {
  const limit = Date.now() - 2 * 24 * 60 * 60 * 1000;
  for (const [id, ts] of seenIds) if (ts < limit) seenIds.delete(id);
}, 60 * 60 * 1000).unref();

// ---------- Server-Sent Events ----------

const clients = new Set();

function broadcast(event, data) {
  const msg = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const res of clients) res.write(msg);
}

function widgetCount() {
  let n = 0;
  for (const res of clients) if (!res.isDashboard) n += 1;
  return n;
}

setInterval(() => {
  for (const res of clients) res.write(': ping\n\n');
}, 20000).unref();

// ---------- Helper HTTP ----------

function sendJson(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(body));
}

function safeEqual(a, b) {
  const ab = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  return ab.length === bb.length && crypto.timingSafeEqual(ab, bb);
}

function hasWidgetKey(url) {
  return safeEqual(url.searchParams.get('key') || '', config.widgetKey);
}

function readJson(req, limit = 16 * 1024) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) { reject(new Error('body_too_large')); req.destroy(); }
      else chunks.push(c);
    });
    req.on('end', () => {
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')); }
      catch { reject(new Error('invalid_json')); }
    });
    req.on('error', reject);
  });
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
};

function serveStatic(res, pathname) {
  const routes = { '/': 'index.html', '/widget': 'widget.html' };
  const rel = routes[pathname] || decodeURIComponent(pathname).replace(/^\/+/, '');
  const file = path.join(PUBLIC_DIR, rel);
  if (!file.startsWith(PUBLIC_DIR + path.sep)) return sendJson(res, 403, { error: 'forbidden' });
  fs.readFile(file, (err, data) => {
    if (err) return sendJson(res, 404, { error: 'not_found' });
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-cache' });
    res.end(data);
  });
}

// ---------- Endpoint ----------

// Terima angka (50000), teks ("Rp50.000,00"), atau ambil dari teks notifikasi utuh
function parseAmount(value, text) {
  let raw = value;
  if (raw === undefined || raw === null || raw === '') {
    const m = /(?:Rp|IDR)\.?\s?(\d[\d.,]*)/i.exec(String(text || ''));
    if (!m) return NaN;
    raw = m[1];
  }
  if (typeof raw === 'number') return Math.round(raw);
  const digits = String(raw).trim().replace(/[.,]+$/, '').replace(/[.,]\d{2}$/, '').replace(/\D/g, '');
  return digits ? Number(digits) : NaN;
}

async function handlePayment(req, res) {
  const auth = req.headers.authorization || '';
  const token = auth.startsWith('Bearer ') ? auth.slice(7).trim() : '';
  if (!safeEqual(token, config.appToken)) return sendJson(res, 401, { error: 'invalid_token' });

  let body;
  try { body = await readJson(req); } catch (e) { return sendJson(res, 400, { error: e.message }); }

  const amount = parseAmount(body.amount, body.text);
  if (!Number.isFinite(amount) || amount <= 0 || amount > 1e12) {
    return sendJson(res, 400, { error: 'invalid_amount' });
  }

  const id = String(body.id || crypto.randomUUID()).slice(0, 64);
  if (seenIds.has(id)) return sendJson(res, 200, { ok: true, duplicate: true });

  const now = Date.now();
  const payment = {
    id,
    amount,
    source: String(body.source || 'QRIS').slice(0, 40),
    text: String(body.text || '').slice(0, 300),
    sentAt: Number(body.time) || now,
    receivedAt: now,
    test: body.test === true,
  };
  seenIds.set(id, now);

  // Pembayaran tes hanya ditampilkan, tidak dihitung dan tidak disimpan.
  if (!payment.test) {
    todayPayments().push(payment);
    fs.appendFile(LOG_FILE, JSON.stringify(payment) + '\n', (err) => {
      if (err) console.error('Gagal menulis log:', err.message);
    });
  }

  const s = summary();
  broadcast('payment', { payment: publicView(payment), total: s.total, count: s.count });

  const widgets = widgetCount();
  const time = new Date(now).toLocaleTimeString('id-ID');
  console.log(`[${time}] ${payment.test ? '[tes] ' : ''}${payment.source} Rp${amount.toLocaleString('id-ID')}  (widget terbuka: ${widgets})`);
  sendJson(res, 200, { ok: true, total: s.total, count: s.count, widgets });
}

function handleStream(req, res, url) {
  if (!hasWidgetKey(url)) return sendJson(res, 401, { error: 'invalid_key' });
  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache, no-transform',
    Connection: 'keep-alive',
    'X-Accel-Buffering': 'no',
  });
  const s = summary();
  res.write('retry: 3000\n\n');
  res.write(`event: hello\ndata: ${JSON.stringify({ total: s.total, count: s.count })}\n\n`);
  res.isDashboard = url.searchParams.get('role') === 'dashboard';
  clients.add(res);
  req.on('close', () => clients.delete(res));
}

// Permintaan dari komputer ini sendiri, bukan lewat tunnel atau proxy
function isLocal(req) {
  const local = ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(req.socket.remoteAddress || '');
  const proxied = req.headers['x-forwarded-for'] || req.headers['cf-connecting-ip'];
  return local && !proxied;
}

// Dashboard yang dibuka di komputer ini. Host dan Origin diperiksa supaya situs lain
// yang terbuka di browser tidak bisa ikut menyetujui permintaan token.
function fromDashboard(req) {
  const hosts = [`localhost:${PORT}`, `127.0.0.1:${PORT}`, `[::1]:${PORT}`];
  const origin = req.headers.origin;
  return isLocal(req)
    && hosts.includes(req.headers.host)
    && (!origin || hosts.some((h) => origin === `http://${h}`));
}

// ---------- Sambungkan HP: aplikasi minta App token, kamu setujui di dashboard ----------

const PAIR_TTL = 2 * 60 * 1000;
const pairings = new Map(); // id -> { id, code, device, ip, createdAt, status }

function activePairings() {
  const now = Date.now();
  for (const [id, p] of pairings) if (now - p.createdAt > PAIR_TTL) pairings.delete(id);
  return [...pairings.values()];
}

function logLine(text) {
  console.log(`[${new Date().toLocaleTimeString('id-ID')}] ${text}`);
}

async function handlePairRequest(req, res) {
  let body;
  try { body = await readJson(req, 2048); } catch (e) { return sendJson(res, 400, { error: e.message }); }
  if (activePairings().filter((p) => p.status === 'pending').length >= 5) {
    return sendJson(res, 429, { error: 'too_many_requests' });
  }
  const pairing = {
    id: crypto.randomBytes(12).toString('base64url'),
    code: String(crypto.randomInt(10000)).padStart(4, '0'),
    device: String(body.device || 'HP Android').replace(/\s+/g, ' ').trim().slice(0, 60) || 'HP Android',
    ip: req.socket.remoteAddress || '',
    createdAt: Date.now(),
    status: 'pending',
  };
  pairings.set(pairing.id, pairing);
  logLine(`${pairing.device} minta App token, kode ${pairing.code}. Setujui di dashboard.`);
  sendJson(res, 200, { id: pairing.id, code: pairing.code, expiresIn: PAIR_TTL / 1000 });
}

// Ditanya HP berulang kali. Token hanya diberikan sekali, ke HP yang sama dengan peminta.
function handlePairStatus(req, res, id) {
  activePairings();
  const pairing = pairings.get(id);
  if (!pairing || pairing.ip !== (req.socket.remoteAddress || '')) return sendJson(res, 404, { status: 'expired' });
  if (pairing.status === 'pending') return sendJson(res, 200, { status: 'pending' });
  pairings.delete(id);
  if (pairing.status === 'approved') return sendJson(res, 200, { status: 'approved', token: config.appToken });
  sendJson(res, 200, { status: 'denied' });
}

// HP membatalkan permintaannya sendiri
function handlePairCancel(req, res, id) {
  const pairing = pairings.get(id);
  if (pairing && pairing.ip === (req.socket.remoteAddress || '')) pairings.delete(id);
  sendJson(res, 200, { ok: true });
}

function handlePairList(req, res) {
  if (!fromDashboard(req)) return sendJson(res, 403, { error: 'local_only' });
  const requests = activePairings()
    .filter((p) => p.status === 'pending')
    .map((p) => ({ id: p.id, code: p.code, device: p.device, expiresAt: p.createdAt + PAIR_TTL }));
  sendJson(res, 200, { requests });
}

function handlePairDecision(req, res, id, approve) {
  const json = String(req.headers['content-type'] || '').startsWith('application/json');
  if (!fromDashboard(req) || !json) return sendJson(res, 403, { error: 'local_only' });
  activePairings();
  const pairing = pairings.get(id);
  if (!pairing || pairing.status !== 'pending') return sendJson(res, 404, { error: 'not_found' });
  pairing.status = approve ? 'approved' : 'denied';
  logLine(`${pairing.device} ${approve ? 'diizinkan mengambil App token' : 'ditolak'}.`);
  sendJson(res, 200, { ok: true });
}

// Balas tombol "Cari" di aplikasi. Hanya untuk alamat jaringan lokal,
// supaya server di VPS tidak bisa dipakai untuk memantulkan lalu lintas UDP.
function isPrivateIPv4(addr) {
  return /^(10\.|127\.|192\.168\.|169\.254\.|172\.(1[6-9]|2\d|3[01])\.)/.test(addr);
}

function startDiscovery() {
  const socket = dgram.createSocket('udp4');
  socket.on('message', (msg, rinfo) => {
    if (msg.toString('utf8').trim() !== DISCOVERY_ASK || !isPrivateIPv4(rinfo.address)) return;
    const reply = Buffer.from(JSON.stringify({ app: 'qris-alert', port: PORT, name: os.hostname() }));
    socket.send(reply, rinfo.port, rinfo.address);
  });
  socket.on('error', (err) => {
    console.log(`  Tombol Cari di HP tidak aktif (UDP ${DISCOVERY_PORT}: ${err.code || err.message}). Isi alamat server manual.\n`);
    socket.close();
  });
  socket.bind(DISCOVERY_PORT);
  socket.unref();
}

// Hanya bisa dipanggil dari komputer ini (dipakai oleh "Berhenti QRIS Alert")
function handleShutdown(req, res) {
  const auth = req.headers.authorization || '';
  const token = auth.startsWith('Bearer ') ? auth.slice(7).trim() : '';
  if (!isLocal(req) || !safeEqual(token, config.appToken)) return sendJson(res, 403, { error: 'forbidden' });
  sendJson(res, 200, { ok: true });
  console.log('\nServer dihentikan.');
  setTimeout(shutdown, 100);
}

function shutdown() {
  for (const res of clients) res.end();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 1500).unref();
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const p = url.pathname;
  try {
    if (req.method === 'POST' && p === '/api/payment') return await handlePayment(req, res);
    if (req.method === 'GET' && p === '/api/stream') return handleStream(req, res, url);
    if (req.method === 'GET' && p === '/api/summary') {
      if (!hasWidgetKey(url)) return sendJson(res, 401, { error: 'invalid_key' });
      return sendJson(res, 200, summary());
    }
    if (req.method === 'GET' && p === '/api/health') return sendJson(res, 200, { ok: true, app: 'qris-alert' });
    if (req.method === 'POST' && p === '/api/shutdown') return handleShutdown(req, res);
    if (req.method === 'POST' && p === '/api/pair') return await handlePairRequest(req, res);
    if (req.method === 'GET' && p === '/api/pair/pending') return handlePairList(req, res);
    const pair = /^\/api\/pair\/([\w-]{16})(?:\/(approve|deny))?$/.exec(p);
    if (pair && !pair[2] && req.method === 'GET') return handlePairStatus(req, res, pair[1]);
    if (pair && !pair[2] && req.method === 'DELETE') return handlePairCancel(req, res, pair[1]);
    if (pair && pair[2] && req.method === 'POST') return handlePairDecision(req, res, pair[1], pair[2] === 'approve');
    if (req.method === 'GET') return serveStatic(res, p);
    sendJson(res, 405, { error: 'method_not_allowed' });
  } catch (e) {
    console.error(e);
    if (!res.headersSent) sendJson(res, 500, { error: 'server_error' });
  }
});

function openBrowser(url) {
  const win = process.platform === 'win32';
  const cmd = win ? 'cmd' : process.platform === 'darwin' ? 'open' : 'xdg-open';
  const args = win ? ['/c', 'start', '""', url] : [url];
  try {
    const child = spawn(cmd, args, { detached: true, stdio: 'ignore', windowsVerbatimArguments: win });
    child.on('error', () => console.log(`  Buka dashboard secara manual: ${url}`));
    child.unref();
  } catch {
    console.log(`  Buka dashboard secara manual: ${url}`);
  }
}

async function isRunning() {
  try {
    const res = await fetch(`http://127.0.0.1:${PORT}/api/health`, { signal: AbortSignal.timeout(2000) });
    const data = await res.json();
    return data.app === 'qris-alert';
  } catch {
    return false;
  }
}

async function stopRunning() {
  try {
    const res = await fetch(`http://127.0.0.1:${PORT}/api/shutdown`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${config.appToken}` },
      signal: AbortSignal.timeout(3000),
    });
    console.log(res.ok ? 'QRIS Alert sudah dihentikan.' : `Server menolak perintah berhenti (HTTP ${res.status}).`);
  } catch {
    console.log('QRIS Alert tidak sedang berjalan.');
  }
}

function start() {
  server.on('error', async (err) => {
    if (err.code !== 'EADDRINUSE') {
      console.error('Server gagal berjalan:', err.message);
      process.exit(1);
    }
    if (await isRunning()) {
      console.log('QRIS Alert sudah berjalan.');
      if (OPEN_DASHBOARD) openBrowser(DASHBOARD_URL);
      setTimeout(() => process.exit(0), 500);
      return;
    }
    console.error(`Port ${PORT} sedang dipakai program lain.`);
    console.error(`Tutup program itu, atau tambahkan "port": 3001 ke ${CONFIG_FILE}`);
    process.exit(1);
  });

  server.listen(PORT, () => {
    const ips = Object.values(os.networkInterfaces())
      .flat()
      .filter((i) => i && (i.family === 'IPv4' || i.family === 4) && !i.internal)
      .map((i) => i.address);

    console.log('\nQRIS Alert server berjalan\n');
    console.log(`  Dashboard        ${DASHBOARD_URL}`);
    console.log(`  URL widget OBS   http://localhost:${PORT}/widget?key=${config.widgetKey}`);
    for (const ip of ips) console.log(`  Alamat untuk HP  http://${ip}:${PORT}`);
    console.log(`  App token        ${config.appToken}`);
    console.log('\n  Di aplikasi HP, ketuk Cari lalu Ambil, kemudian klik Izinkan di dashboard.');
    console.log('  App token hanya untuk aplikasi di HP. Jangan tampilkan di stream.');
    console.log('  Untuk berhenti: jalankan "Berhenti QRIS Alert" atau tutup jendela ini.\n');
    startDiscovery();
    if (OPEN_DASHBOARD) openBrowser(DASHBOARD_URL);
  });

  for (const sig of ['SIGINT', 'SIGTERM', 'SIGHUP']) process.on(sig, shutdown);
}

if (STOP) stopRunning();
else start();
