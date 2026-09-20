# 更新记录

## 1.4.2 — 2026-09-20（修复 Android 14+ 已连接 WiFi 的 BSSID 屏蔽在部分机型失效 conn=impl-missing）

### 修复：已连接 WiFi 的 BSSID 未被屏蔽（一加/Android 16 等）
真机诊断（OnePlus PMB110 / Android 16）显示系统 Hook 摘要里 `conn=impl-missing`：`ConnectivityService` 的定位脱敏改写没能装上，未加入作用域的应用仍能经 `NetworkCapabilities.getTransportInfo()` 读到当前已连接 WiFi 的 BSSID，进而被高德/腾讯等反查位置、把定位拉回真实附近。
- 根因：Android 14 起连接组件被 jarjar 重命名，`ConnectivityService` 实际类名变成 `android.net.connectivity.com.android.server.ConnectivityService`，按原名 `com.android.server.ConnectivityService` 找不到。
- 修复：不再靠猜类名——在 `SystemServiceManager.startService` 启动 `ConnectivityServiceInitializer` 后，直接从它的字段里取到真正的 `ConnectivityService` 实例再挂钩（与包名/jarjar 无关）；脱敏方法名若被改，也按"入参与返回都是 `NetworkCapabilities`、方法名含 sanitiz"的形状兜底匹配。环境检查此项应显示 `conn=ok`。
- WiFi 扫描列表屏蔽（`wifi=ok`）与基站屏蔽（`cellgate`）此前已正常，本次只补上"已连接 WiFi"这一路。

### 说明
- 其余下发路径在该机型上已正常：`report=1 accept=2 pump=12+`（1.4.1 的 `onReportLocation` 改写与逐注册改写都装上了，运行时观察到大量下发）。`deliver=0` 在 Android 12+ 属正常（逐接收者路径只用于 ≤11）。
- 依据 AOSP `android16-release` 与连接 APEX 的 jarjar 规则核对，**未在真机复测**；主机回归全绿。升级后请**完整重启一次手机**。

## 1.4.1 — 2026-09-20（修复模拟位置几秒后又跳回真实位置；作用域内应用不再因 hook 闪退）

### 修复：定位停留几秒又回到真实位置（高德、微信、得力e+、分身类应用）

多个用户反馈"一次性读取到虚拟位置、但持续取位的应用过几秒就跳回真实位置"。根因在 Android 12+ 的下发路径：
- 之前系统侧只在每个"注册"的 `acceptLocationChange` 上改写，而各 OEM ROM 里这些注册类的名字不一致，个别机型上钩子没能装上（环境检查里表现为 `accept=0`）。装不上时，真实定位源的回调、以及测试定位源自带的"模拟来源"标记就原样传给了应用——应用要么收到真实坐标、要么因为 `isFromMockProvider=true` 丢弃我们的坐标，于是显示几秒后跳回真实位置。
- 现在改在 `LocationProviderManager.onReportLocation` 这一处下发总入口改写：**每一条定位（真实源或测试源）在扇出给各应用、写入系统缓存之前都被替换成一条全新的、非模拟标记的坐标**。这一处是稳定公开方法，不依赖各 ROM 的内部类名，同时顺带去掉了测试定位源的"模拟来源"标记，持续取位的应用不再跳回真实位置。设置了豁免应用时，为保留豁免应用的真实定位，此处不接管，仍按注册逐个改写（可跳过豁免应用）。
- 逐注册改写（`acceptLocationChange`）保留，用于豁免场景与"注册时下发缓存位置"的快路径，双重覆盖。

### 修复：勾选得力e+ 等应用进作用域后闪退

- 1.4 新增的"在应用进程内改写回调消息里 `NetworkCapabilities`"的钩子会改动系统正在派发的 `Message`，个别应用（如得力e+）会因此闪退。该屏蔽已由系统进程侧的 `ConnectivityService` 覆盖，**移除这个应用进程内的高风险钩子**；直接调用 `getNetworkCapabilities` 的屏蔽（返回副本）保留。

### 建议：目标应用不必加入作用域

