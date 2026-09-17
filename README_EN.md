# AnyDoor · System-wide GPS Spoofer for Android

<p align="center">
  <a href="https://github.com/zhaoyuxiangyyds-lab/AnyDoor/releases/latest"><img src="https://img.shields.io/github/v/release/zhaoyuxiangyyds-lab/AnyDoor?style=flat-square&color=ff4d7d" alt="Release"></a>
  <a href="https://github.com/zhaoyuxiangyyds-lab/AnyDoor/releases"><img src="https://img.shields.io/github/downloads/zhaoyuxiangyyds-lab/AnyDoor/total?style=flat-square&color=ff4d7d" alt="Downloads"></a>
  <img src="https://img.shields.io/badge/Android-8.1%20~%2016-3ddc84?style=flat-square&logo=android&logoColor=white" alt="Android 8.1-16">
  <img src="https://img.shields.io/badge/Xposed-LSPosed%20%7C%20Vector-blue?style=flat-square" alt="Xposed">
  <a href="LICENSE"><img src="https://img.shields.io/github/license/zhaoyuxiangyyds-lab/AnyDoor?style=flat-square" alt="License"></a>
  <a href="https://github.com/zhaoyuxiangyyds-lab/AnyDoor/stargazers"><img src="https://img.shields.io/github/stars/zhaoyuxiangyyds-lab/AnyDoor?style=flat-square&color=ffc83d" alt="Stars"></a>
</p>

<p align="center"><a href="README.md">简体中文</a> · <b>English</b></p>

> An Xposed module that fakes your location **for every app at once** by rewriting locations inside `system_server` — no mock-provider flag, no per-app hooking, works indoors without a GPS fix.

AnyDoor (任意门, "Anywhere Door") was written to replace the old-school fake-GPS tools that have no map, no search, and only accept raw lat/lng. It patches `LocationManagerService` in the system process, so the spoofed position is **global**, can be toggled at any time, and needs **only one reboot ever**.

<p align="center">
  <img src="docs/screenshots/01-map.png" width="30%" alt="Map picker" />
  <img src="docs/screenshots/02-running.png" width="30%" alt="Spoofing" />
  <img src="docs/screenshots/03-address.png" width="30%" alt="Reverse geocoding" />
</p>

---

## ✨ Features

- **Truly system-wide** — hooks `LocationManagerService` inside `system_server`, so every app gets the fake location. No need to add each target app to the scope.
- **Anti-detection** — delivered `Location` objects have `isFromMockProvider() == false`. Optional blocking of **Wi-Fi scan / cell tower / raw GNSS** data so location SDKs (AMap, Tencent, Baidu, Google FLP) can't infer the real position from the environment.
- **Continuous output** — additionally pushes coordinates through a test provider, so apps keep receiving fixes even indoors with zero GPS signal.
- **A real map UI** (WebView + Leaflet + AMap tiles):
  - Place search, tap-to-pick, paste coordinates
  - **D-pad nudging** and a **floating joystick** overlay to walk around on top of any app
  - **Route simulation** — pick start & destination, choose **walk / run / bike / drive**, and follow real roads (AMap directions); random speed variation, random stops at crossings, return trip or loop; manual waypoints still available
  - **Pedometer sync** — fake step-counter sensor events while walking (WeChat Sport, Keep, …), configurable stride, plus a "add N steps" mode
  - Favorites, history, random jitter, light/dark theme
