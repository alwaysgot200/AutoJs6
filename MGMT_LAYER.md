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
- 36 种消息协议（见 §5）、截图二进制媒体帧、触摸/手势注入、系统动作、远程停止脚本、JPEG 流式自推；
- 脚本仓库查询/推送/增删改、运行与运行态上报、定时/广播任务、APK 安装；
- 前台服务保活、运行时权限申请；
- 左侧抽屉最前「登录」分区（设置页不再保留平台入口）：未登录弹窗填写地址/API Key 登录（弹窗回填上次凭证、行内进度与错误提示）；已登录弹窗展示租户名/设备 ID/服务器并可退出登录（退出保留凭证但不自动重连）。

**明确不做**（避免与上游耦合、便于升级）

- 不改既有消息协议的语义与字段（新增消息类型仅允许追加，见 §5）、不加 MQTT、不做协议信封改造；
- 不拆分 god class（`ManagementPlatformClient` 保持单文件，本期不拆，降低协议搬迁风险）；
- 不引入第二套网络库/JSON 库/依赖注入；不新增第三方依赖（okhttp/okio 上游已有）；
- 不修改上游 `Pref.kt`、`MainActivity.kt`（零修改红线，升级冲突最小化）。

---

## 2. 薄层文件清单

```
app/src/main/java/org/autojs/autojs/mgmt/
├── Mgmt.kt                                         # 唯一门面 object，挂钩只允许调它
├── client/ManagementPlatformClient.kt              # WS 客户端 + 全部协议处理（~2.9k 行）
├── client/ConnectionState.kt                       # 三态连接状态/原因（弱网加固）
├── client/NetworkReachabilityMonitor.kt            # 本机网络可达性监听（弱网加固）
├── client/OutboxManager.kt                         # 上行终态消息持久化发件箱（§5/方案 §5.3）
├── client/ScreenStreamer.kt                        # JPEG 流式自推自适应节奏调度器（§5/P2）
├── client/MgmtApiHttp.kt                           # REST 共享 OkHttp/线程池/信封解析（P1 账号化）
├── client/AccessCodeClient.kt                      # GET /device/access-code/mine 拉本租户 matchCode
├── service/ManagementPlatformService.kt            # dataSync 前台服务，保活 + 承载连接
├── script/ManagementPlatformScriptExecutionListener.kt  # 脚本运行事件 -> SCRIPT_STATUS_UPDATE
├── pref/MgmtPref.kt                                 # 平台地址(环境解析)/接入码/登录态开关读写（默认 SP）
├── perm/MgmtPermissionRequester.kt                 # 运行时权限申请注册
├── config/MgmtServerConfig.kt                      # 环境地址解析(主源集): 读构建类型 source set 的 MgmtEnv + debug SP 覆盖
├── core/DeviceFingerprint.kt                       # 统一设备指纹 ANDROID_ID（WS deviceId 与市场核销同源）
├── auth/AccountSession.kt                          # 账号会话编排: 登录/持久化/接入码下发/退出 + 三态事件
├── auth/AccountApiClient.kt                        # /auth/code、/auth/login、/auth/logout（账号密码+图形验证码）
├── auth/AccountTokenStore.kt                       # JWT AndroidKeyStore AES/GCM 加密 SP 持久化
└── ui/
    ├── MgmtPlatformLoginMenu.kt                    # 抽屉「账号」分区三态状态机 + 账号登录弹窗 (H9, P1)
    └── MgmtWakeUnlockActivity.kt                   # P3 MINIMAL 档亮屏/解锁非密码锁/回主屏透明 Activity (H1)
```

构建类型 source set 双实现（同名类 `config/MgmtEnv`，编译期按构建类型二选一，零上游构建文件改动；
复用上游官方 `app/build.gradle.kts` 已注册的 `src/debug|release/java` 源集，与上游
`leakcanary/LeakCanarySetup` 同机制；app/inrt 双 flavor 各自的 debug/release 均覆盖）：

```
app/src/debug/java/org/autojs/autojs/mgmt/config/MgmtEnv.kt     # 开发环境: IS_PRODUCTION=false,
                                                                 #   DEFAULT_BASE_URL=http://10.0.2.2:8080,
                                                                 #   ALLOW_LOCAL_OVERRIDE=true
app/src/release/java/org/autojs/autojs/mgmt/config/MgmtEnv.kt   # 生产环境: IS_PRODUCTION=true,
                                                                 #   DEFAULT_BASE_URL=https://autojs6.80fafa.com,
                                                                 #   ALLOW_LOCAL_OVERRIDE=false (CI 发版/预览包均编译此份)
```

