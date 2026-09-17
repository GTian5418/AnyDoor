# 任意门 AnyDoor · 全局定位模拟

> 一个基于 Xposed 的安卓**全局虚拟定位**工具，界面美观、功能齐全，专为**中国网络环境**优化。
> 在系统服务内部改写每个 App 收到的定位，并抹掉「模拟位置」标记；室内没有 GPS 信号也能持续输出坐标。

任意门是为了替代那些「搜不出地点、没有地图、只能填经纬度」的老式虚拟定位工具而写的。它把定位改在 `system_server` 里，所以**对所有 App 全局生效**，开关随时切换、无需每次重启。

<p align="center">
  <img src="docs/screenshots/01-map.png" width="30%" alt="地图选点" />
  <img src="docs/screenshots/02-running.png" width="30%" alt="模拟中" />
  <img src="docs/screenshots/03-address.png" width="30%" alt="地址解析" />
</p>

---

## ✨ 功能特性

- **真·全局生效**：在 `system_server` 的 `LocationManagerService` 内改写定位，覆盖所有 App，而不是只 hook 单个应用。
- **反检测**：结果 `isFromMockProvider = false`；可选屏蔽 **WiFi 扫描 / 基站 / 原始 GNSS**，防止定位 SDK（高德、腾讯、百度）用周围环境反推真实位置。
- **持续输出**：同时通过测试定位源（mock provider）持续推送坐标，室内无 GPS 信号也有定位。
- **好用的地图界面**（WebView + Leaflet + 高德瓦片）：
  - 地点搜索（高德，中国可用）、点图选点、粘贴经纬度
  - **方向键微调** & **悬浮摇杆**（可在任意 App 上层实时走动）
  - **路线模拟**：设定路径点 + 速度，自动沿路线移动（步行/骑行/开车）
  - 收藏夹、历史记录、随机漂移、深浅色主题
- **坐标系自动纠偏**：内部统一 WGS-84（GPS 原始坐标），显示与高德瓦片按 GCJ-02 纠偏；支持粘贴 WGS84 / 火星 GCJ-02 / 百度 BD-09 坐标。
- **一键环境自检**：检测框架是否激活、作用域是否正确、权限是否到位，并可一键配置。

---

## 📋 环境要求

| 项目 | 要求 |
|------|------|
| 系统 | Android 8.1 ~ 14（SDK 27+），已在 **华为 EMUI 9 / Android 9** 实测通过 |
| Root | 需要 Root |
| Xposed 框架 | **LSPosed** / **Vector**(JingMatrix) 等任意 Xposed 框架 |
| 架构 | 纯 Java，无 native，各架构通用 |

> ⚠️ 未 Root、无 Xposed 框架的设备无法使用本工具的全局功能。

---

## 🚀 安装与配置

