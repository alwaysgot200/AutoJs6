# MGMT-LAYER — 管理平台薄层（AutoJs6 定制独立化）

> 本文件是本 fork 相对上游 [SuperMonster003/AutoJs6](https://github.com/SuperMonster003/AutoJs6)
> 全部定制内容的**唯一索引与升级操作手册**。
>
> - `vendor` 分支 = 上游纯净镜像（基线：`ed3eb10e88db5a8425fd94bdddefa4176e5e1c94`，v6.7.0 BUILD 3804）
> - `mgmt` 分支 = 基线 + 本文件所述全部定制，**可持续 rebase 跟随官方**
> - 提交前缀：`vendor:fix`（上游缺陷修复）｜`mgmt:`（薄层本体/挂钩）｜`custom:`（与平台无关的小定制）

---

## 1. 设计原则与边界

所有「设备 ↔ 管理平台」能力集中在独立包 `org.autojs.autojs.mgmt`，
对上游代码的侵入收敛为 **7 个挂钩点、每处一行委托**（外加 Manifest/strings 两类声明式挂钩）。

**薄层做什么**

- WebSocket 长连接、鉴权、心跳、断线退避重连；
- 32 种消息协议（见 §5）、截图二进制媒体帧、触摸/手势注入、系统动作；
- 脚本仓库查询/推送/增删改、运行与运行态上报、定时/广播任务、APK 安装；
- 前台服务保活、设置页入口（地址/秘钥/测试连接）、运行时权限申请。

**明确不做**（避免与上游耦合、便于升级）

- 不改 32 种消息协议、不加 MQTT、不做协议信封改造；
- 不拆分 god class（`ManagementPlatformClient` 保持单文件，本期不拆，降低协议搬迁风险）；
- 不引入第二套网络库/JSON 库/依赖注入；不新增第三方依赖（okhttp/okio 上游已有）；
- 不修改上游 `Pref.kt`、`MainActivity.kt`（零修改红线，升级冲突最小化）。

---

## 2. 薄层文件清单

```
app/src/main/java/org/autojs/autojs/mgmt/
├── Mgmt.kt                                         # 唯一门面 object，挂钩只允许调它
├── client/ManagementPlatformClient.kt              # WS 客户端 + 全部 32 协议处理（~2k 行）
├── service/ManagementPlatformService.kt            # dataSync 前台服务，保活 + 承载连接
├── script/ManagementPlatformScriptExecutionListener.kt  # 脚本运行事件 -> SCRIPT_STATUS_UPDATE
├── pref/MgmtPref.kt                                 # 平台地址/秘钥读写（默认 SP）
├── perm/MgmtPermissionRequester.kt                 # 运行时权限申请注册
└── ui/
    ├── ManagementPlatformServerAddressPreference.kt
    ├── ManagementPlatformSecretPreference.kt
    └── ManagementPlatformTestConnectionPreference.kt
```

### 关键配置事实

- 偏好文件：`org.autojs.autojs6_preferences.xml`（PreferenceManager 默认 SP）。
- 键字面值是**固定契约**（已安装版本依赖此二键，改名会丢用户配置）：
  - `key_$_management_platform_server_address`（Kotlin 源码写 `"\$_"`）
  - `key_$_management_platform_secret`
  - 回落键：`key_$_server_address`，再回落 `NetworkUtils.getGatewayAddress()`。
- WS 接入 URL：`{serverAddress}/ws/device?deviceId=<id>&matchCode=<secret>`。
- APK 下载 URL：`{serverAddress}/api/apk/files/{fileName}?matchCode=<secret>`。
- 设备标识 `deviceId()` 取 androidId → serial → MODEL 三级回退，与服务端 `device_info.device_id` 对齐。

---

## 3. 挂钩点清单（升级时只需逐个核对这 7+2 处）

代码内搜索 `@mgmt-hook` 可定位全部锚点；`@mgmt-adapt` 为薄层内部的工具链定点适配。

| 编号 | 文件 | 位置 | 内容 |
|---|---|---|---|
| H1 | `app/src/main/AndroidManifest.xml` | L103-104、L427-431 | `FOREGROUND_SERVICE_DATA_SYNC` 权限 + `ManagementPlatformService` 声明（`foregroundServiceType="dataSync"`、`exported=false`） |
| H2 | `.../autojs/App.kt` | L85-86（+import L30） | 主进程 `onCreate` 调 `Mgmt.bootstrap(this)` |
| H3 | `.../autojs/AutoJs.kt` | L66-67（+import L34） | 引擎初始化后 `Mgmt.onEngineReady(scriptEngineService)` |
| H4 | `.../core/accessibility/AccessibilityService.kt` | L161-162、L173-174（+import L14） | `onDestroy` / `onServiceConnected` 各调一次 `Mgmt.onAccessibilityStateChanged()`（注意：清单中实际绑定服务是 `AccessibilityServiceUsher`） |
| H5 | `.../external/receiver/BaseBroadcastReceiver.java` | L30-35（+import L11） | try/catch 包裹 `Mgmt.connectIfConfigured()`，广播唤起不得因薄层崩溃 |
| H7 | `app/src/main/res/xml/fragment_preferences.xml` | L196-210 | 三个 Preference（class 指向 `org.autojs.autojs.mgmt.ui.*`） |
| H8 | `res/values/strings.xml` L175、`res/values-zh/strings.xml` L1430 | — | 薄层文案（英文 9 条 / 中文 6 条），仅追加 |

> 编号 H6 保留未用（历史方案中曾规划、后取消），不补号。

### 薄层内部 @mgmt-adapt（非挂钩，升级时通常无需动）

- `ManagementPlatformClient.kt:1589` — okio 3.6 下 `ByteString.of()`/`ByteArray.toByteString()`
  废弃且 deprecation 当 error；二进制帧改用 `okio.Buffer().write(frame).readByteString()`。
- `ManagementPlatformService.kt:45` — API <26 的无 channel `Notification.Builder` 构造器，
  定点 `@Suppress("DEPRECATION")`。

### 启动健壮性约定（H2）

`Mgmt.bootstrap` 内部对 FGS 启动做 runCatching 兜底 + 2/8/20/60s 有界重试：
force-stop 后由非 Launcher 入口唤起时 `mAllowStartForeground=false`，
`startForegroundService` 会抛 `ForegroundServiceStartNotAllowedException`——
**该异常绝不能穿透宿主 `Application#onCreate`**（2026-09-28 实测曾导致崩溃循环，已修）。

---

## 4. 与平台无关的小定制（`custom:` 前缀，commit c80d2d72）

| 编号 | 内容 | 文件 |
|---|---|---|
| C1 | 关于页新增「浅若红尘（哔哩哔哩博主）」入口，点击打开 `https://space.bilibili.com/519965290`；布局用安全调用（land 变体可能无控件） | `AboutActivity.kt`、`layout/activity_about.xml`、`layout-sw400dp-land/activity_about.xml`、strings×2 |
| C2 | 打包页「一键勾选常用权限」：9 项常用权限（均已在上游 SUPPORTED_PERMISSIONS 内） | `BuildActivity.java`（COMMON_PERMISSIONS、quickCheckPermissions）、`layout/activity_build.xml`、strings×1 |
| C3 | 崩溃弹窗 Activity 关闭上游默认的导航栏对比度/横屏导航栏自动处理（覆写两个 getter 返回 false） | `ErrorDialogActivity.java` |

---

## 5. 协议契约（一字未改，服务端事实来源 `apps/server/src/module/device/`）

设备端出站 12 种：`DEVICE_INFO`、`HEARTBEAT`、`CAPABILITIES`、`COMMAND_RESULT`、
`SCRIPT_LIST`、`INSTALLED_APPS`、`RUNNING_SCRIPTS`、`SCHEDULED_SCRIPTS`、
`SCRIPT_CONTENT`、`LOG_LINES`、`SCREENSHOT`、`SCRIPT_STATUS_UPDATE`。

设备端入站 18 种（`ManagementPlatformClient` when 块）：
`AUTH_OK`、`AUTH_FAILED`、`REQUEST_SCRIPT_LIST`、`PUSH_SCRIPT`、`RUN_SCRIPT`、
`REQUEST_SCREENSHOT`、`TOUCH_EVENT`、`DEVICE_ACTION`、`REQUEST_RUNNING_SCRIPTS`、
`REQUEST_SCHEDULED_SCRIPTS`、`REQUEST_INSTALLED_APPS`、`REQUEST_LOG_TAIL`、
`REQUEST_SCRIPT_CONTENT`、`UPDATE_SCRIPT_CONTENT`、`DELETE_SCRIPT`、`CREATE_FOLDER`、
`CREATE_INTENT_TASK`、`CREATE_TIMED_TASK`、`DELETE_SCHEDULED_TASK`、`INSTALL_APK`。

- 鉴权：HTTP 升级后服务端按 `matchCode` 校验租户，成功回 `AUTH_OK`，失败回
  `AUTH_FAILED`（业务码 4001）并关闭连接。
- 客户端重试策略：普通网络故障 2s 起指数退避（上限 60s、±25% 抖动）；
  **鉴权失败固定 60s 慢重试**（`AUTH_FAIL_RETRY_SECONDS=60`），避免错误接入码刷服务端。
- 截图为二进制媒体帧（okio ByteString），触摸坐标基于截图宽高比由设备端映射到物理分辨率。
- 服务端 `READY/FRAME_REQUESTED/ERROR` 属于浏览器屏幕墙观看端独立网关
  （`device-view.gateway.ts`），设备端不涉及。

---

## 6. 跟随上游升级 SOP

```powershell
# 环境（构建必带）
$env:JAVA_HOME='D:\programfiles\Java\jdk-17'
$env:JAVA_TOOL_OPTIONS='-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=10808 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=10808'
```

1. `git fetch upstream` → 浏览 upstream/main 新提交与 release notes。
2. `git checkout vendor` → `git merge --ff-only upstream/main`（保留纯净镜像；不能快进则重置）。
3. `git checkout mgmt` → `git rebase vendor`（或 merge，二选一并保持历史习惯）。
4. 冲突只会出现在 §3 的挂钩文件与 §4 的定制文件；解决原则：
   - 上游主体改动保留，挂钩一行委托重新挂回原语义位置；搜 `@mgmt-hook` 核对 7+2 处齐全；
   - 上游若新增 FGS 类型/权限要求（如 targetSdk 升级），更新 H1；
   - `Pref.kt`、`MainActivity.kt` 出现定制改动即属事故，回退并重做设计。
5. 构建双 flavor：
   `.\gradlew.bat :app:assembleAppDebug` 与 `.\gradlew.bat :app:assembleInrtDebug`。
6. **构建后还原** `git checkout -- version.properties`（构建脚本会自动改写 BUILD 号与时间戳，禁止提交）。
7. 冒烟：安装 x86_64 split 包到模拟器，照 §9 记录走核心链路；再出 arm64/universal 包。
8. keystore（`app/src/main/assets/autojs.keystore`、`default_key_store.bks`）上游仓库自带，无需自备。
9. 推送 `origin vendor` 与 `origin mgmt`，再到业务仓执行 §6.1 subtree pull。

### 6.1 仓库拓扑与业务仓（monorepo）同步

```
GitHub fork  https://github.com/<你的GitHub用户名>/AutoJs6.git   → 本地 remote origin
上游         https://github.com/SuperMonster003/AutoJs6.git      → 本地 remote upstream
  vendor 分支 = 上游纯净镜像；mgmt 分支 = vendor + 本文件全部定制
业务仓 d:\autojs6_Management
  AutoJs6-master/ = git subtree --prefix=AutoJs6-master 挂入 mgmt（--squash）
```

fork 侧 `mgmt` 分支有新提交并推送后，在业务仓执行（工作区必须干净，有 WIP 先 stash）：

```bash
git stash push -m "wip-before-subtree-pull"
git subtree pull --prefix=AutoJs6-master https://github.com/<你的GitHub用户名>/AutoJs6.git mgmt --squash
git stash pop
```

- 业务仓内**只允许**通过 `git subtree pull` 更新本目录；不要在业务仓直接改
  `AutoJs6-master/` 内文件（改动回灌不到 fork，下次 pull 必冲突）。
- 如需改客户端，先在 fork 的 `mgmt` 分支提交并推送，再 subtree pull。
- 首次挂入用 `git subtree add --prefix=AutoJs6-master <fork-url> mgmt --squash`。
- GitHub fork 建成前，subtree 远程可临时指本地路径 `D:/work/autojs6-fork`，建后统一换回 URL。

---

## 7. 构建环境约定（仓库外）

仓库内**不携带任何构建补丁或镜像配置**：Maven 镜像放开发机 `~/.gradle/init.gradle.kts`
（阿里云镜像前置），代理走 `JAVA_TOOL_OPTIONS`（127.0.0.1:10808）；
`gradle-wrapper.properties`、`settings.gradle.kts`、各模块 `build.gradle*` 与上游保持一致，
官方 deployer 在上述环境下可完整构建。`.run/`、`.idea/`、`.cxx/`、OCR 构建产物、
`version.properties` 改写均不入库。

---

## 8. vendor:fix 履历（上游缺陷，已在 mgmt 分支修补）

- `6b317b32` — `bottom_sheet_log.xml` 引用缺失的 `console_debug`/`console_verbose`
  颜色资源导致 aapt2 链接失败；改用已有的 `console_view_*` 色。
- `d6f94f06` — `LogBottomSheet.kt` 在 Kotlin 中误用 `AutoJs.getInstance()`（不存在）；
  改为 `AutoJs.instance`（4 处）。

上游日后修复这两点时，rebase 会直接冲突，届时删除我方补丁即可。

---

## 9. 冒烟验证记录（2026-09-28，基线 ed3eb10e / BUILD 3804，模拟器 Android 16 x86_64）

覆盖安装保留已有 SP（键契约不变），配置不丢。以下链路实测通过：

- FGS 启动/保活；后台拒绝场景的崩溃防护与有界重试（commit 4c5e5b75）；
- WS 上线 `AUTH_OK`，`device_info.status=online`、`app_version=6.7.0 (3804)`，15s 心跳；
- 错误接入码 `AUTH_FAILED`（4001）→ 60s 慢重试；恢复配置后自愈上线；
- force-stop → 服务端检测离线 → 重启自动重连上线；
- 无障碍开关（服务组件 `AccessibilityServiceUsher`）→ 即时 CAPABILITIES 推送；
- `REQUEST_SCRIPT_LIST/SCRIPT_LIST`、`REQUEST_INSTALLED_APPS`（250 个）；
- `REQUEST_SCREENSHOT`（JPEG 二进制媒体帧，okio 适配路径）；
- `TOUCH_EVENT` tap/swipe/long_press（dispatchGesture 成功、坐标按截图比例映射）；
- `DEVICE_ACTION` home/back/volumeUp（音量心跳值 3→4 实证生效）；
- `PUSH_SCRIPT` 落盘、`RUN_SCRIPT` 执行（console 输出经日志链路可见）、
  `SCRIPT_STATUS_UPDATE` 落库、`COMMAND_RESULT` 回执；
- `REQUEST_RUNNING_SCRIPTS`、`REQUEST_SCHEDULED_SCRIPTS`、`REQUEST_LOG_TAIL/LOG_LINES`；
- `CREATE_FOLDER`、`DELETE_SCRIPT`（仅文件；目录按安全设计跳过）、
  `REQUEST_SCRIPT_CONTENT`、`UPDATE_SCRIPT_CONTENT`。

待补：真机（无 root）一轮；`CREATE_TIMED_TASK/CREATE_INTENT_TASK/DELETE_SCHEDULED_TASK`、
`INSTALL_APK`（需平台先上传 APK 包）为低频链路，协议与已验证链路同构。

已知非缺陷现象：模拟器冷启动 JIT/dex 校验风暴偶发系统 ANR 弹窗（主线程卡在
LocaleManager binder，与薄层无关），点等待/二次启动即恢复，真机预期无此问题；
脚本内主动 `exit()` 会令引擎抛 `ScriptInterruptedException`，运行事件记为 error
（日志中脚本实际正常「运行结束」），属引擎既有行为。