> Git 安全（fork 为公开开源仓库）：两份 `MgmtEnv.kt` 只放非敏感默认值（公开服务域名 /
> 模拟器通用回环别名），禁止提交本机 LAN IP、内测域名与任何秘钥；真机 `adb reverse` /
> 局域网联调地址走 debug 专属 SP 覆盖（设备私有存储，天然不入库），release 不读覆盖。

薄层自有资源（`mgmt_` 前缀，只增不覆盖）：

- `res/drawable/mgmt_ic_login.xml`：抽屉登录条目图标（vector）；
- `res/drawable/mgmt_ic_{server,key,person,smartphone,check_circle,content_copy}.xml`：弹窗图标（均为 24dp vector）；
- `res/drawable/mgmt_ic_person_add.xml`、`mgmt_ic_link.xml`、`mgmt_ic_link_off.xml`：
  抽屉「免费注册 / 连接设备 / 断开连接」条目图标（P1 账号化）；
- `res/drawable/mgmt_bg_{icon_circle,icon_circle_connected,info_card}.xml`：弹窗圆标底/信息卡片底；
- `res/layout/mgmt_dialog_login.xml`：账号登录表单（头部 + 账号 + 密码密文切换 + 图形验证码
  WebView（点击刷新，服务端开关关闭时整行隐藏）+ 行内错误/进度；**不再含服务器地址/API Key 输入**）；
- `res/values/mgmt_colors.xml`、`res/values/mgmt_ids.xml`：弹窗色板与视图 tag id；
- `res/values/mgmt_styles.xml`：`MgmtThemeMaterialBridge`（`Theme.MaterialComponents.DayNight.Bridge`），
  仅用于包裹登录弹窗视图 inflate（宿主 AppTheme 是 AppCompat，OutlinedBox TextInputLayout 运行时
  强制要求 MaterialComponents 主题，直接 inflate 会抛 InflateException）。

### 关键配置事实

- 偏好文件：`org.autojs.autojs6_preferences.xml`（PreferenceManager 默认 SP）。
- 键字面值是**固定契约**（已安装版本依赖这些键，改名会丢用户配置，均由 `MgmtPref` 常量持有）：
  - `key_$_management_platform_server_address`（Kotlin 源码写 `"\$_"`）：2026-10-08 P1 起
    **退化为 debug 手工覆盖键**——生效地址统一由 `config/MgmtServerConfig.kt` 解析，
    环境常量由构建类型 source set 双实现 `config/MgmtEnv.kt` 编译期注入：
    release（`src/release`）恒为生产域名 `https://autojs6.80fafa.com` 且 `ALLOW_LOCAL_OVERRIDE=false`
    （忽略 SP），debug（`src/debug`）默认 `http://10.0.2.2:8080`（模拟器经回环访问宿主机
    dev nginx 统一入口），SP 非空时覆盖（真机 `adb reverse tcp:8080 tcp:8080` 场景）；
  - `key_$_management_platform_secret`：租户设备接入码（WS matchCode）。P1 起用户不再手填,
    账号登录成功后由 `AccessCodeClient` 调 `/device/access-code/mine` 自动写入；
    **退出登录时连同 JWT 一并清除**（防止换号网络失败时沿用上一租户 matchCode 越权上线）；
  - `key_$_management_platform_enabled`：登录态开关（boolean），语义等同"账号会话存在"：
    `AccountSession` 登录/恢复置 true、退出置 false；缺键（老版本升级）一律按未登录处理,
    需用租户账号重新登录一次（登录后自动拉到与历史值相同的 matchCode，WS 行为不变）；
  - 回落键：`key_$_server_address`（仅 debug 覆盖读取，无默认值副作用）。
- 账号 JWT 持久化键（P1 新增，`mgmt_account_*`，AndroidKeyStore AES/GCM 密文，卸载即清）：
  `mgmt_account_username / mgmt_account_access_token / mgmt_account_refresh_token /
  mgmt_account_expire_at`。当前服务端 refresh_token 与 access_token 同值且无独立刷新端点,
  过期后回到登录态（勿臆造 refresh 接口）；注册 APP 内零表单，唯一通道是系统浏览器打开
  `{serverBase}/admin/register`（一期无 deep link 回跳）。