### 1. 安装 APK
从 [Releases](../../releases) 下载 `AnyDoor.apk` 安装，或[自行编译](#-从源码编译)。

### 2. 在 Xposed 框架中启用模块
打开 LSPosed / Vector 管理器 → 模块 → 启用「任意门」，**作用域必须勾选**：

- ✅ **系统框架**（`android` / `system`）— 全局生效的关键
- ✅ **电话和通讯录**（`com.android.phone`）— 屏蔽基站定位用
- ✅ **任意门自身**（`com.zyx.anydoor`）— 强化模式

> 命令行框架（如 Vector CLI）可执行：
> ```sh
> cli scope set com.zyx.anydoor android/0 system/0 com.android.phone/0 com.zyx.anydoor/0
> ```

### 3. 重启一次手机
系统框架 hook 需要重启才能注入 `system_server`。**这一步只需做一次**，之后开关模拟无需再重启。

### 4. 打开 App，进入「环境检查」
确认各项均为 ✓。若「模拟位置权限」「悬浮窗权限」未授予，点 **一键 Root 配置** 即可。

### 5. 配置高德 Key（中国搜索地点必需）
中国网络下地点搜索走高德 REST API，需要一个**免费**的高德 Key（约 2 分钟，一次配置永久有效）：

1. 打开 [高德开放平台控制台](https://console.amap.com/dev/key/app) 并登录（首次需注册 [lbs.amap.com](https://lbs.amap.com/)）
2. 应用管理 → 创建应用 → 添加 Key
3. **服务平台务必选「Web服务」**（不是 Android / iOS / Web端JS API，否则会报 `INVALID_USER_KEY`）
4. 复制生成的 32 位 Key，填入 App 的 **设置 → 高德 Key**

> 首次搜索时 App 也会弹窗引导你申请。填 Key 之前，点图选点 / 粘坐标 / 摇杆 / 路线都能正常用，只有「按名字搜地点」需要 Key。

---

## 📖 使用方法

1. **选位置**：顶部搜索地点、粘贴坐标 `31.23, 121.47`、或直接点地图。
2. **开始模拟**：点底部 **开始模拟**。此后任何 App 看到的都是这个位置。
3. **微调 / 移动**：
   - 右侧 **方向键**（十字图标）：按固定步长微调
   - 右侧 **摇杆**（圆点图标）：弹出悬浮摇杆，可在其它 App 上层实时走动
   - 抽屉 → **路线模拟**：加路径点、设速度，自动沿路线移动
4. **停止**：再次点按钮，或下拉通知栏点「停止」。

> 通知栏常驻一个「位置模拟中」的通知，可快速停止 / 呼出摇杆。

---

## ❓ 常见问题

**Q：显示「模拟中」但 App 位置没变？**
到「环境检查」确认「系统框架 Hook」为 ✓。若为 ✕，说明作用域没选「系统框架」或没重启，按上面第 2、3 步重来。

**Q：搜索地点报 `INVALID_USER_KEY` / 搜不出来？**
高德 Key 类型不对。必须是「**Web服务**」类型，重新申请一个填入。

**Q：坐标对不上？**
App 内部统一用 WGS-84。从高德/百度网页复制的坐标是 GCJ-02/BD-09，请在 **设置 → 输入坐标系** 里选对应坐标系再粘贴。地图点选无需关心，已自动纠偏。

**Q：某些 App（如银行、打卡）识别出模拟？**
保持「屏蔽 WiFi / 基站定位」开启；把随机漂移设为 2~5 米；必要时在 Xposed 里把目标 App 也加进作用域（强化模式）。

**Q：想让某个 App 保留真实定位？**
设置 → 豁免应用，填入其包名（逗号分隔）。

---

## 🔧 从源码编译

本项目**不用 Gradle**，用 Android SDK 裸工具链直接编译，轻量快速。

**依赖**：JDK 17、Android SDK（`build-tools;34.0.0` + `platforms;android-34`）。

```bash
# 1. 按需修改 build.sh 顶部的 SDK / JDK 路径
#    SDK 默认 D:/Android/Sdk，JAVA_HOME 默认指向本机 JDK17
# 2. 编译（Git Bash / WSL / Linux / macOS 均可）
bash build.sh
# 产物：AnyDoor.apk（首次会自动生成本地调试签名 keystore.jks）
```

`build.sh` 流程：`aapt2 compile/link` → `javac` → `d8` → 打包 `classes.dex` → `zipalign` → `apksigner` 签名。

> `keystore.jks` 是本地自动生成的调试签名，已被 `.gitignore` 排除；克隆后首次 `build.sh` 会自动新建一个。

---

## 🧠 工作原理

| 层 | 说明 |
|----|------|
| **系统层**（`SystemHooks`） | hook `LocationManagerService.getLastLocation` 与定位下发路径（`callLocationChangedLocked` / `onReportLocation`），把所有 App 的定位替换为目标坐标，构造全新 `Location`（不带 mock 标记）。可选屏蔽 WiFi / 基站 / GNSS。 |
| **电话层**（`PhoneHooks`） | hook `PhoneInterfaceManager`，对普通 App 隐藏基站信息。 |
| **应用层**（`AppHooks`） | 对加入作用域的 App 额外 hook `Location` getter、`isFromMockProvider`、`getLastKnownLocation` 等，二次兜底。 |
| **驱动**（`SpoofService`） | 前台服务，用 `addTestProvider` + `setTestProviderLocation` 持续推送坐标，实现路线移动、摇杆、随机漂移；室内无信号也有定位。 |
| **配置** | 通过 `xposedsharedprefs` 世界可读的 SharedPreferences 在 App 与 Hook 间共享。 |
| **界面** | `WebView` 承载单页应用（`assets/web/`），`JsBridge` 做 JS↔Java 桥接；地图用 Leaflet + 高德瓦片。 |

> 兼容性注意：不同 Xposed 分支交付 `system_server` 的包名可能是 `android` 或 `system`，本项目两者都处理。

---

## ⚠️ 免责声明

本项目仅供**学习研究**与在**本人拥有的设备**上做定位测试之用。请勿用于任何违反法律法规、平台服务条款或侵害他人权益的用途。使用本工具产生的一切后果由使用者自行承担。

---

## 🙏 致谢

- 参考了 [android-gps-setter](https://github.com/jqssun/android-gps-setter) 的 Xposed 定位 hook 思路。
- 地图瓦片来自高德地图，地图库为 [Leaflet](https://leafletjs.com/)。

## 📄 License

[MIT](LICENSE)