修复后，"改定位"完全由系统框架侧完成（`getLastLocation`、`onReportLocation`、逐注册改写，以及 WiFi/基站屏蔽都在 system_server / 电话进程里），投递给应用的坐标本身就是非模拟标记的。**高德、微信、得力e+、分身/多开类应用都不需要加入模块作用域，也不要设为豁免应用**——只勾「系统框架」「电话」「蓝牙」即可。作用域只在需要客户端侧计步伪造、客户端 `isFromMockProvider`/身份伪造时才用；把这类应用加进作用域，反而可能触发它们的自我保护而闪退。

### 验证与升级

- 主机回归全绿（含 RouteTest）；系统侧下发路径依据 AOSP 12–16 源码核对，**未在真机复测**。
- 覆盖安装后请**完整重启一次手机**，作用域勾「系统框架」「电话」「蓝牙」即可。

## 1.4 — 2026-09-20（补齐 WiFi / 基站屏蔽的新接口覆盖；路线规划可选备选路线与途经点；修复地图上路线不显示）

### 修复：部分应用 / 微信小程序仍取到原本位置

系统定位（`getLastLocation` 与逐注册下发）此前已全局改写，但应用自带的网络定位 SDK 还会另外读取 WiFi 与基站信息，本模块的 `wifi_block` / `cell_block` 只覆盖了旧接口，Android 10/12 之后新增的接口仍能把真实环境交给这些 SDK：

- **基站**：`TelephonyManager.requestCellInfoUpdate`（Android 10+ 的 `getAllCellInfo` 替代接口，腾讯定位 SDK 7.6 起每 30 秒调用一次）之前完全没有拦截。现在电话进程（`PhoneInterfaceManager.requestCellInfoUpdate[WithWorkSource]`）与强化模式的客户端 hook 都改为以空列表回调，调用方流程正常结束。
- **基站**：`getServiceState()` 返回的 `ServiceState` 自 Android 10 起携带驻留小区标识，改为返回系统自身给无定位权限应用使用的脱敏副本；`TelephonyRegistry` 的 `checkFineLocationAccess / checkCoarseLocationAccess` 对被屏蔽的注册返回否，于是注册表自身跳过小区事件、并投递脱敏的服务状态。
- **已连接 WiFi**：Android 12+ 的 `ConnectivityManager.getNetworkCapabilities()` 与 `NetworkCallback.onCapabilitiesChanged()` 通过 `NetworkCapabilities.getTransportInfo()` 携带与 `getConnectionInfo()` 相同的 BSSID。现在在 `ConnectivityService` 的定位脱敏拷贝处（`createWithLocationInfoSanitizedIfNecessaryWhenParceled`，Android 11 为 `maybeSanitizeLocationInfoForCaller`）把 BSSID 替换为 `02:00:00:00:00:00`；`com.android.tethering` APEX 的类加载器与 WiFi 服务一样在 `SystemServiceManager.startService` 时捕获。强化模式下客户端的 `getNetworkCapabilities` 与回调消息同样处理。
- **GNSS**：系统侧补上 `addNmeaListener` / `registerGnssNmeaCallback`（NMEA 语句含原始经纬度），与既有的测量 / 导航电文拦截一致；豁免应用不受影响。
- 系统 UI 例外新增 ColorOS/OxygenOS 的 `*.wirelesssettings` 等设置类包名，避免模拟期间 WiFi 设置页列表为空。
- 环境检查新增「WiFi / 基站屏蔽」一项，分别显示 WiFi 扫描、已连接 WiFi（connectivity）与基站（registry）三处 hook 状态；系统 Hook 摘要新增 `conn=`、`cellgate=`。

微信 8.0.78 的定位组件（`com.tencent.map.geolocation.sapp`）是运行时动态加载的加密组件，本版没有对其反编译；上述接口覆盖依据公开版腾讯定位 SDK 7.6.1.12 的实现核对。仍无法覆盖的只有公网 IP 归属地（服务端按 IP 判断，只能到省市级）。

### 修复：系统直推按定位源补位

