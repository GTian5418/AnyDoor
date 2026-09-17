'use strict';
/* ==== Native bridge ==== */
const N = window.Native || window.__mockNative;
const cbs = {};
let cbSeq = 1;
window.__cb = (id, data) => { const f = cbs[id]; if (f) { delete cbs[id]; f(data); } };
function call(method, ...args) {
  return new Promise((resolve) => {
    const id = cbSeq++;
    cbs[id] = resolve;
    try { N[method](id, ...args); } catch (e) { resolve({ ok: false, error: String(e) }); }
  });
}
const toast = (m) => { try { N.toast(m); } catch (e) {} showToast(m); };
function showToast(m) {
  const t = $('#toast'); t.textContent = m; t.classList.add('show');
  clearTimeout(showToast._t); showToast._t = setTimeout(() => t.classList.remove('show'), 1900);
}
const vib = (ms) => { try { N.vibrate(ms || 12); } catch (e) {} };

/* ==== helpers ==== */
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const el = (tag, cls, html) => { const e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; };
const esc = (s) => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const fmt = (n, d = 6) => (Math.round(n * 10 ** d) / 10 ** d).toFixed(d);
async function getApp(k, def) { try { const v = await N.getApp(k); return v == null ? def : v; } catch (e) { return def; } }
const setApp = (k, v) => { try { N.setApp(k, v == null ? null : String(v)); } catch (e) {} };
async function getJSON(k, def) { try { const v = await N.getApp(k); return v ? JSON.parse(v) : def; } catch (e) { return def; } }
const setJSON = (k, v) => setApp(k, JSON.stringify(v));

/* ==== GCJ-02 / BD-09 <-> WGS-84 (mirror of GeoMath) ==== */
const G = (() => {
  const PI = Math.PI, a = 6378245.0, ee = 0.00669342162296594323, xpi = PI * 3000 / 180;
  const out = (lat, lng) => lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
  const tLat = (x, y) => { let r = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x)); r += (20 * Math.sin(6 * x * PI) + 20 * Math.sin(2 * x * PI)) * 2 / 3; r += (20 * Math.sin(y * PI) + 40 * Math.sin(y / 3 * PI)) * 2 / 3; r += (160 * Math.sin(y / 12 * PI) + 320 * Math.sin(y * PI / 30)) * 2 / 3; return r; };
  const tLng = (x, y) => { let r = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x)); r += (20 * Math.sin(6 * x * PI) + 20 * Math.sin(2 * x * PI)) * 2 / 3; r += (20 * Math.sin(x * PI) + 40 * Math.sin(x / 3 * PI)) * 2 / 3; r += (150 * Math.sin(x / 12 * PI) + 300 * Math.sin(x / 30 * PI)) * 2 / 3; return r; };
  function wgs2gcj(lat, lng) {
    if (out(lat, lng)) return [lat, lng];
    let dLat = tLat(lng - 105, lat - 35), dLng = tLng(lng - 105, lat - 35);
    const rad = lat / 180 * PI; let magic = Math.sin(rad); magic = 1 - ee * magic * magic; const s = Math.sqrt(magic);
    dLat = (dLat * 180) / ((a * (1 - ee)) / (magic * s) * PI);
    dLng = (dLng * 180) / (a / s * Math.cos(rad) * PI);
    return [lat + dLat, lng + dLng];
  }
  function gcj2wgs(lat, lng) {
    if (out(lat, lng)) return [lat, lng];
    let wLat = lat, wLng = lng;
    for (let i = 0; i < 6; i++) { const g = wgs2gcj(wLat, wLng); wLat -= g[0] - lat; wLng -= g[1] - lng; }
    return [wLat, wLng];
  }
  function gcj2bd(lat, lng) { const z = Math.sqrt(lng * lng + lat * lat) + 0.00002 * Math.sin(lat * xpi); const t = Math.atan2(lat, lng) + 0.000003 * Math.cos(lng * xpi); return [z * Math.sin(t) + 0.006, z * Math.cos(t) + 0.0065]; }
  function bd2gcj(lat, lng) { const x = lng - 0.0065, y = lat - 0.006; const z = Math.sqrt(x * x + y * y) - 0.00002 * Math.sin(y * xpi); const t = Math.atan2(y, x) - 0.000003 * Math.cos(x * xpi); return [z * Math.sin(t), z * Math.cos(t)]; }
  return {
    wgs2gcj, gcj2wgs,
    wgs2bd: (la, ln) => { const g = wgs2gcj(la, ln); return gcj2bd(g[0], g[1]); },
    bd2wgs: (la, ln) => { const g = bd2gcj(la, ln); return gcj2wgs(g[0], g[1]); },
  };
})();

/* ==== app state ==== */
const S = {
  target: null,        // {lat,lng} WGS-84
  name: '', addr: '',
  running: false,
  layer: 'amap',
  crs: 'wgs84',        // input CRS for pasted coords / display
  searchSrc: 'auto',
  page: null,
  routePts: [],        // [{lat,lng}] WGS-84
  routeRunning: false,
  favs: [], hist: [], routes: [],
  dpadStep: 5,
  night: false,
  version: '1.0',
};

/* ==== Leaflet map ==== */
let map, pinMarker, curMarker, realMarker, routeLine, routeMarkers = [];
const LAYERS = {};
function initMap() {
  map = L.map('map', { zoomControl: false, attributionControl: true, tap: true, doubleClickZoom: false, maxZoom: 19 }).setView([39.9087, 116.3975], 12);
  // AutoNavi tiles are GCJ-02; convert the WGS-84 request point through a transform-aware tile layer.
  const gcjLayer = (url, opts) => L.tileLayer(url, Object.assign({ maxZoom: 19, subdomains: '1234' }, opts));
  LAYERS.amap = gcjLayer('https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}', { attribution: '高德地图' });
  LAYERS.amapSat = L.layerGroup([
    gcjLayer('https://webst0{s}.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}', { attribution: '高德卫星' }),
    gcjLayer('https://webst0{s}.is.autonavi.com/appmaptile?style=8&x={x}&y={y}&z={z}', {}),
  ]);
  LAYERS.osm = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: 'OpenStreetMap' });
  patchGCJ();
  setLayer(S.layer, true);
  map.on('click', (e) => { const w = fromMap(e.latlng.lat, e.latlng.lng); pickTarget(w[0], w[1], { reverse: true, fly: false }); vib(); });
  map.on('movestart', () => $('#results').classList.remove('open'));
}
// Convert WGS-84 -> current tile CRS for AutoNavi layers by overriding the projection at tile level.
function patchGCJ() {
  ['amap', 'amapSat'].forEach(k => {
    const lyr = LAYERS[k];
    const layers = lyr.getLayers ? lyr.getLayers() : [lyr];
    layers.forEach(t => {
      const orig = t.getTileUrl.bind(t);
      t.getTileUrl = function (coords) { return orig(coords); };
    });
  });
}
// When the active layer is AutoNavi we place markers using GCJ so they line up with tiles.
function toMap(lat, lng) { return (S.layer === 'amap' || S.layer === 'amapSat') ? G.wgs2gcj(lat, lng) : [lat, lng]; }
function fromMap(lat, lng) { return (S.layer === 'amap' || S.layer === 'amapSat') ? G.gcj2wgs(lat, lng) : [lat, lng]; }

