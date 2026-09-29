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
- 前台服务保活、运行时权限申请；
- 左侧抽屉最前「登录」分区（设置页不再保留平台入口）：未登录弹窗填写地址/API Key 登录（弹窗回填上次凭证、行内进度与错误提示）；已登录弹窗展示租户名/设备 ID/服务器并可退出登录（退出保留凭证但不自动重连）。

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
├── pref/MgmtPref.kt                                 # 平台地址/秘钥/登录态开关读写（默认 SP）
├── perm/MgmtPermissionRequester.kt                 # 运行时权限申请注册
└── ui/
    └── MgmtPlatformLoginMenu.kt                    # 抽屉「登录」分区条目 + 登录/已登录弹窗 (H9)
```

薄层自有资源（`mgmt_` 前缀，只增不覆盖）：

- `res/drawable/mgmt_ic_login.xml`：抽屉登录条目图标（vector）；
- `res/drawable/mgmt_ic_{server,key,person,smartphone,check_circle,content_copy}.xml`：弹窗图标（均为 24dp vector）；
- `res/drawable/mgmt_bg_{icon_circle,icon_circle_connected,info_card}.xml`：弹窗圆标底/信息卡片底；
- `res/layout/mgmt_dialog_login.xml`：登录表单（头部 + 服务器地址 + 平台 API Key 密文切换 + 行内错误/进度）；
- `res/layout/mgmt_dialog_logged_in.xml`：已登录信息卡（用户名 / 设备 ID 可复制 / 服务器）；
- `res/values/mgmt_colors.xml`、`res/values/mgmt_ids.xml`：弹窗色板与视图 tag id；
- `res/values/mgmt_styles.xml`：`MgmtThemeMaterialBridge`（`Theme.MaterialComponents.DayNight.Bridge`），
  仅用于包裹登录弹窗视图 inflate（宿主 AppTheme 是 AppCompat，OutlinedBox TextInputLayout 运行时
  强制要求 MaterialComponents 主题，直接 inflate 会抛 InflateException）。

### 关键配置事实

- 偏好文件：`org.autojs.autojs6_preferences.xml`（PreferenceManager 默认 SP）。
- 键字面值是**固定契约**（已安装版本依赖这些键，改名会丢用户配置，均由 `MgmtPref` 常量持有）：
  - `key_$_management_platform_server_address`（Kotlin 源码写 `"\$_"`）：服务器地址；
  - `key_$_management_platform_secret`：平台 API Key（退出登录后仍保留，用于登录弹窗回填）；
  - `key_$_management_platform_enabled`：登录态开关（boolean）。false=已退出、不得自动上线；
    缺键（老版本升级）按"地址+秘钥均非空"推断为 true，避免老用户升级后掉线；
  - 回落键：`key_$_server_address`（纯字符串读取，无默认值副作用）。
    **禁止**在 SP getter 默认值里放 `NetworkUtils.getGatewayAddress()`——Kotlin 默认参数立即求值,
    键存在也会每次执行 WifiService binder；该调用只允许在登录弹窗的后台线程异步预填（见
    `MgmtPlatformLoginMenu.showLoginDialog`）。
- WS 接入 URL：`{serverAddress}/ws/device?deviceId=<id>&matchCode=<secret>`。
- APK 下载 URL：`{serverAddress}/api/apk/files/{fileName}?matchCode=<secret>`。
- 设备标识 `deviceId()`：直接读 `Settings.Secure.ANDROID_ID` 并**进程内缓存**，缺失回落
  `Build.MODEL`，与服务端 `device_info.device_id` 对齐。**禁止**经 `runtime.api.Device(context)`
  取 androidId——其构造同步调 `TelephonyManager.getImei/getSerial` binder；抽屉行渲染/弹窗在主线程,
  叠加 SP getter 内的 dhcpInfo binder，2026-09-28 实测在模拟器上阻塞主线程 5.3s 触发系统 ANR
  （/data/anr 堆栈实证）。`Device(appContext)` 仅允许出现在 WS 线程的设备动作处理中。

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
| H8 | `res/values/strings.xml`、`res/values-zh/strings.xml` | 搜 `@mgmt-hook H8` | 薄层文案（`text_management_platform_*` / `mgmt_text_*`，仅追加；条目以 strings 实际内容为准） |
| H9 | `.../ui/main/drawer/DrawerFragment.kt` | `initMenuItems()` 内 adapter 构造行（+import） | 一行委托：`DrawerMenuAdapter(Mgmt.decorateDrawerMenuItems(items).filterNot { it.isHidden })`，在菜单最前插入「登录」分区（group + 单条目），逻辑全部在 `mgmt/ui/MgmtPlatformLoginMenu.kt` |

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
- `AUTH_OK` 负载：`{ tenantId, tenantName? }`。`tenantName`（租户公司名）为 2026-09-28
  **追加的可选字段**，仅用于抽屉「已登录」弹窗展示；旧客户端忽略未知字段，旧服务端不下发时
  客户端回落展示 `tenantId`，双向向后兼容，不改变鉴权语义与消息类型集合。
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

fork 侧 `mgmt` 分支有新提交并推送后，在业务仓执行：

```bash
git submodule update --remote AutoJs6-master   # 按 .gitmodules 的 branch=mgmt 拉到 origin/mgmt 最新提交
git add AutoJs6-master                         # 暂存新指针（子模块内 detached HEAD 属正常状态）
git commit -m "chore: bump AutoJs6 submodule to <短SHA>"
```

- 业务仓内**只允许**通过 submodule 更新本目录；不要在业务仓直接改
  `AutoJs6-master/` 内文件（改动回灌不到 fork，且与主仓锁定指针冲突）。
- 如需改客户端，先在本仓（fork）的 `mgmt` 分支提交并推送，再回业务仓更新指针。
- 首次挂入（已完成，备查）：业务仓执行
  `git submodule add -b mgmt ../AutoJs6.git AutoJs6-master`。
- 走本机代理时先设置 `$env:HTTPS_PROXY='http://127.0.0.1:10808'`（PowerShell）：子模块
  克隆/更新进程不读主仓 `http.proxy`，推送本仓到 GitHub 同样需要。

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
`INSTALL_APK`（需平台先上传 APK 包）为低频链路，协议与已验证链路同构。

已知非缺陷现象：模拟器冷启动 JIT/dex 校验风暴偶发系统 ANR 弹窗（主线程卡在
LocaleManager binder，与薄层无关），点等待/二次启动即恢复，真机预期无此问题；
脚本内主动 `exit()` 会令引擎抛 `ScriptInterruptedException`，运行事件记为 error
（日志中脚本实际正常「运行结束」），属引擎既有行为。
