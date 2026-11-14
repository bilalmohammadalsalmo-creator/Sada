// خادم مرجعي صغير بلا اعتمادات: رموز دعوة + قوائم رسمية + تحديث + بلاغات دعم.
// التشغيل:  ADMIN_KEY=سري PORT=8080 node server.js
// ضع التطبيق على عنوانه في API_BASE داخل app/build.gradle.kts (مثلاً https://your.host/api)
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = process.env.PORT || 8080;
const ADMIN_KEY = process.env.ADMIN_KEY || 'change-me';
const DATA = path.join(__dirname, 'data.json');

// الرموز تُحفظ في ملف: { code: { deviceId: null | "..." } }
const load = () => { try { return JSON.parse(fs.readFileSync(DATA, 'utf8')); } catch { return { codes: {} }; } };
const save = (d) => fs.writeFileSync(DATA, JSON.stringify(d, null, 2));

const send = (res, status, obj, type = 'application/json; charset=utf-8') => {
  res.writeHead(status, { 'Content-Type': type });
  res.end(typeof obj === 'string' ? obj : JSON.stringify(obj));
};
const readBody = (req) => new Promise((ok) => {
  let b = '';
  req.on('data', (c) => { b += c; if (b.length > 1e6) req.destroy(); });
  req.on('end', () => { try { ok(JSON.parse(b || '{}')); } catch { ok({}); } });
});
const readFileOr = (f, fallback) => { try { return fs.readFileSync(path.join(__dirname, f), 'utf8'); } catch { return fallback; } };

http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  const p = url.pathname.replace(/^\/api/, '');

  if (req.method === 'POST' && p === '/activate') {
    const { code, deviceId } = await readBody(req);
    const d = load();
    const entry = d.codes[code];
    if (!code || !deviceId || !entry) return send(res, 200, { ok: false, error: 'رمز غير صحيح' });
    if (entry.deviceId && entry.deviceId !== deviceId) return send(res, 200, { ok: false, error: 'هذا الرمز مستخدم على جهاز آخر' });
    entry.deviceId = deviceId;
    save(d);
    return send(res, 200, { ok: true });
  }

  if (req.method === 'GET' && p === '/admin/new-code') {
    if (url.searchParams.get('key') !== ADMIN_KEY) return send(res, 403, { error: 'forbidden' });
    const d = load();
    let code;
    do { code = String(crypto.randomInt(0, 1000000)).padStart(6, '0'); } while (d.codes[code]);
    d.codes[code] = { deviceId: null };
    save(d);
    return send(res, 200, { code });
  }

  if (req.method === 'GET' && p === '/sources.json') return send(res, 200, readFileOr('sources.json', '[]'));
  if (req.method === 'GET' && p === '/update.json') return send(res, 200, readFileOr('update.json', '{"versionCode":0}'));

  if (req.method === 'POST' && p === '/support') {
    const body = await readBody(req);
    fs.appendFileSync(path.join(__dirname, 'support.log'), JSON.stringify({ at: new Date().toISOString(), ...body }) + '\n');
    return send(res, 200, { ok: true });
  }

  send(res, 404, { error: 'not found' });
}).listen(PORT, () => console.log(`Sada server on :${PORT}`));