function setLayer(k, initial) {
  const prev = S.layer;
  Object.values(LAYERS).forEach(l => map.hasLayer(l) && map.removeLayer(l));
  S.layer = LAYERS[k] ? k : 'amap';
  LAYERS[S.layer].addTo(map);
  setApp('map_layer', S.layer);
  $$('#layerPop .opt').forEach(o => o.classList.toggle('on', o.dataset.layer === S.layer));
  if (!initial && S.target) { redrawMarkers(); // keep the geographic center fixed across CRS change
    const c = map.getCenter(); const w = (prev === 'amap' || prev === 'amapSat') ? G.gcj2wgs(c.lat, c.lng) : [c.lat, c.lng];
    const nc = toMap(w[0], w[1]); map.setView(nc, map.getZoom(), { animate: false });
  }
}

const pinHTML = '<div class="pin"><svg viewBox="0 0 44 56"><path class="pin-body" d="M22 2C11.5 2 3 10.5 3 21c0 14 19 33 19 33s19-19 19-33C41 10.5 32.5 2 22 2z"/><rect class="pin-door" x="15" y="12" width="14" height="20" rx="2"/><circle class="pin-knob" cx="26" cy="22" r="1.6"/></svg></div>';
function redrawMarkers() {
  if (!S.target) return;
  const p = toMap(S.target.lat, S.target.lng);
  if (!pinMarker) {
    pinMarker = L.marker(p, { icon: L.divIcon({ className: '', html: pinHTML, iconSize: [44, 56], iconAnchor: [22, 54] }), interactive: false }).addTo(map);
  } else pinMarker.setLatLng(p);
}

function initMovingMarker() {
  if (curMarker) return;
  curMarker = L.marker([0, 0], { icon: L.divIcon({ className: '', html: '<div class="cur-dot"></div>', iconSize: [18, 18], iconAnchor: [9, 9] }), interactive: false, zIndexOffset: 1000 });
}

/* ==== target selection ==== */
let reverseSeq = 0;
function pickTarget(lat, lng, opts = {}) {
  S.target = { lat, lng };
  if (opts.name != null) { S.name = opts.name; S.addr = opts.addr || ''; }
  else if (!opts.keepName) { S.name = '地图选点'; S.addr = ''; }
  redrawMarkers();
  const p = toMap(lat, lng);
  if (opts.fly !== false) map.flyTo(p, Math.max(map.getZoom(), 16), { duration: .5 });
  updateSheet();
  N.setTarget(lat, lng);
  if (opts.reverse) {
    const my = ++reverseSeq;
    S.addr = '正在解析地址…'; updateSheet();
    call('reverse', lat, lng).then(r => {
      if (my !== reverseSeq) return;
      if (r && r.ok) { S.addr = r.address; if (!opts.name) S.name = firstLine(r.address); }
      else S.addr = '';
      updateSheet();
    });
  }
  if (S.page === 'route' && routeAddMode) addRoutePoint(lat, lng);
}
const firstLine = (s) => (s || '').split(/[,，]/)[0].trim() || '未知地点';

function displayCoords() {
  if (!S.target) return '--';
  const { lat, lng } = S.target; let a = lat, b = lng, tag = 'WGS84';
  if (S.crs === 'gcj02') { const g = G.wgs2gcj(lat, lng); a = g[0]; b = g[1]; tag = 'GCJ02'; }
  else if (S.crs === 'bd09') { const d = G.wgs2bd(lat, lng); a = d[0]; b = d[1]; tag = 'BD09'; }
  $('#crsLabel').textContent = tag;
  return fmt(a) + ', ' + fmt(b);
}
function updateSheet() {
  $('#tName').textContent = S.name || '未选择位置';
  $('#tAddr').textContent = S.addr || (S.target ? '点击"开始模拟"应用此位置' : '点击地图或搜索地点');
  $('#coordText').textContent = displayCoords();
  const fav = S.target && S.favs.some(f => near(f, S.target));
  $('#btnFav').classList.toggle('on', !!fav);
  const go = $('#btnGo');
  go.classList.toggle('stop', S.running);
  $('#goText').textContent = S.running ? '停止模拟' : '开始模拟';
}
const near = (a, b) => Math.abs(a.lat - b.lat) < 1e-6 && Math.abs(a.lng - b.lng) < 1e-6;