- 只有 `gps` 或 `network` 之一没有测试定位源时（例如关闭了网络模拟、或单个源注册失败），驱动现在只对缺失的源（及依赖它们的 fused / passive）请求直推，不再要求两者都缺失才启用；探针名形如 `anydoor.pump:network`。
- Android 8.1–11 的直推现在与 `handleLocationChangedLocked` 一致：投递前记录定位 app-op、仅有粗略权限的接收者拿到系统 `LocationFudger` 的粗略副本、`numUpdates` 用尽或过期的注册按系统方式移除（`requestSingleUpdate` 只收到一次），死亡接收者被清理。

### 新增：路线规划的备选路线与途经点

- 步行 / 跑步 / 骑行改用高德路径规划 2.0（`alternative_route=3`），驾车沿用 v3 `strategy=10` 的多结果；失败时回退到原来的 v3 / v4 单结果接口。规划后列出「高德推荐 / 备选 2 / 备选 3」（各自里程、预计时长、路径点与路口数），点列表或地图上的灰色线即可切换，再开始模拟。
- 「起点 → 终点」页可添加**途经点**（地图点选 / 搜索 / 收藏 / 历史，最多 8 个），路线必须经过它们；多段路线时第 k 条备选取每段的第 k 条结果。途经点随路线一起保存。
- 修复：`app.css` 的全局 `svg { width: 22px }` 也作用于 Leaflet 的覆盖层 `<svg>`，导致规划 / 手动路径的折线在地图上只剩 22 像素、几乎看不见。

### 验证与升级

- 主机回归新增 `RouteTest`（27 项：v5 备选解析、v3/v4 回退、多段组合、去重与上限），全部通过；Web 界面在浏览器预览中验证了备选切换、途经点与折线显示。
- Xposed 侧改动依据 AOSP 源码（10/11/12–16 的 `TelephonyRegistry`、`ConnectivityService`、`PhoneInterfaceManager`）与公开 SDK 反编译核对，**未在真机复测**。
- 覆盖安装后请**完整重启一次手机**；作用域仍需勾选「系统框架」「电话」「蓝牙」，基站相关拦截依赖「电话」作用域。

## 1.3.6 — 2026-09-19（豁免应用模式下其他应用不再断供：新增「系统直推」下发）

### 修复：设置了豁免应用后，其他应用只能偶尔定位成功

- 现象：填写了豁免应用后，未豁免的应用一次性查询（`getLastLocation`）能拿到模拟坐标，但通过 `requestLocationUpdates` / `getCurrentLocation` 持续取位的应用只能偶尔成功、室内长时间没有下发。
- 根因：1.3.3 起，只要设置了豁免应用就**停用全局测试定位源**（避免测试源覆盖豁免应用的真实来源）。停用后，未豁免应用就只能等待真实系统定位回调再被改写；室内无真实 GPS 时，这个回调可能长时间不来，于是没有持续下发。同理，`mock_driver` 关闭、或某些 ROM 拒绝注册测试定位源时也是同样的空窗。
- 修复：新增 **系统直推**（system-server 侧回退下发）。当没有测试定位源在运行时，驱动每个周期请求系统框架 Hook 主动向各应用的定位注册**直接投递一次模拟坐标**：
  - Android 12+：对 `gps/network/fused/passive` 的 `LocationProviderManager` 调用 `deliverToListeners(reg -> reg.acceptLocationChange(result))`；
  - Android 8.1–11：遍历 `LocationManagerService.mRecordsByProvider`，对每个 `UpdateRecord` 调 `Receiver.callLocationChangedLocked`，并沿用其 `shouldBroadcastSafe(Locked)` 的间隔/位移过滤。
  - **豁免应用被跳过**（按注册方包名判断），因此它们照常收到真实定位；真实定位源保持运行，系统的 last-location 缓存不被写入模拟数据。注入过程用线程标记（`INJECTING`）短路既有改写 Hook，避免二次替换。

### 环境检查

