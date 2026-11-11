// Pluto Mod friends server. No dependencies. Run with: node server.js
const http = require('http');
const fs = require('fs');
const crypto = require('crypto');

const PORT = process.env.PORT || 8080;
const FILE = process.env.DATA_FILE || 'data.json';
const NAME_RE = /^[A-Za-z0-9_]{3,16}$/;

let db = { users: {}, friends: {}, requests: [], messages: [], nextId: 1 };
try { db = JSON.parse(fs.readFileSync(FILE, 'utf8')); } catch (e) {}

let saveTimer = null;
function save() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => fs.writeFile(FILE, JSON.stringify(db), () => {}), 500);
}

const lastSeen = {};   // lowercase name -> ms
const lastMsg = {};    // lowercase name -> ms
const regLog = {};     // ip -> [timestamps]

const hash = t => crypto.createHash('sha256').update(t).digest('hex');
const send = (res, code, obj) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(obj)); };
const fail = (res, code, msg) => send(res, code, { error: msg });

function readBody(req) {
  return new Promise(resolve => {
    let b = '';
    req.on('data', c => { b += c; if (b.length > 4096) req.destroy(); });
    req.on('end', () => { try { resolve(JSON.parse(b || '{}')); } catch (e) { resolve({}); } });
    req.on('error', () => resolve({}));
  });
}

function auth(req) {
  const t = (req.headers.authorization || '').replace('Bearer ', '');
  if (!t) return null;
  const h = hash(t);
  for (const k in db.users) if (db.users[k].tokenHash === h) return k;
  return null;
}

const areFriends = (a, b) => (db.friends[a] || []).includes(b);
function makeFriends(a, b) {
  (db.friends[a] = db.friends[a] || []).push(b);
  (db.friends[b] = db.friends[b] || []).push(a);
  db.requests = db.requests.filter(r => !((r.from === a && r.to === b) || (r.from === b && r.to === a)));
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const body = req.method === 'POST' ? await readBody(req) : {};

  if (req.method === 'POST' && url.pathname === '/register') {
    const ip = req.socket.remoteAddress;
    const now = Date.now();
    regLog[ip] = (regLog[ip] || []).filter(t => now - t < 3600000);
    if (regLog[ip].length >= 10) return fail(res, 429, 'Too many sign-ups, try later');
    const name = String(body.name || '');
    if (!NAME_RE.test(name)) return fail(res, 400, 'Invalid name');
    const key = name.toLowerCase();
    if (db.users[key]) return fail(res, 409, 'That name is already registered');
    regLog[ip].push(now);
    const token = crypto.randomBytes(24).toString('hex');
    db.users[key] = { name, tokenHash: hash(token) };
    save();
    return send(res, 200, { token });
  }

  const me = auth(req);
  if (!me) return fail(res, 401, 'Not logged in');
  lastSeen[me] = Date.now();

  if (req.method === 'GET' && url.pathname === '/state') {
    const since = parseInt(url.searchParams.get('since') || '0', 10) || 0;
    const now = Date.now();
    return send(res, 200, {
      friends: (db.friends[me] || []).map(f => ({ name: db.users[f].name, online: now - (lastSeen[f] || 0) < 15000 })),
      incoming: db.requests.filter(r => r.to === me).map(r => db.users[r.from].name),
      outgoing: db.requests.filter(r => r.from === me).map(r => db.users[r.to].name),
      messages: db.messages
        .filter(m => m.id > since && (m.from === me || m.to === me))
        .slice(-100)
        .map(m => ({ id: m.id, from: db.users[m.from].name, to: db.users[m.to].name, text: m.text })),
      lastId: db.nextId - 1
    });
  }

  if (req.method !== 'POST') return fail(res, 404, 'Not found');

  if (url.pathname === '/friends/request') {
    const to = String(body.to || '').toLowerCase();
    if (to === me) return fail(res, 400, "You can't add yourself");
    if (!db.users[to]) return fail(res, 404, 'No such player on this server');
    if (areFriends(me, to)) return fail(res, 400, 'Already friends');
    if (db.requests.some(r => r.from === to && r.to === me)) { makeFriends(me, to); save(); return send(res, 200, { ok: true }); }
    if (db.requests.some(r => r.from === me && r.to === to)) return fail(res, 400, 'Request already sent');
    db.requests.push({ from: me, to });
    save();
    return send(res, 200, { ok: true });
  }

  if (url.pathname === '/friends/accept') {
    const from = String(body.from || '').toLowerCase();
    if (!db.requests.some(r => r.from === from && r.to === me)) return fail(res, 404, 'No request from that player');
    makeFriends(me, from);
    save();
    return send(res, 200, { ok: true });
  }

  if (url.pathname === '/friends/remove') {
    const other = String(body.name || '').toLowerCase();
    db.friends[me] = (db.friends[me] || []).filter(f => f !== other);
    db.friends[other] = (db.friends[other] || []).filter(f => f !== me);
    save();
    return send(res, 200, { ok: true });
  }

  if (url.pathname === '/message') {
    const to = String(body.to || '').toLowerCase();
    const text = String(body.text || '').trim().slice(0, 200);
    if (!text) return fail(res, 400, 'Empty message');
    if (!areFriends(me, to)) return fail(res, 403, 'You can only message friends');
    const now = Date.now();
    if (now - (lastMsg[me] || 0) < 500) return fail(res, 429, 'Slow down');
    lastMsg[me] = now;
    db.messages.push({ id: db.nextId++, from: me, to, text });
    if (db.messages.length > 5000) db.messages.splice(0, db.messages.length - 5000);
    save();
    return send(res, 200, { ok: true });
  }

  return fail(res, 404, 'Not found');
});

server.listen(PORT, () => console.log('Pluto friends server listening on port ' + PORT));