/* ==== search ==== */
let searchSeq = 0, searchTimer;
function onSearchInput() {
  const box = $('#searchBox'), q = $('#q').value.trim();
  box.classList.toggle('has-text', q.length > 0);
  clearTimeout(searchTimer);
  const coord = parseCoord(q);
  if (coord) { renderResults([{ coord: true, lat: coord[0], lng: coord[1], name: '坐标定位', addr: fmt(coord[0]) + ', ' + fmt(coord[1]) + '  (' + coord[2] + ')' }]); return; }
  if (q.length < 2) { $('#results').classList.remove('open'); return; }
  searchTimer = setTimeout(() => runSearch(q), 380);
}
function parseCoord(q) {
  const m = q.match(/^\s*(-?\d{1,3}(?:\.\d+)?)\s*[,，\s]\s*(-?\d{1,3}(?:\.\d+)?)\s*$/);
  if (!m) return null;
  let x = parseFloat(m[1]), y = parseFloat(m[2]);
  // decide which is lat: lat in [-90,90]; if first looks like lng (>90) swap
  let lat, lng;
  if (Math.abs(x) <= 90 && Math.abs(y) <= 180 && !(Math.abs(x) > 90)) { lat = x; lng = y; }
  else { lat = y; lng = x; }
  if (Math.abs(lat) > 90) { const t = lat; lat = lng; lng = t; }
  // apply input CRS -> WGS
  let w = [lat, lng], tag = 'WGS84';
  if (S.crs === 'gcj02') { w = G.gcj2wgs(lat, lng); tag = 'GCJ02→WGS84'; }
  else if (S.crs === 'bd09') { w = G.bd2wgs(lat, lng); tag = 'BD09→WGS84'; }
  return [w[0], w[1], tag];
}
async function runSearch(q) {
  const my = ++searchSeq;
  // China network: POI search needs a (free) Amap key. If missing, guide the user instead of failing.
  if (S.searchSrc !== 'nominatim') {
    const key = await getApp('amap_key', '');
    if (!key || !key.trim()) { $('#results').classList.remove('open'); promptAmapKey(q); return; }
  }
  $('#searchBox').classList.add('loading');
  const near = S.target || (map ? mapCenterWGS() : { lat: 0, lng: 0 });
  const r = await call('search', q, near.lat || 0, near.lng || 0);
  $('#searchBox').classList.remove('loading');
  if (my !== searchSeq) return;
  if (!r || !r.ok || !r.items || !r.items.length) {
    let msg = (r && r.error) || '没有找到相关地点\n试试更完整的名称，或直接粘贴坐标';
    if (r && /INVALID_USER_KEY|USERKEY_PLAT_NOMATCH|无权限|INVALID/i.test(r.error || '')) {
      $('#results').classList.remove('open'); promptAmapKey(q, '高德 Key 无效或类型不对，请重新填写（需选「Web服务」类型）'); return;
    }
    if (r && /DAILY_QUERY_OVER_LIMIT|CUQPS|QUOTA|限|LIMIT/i.test(r.error || '')) msg = '高德 Key 今日额度用尽，明天恢复，或更换一个 Key';
    renderResults(null, msg);
    return;
  }
  renderResults(r.items);
}
function promptAmapKey(retryQuery, note) {
  const m = openModal(
    '<h3>开启中国地点搜索</h3>' +
    '<p>' + (note ? esc(note) + '\n\n' : '') + '中国网络下，地点搜索走高德地图，需要一个免费的高德 Key（约 2 分钟，一次配置长期有效）。</p>' +
    '<div style="display:flex;flex-direction:column;gap:8px">' +
    '<button class="chip primary" id="ak_open" style="width:100%;height:42px">① 打开高德开放平台申请 Key</button>' +
    '<div class="muted small" style="line-height:1.6">② 登录 → 应用管理 → 创建应用 → 添加 Key → 服务平台选 <b>「Web服务」</b> → 复制 Key</div>' +
    '<input id="ak_input" placeholder="③ 粘贴 Key（32 位）" autocomplete="off">' +
    '</div>' +
    '<div class="actions"><button id="ak_osm">改用海外(OSM)</button><button id="ak_cancel">取消</button><button class="primary" id="ak_ok">保存并搜索</button></div>');
  const inp = m.querySelector('#ak_input');
  getApp('amap_key', '').then(k => { if (k) inp.value = k; });
  const clip = N.paste ? N.paste() : '';
  if (clip && /^[0-9a-f]{24,40}$/i.test(clip.trim())) inp.value = clip.trim();
  m.querySelector('#ak_open').onclick = () => N.openUrl('https://lbs.amap.com/api/webservice/guide/create-project/get-key');
  m.querySelector('#ak_cancel').onclick = closeModal;
  m.querySelector('#ak_osm').onclick = () => { S.searchSrc = 'nominatim'; setApp('search_src', 'nominatim'); closeModal(); toast('已切换到 OSM（海外网络更稳）'); if (retryQuery) runSearch(retryQuery); };
  m.querySelector('#ak_ok').onclick = async () => {
    const key = inp.value.trim();
    if (!key) { toast('请粘贴 Key'); return; }
    setApp('amap_key', key);
    closeModal();
    toast('正在验证 Key…');
    if (retryQuery) { $('#q').value = retryQuery; onSearchInput(); runSearch(retryQuery); }
  };
}
function mapCenterWGS() { const c = map.getCenter(); const w = fromMap(c.lat, c.lng); return { lat: w[0], lng: w[1] }; }
function renderResults(items, emptyMsg) {
  const box = $('#results'); box.innerHTML = '';
  if (!items) { box.appendChild(el('div', 'empty', esc(emptyMsg || '无结果'))); box.classList.add('open'); return; }
  items.forEach(it => {
    const b = el('button', 'result');
    const ico = it.coord ? '<circle cx="12" cy="12" r="3"/><circle cx="12" cy="12" r="8"/>' : '<path d="M12 21s7-6.5 7-12a7 7 0 1 0-14 0c0 5.5 7 12 7 12z"/><circle cx="12" cy="9.5" r="2.5"/>';
    b.innerHTML = '<span class="ri"><svg viewBox="0 0 24 24">' + ico + '</svg></span><span class="rt"><div class="rn">' + esc(it.name) + '</div><div class="ra">' + esc(it.addr || it.address || '') + '</div></span>';
    b.onclick = () => {
      $('#results').classList.remove('open');
      $('#q').blur();
      pickTarget(it.lat, it.lng, { name: it.name, addr: it.addr || it.address || '', reverse: it.coord });
      addHistory({ name: it.name, addr: it.addr || it.address || '', lat: it.lat, lng: it.lng });
      collapseSheet(false);
    };
    box.appendChild(b);
  });
  box.classList.add('open');
}

/* ==== favorites & history ==== */
async function loadLists() {
  S.favs = await getJSON('favorites', []);
  S.hist = await getJSON('history', []);
  S.routes = await getJSON('routes', []);
}
function addHistory(x) {
  S.hist = S.hist.filter(h => !near(h, x));
  S.hist.unshift(Object.assign({ t: Date.now() }, x));
  if (S.hist.length > 50) S.hist.length = 50;
  setJSON('history', S.hist);
}
function toggleFav() {
  if (!S.target) { toast('请先选择一个位置'); return; }
  const i = S.favs.findIndex(f => near(f, S.target));
  if (i >= 0) { S.favs.splice(i, 1); toast('已取消收藏'); }
  else { S.favs.unshift({ name: S.name || firstLine(S.addr), addr: S.addr, lat: S.target.lat, lng: S.target.lng, t: Date.now() }); toast('已收藏'); vib(); }
  setJSON('favorites', S.favs); updateSheet();
}

/* ==== spoof toggle ==== */
async function toggleSpoof() {
  if (!S.running && !S.target) { toast('请先选择一个位置'); return; }
  S.running = !S.running;
  N.setStarted(S.running);
  updateSheet(); updatePill();
  vib(S.running ? 18 : 10);
  if (S.running) { toast('已开始 · 全局定位已切换'); addHistory({ name: S.name || '地图选点', addr: S.addr, lat: S.target.lat, lng: S.target.lng }); }
  else toast('已停止');
  setTimeout(refreshStatus, 400);
}
function updatePill() {
  const p = $('#pill');
  p.classList.toggle('on', S.running);
  $('#pillText').textContent = S.running ? (S.routeRunning ? '路线模拟中' : '模拟中') : '未开始';
}