- 「定位服务」一项在无测试定位源时显示「系统直推模式」，并给出已直推次数与最近时间；系统 Hook 摘要新增 `pump=` 状态。
- 豁免应用设置说明改为：豁免应用照常收到真实定位，其他应用改由系统框架直推，不需要豁免时留空更稳。

### 验证与升级

- 主机回归全绿（核心/状态/迁移/Shell/镜像/真实定位/诊断/桥接）；Android SDK 编译、DEX、APK 对齐与 v2/v3 签名校验通过，签名证书与既往一致，可覆盖升级。
- 系统直推属 Xposed 侧代码，各 Android 版本路径经 AOSP 源码（8.1/9/10/11 与 `android16-release`）核对，**未在真机复测**；华为 Mate 9 / Android 9 仅确认编译与启动无回归。
- 覆盖安装后请**完整重启一次手机**，让系统框架侧模块随 v1.3.6 重新注入。作用域仍需勾选「系统框架」「电话」「蓝牙」。


## 1.3.5 — 2026-09-18（ColorOS/OxygenOS 等 ROM 模拟定位被拒修复 → 微信小程序/高德重新可定位）

修复用户反馈的两处（realme、一加 等 Android 16 机型）：一加机型环境检查全绿但底部报 `java.lang.SecurityException: … not allowed to perform MOCK_LOCATION`；微信本身“发送位置”能定位，但小程序（如哈啰）定位失败、高德类应用报 `errorCode=13 获取到的基站和WIFI信息为空`。两者其实是同一个根因。

### 根因

- ColorOS/OxygenOS/realme 等 ROM 在 Android 12+ 上，即便 `appops set … android:mock_location allow` 后 `appops get` 显示 allow（环境检查“模拟位置权限 ✓”），系统框架里 `SystemAppOpsHelper.noteOp(OP_MOCK_LOCATION)` 仍返回 `MODE_ERRORED`。于是 `LocationManagerService` 的 `addTestProvider / setTestProviderEnabled / setTestProviderLocation` 直接抛 `SecurityException`，**任意门的 gps/network 测试定位源根本没能注册**。
- 没有测试定位源持续下发，室内/无真实 GPS 时就没有“活的”定位源。系统侧 `getLastLocation` hook 仍能改写一次性查询（所以微信“发送位置”正常），但通过 `requestLocationUpdates / getCurrentLocation` 持续取位的应用（微信小程序走腾讯定位 SDK、高德）拿不到下发，转而用自带的 WiFi/基站网络定位——而这部分被本模块清空，于是彻底定位失败。
- 已比对 AOSP `android16-release`：四个 test-provider 接口都以 `noteOp(OP_MOCK_LOCATION)` 为闸门，`MODE_ERRORED` 即抛异常；并反编译腾讯定位 SDK 7.6.1.12 确认其 `network` 分支消费系统 `network` provider、`gps` 分支消费系统 `gps` provider——恢复测试定位源即可让它们经既有 `acceptLocationChange`（改写成非 mock）拿到模拟坐标。

### 修复

- 在系统框架进程新增对 `com.android.server.location.injector.SystemAppOpsHelper` 的 hook：当被查询的 app-op 是 `OP_MOCK_LOCATION` 且调用方包名为本应用时，`noteOp/noteOpNoThrow/checkOpNoThrow/startOpNoThrow` 直接返回“允许”。**只针对本应用、只针对模拟定位这一个 op**，其他应用与其他权限不受影响。这样测试定位源在各 ROM 都能注册，微信小程序、高德等重新能取到模拟坐标。
- `OP_MOCK_LOCATION` 的 op 码优先反射自 `AppOpsManager`（隐藏常量），失败回退到稳定值 58。
- 环境检查“模拟位置权限”改为 `appops` 放行或系统框架放行任一满足即视为已授予，并说明“已由系统框架放行（无需在开发者选项手动选择）”；系统 Hook 摘要新增 `mock=` 数量。若仍出现 `MOCK_LOCATION` 报错，界面给出可操作提示（升级后完整重启；仍失败则在开发者选项将“模拟位置信息应用”设为任意门），不再只显示原始异常。

### 验证与升级