- WS 接入 URL：`{serverBase}/ws/device?deviceId=<id>&matchCode=<secret>`。
- APK 下载 URL：`{serverBase}/api/apk/files/{fileName}?matchCode=<secret>`。
- 设备标识统一收敛到 `core/DeviceFingerprint.kt`：直接读 `Settings.Secure.ANDROID_ID` 并
  **进程内缓存**，缺失回落 `Build.MODEL`，与服务端 `device_info.device_id` 及市场下载核销
  `X-Device-Id` 同源同值。**禁止**经 `runtime.api.Device(context)`
  取 androidId——其构造同步调 `TelephonyManager.getImei/getSerial` binder；抽屉行渲染/弹窗在主线程,
  叠加 SP getter 内的 dhcpInfo binder，2026-09-28 实测在模拟器上阻塞主线程 5.3s 触发系统 ANR
  （/data/anr 堆栈实证）。`Device(appContext)` 仅允许出现在 WS 线程的设备动作处理中。

---

## 3. 挂钩点清单（升级时只需逐个核对这 7+2 处）

代码内搜索 `@mgmt-hook` 可定位全部锚点；`@mgmt-adapt` 为薄层内部的工具链定点适配。

| 编号 | 文件 | 位置 | 内容 |
|---|---|---|---|
| H1 | `app/src/main/AndroidManifest.xml` | L103-104、L106-107、L430-434、L436-443 | `FOREGROUND_SERVICE_DATA_SYNC` + `USE_FULL_SCREEN_INTENT` 权限；`ManagementPlatformService` 声明（`foregroundServiceType="dataSync"`、`exported=false`）；`MgmtWakeUnlockActivity` 声明（P3，`exported=false`、透明主题，仅追加条目） |
| H2 | `.../autojs/App.kt` | L85-86（+import L30） | 主进程 `onCreate` 调 `Mgmt.bootstrap(this)` |
| H3 | `.../autojs/AutoJs.kt` | L66-67（+import L34） | 引擎初始化后 `Mgmt.onEngineReady(scriptEngineService)` |
| H4 | `.../core/accessibility/AccessibilityService.kt` | L161-162、L173-174（+import L14） | `onDestroy` / `onServiceConnected` 各调一次 `Mgmt.onAccessibilityStateChanged()`（注意：清单中实际绑定服务是 `AccessibilityServiceUsher`） |
| H5 | `.../external/receiver/BaseBroadcastReceiver.java` | L30-35（+import L11） | try/catch 包裹 `Mgmt.connectIfConfigured()`，广播唤起不得因薄层崩溃 |
| H8 | `res/values/strings.xml`、`res/values-zh/strings.xml` | 搜 `@mgmt-hook H8` | 薄层文案（`text_management_platform_*` / `mgmt_text_*`，仅追加；条目以 strings 实际内容为准） |
| H9 | `.../ui/main/drawer/DrawerFragment.kt` | `initMenuItems()` 内 adapter 构造行（+import） | 一行委托：`DrawerMenuAdapter(Mgmt.decorateDrawerMenuItems(items).filterNot { it.isHidden })`，在菜单最前插入「账号」分区。P1 起为三态状态机（未登录：登录/免费注册；已登录未连接：连接设备/退出登录；已连接：断开连接/退出登录），含账号密码+图形验证码登录弹窗；逻辑全部在 `mgmt/ui/MgmtPlatformLoginMenu.kt`（adapter 活列表 mgmt 段原地增删刷新，挂钩行本身不变） |

> 编号 H6 保留未用（历史方案中曾规划、后取消）；H7 为旧设置页入口（地址/秘钥/测试连接
> 三个 Preference），2026-09-28 起被抽屉「登录」分区整体替代，官方
> `fragment_preferences.xml` 已恢复纯净、编号不补号。

### 上游内部类依赖（易碎点登记，升级 SOP 优先核对）

H9 的薄层实现引用了上游 `ui.main.drawer` 包的内部类（非公共 SDK，但属该包稳定的菜单模型）：