/* ==== status polling ==== */
let statusTimer;
async function refreshStatus() {
  let st;
  try { st = JSON.parse(await N.getState()); } catch (e) { return; }
  const svc = st.service || {};
  S.running = !!st.started && !!svc.running;
  S.routeRunning = !!svc.routeActive;
  S.version = st.version || '1.0';
  if (svc.running && svc.curLat != null) {
    initMovingMarker();
    const p = toMap(svc.curLat, svc.curLng);
    curMarker.setLatLng(p); if (!map.hasLayer(curMarker)) curMarker.addTo(map);
    curMarker.getElement() && curMarker.getElement().querySelector('.cur-dot').classList.toggle('moving', svc.speed > 0.3);
  } else if (curMarker && map.hasLayer(curMarker)) map.removeLayer(curMarker);
  // route progress
  const rb = $('#routeBar');
  if (S.routeRunning && svc.routeTotal) {
    rb.classList.remove('hidden');
    const pct = Math.min(100, svc.routeDone / svc.routeTotal * 100);
    $('#routeProg').style.width = pct.toFixed(1) + '%';
    $('#routeText').textContent = '路线模拟 · ' + Math.round(svc.routeDone) + '/' + Math.round(svc.routeTotal) + ' m';
  } else rb.classList.add('hidden');
  if (svc.mockError) $('#pill').classList.add('warn'); else $('#pill').classList.remove('warn');
  $('#fabJoy').classList.toggle('on', !!svc.joystick);
  updatePill(); updateSheet();
}

/* ==== bottom sheet ==== */
let sheetCollapsed = false;
function collapseSheet(c) { sheetCollapsed = c; $('#sheet').classList.toggle('collapsed', c); }
function initSheetDrag() {
  const sheet = $('#sheet'), handle = $('.sheet-handle');
  let sy = 0, moved = 0, dragging = false;
  const start = (y) => { sy = y; moved = 0; dragging = true; sheet.style.transition = 'none'; };
  const move = (y) => { if (!dragging) return; moved = y - sy; if (moved < -20) moved = -20; sheet.style.transform = 'translateY(' + Math.max(0, moved) + 'px)'; };
  const end = () => { if (!dragging) return; dragging = false; sheet.style.transition = ''; sheet.style.transform = ''; if (moved > 60) collapseSheet(true); else if (moved < -30) collapseSheet(false); };
  handle.addEventListener('touchstart', e => start(e.touches[0].clientY), { passive: true });
  handle.addEventListener('touchmove', e => move(e.touches[0].clientY), { passive: true });
  handle.addEventListener('touchend', end);
  $('.sheet-head').addEventListener('click', () => { if (sheetCollapsed) collapseSheet(false); });
}

/* ==== d-pad ==== */
let routeAddMode = false;
function nudge(bearing) {
  if (!S.target) { toast('请先选择位置'); return; }
  const d = S.dpadStep;
  const rad = bearing * Math.PI / 180;
  const dyNorth = Math.cos(rad) * d, dxEast = Math.sin(rad) * d;
  const dLat = dyNorth / 111320, dLng = dxEast / (111320 * Math.cos(S.target.lat * Math.PI / 180));
  pickTarget(S.target.lat + dLat, S.target.lng + dLng, { keepName: true, fly: false });
  vib(8);
}

/* ==== drawer & pages ==== */
function openDrawer(o) { $('#drawer').classList.toggle('open', o); $('#scrim').classList.toggle('hidden', !o); requestAnimationFrame(() => $('#scrim').classList.toggle('show', o)); }
function openPage(id) {
  S.page = id;
  const p = $('#page-' + id); if (!p) return;
  p.classList.add('open');
  openDrawer(false);
  if (id === 'favorites') renderFavs();
  else if (id === 'history') renderHist();
  else if (id === 'settings') renderSettings();
  else if (id === 'env') renderEnv();
  else if (id === 'route') renderRoutePage();
  else if (id === 'about') $('#aboutVer').textContent = S.version;
}
function closePage() {
  const open = $$('.page.open');
  if (!open.length) return false;
  const top = open[open.length - 1];
  top.classList.remove('open');
  if (top.id === 'page-route') exitRouteAddMode();
  S.page = null;
  return true;
}
window.handleBack = () => {
  if ($('#modalScrim').classList.contains('hidden') === false) { closeModal(); return true; }
  if ($('#drawer').classList.contains('open')) { openDrawer(false); return true; }
  if ($('#results').classList.contains('open')) { $('#results').classList.remove('open'); return true; }
  return closePage();
};

/* ==== favorites/history rendering ==== */
function renderFavs() {
  const box = $('#favList'); box.innerHTML = '';
  if (!S.favs.length) { box.appendChild(el('div', 'empty-state', '还没有收藏\n在地图选好位置后点右下角星标即可收藏')); return; }
  S.favs.forEach((f, i) => box.appendChild(listItem(f, i, 'fav')));
}
function renderHist() {
  const box = $('#histList'); box.innerHTML = '';
  if (!S.hist.length) { box.appendChild(el('div', 'empty-state', '暂无历史记录')); return; }
  S.hist.forEach((h, i) => box.appendChild(listItem(h, i, 'hist')));
}
function listItem(x, i, type) {
  const it = el('div', 'item');
  const ini = (x.name || '?').trim().slice(0, 1);
  it.innerHTML = '<span class="ii">' + esc(ini) + '</span><span class="it"><div class="in">' + esc(x.name || '未命名') + '</div><div class="ia">' + esc(x.addr || (fmt(x.lat, 5) + ', ' + fmt(x.lng, 5))) + '</div></span><button class="more">⋯</button>';
  it.querySelector('.it').onclick = () => { closePage(); pickTarget(x.lat, x.lng, { name: x.name, addr: x.addr }); collapseSheet(false); };
  it.querySelector('.more').onclick = (e) => { e.stopPropagation(); itemMenu(x, i, type); };
  return it;
}
function itemMenu(x, i, type) {
  const acts = [{ t: '设为目标并开始', fn: () => { closePage(); pickTarget(x.lat, x.lng, { name: x.name, addr: x.addr }); if (!S.running) toggleSpoof(); collapseSheet(false); } },
    { t: '复制坐标', fn: () => { N.copy(fmt(x.lat) + ', ' + fmt(x.lng)); toast('已复制'); } }];
  if (type === 'fav') acts.push({ t: '重命名', fn: () => renameFav(i) });
  acts.push({ t: '删除', danger: true, fn: () => { if (type === 'fav') { S.favs.splice(i, 1); setJSON('favorites', S.favs); renderFavs(); } else { S.hist.splice(i, 1); setJSON('history', S.hist); renderHist(); } } });
  sheetMenu(x.name || '操作', acts);
}
function renameFav(i) {
  modalInput('重命名收藏', S.favs[i].name || '', (v) => { S.favs[i].name = v || '未命名'; setJSON('favorites', S.favs); renderFavs(); });
}

