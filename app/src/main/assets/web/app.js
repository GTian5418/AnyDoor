'use strict';
/* ==== Native bridge ==== */
const N = window.Native || window.__mockNative;
const cbs = {};
let cbSeq = 1;
window.__cb = (id, data) => { const f = cbs[id]; if (f) { delete cbs[id]; f(data); } };
function call(method, ...args) {
  return new Promise((resolve) => {
    const id = cbSeq++;
    const timeoutMs = method === 'locate' ? 12000 : method === 'planRoute' ? 120000 : 30000;
    const finish = (data) => { clearTimeout(timer); delete cbs[id]; resolve(data); };
    const timer = setTimeout(() => finish({ ok: false, error: method === 'locate'
      ? '定位超时，请确认定位权限和系统定位开关后重试' : '请求超时，请检查网络后重试' }), timeoutMs);
    cbs[id] = finish;
    try { N[method](id, ...args); } catch (e) { finish({ ok: false, error: String(e) }); }
  });
}
// Both entry points share one request; repeated taps cannot build an unbounded native queue.
let locating = null;
function requestRealLocation() {
  if (!locating) locating = call('locate').then(r => { locating = null; return r; });
  return locating;
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
  routePts: [],        // [{lat,lng,s?}] WGS-84 – manual waypoints or the planned polyline
  routeRunning: false,
  routeTab: 'plan',    // plan | manual
  routeMode: 'walking',
  routeLoop: 'none',   // none | pingpong | loop
  plan: { start: null, end: null, planned: null },   // start/end: {lat,lng,name}; planned: {distance,duration,mode}
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
  map.on('click', (e) => {
    const w = fromMap(e.latlng.lat, e.latlng.lng);
    if (mapPick) { const f = mapPick; mapPick = null; vib(); f(w[0], w[1]); return; }
    pickTarget(w[0], w[1], { reverse: true, fly: false }); vib();
  });
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
  if (S.running) { toast('已请求开始 · 可到环境检查确认同步'); addHistory({ name: S.name || '地图选点', addr: S.addr, lat: S.target.lat, lng: S.target.lng }); }
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
    $('#routeText').textContent = (svc.paused ? '路口停顿 · ' : '路线模拟 · ') + Math.round(svc.routeDone) + '/' + Math.round(svc.routeTotal) + ' m';
  } else rb.classList.add('hidden');
  if (S.page === 'route') updateStepStatus(svc);
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
  else if (id === 'privacy') renderPrivacy();
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
  row.innerHTML = '<div class="rl"><input type="text" class="wide" id="exempt" placeholder="包名，逗号分隔，如 com.autonavi.minimap"><div class="desc">设置后会停用全局测试定位源，保留这些应用的系统定位；其他应用依赖真实定位回调，室内可能等待。</div></div>';
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
let mapPick = null;   // one-shot map click handler (start / end picking)
const MODES = {
  walking:   { name: '步行', def: 5,  min: 2,  max: 8,   stride: 0.7, chips: [3, 5, 6] },
  running:   { name: '跑步', def: 10, min: 6,  max: 20,  stride: 1.1, chips: [8, 10, 12, 15] },
  bicycling: { name: '骑行', def: 18, min: 8,  max: 35,  stride: 0,   chips: [12, 18, 25] },
  driving:   { name: '驾车', def: 45, min: 15, max: 120, stride: 0,   chips: [30, 45, 60, 80, 100] },
};
async function renderRoutePage() {
  cfg = await getConfig();
  setRouteTab(S.routeTab);
  applyMode(S.routeMode, true);
  $('#stepFake').checked = !!cfg.step_fake;
  $('#stride').value = cfg.stride || 0.7;
  $$('#loopSeg button').forEach(b => b.classList.toggle('on', b.dataset.loop === S.routeLoop));
  renderEndpoints(); renderRoutePoints(); renderSavedRoutes(); updateRouteSummary();
  refreshStatus();
}
function setRouteTab(t) {
  S.routeTab = t;
  $$('#routeTabs button').forEach(b => b.classList.toggle('on', b.dataset.tab === t));
  $('#routePlan').classList.toggle('hidden', t !== 'plan');
  $('#routeManual').classList.toggle('hidden', t !== 'manual');
  if (t === 'manual' && S.plan.planned) { S.routePts = []; S.plan.planned = null; drawRoute(); }
  if (t === 'plan' && !S.plan.planned && S.routePts.length) { S.routePts = []; drawRoute(); }
  updateRouteSummary();
}
function applyMode(m, keepSpeed) {
  const M = MODES[m] || MODES.walking; S.routeMode = m;
  $$('#modeChips .chip').forEach(b => b.classList.toggle('on', b.dataset.mode === m));
  const sl = $('#routeSpeed'); sl.min = M.min; sl.max = M.max;
  if (!keepSpeed || +sl.value < M.min || +sl.value > M.max) sl.value = M.def;
  $('#routeSpeedText').textContent = sl.value + ' km/h';
  const sc = $('#speedChips'); sc.innerHTML = '';
  M.chips.forEach(v => { const b = el('button', 'chip', v + ' km/h'); b.onclick = () => { sl.value = v; $('#routeSpeedText').textContent = v + ' km/h'; updateRouteSummary(); }; sc.appendChild(b); });
  if (M.stride) $('#stride').value = M.stride;
  if (S.plan.planned && S.plan.planned.mode !== m) { S.plan.planned = null; $('#planSummary').textContent = '交通方式已变，请重新规划。'; }
  updateRouteSummary();
}
function renderEndpoints() {
  const st = S.plan.start, en = S.plan.end;
  $('#epStartText').textContent = st ? (st.name || fmt(st.lat, 5) + ', ' + fmt(st.lng, 5)) : '当前模拟位置' + (S.target ? '（' + (S.name || fmt(S.target.lat, 5) + ', ' + fmt(S.target.lng, 5)) + '）' : '');
  $('#epEndText').textContent = en ? (en.name || fmt(en.lat, 5) + ', ' + fmt(en.lng, 5)) : '未设置终点';
  drawEndpoints();
}
function endpointMenu(which) {
  const set = (lat, lng, name) => { S.plan[which] = { lat, lng, name }; S.plan.planned = null; S.routePts = []; drawRoute(); renderEndpoints(); updateRouteSummary(); };
  const acts = [];
  if (which === 'start') acts.push({ t: '当前模拟位置（默认）', fn: () => { S.plan.start = null; S.plan.planned = null; renderEndpoints(); } });
  else if (S.target) acts.push({ t: '当前模拟目标', fn: () => set(S.target.lat, S.target.lng, S.name) });
  acts.push({ t: '在地图上点选', fn: () => { closePage(); toast('点击地图选择' + (which === 'start' ? '起点' : '终点')); mapPick = (lat, lng) => { set(lat, lng, (which === 'start' ? '起点' : '终点') + ' · 地图选点'); openPage('route'); }; } });
  acts.push({ t: '搜索地点', fn: () => placePicker(which === 'start' ? '搜索起点' : '搜索终点', (it) => set(it.lat, it.lng, it.name)) });
  if (S.favs.length) acts.push({ t: '从收藏选择', fn: () => listPicker('收藏夹', S.favs, (f) => set(f.lat, f.lng, f.name)) });
  if (S.hist.length) acts.push({ t: '从历史选择', fn: () => listPicker('历史记录', S.hist, (h) => set(h.lat, h.lng, h.name)) });
  if (which === 'start') acts.push({ t: '真实位置', fn: async () => { const r = await requestRealLocation(); if (r && r.ok) set(r.lat, r.lng, '真实位置'); else toast(r && r.error || '定位失败'); } });
  sheetMenu(which === 'start' ? '起点' : '终点', acts);
}
function listPicker(title, items, onPick) {
  const m = openModal('<h3>' + esc(title) + '</h3><div class="picker-list" id="pl"></div><div class="actions"><button id="mc">取消</button></div>');
  const box = m.querySelector('#pl');
  items.slice(0, 40).forEach(it => { const b = el('button', '', esc(it.name || '未命名') + '<small>' + esc(it.addr || fmt(it.lat, 5) + ', ' + fmt(it.lng, 5)) + '</small>'); b.onclick = () => { closeModal(); onPick(it); }; box.appendChild(b); });
  m.querySelector('#mc').onclick = closeModal;
}
function placePicker(title, onPick) {
  const m = openModal('<h3>' + esc(title) + '</h3><input id="pq" placeholder="地点名称，或粘贴坐标" autocomplete="off"><div class="picker-list" id="pl"></div><div class="actions"><button id="mc">取消</button></div>');
  const inp = m.querySelector('#pq'), box = m.querySelector('#pl');
  m.querySelector('#mc').onclick = closeModal;
  let seq = 0, timer;
  inp.oninput = () => {
    clearTimeout(timer);
    const q = inp.value.trim();
    const c = parseCoord(q);
    if (c) { box.innerHTML = ''; const b = el('button', '', '坐标 ' + fmt(c[0]) + ', ' + fmt(c[1]) + '<small>' + c[2] + '</small>'); b.onclick = () => { closeModal(); onPick({ lat: c[0], lng: c[1], name: '坐标 ' + fmt(c[0], 4) + ', ' + fmt(c[1], 4) }); }; box.appendChild(b); return; }
    if (q.length < 2) { box.innerHTML = ''; return; }
    timer = setTimeout(async () => {
      const my = ++seq;
      box.innerHTML = '<div class="muted small">搜索中…</div>';
      const near = S.target || mapCenterWGS();
      const r = await call('search', q, near.lat || 0, near.lng || 0);
      if (my !== seq) return;
      box.innerHTML = '';
      if (!r || !r.ok || !r.items || !r.items.length) { box.innerHTML = '<div class="muted small">' + esc((r && r.error) || '没有找到相关地点') + '</div>'; return; }
      r.items.forEach(it => { const b = el('button', '', esc(it.name) + '<small>' + esc(it.address || it.addr || '') + '</small>'); b.onclick = () => { closeModal(); onPick({ lat: it.lat, lng: it.lng, name: it.name, addr: it.address }); }; box.appendChild(b); });
    }, 350);
  };
  setTimeout(() => inp.focus(), 50);
}
async function planRoute() {
  const start = S.plan.start || (S.target ? { lat: S.target.lat, lng: S.target.lng } : null);
  if (!start) { toast('请先设置起点'); return; }
  if (!S.plan.end) { toast('请先选择终点'); return; }
  const key = await getApp('amap_key', '');
  if (!key || !key.trim()) { promptAmapKey(null, '路径规划需要高德 Key'); return; }
  $('#planSummary').textContent = '正在规划…';
  const r = await call('planRoute', JSON.stringify({ mode: S.routeMode, points: [start, S.plan.end] }));
  if (!r || !r.ok) {
    let msg = (r && r.error) || '规划失败';
    if (/NO_KEY/.test(msg)) msg = '需要高德 Key';
    else if (/DAILY_QUERY_OVER_LIMIT|CUQPS|QUOTA|LIMIT/i.test(msg)) msg = '高德 Key 今日额度用尽';
    else if (/USERKEY|INVALID/i.test(msg)) msg = '高德 Key 无效（需「Web服务」类型）';
    $('#planSummary').textContent = '规划失败：' + msg; toast('规划失败'); return;
  }
  S.routePts = r.points; S.plan.planned = { distance: r.distance, duration: r.duration, mode: S.routeMode };
  drawRoute(); fitRoute(); updateRouteSummary(); vib();
  const crossings = r.points.filter(p => p.s).length;
  $('#planSummary').textContent = MODES[S.routeMode].name + '路线 · 全程 ' + fmtDist(r.distance) + ' · ' + r.points.length + ' 个路径点 · ' + crossings + ' 处路口 · 高德预计 ' + fmtDur(r.duration);
}
function fitRoute() { if (S.routePts.length > 1) map.fitBounds(L.latLngBounds(S.routePts.map(p => toMap(p.lat, p.lng))), { padding: [40, 40], maxZoom: 17 }); }
let epMarkers = [];
function drawEndpoints() {
  epMarkers.forEach(m => map.removeLayer(m)); epMarkers = [];
  if (S.routeTab !== 'plan') return;
  const st = S.plan.start, en = S.plan.end;
  const mk = (pt, cls, txt) => { const m = L.marker(toMap(pt.lat, pt.lng), { icon: L.divIcon({ className: '', html: '<div class="wp ' + cls + '">' + txt + '</div>', iconSize: [22, 22], iconAnchor: [11, 11] }), interactive: false }).addTo(map); epMarkers.push(m); };
  if (st) mk(st, 'start', '起');
  if (en) mk(en, 'end', '终');
}
function enterRouteAddMode() { routeAddMode = true; $('#routeEdit').classList.add('on'); $('#routeEdit').textContent = '点击地图添加（完成）'; closePage(); toast('点击地图添加路径点，完成后回到本页'); collapseSheet(true); }
function exitRouteAddMode() { routeAddMode = false; const b = $('#routeEdit'); if (b) { b.classList.remove('on'); b.textContent = '在地图上添加路径点'; } clearRouteMarkers(); }
function addRoutePoint(lat, lng) { S.routePts.push({ lat, lng }); drawRoute(); toast('已添加第 ' + S.routePts.length + ' 个点'); vib(); }
function drawRoute() {
  clearRouteMarkers();
  if (routeLine) { map.removeLayer(routeLine); routeLine = null; }
  if (!S.routePts.length) return;
  const pts = S.routePts.map(p => toMap(p.lat, p.lng));
  const planned = !!S.plan.planned;
  routeLine = L.polyline(pts, { color: '#3B82F6', weight: planned ? 5 : 4, opacity: .85, dashArray: planned ? null : '2 8' }).addTo(map);
  if (!planned) pts.forEach((p, i) => { const m = L.marker(p, { icon: L.divIcon({ className: '', html: '<div class="wp">' + (i + 1) + '</div>', iconSize: [22, 22], iconAnchor: [11, 11] }) }).addTo(map); routeMarkers.push(m); });
}
function clearRouteMarkers() { routeMarkers.forEach(m => map.removeLayer(m)); routeMarkers = []; }
function renderRoutePoints() {
  const box = $('#routePoints'); box.innerHTML = '';
  const pts = S.plan.planned ? [] : S.routePts;
  $('#routeCount').textContent = pts.length + ' 个';
  if (!pts.length) { box.appendChild(el('div', 'empty-state', '还没有路径点')); return; }
  pts.forEach((p, i) => {
    const it = el('div', 'item');
    it.innerHTML = '<span class="ii">' + (i + 1) + '</span><span class="it"><div class="in">路径点 ' + (i + 1) + '</div><div class="ia">' + fmt(p.lat, 5) + ', ' + fmt(p.lng, 5) + '</div></span><button class="more">✕</button>';
    it.querySelector('.more').onclick = () => { S.routePts.splice(i, 1); drawRoute(); renderRoutePoints(); updateRouteSummary(); };
    box.appendChild(it);
  });
}
function haversine(a, b) { const R = 6371008.8, p1 = a.lat * Math.PI / 180, p2 = b.lat * Math.PI / 180, dp = (b.lat - a.lat) * Math.PI / 180, dl = (b.lng - a.lng) * Math.PI / 180; const h = Math.sin(dp / 2) ** 2 + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) ** 2; return 2 * R * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h)); }
const fmtDist = (m) => m >= 1000 ? (m / 1000).toFixed(2) + ' km' : Math.round(m) + ' m';
function routeDistance() { let d = 0; for (let i = 1; i < S.routePts.length; i++) d += haversine(S.routePts[i - 1], S.routePts[i]); return d; }
function currentStride() { const M = MODES[S.routeMode]; if (!M.stride) return 0; const v = parseFloat($('#stride').value); return isNaN(v) || v <= 0 ? M.stride : v; }
function updateRouteSummary() {
  const d = routeDistance();
  const spd = parseFloat($('#routeSpeed').value) || 5;
  const sec = d / (spd / 3.6);
  $('#routeVarText').textContent = '±' + $('#routeVar').value + '%';
  const stride = currentStride();
  $('#routeSummary').textContent = S.routePts.length < 2 ? (S.routeTab === 'plan' ? '先规划一条路线' : '添加至少 2 个路径点') : ('全程 ' + fmtDist(d) + '，约 ' + fmtDur(sec) + '（' + spd + ' km/h' + (S.routeLoop === 'pingpong' ? '，往返' : S.routeLoop === 'loop' ? '，循环' : '') + '）');
  $('#stepEstimate').textContent = stride ? ('按步幅 ' + stride + ' m，全程约 ' + Math.round(d / stride) + ' 步' + (S.routeMode === 'running' ? '（跑步）' : '')) : '骑行 / 驾车不产生步数';
}
function fmtDur(sec) { if (!isFinite(sec)) return '--'; if (sec < 60) return Math.round(sec) + ' 秒'; if (sec < 3600) return Math.round(sec / 60) + ' 分钟'; return (sec / 3600).toFixed(1) + ' 小时'; }
function startRouteSim() {
  if (S.routePts.length < 2) { toast(S.routeTab === 'plan' ? '请先规划路线' : '至少需要 2 个路径点'); return; }
  const spd = (parseFloat($('#routeSpeed').value) || 5) / 3.6;
  saveConfig({ step_fake: $('#stepFake').checked, stride: parseFloat($('#stride').value) || 0.7 });
  N.startRoute(JSON.stringify({ points: S.routePts, speed: spd, loop: S.routeLoop, mode: S.routeMode,
    var: (parseFloat($('#routeVar').value) || 0) / 100, pause: $('#routePause').checked, stride: currentStride() }));
  S.running = true; S.routeRunning = true;
  closePage(); collapseSheet(false); updatePill(); updateSheet();
  toast('路线模拟开始 · ' + MODES[S.routeMode].name); vib(18);
  setTimeout(refreshStatus, 400);
}
function updateStepStatus(svc) {
  $('#curSteps').textContent = Math.round(svc.steps || cfg.steps || 0);
  const left = Math.round(svc.burstLeft || 0);
  $('#burstText').textContent = left > 0 ? ('刷步中，还剩 ' + left + ' 步') : '';
}
function stepBurst() {
  const n = parseInt($('#burstN').value, 10), rate = parseInt($('#burstRate').value, 10);
  if (!n || n <= 0) { toast('请输入步数'); return; }
  saveConfig({ step_fake: true }); $('#stepFake').checked = true;
  N.stepBurst(n, rate || 110); S.running = true; toast('开始刷步：' + n + ' 步 @ ' + (rate || 110) + ' 步/分'); vib();
  setTimeout(refreshStatus, 400);
}
function renderSavedRoutes() {
  const box = $('#routeSaved'); box.innerHTML = '';
  if (!S.routes.length) { box.appendChild(el('div', 'empty-state', '暂无保存的路线')); return; }
  S.routes.forEach((r, i) => {
    const it = el('div', 'item');
    const tag = r.planned ? (MODES[r.mode] || MODES.walking).name + ' · ' : '';
    it.innerHTML = '<span class="ii">' + (r.planned ? '⇢' : '⤳') + '</span><span class="it"><div class="in">' + esc(r.name) + '</div><div class="ia">' + tag + r.points.length + ' 个点 · ' + r.speed + ' km/h' + (r.loop && r.loop !== 'none' && r.loop !== false ? ' · ' + (r.loop === 'pingpong' ? '往返' : '循环') : '') + '</div></span><button class="more">⋯</button>';
    it.querySelector('.it').onclick = () => {
      S.routePts = r.points.slice(); S.routeLoop = typeof r.loop === 'boolean' ? (r.loop ? 'loop' : 'none') : (r.loop || 'none');
      if (r.planned) { S.plan.start = r.start || null; S.plan.end = r.end || null; S.plan.planned = { distance: routeDistance(), duration: 0, mode: r.mode }; S.routeTab = 'plan'; }
      else { S.plan.planned = null; S.routeTab = 'manual'; }
      setRouteTab(S.routeTab); applyMode(r.mode || 'walking', true); $('#routeSpeed').value = r.speed; $('#routeSpeedText').textContent = r.speed + ' km/h';
      $$('#loopSeg button').forEach(b => b.classList.toggle('on', b.dataset.loop === S.routeLoop));
      drawRoute(); renderEndpoints(); renderRoutePoints(); updateRouteSummary(); fitRoute(); toast('已载入路线');
      if (r.planned) $('#planSummary').textContent = '已载入保存的' + MODES[r.mode || 'walking'].name + '路线 · ' + fmtDist(routeDistance());
    };
    it.querySelector('.more').onclick = () => sheetMenu(r.name, [{ t: '删除', danger: true, fn: () => { S.routes.splice(i, 1); setJSON('routes', S.routes); renderSavedRoutes(); } }]);
    box.appendChild(it);
  });
}
function saveRoute() {
  if (S.routePts.length < 2) { toast('至少需要 2 个路径点'); return; }
  const def = S.plan.planned && S.plan.end ? ((S.plan.start ? S.plan.start.name : (S.name || '当前位置')) + ' → ' + S.plan.end.name) : '路线 ' + (S.routes.length + 1);
  modalInput('保存路线', def, (name) => {
    S.routes.unshift({ name: name || '未命名路线', points: S.routePts.slice(), speed: parseFloat($('#routeSpeed').value) || 5, loop: S.routeLoop,
      mode: S.routeMode, planned: !!S.plan.planned, start: S.plan.start, end: S.plan.end });
    setJSON('routes', S.routes); renderSavedRoutes(); toast('已保存');
  });
}