- `DrawerMenuItem`（构造、`setAction`、`getTitle()` 可覆写以实现「登录平台/已登录」动态标题）；
- `DrawerMenuGroup`（分区标题）；
- `DrawerMenuItemViewHolder`（点击回调中经 `bindingAdapterPosition`/`itemView.parent` 触发本条目刷新；
  并在 `itemView` 上挂 `OnAttachStateChangeListener`，抽屉重开时按条目最新状态自愈补刷）。

上游若重构抽屉菜单（改模型类名/标题为 CharSequence/Adapter 刷新方式），H9 需重新适配；
挂钩行本身（装饰 items 列表）保持不变即可不丢委托。

- 弹窗依赖 Material Components 库随宿主存在（`com.google.android.material.textfield.TextInputLayout`
  与 afollestad MaterialDialog `getActionButton(DialogAction)`）；上游若移除 material 依赖或升级
  MaterialDialog 大版本，登录弹窗需重新适配。宿主主题为 `Theme.AppCompat.DayNight`，凡
  MaterialComponents 专属样式必须经 `MgmtThemeMaterialBridge` 包裹 inflate，不可直接改 AppTheme。

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

## 5. 协议契约（服务端事实来源 `apps/server/src/module/device/`）

> 2026-09-29 起以**追加可选字段/新增消息类型**方式向后兼容扩展（弱网方案 §5.3/§6.1），
> 既有 30 种消息的语义与字段一字未改；旧 APK 不带 msgId 时服务端完全按旧路径处理。

设备端出站 12 种：`DEVICE_INFO`、`HEARTBEAT`、`CAPABILITIES`、`COMMAND_RESULT`、
`SCRIPT_LIST`、`INSTALLED_APPS`、`RUNNING_SCRIPTS`、`SCHEDULED_SCRIPTS`、
`SCRIPT_CONTENT`、`LOG_LINES`、`SCREENSHOT`、`SCRIPT_STATUS_UPDATE`。

设备端入站 24 种（`ManagementPlatformClient` when 块）：
`AUTH_OK`、`AUTH_FAILED`、`REQUEST_SCRIPT_LIST`、`PUSH_SCRIPT`、`RUN_SCRIPT`、
`STOP_SCRIPT`、
`REQUEST_SCREENSHOT`、`SCREEN_STREAM_START`、`SCREEN_STREAM_STOP`、
`TOUCH_EVENT`、`DEVICE_ACTION`、`REQUEST_RUNNING_SCRIPTS`、
`REQUEST_SCHEDULED_SCRIPTS`、`REQUEST_INSTALLED_APPS`、`REQUEST_LOG_TAIL`、
`REQUEST_SCRIPT_CONTENT`、`UPDATE_SCRIPT_CONTENT`、`DELETE_SCRIPT`、`CREATE_FOLDER`、
`CREATE_INTENT_TASK`、`CREATE_TIMED_TASK`、`DELETE_SCHEDULED_TASK`、`INSTALL_APK`、
`MESSAGE_ACK`。

- **`STOP_SCRIPT`（2026-10-07 追加，群控工作台群停止/单台停止共用）**：入站
  `{ commandId?, scriptId }`；`scriptId` 为空串时调用
  `ScriptEngineService.stopAll()` 停止设备全部脚本引擎；非空（设备端脚本绝对路径）时
  遍历 `scriptExecutions`，按 `source.fullPath` 匹配并逐个 `engine.destroy()`
  （支持同脚本多开）；结果经 `COMMAND_RESULT` 回执（无运行实例回失败并带中文原因）。
  支持该指令的客户端在 `CAPABILITIES`/`HEARTBEAT` 能力快照中上报 `stopScript:true`，
  旧客户端缺省该位时服务端前置拦截（错误码 7011），不静默超时。