/* ==== settings ==== */
let cfg = {};
async function renderSettings() {
  cfg = await getConfig();
  const b = $('#settingsBody'); b.innerHTML = '';
  b.appendChild(sectionTitle('模拟参数'));
  const c1 = el('div', 'card');
  c1.appendChild(rangeRow('随机漂移半径', 'jitter', 0, 30, 1, +cfg.jitter || 0, 'm', '让坐标在小范围内随机抖动，更接近真实 GPS。0 为固定不动。'));
  c1.appendChild(numRow('海拔高度', 'alt', +cfg.alt || 50, 'm'));
  c1.appendChild(numRow('定位精度', 'acc', +cfg.acc || 8, 'm'));
  c1.appendChild(numRow('刷新间隔', 'interval', +cfg.interval || 1000, 'ms', true));
  b.appendChild(c1);

  b.appendChild(sectionTitle('防检测 / 生效范围'));
  const c2 = el('div', 'card');
  c2.appendChild(switchRow('屏蔽 WiFi 定位', 'wifi_block', cfg.wifi_block !== false, '隐藏周围 WiFi，避免被反推真实位置（强烈建议开启）'));
  c2.appendChild(switchRow('屏蔽基站定位', 'cell_block', cfg.cell_block !== false, '隐藏基站信息，防止运营商定位反推'));
  c2.appendChild(switchRow('屏蔽原始 GNSS', 'gnss_block', cfg.gnss_block !== false, '屏蔽卫星原始观测量，防止应用自行解算'));
  c2.appendChild(switchRow('推送到测试定位源', 'mock_driver', cfg.mock_driver !== false, '室内无 GPS 信号时也能持续输出定位'));
  c2.appendChild(switchRow('同时模拟网络定位', 'mock_network', cfg.mock_network !== false, '连同 network provider 一起改写'));
  b.appendChild(c2);

  b.appendChild(sectionTitle('豁免应用（保留真实定位）'));
  const c3 = el('div', 'card');
  const row = el('div', 'row col');
  row.innerHTML = '<div class="rl"><input type="text" class="wide" id="exempt" placeholder="包名，逗号分隔，如 com.autonavi.minimap"><div class="desc">这些应用不受影响，仍使用真实定位。</div></div>';
  c3.appendChild(row);
  b.appendChild(c3);
  $('#exempt').value = cfg.exempt || '';
  $('#exempt').onchange = () => saveConfig({ exempt: $('#exempt').value.trim() });

  b.appendChild(sectionTitle('坐标与地址'));
  const c4 = el('div', 'card');
  c4.appendChild(segRow('输入坐标系', ['wgs84', 'gcj02', 'bd09'], ['WGS84 (GPS)', '火星 GCJ02', '百度 BD09'], S.crs, (v) => { S.crs = v; setApp('input_crs', v); updateSheet(); }));
  c4.appendChild(segRow('搜索来源', ['auto', 'amap', 'nominatim'], ['自动', '高德', 'OSM'], S.searchSrc, (v) => { S.searchSrc = v; setApp('search_src', v); }));
  const keyRow = el('div', 'row col');
  keyRow.innerHTML = '<div class="rl"><input type="text" class="wide" id="amapKey" placeholder="高德 Web 服务 Key（可选，中国大陆搜索更准）"><div class="desc">在高德开放平台申请「Web服务」类型 Key，填入后国内地点搜索/逆地址更准确。</div></div>';
  c4.appendChild(keyRow);
  b.appendChild(c4);
  $('#amapKey').value = await getApp('amap_key', '');
  $('#amapKey').onchange = () => setApp('amap_key', $('#amapKey').value.trim());

  b.appendChild(sectionTitle('外观'));
  const c5 = el('div', 'card');
  const theme = await getApp('theme', 'auto');
  c5.appendChild(segRow('主题', ['auto', 'light', 'dark'], ['跟随系统', '浅色', '深色'], theme, (v) => { setApp('theme', v); applyTheme(v); }));
  b.appendChild(c5);

  b.appendChild(sectionTitle('数据'));
  const c6 = el('div', 'card');
  const tb = el('div', 'toolbar');
  tb.append(chip('导出全部设置', () => exportAll()), chip('导入设置', () => importAll()), chip('恢复默认', () => resetDefaults(), true));
  c6.appendChild(tb);
  b.appendChild(c6);
}
function sectionTitle(t) { return el('div', 'section-title', esc(t)); }
function numRow(label, key, val, unit, isInt) {
  const r = el('div', 'row'); r.innerHTML = '<div class="rl">' + esc(label) + '</div><input type="number" value="' + val + '"> <span class="muted" style="width:26px">' + esc(unit) + '</span>';
  const inp = r.querySelector('input');
  inp.onchange = () => { let v = parseFloat(inp.value); if (isNaN(v)) return; if (isInt) v = Math.round(v); saveConfig({ [key]: v }); };
  return r;
}
function rangeRow(label, key, min, max, step, val, unit, desc) {
  const r = el('div', 'row col');
  r.innerHTML = '<div class="row" style="padding:0"><div class="rl">' + esc(label) + '</div><span class="muted"><b id="rv_' + key + '">' + val + '</b> ' + esc(unit) + '</span></div><input type="range" min="' + min + '" max="' + max + '" step="' + step + '" value="' + val + '"><div class="desc">' + esc(desc) + '</div>';
  const inp = r.querySelector('input');
  inp.oninput = () => { $('#rv_' + key).textContent = inp.value; };
  inp.onchange = () => saveConfig({ [key]: parseFloat(inp.value) });
  return r;
}
function switchRow(label, key, on, desc) {
  const r = el('label', 'row switch');
  r.innerHTML = '<div class="rl">' + esc(label) + '<div class="desc">' + esc(desc) + '</div></div><input type="checkbox"' + (on ? ' checked' : '') + '><i></i>';
  r.querySelector('input').onchange = (e) => saveConfig({ [key]: e.target.checked });
  return r;
}
function segRow(label, vals, names, cur, fn) {
  const r = el('div', 'row col');
  const seg = el('div', 'seg');
  vals.forEach((v, i) => { const b = el('button', v === cur ? 'on' : '', esc(names[i])); b.onclick = () => { seg.querySelectorAll('button').forEach(x => x.classList.remove('on')); b.classList.add('on'); fn(v); }; seg.appendChild(b); });
  r.innerHTML = '<div class="rl" style="margin-bottom:8px">' + esc(label) + '</div>';
  r.appendChild(seg);
  return r;
}
function chip(t, fn, danger) { const b = el('button', 'chip' + (danger ? '' : ''), esc(t)); if (danger) b.style.color = 'var(--bad)'; b.onclick = fn; return b; }

async function getConfig() { try { const st = JSON.parse(await N.getState()); return st.config || {}; } catch (e) { return {}; } }
function saveConfig(obj) { N.setConfig(JSON.stringify(obj)); Object.assign(cfg, obj); }

