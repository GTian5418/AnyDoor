'use strict';
/* Mock Native bridge — only used when running in a plain browser (no Android WebView).
   On device, window.Native exists and this whole shim is skipped. */
if (!window.Native) {
  const store = {
    config: { started: false, lat: '39.908722', lng: '116.397499', alt: '50', acc: '8', jitter: '3', interval: '1000', wifi_block: true, cell_block: true, gnss_block: true, mock_driver: true, mock_network: true, exempt: '' },
    app: { map_layer: 'amap', input_crs: 'wgs84', search_src: 'auto', theme: 'auto' },
  };
  let running = false, curLat = 39.908722, curLng = 116.397499, route = null, routeStart = 0, steps = 1234, burst = 0;
  const cb = (id, obj) => setTimeout(() => window.__cb(id, obj), 260 + Math.random() * 300);
  const demo = [
    { name: '天安门广场', addr: '北京市东城区东长安街', lat: 39.905607, lng: 116.391300 },
    { name: '故宫博物院', addr: '北京市东城区景山前街4号', lat: 39.917530, lng: 116.397077 },
    { name: '上海外滩', addr: '上海市黄浦区中山东一路', lat: 31.240710, lng: 121.490250 },
    { name: '广州塔', addr: '广东省广州市海珠区阅江西路222号', lat: 23.106520, lng: 113.324520 },
    { name: '西湖', addr: '浙江省杭州市西湖区龙井路1号', lat: 30.242700, lng: 120.150000 },
  ];
  window.__mockNative = window.Native = {
    getState: () => JSON.stringify({ config: store.config, app: store.app, started: running, version: '1.3.3',
      service: running ? { running: true, lat: +store.config.lat, lng: +store.config.lng, curLat, curLng, speed: route ? route.speed : 0, bearing: 0, routeActive: !!route, paused: false, steps: Math.floor(steps), burstLeft: burst, routeDone: route ? Math.min(route.total, (Date.now() - routeStart) / 1000 * route.speed) : 0, routeTotal: route ? route.total : 0, joystick: false, providers: true, mockError: '' } : { running: false } }),
    checkEnv: () => JSON.stringify({ version: '1.3.5', protocol: 1, sysVersion: '1.3.5', sysProtocol: 1, configRevision: 1, sysRevision: 1, mirrorRevision: 1, mirrorError: '', liveHook: true, deliveries: 0, moduleActive: true, systemHook: true, sysPrefs: true, sysChannel: 'prefs', sysStarted: false, started: false, mockGrant: true, sysHooks: 'last=1 deliver=0 report=1 accept=2 mock=4 wifi=ok', root: true, mockAllowed: true, overlay: false, vectorCli: true, moduleEnabled: true, scope: 'android/0 com.android.phone/0', sdk: 28, device: 'Preview Device / Android 9', mockError: '' }),
    diagnosticReport: () => JSON.stringify({ preview: true, version: '1.3.3' }),
    rootSetup: () => '✓ 已授予模拟位置权限\n✓ 已授予悬浮窗权限\nVector: ok\n✓ 已启用模块并设置作用域',
    reboot: () => alert('reboot (preview)'),
    setTarget: (lat, lng) => { store.config.lat = String(lat); store.config.lng = String(lng); curLat = lat; curLng = lng; },
    setStarted: (on) => { running = on; store.config.started = on; if (!on) route = null; },
    setConfig: (j) => { try { Object.assign(store.config, JSON.parse(j)); } catch (e) {} },
    getApp: (id, key) => { if (typeof id === 'number') return; },
    setApp: (k, v) => { if (v == null) delete store.app[k]; else store.app[k] = v; },
    startRoute: (j) => { try { const o = JSON.parse(j); let t = 0; for (let i = 1; i < o.points.length; i++) { const a = o.points[i - 1], b = o.points[i]; t += Math.hypot((b.lat - a.lat) * 111320, (b.lng - a.lng) * 88000); } route = { points: o.points, speed: o.speed, total: t, stride: o.stride || 0 }; routeStart = Date.now(); running = true; curLat = o.points[0].lat; curLng = o.points[0].lng; return true; } catch (e) { return false; } },
    stopRoute: () => { route = null; },
    planRoute: (id, j) => { const o = JSON.parse(j); const a = o.points[0], b = o.points[o.points.length - 1]; const pts = []; const n = 24; for (let i = 0; i <= n; i++) { const f = i / n; pts.push({ lat: a.lat + (b.lat - a.lat) * f + Math.sin(f * Math.PI * 3) * 0.0012, lng: a.lng + (b.lng - a.lng) * f + Math.cos(f * Math.PI * 2) * 0.0012, s: i % 6 === 0 ? 1 : 0 }); } let d = 0; for (let i = 1; i < pts.length; i++) d += Math.hypot((pts[i].lat - pts[i - 1].lat) * 111320, (pts[i].lng - pts[i - 1].lng) * 88000); cb(id, { ok: true, points: pts, distance: d, duration: d / 1.3, mode: o.mode }); },
    stepBurst: (n, r) => { burst = n; running = true; store.config.started = true; },
    stopSteps: () => { burst = 0; },
    setSteps: (n) => { steps = n; },
    getIdentity: () => JSON.stringify({ fake_imei: '862345678901234', fake_meid: 'A0000012345678', fake_imsi: '460011234567890', fake_iccid: '89860112345678901234', fake_android_id: '9f3c2a1b8d7e6f50', fake_serial: 'K7Q2M9X4T1B8R6ZP', fake_phone: '13812345678' }),
    regenIdentity: () => window.Native.getIdentity(),
    privacyPreset: (on) => { store.config.privacy = on; if (on) Object.assign(store.config, { wifi_block: true, cell_block: true, gnss_block: true, id_spoof: true, bt_block: true, sensor_block: true }); },
    toggleJoystick: () => alert('悬浮摇杆需要在真机上使用'),
    toast: (m) => {},
    copy: (t) => { try { navigator.clipboard.writeText(t); } catch (e) {} },
    paste: () => '',
    openUrl: (u) => window.open(u, '_blank'),
    share: () => {},
    vibrate: () => {},
    getLogs: () => '[]',
    frameworkLog: () => '(preview) no framework log',
  };
  // async, id-first methods
  const asyncGetApp = (id, key) => cb ? null : null;
  window.Native.getApp = function (key) { return Promise.resolve(store.app[key]); };
  // getApp is awaited via await N.getApp(k) in app.js — return value directly
  window.Native.getApp = (key) => store.app[key];
  window.Native.search = (id, q) => {
    const items = demo.filter(d => d.name.includes(q) || d.addr.includes(q));
    cb(id, { ok: items.length > 0, source: 'preview', items: items.length ? items : demo.slice(0, 3), error: '' });
  };
  window.Native.reverse = (id, lat, lng) => cb(id, { ok: true, address: '预览地址 ' + lat.toFixed(4) + ', ' + lng.toFixed(4), source: 'preview' });
  window.Native.locate = (id) => cb(id, { ok: true, lat: 39.9087, lng: 116.3975, acc: 20, provider: 'gps', age: 3000 });
  // simulate movement along route
  setInterval(() => {
    if (burst > 0) { const a = Math.min(burst, 1); burst -= a; steps += a; }
    if (!route) return;
    if (route.stride) steps += route.speed * 0.5 / route.stride;
    const elapsed = (Date.now() - routeStart) / 1000, dist = elapsed * route.speed;
    let acc = 0;
    for (let i = 1; i < route.points.length; i++) {
      const a = route.points[i - 1], b = route.points[i];
      const seg = Math.hypot((b.lat - a.lat) * 111320, (b.lng - a.lng) * 88000);
      if (acc + seg >= dist) { const f = (dist - acc) / seg; curLat = a.lat + (b.lat - a.lat) * f; curLng = a.lng + (b.lng - a.lng) * f; return; }
      acc += seg;
    }
    route = null;
  }, 500);
}