- **`SCREEN_STREAM_START` / `SCREEN_STREAM_STOP`（2026-10-07 追加，P2 JPEG 流式自推）**：
  入站 START `{ quality, maxWidth?, maxHeight? }`。首个观看者加入观看网关时由服务端下发，
  客户端经 `mgmt/client/ScreenStreamer.kt`（调度线程 `mgmt-screen-stream`）自步进连续抓帧推送
  （帧间不等浏览器请求，消除每帧一个跨境往返 RTT 的停顿）；末个观看者离开下发 STOP。
  初始帧间隔 80ms，系统截图限流（errorCode=3）按 +100ms 阶梯退避学习本机最小间隔，
  连续成功 20 帧后 -20ms 下探（下限 33ms）；in-flight 始终单帧。START 重复发送=参数热更新
  （幂等），STOP 幂等；连接断开/退出登录立即停止，重连后服务端按观看者存在情况恢复并经
  观看网关通知 `STREAM_RESUMED`。推流期间的手动 REQUEST_SCREENSHOT 聚合到下一帧成功推送后
  统一回执。支持客户端在 CAPABILITIES 上报 `screenStream:true`；旧客户端缺省该位时服务端
  回退旧的浏览器 request-frame 按需路径。

- **DEVICE_ACTION `unlock` + 唤醒 Activity（2026-10-07 追加，P3 MINIMAL 无障档）**：
  新增动作 `action:"unlock"`，启动 `mgmt/ui/MgmtWakeUnlockActivity.kt`（op=wake_unlock）：
  `setTurnScreenOn/setShowWhenLocked` 亮屏，`KeyguardManager.requestDismissKeyguard`
  解除**非密码**锁屏（上滑/无密码直接进桌面）；`isDeviceSecure=true`（密码/PIN/图案）
  不发起 dismiss，直接 COMMAND_RESULT 失败并附中文指引。同一 Activity 承接
  `screen_on`（WakeLock/root 键码均失败后 op=wake）与 `home`（无障碍/root 均不可用时
  op=home，解锁成功后发 CATEGORY_HOME）的逐级兜底。后台 startActivity 被 BAL 静默拦截时
  以 Activity onCreate 桥接回调 + 1200ms 超时识别，超时走 `USE_FULL_SCREEN_INTENT`
  全屏意图通知（CATEGORY_CALL，2000ms 超时），仍失败回报"请手动点亮屏幕"。
  CAPABILITIES 上报 `minimal:true`；服务端在 accessibility/root 皆无时**不**下发
  SCREEN_STREAM_START（避免无限退避空转），前端画面区切空态卡 + 可用操作按钮矩阵。

- 鉴权：HTTP 升级后服务端按 `matchCode` 校验租户，成功回 `AUTH_OK`，失败回
  `AUTH_FAILED`（业务码 4001）并关闭连接。
- `AUTH_OK` 负载：`{ tenantId, tenantName? }`。`tenantName`（租户公司名）为 2026-09-28
  **追加的可选字段**，仅用于抽屉「已登录」弹窗展示；旧客户端忽略未知字段，旧服务端不下发时
  客户端回落展示 `tenantId`，双向向后兼容，不改变鉴权语义与消息类型集合。
- **上行幂等扩展（2026-09-29，方案 §5.3/§6.1）**：仅终态/回执类出站消息
  （`SCRIPT_STATUS_UPDATE`、`COMMAND_RESULT`、`LOG_LINES`）在信封顶层追加
  `msgId`（32 位十六进制 UUID）；客户端先落盘 outbox
  （`mgmt/client/OutboxManager.kt`，文件 `filesDir/mgmt/upstream_outbox.json`，
  上限 500 条/1MiB，媒体帧与 HEARTBEAT 不入队），AUTH_OK 后按 FIFO 限速重放；
  服务端 Redis `SET NX EX 600` 去重，首帧处理后回
  `{ type:'MESSAGE_ACK', payload:{ msgId, duplicate } }`，重复帧只回 ACK 不再落库；
  客户端凭 ACK 删条目，ACK 丢失则下次重连重放、由服务端去重兜底；退出登录清空 outbox，
  禁止跨账号/跨租户重放。msgId 缺失（旧 APK）时服务端跳过去重，行为与旧版一致。
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
9. 推送 `origin vendor` 与 `origin mgmt`，再到业务仓按 §6.1 更新 submodule 指针。

### 6.1 仓库拓扑与业务仓（submodule）同步

```
GitHub fork  https://github.com/alwaysgot200/AutoJs6.git             → 本地 remote origin
上游         https://github.com/SuperMonster003/AutoJs6.git      → 本地 remote upstream
  vendor 分支 = 上游纯净镜像；mgmt 分支 = vendor + 本文件全部定制
业务仓 d:\work\autojs6_Management
  AutoJs6-master/ = git submodule（.gitmodules: path=AutoJs6-master, url=../AutoJs6.git, branch=mgmt）
```