/* ==== route page ==== */
function renderRoutePage() {
  renderRoutePoints();
  renderSavedRoutes();
  $('#routeSpeed').value = 5; $('#routeSpeedText').textContent = '5 km/h';
  updateRouteSummary();
}
function enterRouteAddMode() { routeAddMode = true; $('#routeEdit').classList.add('on'); $('#routeEdit').textContent = '点击地图添加（完成）'; closePage(); toast('点击地图添加路径点，完成后回到本页'); collapseSheet(true); }
function exitRouteAddMode() { routeAddMode = false; const b = $('#routeEdit'); if (b) { b.classList.remove('on'); b.textContent = '在地图上添加路径点'; } clearRouteMarkers(); }
function addRoutePoint(lat, lng) { S.routePts.push({ lat, lng }); drawRoute(); toast('已添加第 ' + S.routePts.length + ' 个点'); vib(); }
function drawRoute() {
  clearRouteMarkers();
  if (routeLine) { map.removeLayer(routeLine); routeLine = null; }
  if (!S.routePts.length) return;
  const pts = S.routePts.map(p => toMap(p.lat, p.lng));
  routeLine = L.polyline(pts, { color: '#3B82F6', weight: 4, opacity: .8, dashArray: '2 8' }).addTo(map);
  pts.forEach((p, i) => { const m = L.marker(p, { icon: L.divIcon({ className: '', html: '<div class="wp">' + (i + 1) + '</div>', iconSize: [22, 22], iconAnchor: [11, 11] }) }).addTo(map); routeMarkers.push(m); });
}
function clearRouteMarkers() { routeMarkers.forEach(m => map.removeLayer(m)); routeMarkers = []; }
function renderRoutePoints() {
  const box = $('#routePoints'); box.innerHTML = '';
  $('#routeCount').textContent = S.routePts.length + ' 个';
  if (!S.routePts.length) { box.appendChild(el('div', 'empty-state', '还没有路径点')); return; }
  S.routePts.forEach((p, i) => {
    const it = el('div', 'item');
    it.innerHTML = '<span class="ii">' + (i + 1) + '</span><span class="it"><div class="in">' + fmt(p.lat, 5) + ', ' + fmt(p.lng, 5) + '</div></span><button class="more">✕</button>';
    it.querySelector('.more').onclick = () => { S.routePts.splice(i, 1); drawRoute(); renderRoutePoints(); updateRouteSummary(); };
    box.appendChild(it);
  });
}
function updateRouteSummary() {
  let d = 0; for (let i = 1; i < S.routePts.length; i++) d += haversine(S.routePts[i - 1], S.routePts[i]);
  const spd = parseFloat($('#routeSpeed').value) || 5;
  const sec = d / (spd / 3.6);
  $('#routeSummary').textContent = S.routePts.length < 2 ? '添加至少 2 个路径点' : ('全程约 ' + Math.round(d) + ' m，' + fmtDur(sec) + '（' + spd + ' km/h）');
}
function haversine(a, b) { const R = 6371008.8, p1 = a.lat * Math.PI / 180, p2 = b.lat * Math.PI / 180, dp = (b.lat - a.lat) * Math.PI / 180, dl = (b.lng - a.lng) * Math.PI / 180; const h = Math.sin(dp / 2) ** 2 + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) ** 2; return 2 * R * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h)); }
const fmtDur = (s) => s < 60 ? Math.round(s) + ' 秒' : s < 3600 ? Math.round(s / 60) + ' 分钟' : (s / 3600).toFixed(1) + ' 小时';
function startRouteSim() {
  if (S.routePts.length < 2) { toast('至少需要 2 个路径点'); return; }
  const spd = (parseFloat($('#routeSpeed').value) || 5) / 3.6;
  N.startRoute(JSON.stringify({ points: S.routePts, speed: spd, loop: $('#routeLoop').checked }));
  S.running = true; S.routeRunning = true;
  toast('路线模拟已开始'); vib(18);
  closePage(); collapseSheet(false);
  setTimeout(refreshStatus, 400);
}
function renderSavedRoutes() {
  const box = $('#routeSaved'); box.innerHTML = '';
  if (!S.routes.length) { box.appendChild(el('div', 'empty-state', '暂无保存的路线')); return; }
  S.routes.forEach((r, i) => {
    const it = el('div', 'item');
    it.innerHTML = '<span class="ii">' + r.points.length + '</span><span class="it"><div class="in">' + esc(r.name) + '</div><div class="ia">' + r.points.length + ' 点 · ' + r.speed + ' km/h</div></span><button class="more">⋯</button>';
    it.querySelector('.it').onclick = () => { S.routePts = r.points.slice(); $('#routeSpeed').value = r.speed; $('#routeSpeedText').textContent = r.speed + ' km/h'; $('#routeLoop').checked = !!r.loop; drawRoute(); renderRoutePoints(); updateRouteSummary(); toast('已载入路线'); };
    it.querySelector('.more').onclick = () => sheetMenu(r.name, [{ t: '删除', danger: true, fn: () => { S.routes.splice(i, 1); setJSON('routes', S.routes); renderSavedRoutes(); } }]);
    box.appendChild(it);
  });
}
function saveRoute() {
  if (S.routePts.length < 2) { toast('至少需要 2 个路径点'); return; }
  modalInput('保存路线', '路线 ' + (S.routes.length + 1), (name) => {
    S.routes.unshift({ name: name || '未命名路线', points: S.routePts.slice(), speed: parseFloat($('#routeSpeed').value) || 5, loop: $('#routeLoop').checked });
    setJSON('routes', S.routes); renderSavedRoutes(); toast('已保存');
  });
}

