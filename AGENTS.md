# AGENTS.md — AutoJs6 二开开发宪法（fork: alwaysgot200/AutoJs6）

> 本文件是本仓库二次开发的唯一强制约束，对人与 AI Agent 同等有效。
> 规则级别：**【强制】** 必须遵守｜**【禁止】** 不得出现｜**【推荐】** 默认遵守，偏离需说明理由。
> 薄层（管理平台定制）的事实索引是仓库根 `MGMT_LAYER.md`，本文件约束全局，两者冲突以更严格者为准。

---

## 1. 仓库定位与分支铁律

本仓是 [SuperMonster003/AutoJs6](https://github.com/SuperMonster003/AutoJs6) 的二开 fork
（基线 `ed3eb10e`，v6.7.0 BUILD 3804），在保持可持续跟随官方升级的前提下承载我方定制。

| 远程/分支 | 角色 | 允许的操作 |
|---|---|---|
| `upstream` → SuperMonster003/AutoJs6 | 官方源 | 只 fetch/merge，不推送 |
| `origin` → alwaysgot200/AutoJs6 | 我方 fork | 推送 vendor/mgmt |
| `vendor` 分支 | 官方纯净镜像 | **只允许** fast-forward 合入 upstream，禁止任何定制提交 |
| `mgmt` 分支 | vendor + 全部定制 | **唯一日常开发分支**，所有改动在此提交 |
| `master` | fork 默认分支（=基线） | 不在此开发 |

- **【强制】** 客户端开发与构建只在本仓 `mgmt` 分支进行；业务仓（`d:\autojs6_Management`）
  通过 `git subtree --prefix=AutoJs6-master` 只读消费本分支，**禁止**在业务仓里改本工程文件。
- **【强制】** 提交前缀分层，禁止混提：`mgmt:`（薄层/挂钩/本仓文档）、`custom:`（与管理平台
  无关的小定制）、`vendor:fix:`（官方缺陷修补）、`vendor:build:`（构建链定点修补）。

### 1.1 去重优先三法则（vendor 为主，mgmt 永不膨胀）

1. **【强制】日常开发去重**：发现某能力 vendor 已具备，立即删除 mgmt 中的重复实现，
   改为经门面/挂钩复用 vendor；同一能力全仓只保留一份。
2. **【强制】升级合并去重**：rebase/merge vendor 前先逐项比对——新版本已覆盖的 mgmt 功能，
   **先删 mgmt 重复实现、再合并**；冲突取舍一律 vendor 优先，合并后双 flavor 构建 + 冒烟。
3. **【强制】独立低耦合永存**：mgmt 始终是可整包摘除的独立模块——自有文件、自有包、
   只经 `Mgmt` 门面与一行挂钩触碰官方；不复制官方类、不依赖官方内部实现，耦合面只许缩小。

---

## 2. 环境与构建

- JDK 17–25（CI 用 21，本机实测 JDK 17）；Android SDK + NDK + CMake 3.22.1，
  路径写入 **不入库** 的 `local.properties`（`sdk.dir`/`cmake.dir`）。
- Gradle 用 wrapper（9.4.0），**禁止**直接用系统 gradle；AGP/Kotlin 版本由
  `settings.gradle.kts` 依 `.utils/` 元数据按 Gradle 版本自动解析，仅可通过
  `version.properties` 的 `OVERRIDDEN_*` 键临时覆盖。
- 首次配置需联网下载 OpenCV/ONNX/Paddle 等原生依赖（build-logic 的 LibDeployer）；
  网络受限时构建必带代理：
  `$env:JAVA_TOOL_OPTIONS='-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=10808 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=10808'`
  （根 `build.gradle.kts` 已内置阿里云 maven 镜像，仓库内不得再加镜像/改分发 URL）。

```powershell
$env:JAVA_HOME='D:\programfiles\Java\jdk-17'
$env:JAVA_TOOL_OPTIONS='-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=10808 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=10808'
.\gradlew.bat :app:assembleAppDebug      # 完整 AutoJs6 IDE（包名 org.autojs.autojs6）
.\gradlew.bat :app:assembleInrtDebug     # 打包用运行时（包名 org.autojs.autojs6.inrt）
```

- 产物：`app/build/outputs/apk/<flavor>/<buildType>/autojs6-vX.Y.Z-<abi>.apk`
  （按 ABI 分包 + universal）；模拟器 x86_64 装 `-x86_64.apk`，真机装 `-arm64-v8a.apk`。
- 签名：release 读取根目录 `sign.properties`（**不入库**）；给用户打包 APK 用的
  `assets/autojs.keystore`、`default_key_store.bks` 为上游自带资产，勿删勿换。
- **【强制】** 构建会自动改写 `version.properties`（BUILD 号/时间戳），构建后必须
  `git checkout -- version.properties`，禁止提交。
- **【禁止】** 提交任何构建产物（`build/`、`**/bin/`、`.cxx/`、`.gradle/`、`.run/`、`.idea/`）。

---

## 3. 仓库模块布局

```
app/                     主应用模块（1740 个 kt/java 源文件，40 Activity/11 Service）
modules/                 9 个以源码形式 vendor 的第三方 UI/工具库（material-dialogs、apk-signer 等）
libs/                    二进制/源码第三方库（OpenCV、RapidOCR、imagequant、androidterm、markwon、root-shell…）
plugin-api/              paddle-ocr-api / paddle-ocr-engine 插件接口
build-logic/             included build：版本解析(Versions)、签名(Signs)、原生库部署(LibDeployer/SevenZExtractor)
gradle/libs.versions.toml  统一依赖版本目录（新增依赖先在此确认是否已存在）
.utils/ .changelog/      构建兼容性元数据与多语言更新日志（生成物，勿手改）
```

- compileSdk 36、minSdk 24、targetSdk 36（app）/29（inrt）；MultiDex + core library desugaring。
- 两个 flavor：**app**（完整脚本 IDE，LAUNCHER 入口）与 **inrt**（脚本打包后的独立运行时，
  targetSdk 固定 29）；共享 `src/main`，差异仅靠 buildConfig 字段与 manifestPlaceholders。
- **【强制】** 新增第三方依赖前先查 `libs.versions.toml` 与各模块已有依赖；
  **【禁止】** 给 mgmt 薄层引入新依赖（okhttp 4.12/okio/gson/joda-time 等上游均已自带）。

---

## 4. 应用架构（官方代码，改动前先理解）

启动与门面链路：

```
App (MultiDexApplication, app/org/autojs/autojs/App.kt)
  └─ GlobalAppContext / CrashHandler / EventBus / TimedTaskManager / 浮窗管理初始化
  └─ H2: Mgmt.bootstrap(this)                         ← 我方唯一 Application 挂钩
AutoJs.instance (AutoJs.kt : AbstractAutoJs.kt)       全局门面单例
  └─ scriptEngineManager / ScriptEngineService        脚本引擎服务（H3 引擎就绪挂钩点）
MainActivity (ui/main/MainActivity.kt)                主界面，mgmt 零修改红线
```

关键包地图（`app/src/main/java/org/autojs/autojs/`）：

| 包 | 职责 |
|---|---|
| `engine/` | 脚本引擎抽象：`ScriptEngineService`、`RhinoJavaScriptEngine`（Rhino JS 引擎）、RootAutomatorEngine |
| `rhino/` `runtime/` | JS 运行时与 `$`/`floaty`/`http` 等脚本 API 注入 |
| `model/script/` | `ScriptFile`、`Scripts.run(context, file)`（运行脚本的官方入口）、工程/索引模型 |
| `core/accessibility/` | 无障碍服务与手势/控件 API；清单实际绑定组件为 `AccessibilityServiceUsher` |
| `timing/` | 定时任务：`TimedTaskManager` + Alarm/Job/Work 三套 Scheduler + 广播 Receiver |
| `external/` | 对外 Intent API：`RunIntentActivity`、`AppFileProvider`、`BaseBroadcastReceiver`（H5 挂钩点） |
| `service/` | `AccessibilityService`、`ForegroundService`、`NotificationService` |
| `ui/` | View/Fragment + Material 传统视图体系（**无 Compose**），`ui/main` 主界面、`ui/settings` 设置、`ui/floating` 悬浮窗、`ui/viewmodel` |
| `core/pref/Pref.kt` | 全局偏好（PreferenceManager 默认 SP，键来自 `R.string.key_*`）；mgmt **禁止修改**，自有配置走 `mgmt/pref/MgmtPref.kt` |
| `inrt/` | inrt flavor 的启动/设置/更新检查 |
| `net/` `network/` | 旧版 VSCode 调试插件连接（HTTP），与 mgmt 无关 |
| `mgmt/` | **我方管理平台薄层（独立子树，见 §5）** |
| `permission/` `core/permission/` | 运行时权限框架；新权限优先复用，勿自造申请流程 |
| `tool/` `util/` `pio/` `storage/` | 通用工具、文件 IO、工作目录（`WorkingDirectoryUtils.path` = 脚本根目录） |

其它架构事实：

- 异步以 RxJava2 + EventBus 为主，少量协程；UI 为命令式 Fragment，无 MVVM/Compose 强约束。
- `android.nonTransitiveRClass=true`、`nonFinalResIds=true`：R 类按模块包名
  （主工程为 `org.autojs.autojs6.R`），资源 ID 非 final，Java 侧不得把 R.id 当编译期常量。
- 国际化 9 语言（values/values-en/values-zh/values-zh-rTW/…）；**【强制】** 新增文案至少补
  `values`（英文）与 `values-zh`，禁止硬编码中文到代码/布局。
- minSdk 24：commons-io 锁 2.8.0、jackson 锁 2.13.4.2 等均为低版本兼容选择，**【禁止】**擅自升级。
- 日志用 `android.util.Log`（常量 TAG）或脚本面向的 `GlobalConsole`；**【禁止】** `println`/`printStackTrace` 入库。

---

## 5. mgmt 薄层铁律（我方定制，详见 MGMT_LAYER.md）

- 代码全部在 `org.autojs.autojs.mgmt`：`Mgmt.kt`（唯一门面）+ client/service/script/pref/perm/ui。
- 对官方文件的修改实行挂钩白名单：仅 H1 Manifest、H2 App.kt、H3 AutoJs.kt、
  H4 AccessibilityService 两处、H5 BaseBroadcastReceiver、H7 fragment_preferences.xml、
  H8 strings（values + values-zh），每处一行委托并带 `@mgmt-hook Hx` 注释；
  新增挂钩点必须**先改 `MGMT_LAYER.md` §3 清单再写代码**。
- **【禁止】** 复制官方类整文件改写、在官方类中加平台方法/字段/业务 import；
  资源只增不覆盖（新资源独立命名，禁止改官方同名资源）。
- 32 种 WS 消息协议、`/ws/device` 接入路径、APK 下载路径为冻结契约；
  任何协议变更必须服务端先出兼容方案、两端同改同验。
- SP 键（`key_$_management_platform_server_address`、`key_$_management_platform_secret`）
  为固定契约，禁止改名；薄层任何异常不得穿透宿主 Application/Receiver。

---

## 6. 跟随官方升级 SOP

1. `git fetch upstream` → 读 release/changelog，评估挂钩、被引用 API、构建链影响；
2. `git switch vendor && git merge --ff-only upstream/<默认分支>`，推送 origin vendor；
3. `git switch mgmt && git rebase vendor`：**先执行 §1.1 去重比对**，冲突以 vendor 为准，
   保上游主体、按 `@mgmt-hook` 把一行委托挂回原语义位置；`vendor:fix` 若上游已修则删补丁；
4. 双 flavor 构建：`assembleAppDebug` + `assembleInrtDebug`；还原 version.properties；
5. 全量搜索 `@mgmt-hook` 核对 7+2 处齐全，跑 `MGMT_LAYER.md` §9 冒烟清单；
6. 推送 origin mgmt，业务仓 `git subtree pull` 同步。

---

## 7. 完成定义（DoD）

- [ ] 改动在 mgmt 分支；定制代码 = `mgmt/` 自有文件 + 白名单挂钩，无越界官方文件改动
- [ ] 已执行去重三法则：vendor 已有的能力不在 mgmt 重复实现
- [ ] 未新增不必要第三方依赖；未改 version.properties/镜像 URL/构建产物入库
- [ ] 新增文案含 values + values-zh；无硬编码密钥、无 println/printStackTrace
- [ ] app/inrt 双 debug flavor 构建通过；受影响链路按 MGMT_LAYER §9 冒烟通过
- [ ] commit 前缀合规（mgmt:/custom:/vendor:fix:/vendor:build:）
- [ ] 协议/挂钩/SP 键等冻结契约零改动（或已与服务端同改同验并更新 MGMT_LAYER）