业务仓首次获取子模块（二选一）：

```bash
git clone --recurse-submodules https://github.com/alwaysgot200/autojs6_Management.git
# 或已 clone 主仓后补初始化：
git submodule update --init --recursive
```

fork 侧 `mgmt` 分支有新提交并推送后（例如换机器、他人推进），在业务仓执行：

```bash
git submodule update --remote AutoJs6-master   # 按 .gitmodules 的 branch=mgmt 拉到 origin/mgmt 最新提交
git add AutoJs6-master                         # 暂存新指针（子模块内 detached HEAD 属正常状态）
git commit -m "chore: bump AutoJs6 submodule to <短SHA>"
```

**日常客户端开发（2026-10-11 起的唯一路径，旧的独立 clone `d:\work\autojs6-fork` 已废弃）**：

```bash
# 业务仓内的 AutoJs6-master/ 本身就是 fork 的完整 clone（origin = GitHub fork），直接在里面开发：
cd AutoJs6-master
git switch mgmt            # 跟踪 origin/mgmt；不要停留在 detached HEAD 上提交
# …改 mgmt/ 自有文件 + 白名单挂钩，双 flavor 构建、git checkout -- version.properties…
git add -A && git commit -m "mgmt: …"
git push origin mgmt       # 直接推到 GitHub fork；走代理先 $env:HTTPS_PROXY='http://127.0.0.1:10808'
cd ..
git add AutoJs6-master && git commit -m "chore: bump AutoJs6 submodule to <短SHA>"
```

- 子模块工作区 = fork 工作区，提交推送一步到位；**【禁止】**"独立 clone 改 → 推送 → 子模块
  再 pull"的两段式流程，也不要在 detached HEAD 上提交（提交会因切分支丢失）。
- 主仓通过 submodule 指针（精确 commit）消费本目录历史；**【禁止】** subtree merge、
  手工拷贝文件。
- 首次挂入（已完成，备查）：业务仓执行
  `git submodule add -b mgmt ../AutoJs6.git AutoJs6-master`。
- 走本机代理时先设置 `$env:HTTPS_PROXY='http://127.0.0.1:10808'`（PowerShell）：子模块
  克隆/更新/推送进程不读主仓 `http.proxy`。

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
- 2026-09-28 — `runtime/api/Permissions.kt` 静态
  `requestMultiplePermissionsLauncherCache` 为 `WeakHashMap<Activity, Launcher>`，
  但 launcher 强引用 registry→Activity（value→key 强链），条目永不被清除，
  MainActivity onDestroy 后整实例被静态缓存泄漏（LeakCanary 实证，
  签名 d4b3ca18…，见代码内 `vendor:fix` 注释）。
  **2026-09-29 二次修正**：首版"value 包 `WeakReference`"经 androidx.activity 1.12.2
  字节码证伪——`ActivityResultRegistry` 字段表不含 launcher（`register()` 创建后直接
  返回、从不回存），而调用点又丢弃了 register 返回值；弱引用会导致 launcher 被 GC 后，
  RESUMED 态申请通知权限时抛 `IllegalStateException`。最终修补：**value 恢复强引用 +
  注册 `DefaultLifecycleObserver` 在 ON_DESTROY 显式移除条目**（观察者随
  LifecycleRegistry 一同销毁，不引入新静态链）。
  上游修复方式大概率相同或改为 lifecycle 自动 unregister，届时删除我方补丁。

上游日后修复这几点时，rebase 会直接冲突，届时删除我方补丁即可。

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
`INSTALL_APK`（需平台先上传 APK 包）为低频链路，协议与已验证链路同构；
`STOP_SCRIPT`（2026-10-07 新增：空 scriptId 全停 / 指定路径精确停 / COMMAND_RESULT 回执 /
能力位 stopScript 上报）本轮以双 flavor 构建验证，设备侧运行冒烟待补。

已知非缺陷现象：模拟器冷启动 JIT/dex 校验风暴偶发系统 ANR 弹窗（主线程卡在
LocaleManager binder，与薄层无关），点等待/二次启动即恢复，真机预期无此问题；
脚本内主动 `exit()` 会令引擎抛 `ScriptInterruptedException`，运行事件记为 error
（日志中脚本实际正常「运行结束」），属引擎既有行为。