/* ==== env ==== */
async function renderEnv() {
  const box = $('#envList'); box.innerHTML = '<div class="empty-state">正在检查…</div>';
  let e;
  try { e = JSON.parse(await N.checkEnv()); } catch (err) { box.innerHTML = '<div class="empty-state">检查失败：' + esc(String(err)) + '</div>'; return; }
  box.innerHTML = '';
  const items = [
    ['Root 权限', e.root, e.root ? '已获取 su' : '未获取，无法自动配置，请授予本应用 Root'],
    ['Xposed 框架模块', e.moduleActive, e.moduleActive ? '模块已被框架加载' : '未激活，请在 LSPosed/Vector 中启用并重启'],
    ['系统框架 Hook', e.systemHook, e.systemHook ? '系统服务已注入，全局生效' : '未检测到 · 需勾选作用域「android」并重启一次'],
    ['配置可被读取', e.prefsWorldReadable, e.prefsWorldReadable ? '世界可读，Hook 能读到设置' : '不可读 · 需在模块设置里开启「使用共享偏好」'],
    ['模拟位置权限', e.mockAllowed, e.mockAllowed ? '已授予' : '未授予（点下方一键配置）'],
    ['悬浮窗权限', e.overlay, e.overlay ? '已授予（摇杆可用）' : '未授予，摇杆不可用'],
  ];
  items.forEach(([n, ok, d]) => box.appendChild(envItem(n, ok ? 'ok' : (n.includes('悬浮') ? 'warn' : 'bad'), d)));
  const dev = el('div', 'card muted small'); dev.textContent = e.device + ' · SDK ' + e.sdk + (e.scope ? '\n作用域: ' + e.scope.replace(/\s+/g, ' ') : '');
  dev.style.whiteSpace = 'pre-wrap'; box.appendChild(dev);
  if (e.mockError) { const m = el('div', 'card small'); m.style.color = 'var(--bad)'; m.textContent = '测试定位源错误：' + e.mockError; box.appendChild(m); }
  // drawer status summary
  updateDrawerStatus(e);
}
function envItem(name, st, desc) {
  const it = el('div', 'env');
  const sym = st === 'ok' ? '✓' : st === 'warn' ? '!' : '✕';
  it.innerHTML = '<span class="st ' + st + '">' + sym + '</span><span class="et"><div class="en">' + esc(name) + '</div><div class="ed">' + esc(desc) + '</div></span>';
  return it;
}
async function updateDrawerStatus(e) {
  if (!e) { try { e = JSON.parse(await N.checkEnv()); } catch (err) { return; } }
  const ds = $('#drawerStatus');
  if (e.systemHook) { ds.className = 'drawer-status ok'; ds.textContent = '✓ 全局模拟已就绪，系统框架已注入'; }
  else if (e.moduleActive) { ds.className = 'drawer-status bad'; ds.textContent = '⚠ 模块已启用但系统框架未生效，请到「环境检查」勾选作用域并重启手机'; }
  else { ds.className = 'drawer-status bad'; ds.textContent = '⚠ 模块未激活，请在 LSPosed/Vector 里启用后重启'; }
}

/* ==== modal & sheet menu ==== */
function openModal(html) { const m = $('#modal'); m.innerHTML = html; $('#modalScrim').classList.remove('hidden'); return m; }
function closeModal() { $('#modalScrim').classList.add('hidden'); }
function modalInput(title, val, onOk) {
  const m = openModal('<h3>' + esc(title) + '</h3><input id="mi" value="' + esc(val) + '"><div class="actions"><button id="mc">取消</button><button class="primary" id="mo">确定</button></div>');
  const inp = m.querySelector('#mi'); inp.focus(); inp.select && inp.select();
  m.querySelector('#mc').onclick = closeModal;
  m.querySelector('#mo').onclick = () => { closeModal(); onOk(inp.value.trim()); };
}
function modalText(title, placeholder, val, onOk) {
  const m = openModal('<h3>' + esc(title) + '</h3><textarea id="mt" placeholder="' + esc(placeholder) + '">' + esc(val || '') + '</textarea><div class="actions"><button id="mc">取消</button><button class="primary" id="mo">确定</button></div>');
  m.querySelector('#mc').onclick = closeModal;
  m.querySelector('#mo').onclick = () => { const v = m.querySelector('#mt').value; closeModal(); onOk(v); };
}
function confirmModal(title, msg, onOk, okText, danger) {
  const m = openModal('<h3>' + esc(title) + '</h3><p>' + esc(msg) + '</p><div class="actions"><button id="mc">取消</button><button class="' + (danger ? 'danger' : 'primary') + '" id="mo">' + esc(okText || '确定') + '</button></div>');
  m.querySelector('#mc').onclick = closeModal;
  m.querySelector('#mo').onclick = () => { closeModal(); onOk(); };
}
function sheetMenu(title, acts) {
  let h = '<h3>' + esc(title) + '</h3><div style="display:flex;flex-direction:column;gap:6px">';
  h += '</div>';
  const m = openModal(h);
  const box = m.querySelector('div');
  acts.forEach(a => { const b = el('button', 'chip', esc(a.t)); b.style.width = '100%'; b.style.height = '44px'; if (a.danger) b.style.color = 'var(--bad)'; b.onclick = () => { closeModal(); a.fn(); }; box.appendChild(b); });
  const c = el('button', 'chip', '取消'); c.style.width = '100%'; c.style.height = '44px'; c.onclick = closeModal; box.appendChild(c);
}

/* ==== import/export ==== */
async function exportAll() {
  const data = { config: await getConfig(), favorites: S.favs, routes: S.routes, amap_key: await getApp('amap_key', ''), input_crs: S.crs, theme: await getApp('theme', 'auto') };
  N.copy(JSON.stringify(data, null, 2)); toast('已复制到剪贴板');
}
function importAll() {
  const clip = N.paste ? N.paste() : '';
  modalText('导入设置', '粘贴之前导出的 JSON', clip, (v) => {
    try { const d = JSON.parse(v); if (d.config) saveConfig(d.config); if (d.favorites) { S.favs = d.favorites; setJSON('favorites', S.favs); } if (d.routes) { S.routes = d.routes; setJSON('routes', S.routes); } if (d.amap_key != null) setApp('amap_key', d.amap_key); if (d.input_crs) { S.crs = d.input_crs; setApp('input_crs', d.input_crs); } if (d.theme) { setApp('theme', d.theme); applyTheme(d.theme); } toast('导入成功'); renderSettings(); } catch (e) { toast('JSON 解析失败'); }
  });
}
function resetDefaults() { confirmModal('恢复默认', '将清空所有模拟参数并恢复默认值，收藏与历史保留。', () => { saveConfig({ jitter: 3, alt: 50, acc: 8, interval: 1000, wifi_block: true, cell_block: true, gnss_block: true, mock_driver: true, mock_network: true, exempt: '' }); renderSettings(); toast('已恢复默认'); }, '恢复', true); }

/* ==== favorites import/export ==== */
function favExport() { N.copy(JSON.stringify(S.favs)); toast('收藏已复制'); }
function favImport() { const clip = N.paste ? N.paste() : ''; modalText('导入收藏', '粘贴收藏 JSON', clip, (v) => { try { const a = JSON.parse(v); if (Array.isArray(a)) { S.favs = a.concat(S.favs); setJSON('favorites', S.favs); renderFavs(); toast('已导入 ' + a.length + ' 条'); } } catch (e) { toast('解析失败'); } }); }

/* ==== theme ==== */
function applyTheme(t) {
  let dark = t === 'dark';
  if (t === 'auto') dark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
  document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
  S.night = dark;
}