主机回归全绿（沿用既有用例：核心/状态/迁移/Shell/镜像/真实定位/诊断/桥接）；Android SDK 编译、DEX、APK 对齐与 v2/v3 签名校验通过，签名证书与既往版本一致，可覆盖升级。新增的系统框架 app-op 放行属 Xposed 侧代码，逻辑基于 AOSP `android16-release` 源码核对，**未在 ColorOS/OxygenOS 真机验证**。

覆盖安装 `AnyDoor.apk` 后请**完整重启一次手机**，让系统框架侧模块随 v1.3.5 重新注入。仍需在框架管理器勾选「系统框架 (system/android)」「电话」「蓝牙」作用域。升级无需卸载或清除数据。若小程序仍定位失败，请反馈机型、Android/框架版本与“复制诊断”，不要公开真实坐标。

## 1.3.4 — 2026-09-18（真实定位连续使用修复 + Android 15/16 WiFi 屏蔽修复）

修复用户反馈的“真实位置只有前 1～2 次正常，之后卡住，划掉后台才恢复”，以及 Android 15/16 上 WiFi 扫描屏蔽失效导致的定位泄漏（表现之一：云闪付等应用定位不对/被判环境异常）。

### 修复：真实位置连续使用卡住

- 真实定位脱离搜索、地址解析和路线规划共用的网络线程池，使用独立异步定位流程，不再排队等待慢网络任务。
- GPS 与网络定位分别使用独立监听器；一方不可用仍尝试另一方。成功、约 8 秒无结果超时、取消与页面销毁均清理本次监听和定时器。
- 清理异常时保留待清理记录，下次先重试释放，不继续叠加监听；上一轮迟到的位置或超时回调不能结束新请求。
- 前端定位回调增加 12 秒兜底超时，清理回调记录；重复点击共用同一请求，定位按钮在成功或失败后恢复，不需靠杀后台解锁。
- 页面销毁时关闭定位请求及后台线程池，不再向已销毁的 WebView 投递结果。
- 缓存和实时结果统一排除 mock、超过 60 秒、未来时间及无效坐标；提示定位权限、定位开关和超时原因。获取真实位置仍需先停止模拟。

### 修复：Android 15/16 WiFi 扫描屏蔽失效（定位泄漏）

- Android 15 起系统 `WifiServiceImpl.getScanResults` 的返回类型从 `List<ScanResult>` 改为 `ParceledListSlice<ScanResult>`，其列表构造器只接受 `List`。旧代码用 `ArrayList` 反射构造会抛 `NoSuchMethodError`，屏蔽被静默跳过——高德/腾讯/百度以及云闪付等的网络定位据此用真实 WiFi 反推真实位置，覆盖被模拟的 GPS，导致“各项检查正常却定位不对”“定位与真实环境不一致被风控拦下”。
- 现按实际返回类型构造空结果（`List` 直接空表，`ParceledListSlice` 按 `List` 形参构造空表），Android 8～16 均能真正清空扫描结果。构造失败时环境检查的 `wifi` 状态改为 `scan-replace-failed`，不再误报正常。
- 结论基于 AOSP `android15-release`/`android16-release` 源码逐行核对（`WifiServiceImpl.getScanResults`、`ParceledListSlice(List)` 构造器），**未在 Android 15/16 真机验证**。

### 验证与升级

主机回归新增真实定位连续 20 次、连续 10 次超时后恢复、双源清理、权限拒绝、迟到回调、重复点击和页面销毁覆盖，并保留既有回归，全部通过；Android SDK 编译、DEX、APK 对齐与 v2/v3 签名校验通过，签名证书与 1.3.2/1.3.3 一致，可覆盖升级。WiFi 修复仅改动系统侧 hook，已在 Android 9 真机确认构建可正常启动、不回退，但该代码路径本身只在 Android 15/16 运行。

覆盖安装 `AnyDoor.apk` 后建议完整重启一次手机，让系统侧模块版本与 App 一致。停止模拟后点击“真实位置”；没有信号时会超时提示，可直接重试。升级无需卸载或清除数据，保留原签名与设置。