### 9.1 P1 账号化地基（2026-10-08，集成市场方案 §7/§10.1，双 flavor 构建通过）

本轮双 flavor（`:app:assembleAppDebug` / `:app:assembleInrtDebug`）编译/资源链接通过；
设备侧运行冒烟待模拟器执行，验收剧本（A13）：

- 未登录抽屉仅「登录 / 免费注册」两行；点注册**只**拉起系统浏览器打开
  `{serverBase}/admin/register`，APP 内无注册表单/无 WebView 注册页；
- 登录弹窗 = 账号 + 密码 + 图形验证码（点击图片刷新；服务端关验证码时整行隐藏），
  调 `/api/v1/auth/login`；登录成功后自动 `/device/access-code/mine` 下发 matchCode
  并 FGS 上线（副标题 账号名·在线）；平台超管登录明确 toast 7018 文案、不静默失败；
- 已登录未连接：「连接设备」（接入码缺失现场补拉重试）/「退出登录」；
  已连接：「断开连接」（只断 WS 不掉登录，FGS 通知同步消失）/「退出登录」；
- 进程重启（force-stop 后桌面启动）：JWT 经 AndroidKeyStore 解密恢复，免登录自动上线；
  退出登录清全部凭证（`mgmt_account_*` 密文 + secret 键 + enabled=false），不自动重连；
- SP 两字面值键逐字符不变；地址 release 恒为 `https://autojs6.80fafa.com`，
  debug 默认 `http://10.0.2.2:8080`（真机 `adb reverse` 后可用原键覆盖）；
- 挂钩自检：H9 委托行未改，白名单外官方文件零改动（本轮无新挂钩点）。

---

## 10. P3 脚本市场薄层（2026-10-10 追加，无新挂钩点）

市场为「WebView 容器 + REST 下载 + 本地安装」薄层，全部为 mgmt 自有新文件，
官方文件仅在既有白名单锚点**追加条目**（H1 Manifest 追加 Activity、H8 strings 追加字符串、
H9 抽屉条目由 mgmt 自有 `MgmtPlatformLoginMenu.buildItems()` 追加，未新增挂钩点）：

```
mgmt/
├── client/MarketApiClient.kt          # GET /market/download/:id（带 JWT/X-Device-Id），
│                                      # 响应头元数据 + 流式落盘 + SHA-256/size 校验
├── script/MarketProjectLayout.kt      # 工程包 zip 预检（zip-slip/.so 白名单/炸弹防护）+ 解压
├── script/MarketScriptInstaller.kt    # SINGLE 单文件 / PROJECT 工程包安装到工作目录 market/<slug>/
└── ui/market/
    ├── MarketActivity.kt              # 双 tab WebView（脚本市场/技术论坛），@mgmt-hook H1 声明
    ├── MarketWebBridge.kt             # JS 桥 MgmtBridge（downloadAndInstall/getToken/...）
    └── MarketWebClients.kt            # 同源白名单：/admin/login 收口原生登录，外跳系统浏览器
```

自有资源：`res/layout/mgmt_activity_market.xml`、`res/drawable/mgmt_ic_store.xml`、
`mgmt_action_market` + `mgmt_market_*` 字符串（values/ 与 values-zh/ 双份，均带 @mgmt-hook H8）。

契约要点：

- 安装目录只信下载响应头 `x-install-slug`（服务端稳定短码 `script-<id>`），工程包安装后经
  `ProjectConfig.fromProjectDir` + main 文件存在性校验再原子替换旧目录；
- JS 桥 `MgmtBridge.downloadAndInstall(JSON.stringify({scriptId}))` 结果以
  `window.dispatchEvent(new CustomEvent('mgmt:install-result',{detail:{ok,message}}))` 回派；
- 游客可浏览/下载免费脚本；需要登录的交互（发帖/评论）由门户页引导 /admin/login，
  客户端拦截该 URL 收口到 P1 原生登录弹窗，登录成功后刷新 WebView。

---

## 11. P5 收费购买闭环、会话同步与失败指引（2026-10-11 追加，无新挂钩点）