/* ==== privacy page ==== */
const ID_LABELS = { fake_imei: 'IMEI', fake_meid: 'MEID', fake_imsi: 'IMSI', fake_iccid: 'ICCID', fake_android_id: 'Android ID', fake_serial: '序列号', fake_phone: '手机号' };
async function renderPrivacy() {
  cfg = await getConfig();
  const b = $('#privacyBody'); b.innerHTML = '';
  const on = !!cfg.privacy;
  const c0 = el('div', 'card');
  c0.innerHTML = '<label class="big-switch switch"><div><div class="bt">一键隐私加固</div><div class="desc">对所有普通 App：屏蔽 WiFi / 基站 / GNSS / 蓝牙环境，伪造 IMEI、IMSI、ICCID、Android ID、序列号等设备标识，屏蔽气压计。不依赖是否正在模拟位置。</div></div><input type="checkbox" id="pvMaster"' + (on ? ' checked' : '') + '><i></i></label>';
  b.appendChild(c0);
  $('#pvMaster').onchange = (e) => { N.privacyPreset(e.target.checked); vib(); toast(e.target.checked ? '隐私加固已开启' : '隐私加固已关闭'); setTimeout(renderPrivacy, 150); };

  b.appendChild(sectionTitle('细分开关'));
  const c1 = el('div', 'card');
  const envOn = cfg.wifi_block !== false && cfg.cell_block !== false && cfg.gnss_block !== false;
  const envRow = switchRow('屏蔽定位环境', '_env', envOn, 'WiFi 列表、基站、原始 GNSS 一律返回空（三项一起切换）');
  envRow.querySelector('input').onchange = (e) => saveConfig({ wifi_block: e.target.checked, cell_block: e.target.checked, gnss_block: e.target.checked });
  c1.appendChild(envRow);
  c1.appendChild(switchRow('伪造设备标识', 'id_spoof', cfg.id_spoof !== false, 'IMEI / MEID / IMSI / ICCID / Android ID / 序列号 / 本机号码；Android 10+ 普通 App 本就读不到 IMEI，此处主要覆盖 Android ID 与旧系统'));
  c1.appendChild(switchRow('屏蔽蓝牙扫描', 'bt_block', cfg.bt_block !== false, '任何 App（包括系统设置）都扫不到蓝牙设备与 Beacon；需把「蓝牙」加入作用域'));
  c1.appendChild(switchRow('屏蔽气压计', 'sensor_block', cfg.sensor_block !== false, '防止用气压反推楼层 / 海拔；仅对加入作用域的 App 生效'));
  b.appendChild(c1);

  b.appendChild(sectionTitle('虚拟设备身份'));
  const c2 = el('div', 'card');
  let ids = {};
  try { ids = JSON.parse(await N.getIdentity()); } catch (e) {}
  Object.keys(ID_LABELS).forEach(k => { const r = el('div', 'id-row'); r.innerHTML = '<span>' + ID_LABELS[k] + '</span><code>' + esc(ids[k] || '--') + '</code>'; c2.appendChild(r); });
  const tb = el('div', 'toolbar');
  tb.append(chip('重新生成身份', () => confirmModal('重新生成', '将生成一套新的随机标识。部分 App（银行、支付、游戏）可能因此判定为新设备并要求重新登录。', async () => { try { await N.regenIdentity(); } catch (e) {} toast('已生成新身份'); renderPrivacy(); }, '生成', true)),
    chip('复制', () => { N.copy(Object.keys(ID_LABELS).map(k => ID_LABELS[k] + ': ' + (ids[k] || '')).join('\\n')); toast('已复制'); }));
  c2.appendChild(tb);
  const note = el('div', 'desc muted small'); note.style.marginTop = '8px'; note.textContent = '标识固定不变，App 会把它当作同一台「别的手机」。系统 App（设置 / SystemUI）、豁免应用看到的仍是真实值。';
  c2.appendChild(note);
  b.appendChild(c2);

  b.appendChild(sectionTitle('必须知道的边界'));
  const c3 = el('div', 'card warn-card');
  c3.innerHTML = '<div class="card-title">这些做不到，任何软件都做不到</div><ul class="tight">' +
    '<li><b>运营商</b>始终能通过基站知道 SIM 卡在哪；本工具只能挡住 App，挡不住运营商。真要不可定位：飞行模式 + 拔卡。</li>' +
    '<li><b>公网 IP</b> 由网络决定，IP 归属地不会跟着模拟位置走（手机流量通常只精确到省）。</li>' +
    '<li><b>GrapheneOS</b> 不能 Root、不能装 Xposed，本模块无法在其上运行；它自带的按 App 权限 / 网络 / 传感器开关已经覆盖了大部分需求。</li>' +
    '<li><b>机型信息</b>（Build.MODEL 等）、厂商广告 ID（OAID）、账号登录态未伪造 —— 改机型容易让 App 崩溃或封号。</li>' +
    '<li>计步 / 气压 / 客户端侧标识伪造只对<b>加入作用域</b>的 App 生效（强化模式）。</li>' +
    '</ul><div class="muted small" style="margin-top:8px">新增的 hook 在更新本版本后需要<b>重启一次手机</b>；作用域里请勾上「蓝牙 (com.android.bluetooth)」。</div>';
  b.appendChild(c3);
}