本版不做 Android 15/16 真机验证，不宣称所有版本、ROM 或目标应用均兼容。若云闪付表现为“直接打不开/闪退”，那属于应用检测 Root/Xposed，需要用 LSPosed 隐藏或 Shamiko 等隐藏方案，AnyDoor 不负责；请反馈手机型号、Android／框架版本及“复制诊断”，不要公开真实坐标等个人信息。

## 1.3.3 — 2026-09-18（兼容性修复验证版）

修复会导致不同机型出现“模拟不更新、定位一直等待、停止后残留”和环境检查误报的问题。本版不宣称已完成微信、钉钉或 Android 16 真机适配验证。

### 配置与框架兼容

- 不再依赖被弃用的 New XSharedPreferences（NSP）扩展：移除 `xposedsharedprefs`，最低 legacy API 声明调整为 82，应用配置使用私有存储。
- 升级时迁移旧 NSP 配置和收藏，保留原文件；在后台进行，失败可重试；迁移过程不恢复旧运行状态。
- 标准 XSharedPreferences API 与 Root 快照分别尝试，主通道初始化失败不阻断回退；不再用 File.canRead 判断 API 是否能读。
- 配置携带协议和递增版本，选择较新且有效的快照，拒绝版本倒退；不再仅凭文件 mtime 判断更新。
- 缺失或无效的通道不继续宣称可读；运行状态采用 15 秒心跳有效期，服务失联后不会无限沿用 started=true。
- Root 快照用独立临时文件、检查权限／SELinux 标记后原子替换；失败保留原文件、回传错误并重试；授权延迟过久的旧写入会被拒绝。
- 作用域内进程可通过系统状态桥读取模拟配置，避免直接访问 `/data/system`；该桥对普通应用要求定位权限，不传递 app 私有设置。
- Root 命令并发读取输出，保留退出码与 UTF-8，限制等待时间。

### 定位源与启停

- GPS 和 network 独立创建、推送、重试与清理。单源失败不影响另一个源，启用失败后的部分创建也会被追踪。
- 关闭网络模拟会真正移除 network 测试源；运行中切换立即参与下一次协调。
- 启停带批次校验，旧后台任务不能在停止后或新一轮启动时重新安装定位源。
- 记录定位源所有权，清理失败不丢失状态；下次启动可重试清理。
- Android 12+ 在逐接收者阶段改写，不提前污染系统最后位置缓存；显式豁免时停用全局测试源，保留豁免应用的真实定位来源。
- 强化模式动态遵守豁免和开关，单次请求不再额外启动重复下发计时器；获取真实位置要求先停止模拟，并忽略 mock 缓存。

### 自检与反馈

- 报告 App／系统模块版本和诊断协议；旧模块缺少字段时提示完整重启，不再猜测 Root 写入失败。
- 检查开始、停止两个方向的状态差异和配置版本；展示每个定位源错误、实际 Hook 安装情况与观察到的下发次数。
- 探针响应不再被描述为“全局生效”；一键配置主动同步，不覆盖用户已有目标应用作用域。
- 增加“复制诊断”，便于定位评论区反馈，默认不含坐标、收藏、虚拟身份和高德 Key。

### 验证

- 主机回归测试：定位源 26 项、配置读取 17 项、升级迁移 16 项、Root 执行 3 项、原子发布 5 项、诊断判断 11 项，共 **78 项断言**。
- Android SDK 工具链编译、DEX 转换、APK 对齐与签名验证。
- 测试使用外部 Android／Xposed 依赖替身，不等同于手机集成测试；本版尚未连接真机验证 Android 16、微信、钉钉、分身或工作资料。作为 prerelease 发布，建议先在测试设备回归。

### 升级方式

覆盖安装 → 完整重启手机 → 打开任意门并按需授权迁移 → 一键 Root 配置 → 开始模拟并在目标应用请求位置。仍有异常，请附“复制诊断”内容、目标应用版本及失败表现。

项目作者：zhaoyuxiangyydslab；本版 AI 协作：Codex。
