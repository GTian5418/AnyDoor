# 更新记录

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