收费购买/续费/席位设备管理的 APP 端全流程（方案 §13.1 P5）。后端接口、前端门户页
（订单/收银/席位/详情支付后自动安装）均在业务仓实现；客户端本轮只补「会话桥 +
失败提示」两处缺口，**全部为 mgmt 自有文件改动 + H8 追加，白名单外官方文件零改动**：

```
mgmt/
├── auth/AccountSession.kt            # +refreshToken() 只读访问器（供桥同步 refresh 令牌）
├── client/MarketApiClient.kt        # DownloadException(val bizCode: Int?, message) +
│                                     # 次构造；非 2xx 先解析统一信封再抛，码值随消息上抛
└── ui/market/
    ├── MarketActivity.kt             # 账号监听登录/退出均 reload；+nativeLogout()；
    │                                 # 安装失败改 AlertDialog（替 toast）+ TAG 日志
    └── MarketWebBridge.kt           # +@JavascriptInterface getRefreshToken()/nativeLogout()
```

H8 字符串双份追加（values + values-zh，均带 `@mgmt-hook H8: P5 …` 注释）：
`mgmt_market_failed_title`（安装失败 / Installation failed）、
`mgmt_market_failed_confirm`（我知道了 / Got it）。对话框正文不新增字符串，
直接使用后端 71xx 信封中文指引或本地校验原因。

桥契约增量（注入名仍为 `window.MgmtBridge`，前端事实来源业务仓
`apps/web/src/utils/native-bridge.ts`）：

- `getToken()` 已有；新增 `getRefreshToken()` —— 门户启动时把原生双令牌同步进
  WebView localStorage（前端 `syncNativeSession()` 在 store/auth 初始化前执行），
  此后购买/订单/席位等鉴权请求与普通浏览器同链路，**禁止**门户页另造认证；
- 新增 `nativeLogout()` —— 门户顶栏退出的唯一原生收口；WebView 不持独立登录态，
  仅清 web 存储会在下次启动同步时被原生会话恢复，必须走原生。

退出编排（全部主线程，薄层异常 `runCatching` 吞掉不影响宿主）：

```
顶栏「退出登录」→ 确认对话框 → MgmtBridge.nativeLogout()
  → Activity.runOnUiThread { Mgmt.logout(ctx) }   # 复用 P1 抽屉同一编排：
  服务端拉黑(最佳努力) → 清 Keystore 凭证/matchCode → 断 WS/停 FGS → toast
  → AccountSession Listener(登出) → Activity.reload()
  → 前端启动同步读桥(未登录) → 清空 localStorage → 顶栏回游客态
```

安装失败提示体系（替代 P3 的长 toast，指引文案后端可运营热改）：

- 非 2xx：解析统一信封，`DownloadException(bizCode = envelope.code（非 200）, msg)`；
  网络层/无信封网关错误 `bizCode=null`，UI 仅记日志不改变形态；
- 业务拒绝（7154 授权过期需续费 / 7158 席位已满需移除 / 7166 设备解绑需联系客服 /
  7167 非授权设备改用 APP）→ `AlertDialog`（`setCancelable(false)`，必须显式确认）
  展示后端完整指引句；`Log.w("MgmtMarket", …, err)` 留证；结果事件
  `mgmt:install-result` 回派契约不变。

构建/验证：

- 2026-10-11 双 flavor 构建 `:app:assembleAppDebug :app:assembleInrtDebug`
  **BUILD SUCCESSFUL**（Gradle 9.4.0 / JDK17 / 358 tasks，17m51s），
  产物 `autojs6-*universal.apk`、`inrt-*universal.apk` 均重新生成；
  构建后已 `git checkout -- version.properties`（自动改写的 BUILD 号不提交）；
- 业务仓桌面端以 Cypress 注入模拟 `window.MgmtBridge` 实测三旅程通过：
  桥令牌启动同步后 `/admin/market/orders` 鉴权接口 200、顶栏为已登录态；
  360px 窄屏商店/收款/订单/论坛四页无横向滚动（实测中修复了门户顶栏与
  商店筛选栏的窄屏溢出）；顶栏退出经 `nativeLogout` → reload 后 localStorage
  清空并回到游客态。Cypress 为临时验证，spec 未入库；
- **待补：设备侧运行冒烟**（无连接设备，账号弹窗购买→收银→失败指引对话框/
  续费/席位移除的真机链路），按 §9 清单口径在下一轮真机执行。