/* ==== env ==== */
// Pure diagnostic decision, also exercised by tests. Missing fields mean incompatible protocol, not IO failure.
function configDiagnosis(e) {
  if (!e.systemHook) return ['bad', '系统模块未响应，请检查作用域并重启手机'];
  if (e.sysProtocol !== e.protocol || e.sysVersion !== e.version)
    return ['bad', `应用 ${e.version || '?'} / 系统模块 ${e.sysVersion || '旧版'}：升级后请完整重启手机`];
  if (!e.sysPrefs) return ['bad', '系统未读到有效配置：' + (e.sysError || '请点一键 Root 配置后刷新')];
  if (!!e.started !== !!e.sysStarted) return ['bad', e.started ? '启动状态尚未同步，或服务心跳已过期' : '已停止，但系统仍读到开启状态'];
  if (!e.configRevision || e.sysRevision < (e.probeRevisionStart || e.configRevision) || e.sysRevision > e.configRevision) return ['warn', '配置正在同步，请稍后刷新；持续不同步请导出诊断'];
  return ['ok', '系统已确认当前配置（' + (e.sysChannel || 'unknown') + '），' + (e.started ? '模拟开关已同步' : '当前已停止')];
}
async function renderEnv() {
  const box = $('#envList'); box.innerHTML = '<div class="empty-state">正在检查…</div>';
  let e;
  try { e = JSON.parse(await N.checkEnv()); } catch (err) { box.innerHTML = '<div class="empty-state">检查失败：' + esc(String(err)) + '</div>'; return; }
  box.innerHTML = '';
  const rows = [
    ['Root 权限', e.root ? 'ok' : 'bad', e.root ? '已获取 su' : '未获取，请授予本应用 Root'],
    ['Xposed 模块', e.moduleActive || e.systemHook ? 'ok' : 'bad', e.moduleActive || e.systemHook ? '检测到模块响应' : '未激活，请在框架中启用并重启'],
    ['系统模块响应', e.systemHook ? 'ok' : 'bad', e.systemHook ? '探针已响应，继续检查配置与定位下发' : '未响应，请勾选系统框架（system / android）并重启'],
  ];
  const diag = configDiagnosis(e);
  rows.push(['系统配置同步', ...diag]);
  rows.push(['Root 配置快照', e.mirrorError ? 'warn' : 'ok', e.mirrorError || '已写入版本 ' + e.mirrorRevision]);
  if (e.sysProtocol === e.protocol && e.sysVersion === e.version) {
    rows.push(['定位回调 Hook', e.liveHook ? 'ok' : 'bad', e.liveHook ? '已安装实时下发 Hook；不代表目标应用已采用坐标' : '未找到实时下发方法，请导出诊断']);
    rows.push(['最近定位下发', e.deliveries > 0 ? 'ok' : 'warn', e.deliveries > 0 ? '本次系统启动已改写 ' + e.deliveries + ' 次；最近 ' + new Date(e.lastDelivery).toLocaleTimeString() : '尚未观察到下发，请开始模拟并在目标应用请求定位']);
  }
  const svc = e.service || {};
  if (e.started) rows.push(['定位服务', svc.running ? 'ok' : 'bad', svc.running ? `GPS ${svc.gpsReady ? '就绪' : '未就绪'} / 网络 ${svc.networkReady ? '就绪' : '未就绪'}` : '服务未运行，请重新开始模拟']);
  rows.push(['模拟位置权限', e.mockAllowed ? 'ok' : 'bad', e.mockAllowed ? '已授予' : '未授予（点下方一键配置）']);
  rows.push(['悬浮窗权限', e.overlay ? 'ok' : 'warn', e.overlay ? '已授予（摇杆可用）' : '未授予，摇杆不可用']);
  rows.forEach(([name, state, desc]) => box.appendChild(envItem(name, state, desc)));
  const dev = el('div', 'card muted small');
  dev.textContent = `${e.device} · SDK ${e.sdk}\nApp ${e.version || '?'} / Hook ${e.sysVersion || 'unknown'}\n配置 ${e.configRevision || 0} / 系统 ${e.sysRevision || 0}`
    + (e.scope ? '\n作用域: ' + e.scope.replace(/\s+/g, ' ') : '') + (e.sysHooks ? '\n系统 Hook: ' + e.sysHooks : '');
  dev.style.whiteSpace = 'pre-wrap'; box.appendChild(dev);
  for (const text of [e.mockError, svc.providerNote]) if (text) { const m = el('div', 'card small'); m.textContent = text; box.appendChild(m); }
  updateDrawerStatus(e);
}
function envItem(name, st, desc) {
  const it = el('div', 'env');
  const sym = st === 'ok' ? '✓' : st === 'warn' ? '!' : '✕';
  it.innerHTML = '<span class="st ' + st + '">' + sym + '</span><span class="et"><div class="en">' + esc(name) + '</div><div class="ed">' + esc(desc) + '</div></span>';
  return it;
}
async function updateDrawerStatus(e) {
  if (!e) { try { e = JSON.parse(await N.checkEnv()); } catch (_) { return; } }
  const ds = $('#drawerStatus'); if (!ds) return;
  const [state, desc] = configDiagnosis(e);
  ds.className = 'drawer-status ' + state;
  ds.textContent = desc;
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
  $('#routeVar').oninput = updateRouteSummary;
  $$('#routeTabs button').forEach(b => b.onclick = () => { setRouteTab(b.dataset.tab); renderEndpoints(); renderRoutePoints(); });
  $$('#modeChips .chip').forEach(b => b.onclick = () => { applyMode(b.dataset.mode, false); vib(8); });
  $$('#loopSeg button').forEach(b => b.onclick = () => { $$('#loopSeg button').forEach(x => x.classList.remove('on')); b.classList.add('on'); S.routeLoop = b.dataset.loop; updateRouteSummary(); });
  $('#epStartBtn').onclick = () => endpointMenu('start');
  $('#epEndBtn').onclick = () => endpointMenu('end');
  $('#routePlanBtn').onclick = planRoute;
  $('#stepFake').onchange = (e) => saveConfig({ step_fake: e.target.checked });
  $('#stride').onchange = () => { const v = parseFloat($('#stride').value); if (v > 0) saveConfig({ stride: v }); updateRouteSummary(); };
  $('#stepsSet').onclick = () => modalInput('设置当前累计步数', $('#curSteps').textContent, (v) => { const n = parseInt(v, 10); if (isNaN(n) || n < 0) return; N.setSteps(n); saveConfig({ steps: n }); $('#curSteps').textContent = n; toast('已设置'); });
  $('#burstGo').onclick = stepBurst;
  $('#burstStop').onclick = () => { N.stopSteps(); toast('已停止刷步'); setTimeout(refreshStatus, 300); };
  // env page
  $('#envCopy').onclick = async () => { const report = await N.diagnosticReport(); await N.copy(report); toast('诊断已复制，可发给开发者'); };
  $('#envRefresh').onclick = renderEnv;
  $('#envSetup').onclick = () => { toast('正在配置…'); call2(() => N.rootSetup()).then(r => { openModal('<h3>一键配置结果</h3><p>' + esc(r) + '</p><div class="actions"><button class="primary" id="mo">好</button></div>').querySelector('#mo').onclick = closeModal; renderEnv(); }); };
  $('#envReboot').onclick = () => confirmModal('重启手机', '现在重启手机以使框架作用域生效？', () => N.reboot(), '重启');
  $('#envLog').onclick = () => { const box = $('#envLogBox'); box.classList.toggle('hidden'); if (!box.classList.contains('hidden')) box.textContent = N.frameworkLog ? N.frameworkLog() : '不可用'; };
  window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => { getApp('theme', 'auto').then(t => { if (t === 'auto') applyTheme('auto'); }); });
}
// call a Native method that returns a String synchronously
function call2(fn) { return new Promise(res => { try { res(fn()); } catch (e) { res(String(e)); } }); }

let locateUiBusy = false;
async function locateReal() {
  if (locateUiBusy) return;
  locateUiBusy = true;
  const button = $('#fabLocate');
  button.disabled = true;
  button.setAttribute('aria-busy', 'true');
  try {
    toast('正在获取真实位置…');
    const r = await requestRealLocation();
    if (!r || !r.ok) { toast(r && r.error || '定位失败'); return; }
    if (!realMarker) realMarker = L.marker([0, 0], { icon: L.divIcon({ className: '', html: '<div class="real-dot"></div>', iconSize: [14, 14], iconAnchor: [7, 7] }), interactive: false });
    const p = toMap(r.lat, r.lng); realMarker.setLatLng(p); realMarker.addTo(map);
    map.flyTo(p, 16, { duration: .5 });
    toast('真实位置 · ' + r.provider + ' · 精度' + Math.round(r.acc) + 'm');
    confirmModal('使用真实位置？', '把当前真实位置设为模拟目标（会先纠偏为标准坐标）。', () => pickTarget(r.lat, r.lng, { name: '当前真实位置', reverse: true }));
  } finally {
    locateUiBusy = false;
    button.disabled = false;
    button.removeAttribute('aria-busy');
  }
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