- **One-tap privacy hardening** — blank Wi-Fi / cell / GNSS / Bluetooth environment + spoofed IMEI / IMSI / ICCID / Android ID / serial + barometer blocked; independent of location spoofing. The [limits](#-what-privacy-mode-cannot-do) are documented honestly.
- **Coordinate systems handled for you** — internally WGS-84; display corrected to GCJ-02 for Chinese map tiles; paste WGS-84 / GCJ-02 / BD-09 and it converts.
- **One-tap environment check** — verifies the framework is active, scope is correct and permissions are granted, and can fix them via root.

---

## 📋 Requirements

| | |
|---|---|
| Android | 8.1 – 16 (API 27+). Tested on a real Android 9 (EMUI 9) device; Android 12–16 share the same location architecture and are supported in code (uses the `ProviderProperties` API on 31+), but not yet verified on physical 15/16 hardware |
| Root | Required |
| Framework | **LSPosed**, **Vector** (JingMatrix) or any other Xposed-compatible framework |
| ABI | Pure Java, no native code — works on every architecture |

---

## 🚀 Install

1. **Install the APK** from [Releases](../../releases) (or [build it yourself](#-build-from-source)).
2. **Enable the module** in LSPosed / Vector and tick these scopes:
   - ✅ **System Framework** (`android` / `system`) — required for the global hook
   - ✅ **Phone** (`com.android.phone`) — for hiding cell-tower info and spoofing IMEI etc.
   - ✅ **Bluetooth** (`com.android.bluetooth`) — optional, blocks BLE/classic scans in privacy mode
   - ✅ **AnyDoor itself** (`io.github.zhaoyuxiangyyds_lab.anydoor`)
3. **Reboot once.** The `system_server` hook only loads at boot; after that you can start/stop spoofing freely with no further reboots.
4. Open the app → **Environment Check** → make sure everything is ✓. If "Mock location" or "Overlay" permission is missing, tap **Fix with Root**.
5. *(Optional, China only)* Place search uses the AMap REST API and needs a free **"Web Service"** key from [console.amap.com](https://console.amap.com/dev/key/app). Paste it under Settings → AMap Key. Everything else (tap-to-pick, paste coords, joystick, routes) works without a key.

> CLI-based frameworks (e.g. Vector CLI):
> ```sh
> cli scope set io.github.zhaoyuxiangyyds_lab.anydoor android/0 system/0 com.android.phone/0 com.android.bluetooth/0 io.github.zhaoyuxiangyyds_lab.anydoor/0
> ```

---

## 📖 Usage

1. **Pick a spot** — search, paste `31.23, 121.47`, or tap the map.
2. **Start** — hit the big button. From now on every app sees that position.
3. **Move around**
   - **D-pad** (cross icon): nudge by a fixed step
   - **Joystick** (dot icon): floating overlay you can drag while another app is in the foreground
   - Drawer → **Route**: pick a destination (search / tap map / favorites), choose walk, run, bike or drive, tap **Plan** to get a real-road path, tune speed, variation, stops and return/loop, then start
4. **Stop** — tap the button again or use the persistent notification.
5. **Steps** (optional) — on the Route page enable **Fake step sensor**, add the target app (e.g. WeChat) to the module scope and restart it; steps grow with the simulated walk. "Add steps" bumps the counter at a chosen rate without moving.
6. **Privacy hardening** (optional) — Drawer → **Privacy**, flip the master switch. The page shows the current fake identity and can regenerate it.

---

## ❓ FAQ

**Spoofing is "on" but apps still show my real location?**
Open Environment Check and look at "System framework hook". If it's ✕, the System Framework scope isn't ticked or you haven't rebooted since enabling it.

**Amap / WeChat / apps built on the Amap SDK never pick up the fake position, or report `errorCode=8` / `LatLng is error#0802`?**
Update to 1.3.1 and reboot once. Older builds had two bugs: the fake GPS fix carried no `satellites` extra, so the Amap/Baidu/Tencent SDKs classified it as mocked and dropped it; and the in-scope ("strong mode") hook on `Location.hasAltitude()` corrupted the `Location` parcel layout on Android 12+, which turned Amap's `AMapLocation` lat/lng into garbage. On Android 11+ the Wi-Fi service also lives in its own APEX class loader and was never hooked, so Wi-Fi positioning leaked the real position — fixed as well. The environment check now shows what `system_server` itself sees (config readable, spoof started, hook hit counts).

**Search returns `INVALID_USER_KEY`?**
Wrong AMap key type — it must be a **Web Service** key, not Android/iOS/JS.

**Coordinates are off by a few hundred meters?**
You pasted GCJ-02 / BD-09 coordinates. Set the input coordinate system under Settings, or just tap the map (already corrected).

**A banking / attendance app still detects mocking?**
Keep "Block Wi-Fi / cell location" on, set random jitter to 2–5 m, and add that app to the Xposed scope for the extra in-app hooks.

**I want one app to keep the real location?**
Settings → Exempt apps → enter package names (comma separated).

---

## 🔒 What privacy mode cannot do

Privacy mode makes sure **ordinary apps get nothing that locates or identifies this phone**: Wi-Fi / cell / raw GNSS / Bluetooth scans come back empty; `ANDROID_ID`, `Build.getSerial()`, IMEI / MEID / IMSI / ICCID / line number return a fixed random identity; barometer events are dropped for scoped apps.

What **no software can do**, so don't trust tools that claim otherwise:

| Not possible | Why |
|---|---|
| Hide from the carrier | With a SIM inserted the network always knows which cell you are in; that happens between baseband and carrier, below any app-level hook. Airplane mode + no SIM is the only answer. |
| Change your IP geolocation | Decided by the network path. Only routing traffic through a machine elsewhere changes it. |
| Run on GrapheneOS | GrapheneOS has no root / Xposed (it would break verified boot). Its per-app permission, network and sensor toggles already cover most of this. |
| Fake device model, OAID, accounts | Changing `Build.MODEL` etc. crashes apps or gets accounts banned; vendor ad IDs (OAID) go through proprietary services. |
| Sensors / client-side IDs outside strong mode | Sensor events and some ID reads happen inside the app process; they are only covered for apps added to the scope. |

---

## 🔧 Build from source

No Gradle — the project is built straight with the Android SDK toolchain, which keeps it small and fast.

**Needs:** JDK 17, Android SDK with `build-tools;34.0.0` and `platforms;android-34`.

```bash
# adjust SDK / JAVA_HOME at the top of build.sh if needed
bash build.sh
# output: AnyDoor.apk (a local debug keystore.jks is generated on first run)
```

Pipeline: `aapt2 compile/link` → `javac` → `d8` → pack `classes.dex` → `zipalign` → `apksigner`.

---

## 🧠 How it works

| Layer | What it does |
|---|---|
| **System** (`SystemHooks`) | Hooks `LocationManagerService.getLastLocation` and the delivery path (`callLocationChangedLocked` / `onReportLocation`), replacing every app's location with a freshly built `Location` that carries no mock flag. Optionally blocks Wi-Fi / cell / GNSS data. |
| **Phone** (`PhoneHooks`) | Hooks `PhoneInterfaceManager` to hide cell-tower info from ordinary apps. |
| **App** (`AppHooks`) | For apps in scope, additionally hooks `Location` getters, `isFromMockProvider`, `getLastKnownLocation` etc. as a second line of defense. |
| **Driver** (`SpoofService`) | Foreground service that keeps pushing coordinates via `addTestProvider` + `setTestProviderLocation`, implementing routes, joystick and jitter. |
| **Config** | World-readable `SharedPreferences` (`xposedsharedprefs`) shared between the app and the hooks. |
| **UI** | A single-page web app (`assets/web/`) in a `WebView`, bridged via `JsBridge`; map is Leaflet + AMap tiles. |

> Note: depending on the Xposed fork, `system_server` may be reported as package `android` or `system`. Both are handled.

---

## 🌟 Support

If AnyDoor is useful to you, a **Star** ⭐ helps other people find it and keeps the project going.
Bugs → [Issues](../../issues) · Ideas → [Discussions](../../discussions)

---

## ⚠️ Disclaimer

For research and testing on **devices you own** only. Do not use it to violate laws, platform terms of service, or other people's rights. You are solely responsible for how you use this software.