/* ==== wiring ==== */
function bind() {
  $('#btnMenu').onclick = () => openDrawer(true);
  $('#scrim').onclick = () => openDrawer(false);
  $$('.nav').forEach(b => b.onclick = () => openPage(b.dataset.page));
  $$('[data-back]').forEach(b => b.onclick = () => closePage());
  $('#q').addEventListener('input', onSearchInput);
  $('#q').addEventListener('focus', () => { if ($('#q').value.trim().length >= 2) $('#results').classList.add('open'); });
  $('#btnClear').onclick = () => { $('#q').value = ''; $('#searchBox').classList.remove('has-text'); $('#results').classList.remove('open'); };
  $('#btnGo').onclick = toggleSpoof;
  $('#btnFav').onclick = toggleFav;
  $('#btnCopy').onclick = () => { if (!S.target) return; N.copy($('#coordText').textContent); toast('已复制'); };
  $('#coordBtn').onclick = () => { const arr = ['wgs84', 'gcj02', 'bd09']; S.crs = arr[(arr.indexOf(S.crs) + 1) % 3]; setApp('input_crs', S.crs); updateSheet(); };
  $('#fabLocate').onclick = locateReal;
  $('#fabLayers').onclick = (e) => { e.stopPropagation(); $('#layerPop').classList.toggle('hidden'); };
  document.addEventListener('click', (e) => { if (!$('#layerPop').contains(e.target) && e.target !== $('#fabLayers') && !$('#fabLayers').contains(e.target)) $('#layerPop').classList.add('hidden'); });
  $$('#layerPop .opt').forEach(o => o.onclick = () => { setLayer(o.dataset.layer); $('#layerPop').classList.add('hidden'); });
  $('#fabDpad').onclick = () => { const d = $('#dpad'); d.classList.toggle('hidden'); $('#fabDpad').classList.toggle('on', !d.classList.contains('hidden')); };
  $('#dpadClose').onclick = () => { $('#dpad').classList.add('hidden'); $('#fabDpad').classList.remove('on'); };
  $$('#dpad .dpad-grid button[data-d]').forEach(b => b.onclick = () => nudge(+b.dataset.d));
  $$('#dpad .dpad-steps button').forEach(b => b.onclick = () => { $$('#dpad .dpad-steps button').forEach(x => x.classList.remove('on')); b.classList.add('on'); S.dpadStep = +b.dataset.step; $('#dpadStep').textContent = b.textContent; });
  $('#fabJoy').onclick = () => { N.toggleJoystick(); vib(); setTimeout(refreshStatus, 300); };
  // favorites page
  $('#favAdd').onclick = () => { if (S.target) toggleFav(); else toast('请先选择位置'); };
  $('#favImport').onclick = favImport; $('#favExport').onclick = favExport;
  $('#histClear').onclick = () => confirmModal('清空历史', '确定清空所有历史记录？', () => { S.hist = []; setJSON('history', []); renderHist(); }, '清空', true);
  // route page
  $('#routeEdit').onclick = () => { if (routeAddMode) exitRouteAddMode(); else enterRouteAddMode(); };
  $('#routeAddCur').onclick = () => { if (S.target) { addRoutePoint(S.target.lat, S.target.lng); renderRoutePoints(); updateRouteSummary(); } else toast('请先选择目标'); };
  $('#routeClear').onclick = () => { S.routePts = []; drawRoute(); renderRoutePoints(); updateRouteSummary(); };
  $('#routeGo').onclick = startRouteSim;
  $('#routeStop').onclick = () => { N.stopRoute(); S.routeRunning = false; toast('已停止路线'); setTimeout(refreshStatus, 300); };
  $('#routeSave').onclick = saveRoute;
  $('#routeSpeed').oninput = () => { $('#routeSpeedText').textContent = $('#routeSpeed').value + ' km/h'; updateRouteSummary(); };
  $$('#page-route [data-speed]').forEach(b => b.onclick = () => { $('#routeSpeed').value = b.dataset.speed; $('#routeSpeedText').textContent = b.dataset.speed + ' km/h'; updateRouteSummary(); });
  // env page
  $('#envRefresh').onclick = renderEnv;
  $('#envSetup').onclick = () => { toast('正在配置…'); call2(N.rootSetup).then(r => { openModal('<h3>一键配置结果</h3><p>' + esc(r) + '</p><div class="actions"><button class="primary" id="mo">好</button></div>').querySelector('#mo').onclick = closeModal; renderEnv(); }); };
  $('#envReboot').onclick = () => confirmModal('重启手机', '现在重启手机以使框架作用域生效？', () => N.reboot(), '重启');
  $('#envLog').onclick = () => { const box = $('#envLogBox'); box.classList.toggle('hidden'); if (!box.classList.contains('hidden')) box.textContent = N.frameworkLog ? N.frameworkLog() : '不可用'; };
  window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => { getApp('theme', 'auto').then(t => { if (t === 'auto') applyTheme('auto'); }); });
}
// call a Native method that returns a String synchronously
function call2(fn) { return new Promise(res => { try { res(fn()); } catch (e) { res(String(e)); } }); }

async function locateReal() {
  toast('正在获取真实位置…');
  const r = await call('locate');
  if (!r || !r.ok) { toast(r && r.error || '定位失败'); return; }
  if (!realMarker) realMarker = L.marker([0, 0], { icon: L.divIcon({ className: '', html: '<div class="real-dot"></div>', iconSize: [14, 14], iconAnchor: [7, 7] }), interactive: false });
  const p = toMap(r.lat, r.lng); realMarker.setLatLng(p); realMarker.addTo(map);
  map.flyTo(p, 16, { duration: .5 });
  toast('真实位置 · ' + r.provider + ' · 精度' + Math.round(r.acc) + 'm');
  confirmModal('使用真实位置？', '把当前真实位置设为模拟目标（会先纠偏为标准坐标）。', () => pickTarget(r.lat, r.lng, { name: '当前真实位置', reverse: true }));
}

/* ==== boot ==== */
async function boot() {
  S.layer = await getApp('map_layer', 'amap');
  S.crs = await getApp('input_crs', 'wgs84');
  S.searchSrc = await getApp('search_src', 'auto');
  const theme = await getApp('theme', 'auto'); applyTheme(theme);
  await loadLists();
  initMap();
  bind();
  initSheetDrag();
  // restore last target
  try {
    const st = JSON.parse(await N.getState());
    S.version = st.version || '1.0'; $('#verText').textContent = S.version;
    const c = st.config || {};
    if (c.lat && c.lng) { S.target = { lat: +c.lat, lng: +c.lng }; redrawMarkers(); map.setView(toMap(S.target.lat, S.target.lng), 15); }
    if (S.hist[0]) S.name = S.hist[0].name;
  } catch (e) {}
  updateSheet();
  refreshStatus();
  updateDrawerStatus();
  statusTimer = setInterval(refreshStatus, 1500);
  window.onNativeResume = () => { refreshStatus(); loadLists(); };
}
window.addEventListener('DOMContentLoaded', boot);
