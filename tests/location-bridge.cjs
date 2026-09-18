// Execute the actual bridge and locate UI code with deterministic timers, not a reimplementation.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/web/app.js'), 'utf8');
let count = 0;
function check(value, message) { assert.ok(value, message); count++; }
function harness(native = {}) {
  let seq = 0;
  const timers = new Map(), button = { disabled: false, setAttribute() {}, removeAttribute() {} };
  const ctx = { window: { Native: native }, Promise, String, Math,
    setTimeout(fn, delay) { const id = ++seq; timers.set(id, { fn, delay }); return id; },
    clearTimeout(id) { timers.delete(id); },
    $: () => button, toast() {}, toMap: (a, b) => [a, b],
    realMarker: { setLatLng() {}, addTo() {} }, map: { flyTo() {} }, confirmModal() {}, pickTarget() {} };
  vm.createContext(ctx);
  vm.runInContext(source.slice(0, source.indexOf('const toast =')), ctx);
  vm.runInContext(source.slice(source.indexOf('let locateUiBusy ='), source.indexOf('/* ==== boot ==== */')), ctx);
  return { ctx, timers, button, native,
    pending: () => vm.runInContext('Object.keys(cbs).length', ctx),
    runTimer(delay) { const entry = [...timers.values()].find(t => t.delay === delay); assert.ok(entry); entry.fn(); } };
}
(async () => {
  let ids = [], native = { locate(id) { ids.push(id); } }, h = harness(native);
  for (let n = 0; n < 20; n++) {
    const a = h.ctx.requestRealLocation(), b = h.ctx.requestRealLocation();
    check(a === b, 'repeat taps share the same promise');
    check(ids.length === n + 1, 'one native request per round');
    h.ctx.window.__cb(ids[n], { ok: true, lat: 1, lng: 2 });
    check((await a).ok && (await b).ok, 'both callers receive the result');
    check(h.pending() === 0 && h.timers.size === 0, 'success releases callback and timer');
  }
  const timeout = h.ctx.requestRealLocation(), stale = ids.at(-1);
  h.runTimer(12000);
  check(!(await timeout).ok, 'lost callback resolves with timeout');
  check(h.pending() === 0 && h.timers.size === 0, 'timeout releases callback and timer');
  const retry = h.ctx.requestRealLocation(), next = ids.at(-1);
  h.ctx.window.__cb(stale, { ok: true });
  check(h.pending() === 1, 'late callback cannot consume a newer request');
  h.ctx.window.__cb(next, { ok: true });
  check((await retry).ok, 'retry succeeds after timeout');
  h = harness({ locate() { throw new Error('bridge unavailable'); } });
  check(!(await h.ctx.requestRealLocation()).ok, 'bridge exceptions resolve');
  check(h.pending() === 0 && h.timers.size === 0, 'exceptions release callback and timer');
  let receiver;
  h = harness({ locate(id) { receiver = this; h.ctx.window.__cb(id, { ok: true }); } });
  check((await h.ctx.requestRealLocation()).ok && receiver === h.native, 'native receiver identity is preserved');
  let locateId;
  h = harness({ locate(id) { locateId = id; }, reverse() {} });
  const stuck = [h.ctx.call('reverse'), h.ctx.call('reverse'), h.ctx.call('reverse')];
  const independent = h.ctx.requestRealLocation();
  h.ctx.window.__cb(locateId, { ok: true });
  check((await independent).ok, 'pending network calls do not gate the locate bridge');
  for (const t of [...h.timers.values()]) t.fn();
  check((await Promise.all(stuck)).every(r => !r.ok), 'network calls also have bounded callback lifetimes');
  check(h.pending() === 0 && h.timers.size === 0, 'all timed-out network callbacks are released');
  let calls = 0;
  h = harness({ locate() { calls++; } });
  const ui = h.ctx.locateReal();
  await h.ctx.locateReal();
  check(h.button.disabled && calls === 1, 'UI disables duplicate taps while locating');
  h.runTimer(12000);
  await ui;
  check(!h.button.disabled, 'UI button is restored after timeout');
  const uiRetry = h.ctx.locateReal();
  check(calls === 2, 'UI permits retry without restarting the app');
  h.ctx.window.__cb(2, { ok: true, lat: 1, lng: 2, acc: 5, provider: 'gps' });
  await uiRetry;
  check(!h.button.disabled && h.pending() === 0, 'UI button is restored after success');
  // Structural integration checks supplement the behavior tests: GPS must not enter the HTTP pool.
  const java = fs.readFileSync(path.join(__dirname, '../app/src/main/java/io/github/zhaoyuxiangyyds_lab/anydoor/JsBridge.java'), 'utf8');
  const locate = java.slice(java.indexOf('public void locate('), java.indexOf('// ------------------------------------------------------------------ misc'));
  check(!locate.includes('pool.submit') && locate.includes('realLocation.request'), 'native GPS bypasses the network pool');
  check(java.includes('realLocation.close()') && java.includes('pool.shutdownNow()'), 'bridge tears down location and worker resources');
  console.log('location-bridge: ' + count + ' assertions passed');
})().catch(e => { console.error(e); process.exitCode = 1; });
