<#
.SYNOPSIS
    AutoJs6 客户端 (app flavor) 构建 / 部署 / 日志工具 (Windows PowerShell 5.1+)

.DESCRIPTION
    脚本只负责: 编译 -> 安装到设备 -> 启动 -> 看日志 (等价于 Android Studio 的 Run)。
    构建类命令 (dev / build / test / release 等) 先过 Kotlin 编译门禁
    (:app:compileApp<Debug|Release>Kotlin), 语法/类型不合格立即终止, 不白启模拟器。
    管理平台地址默认由脚本注入 http://192.168.1.27（局域网测试服务器，GitLab CI 部署），
    装完即连测试环境；-ServerUrl '' 可跳过注入，再在 App 内手动配置。
    AutoJs6-master 是 fork 的独立 clone (跟踪 mgmt 分支): 检测到工程文件缺失时自动
    git clone, 失败会给出手动命令。脚本既可放在 fork 仓库根目录（自身即工程），
    也可放在管理仓根目录（工程在其下的 AutoJs6-master/）。

    交互模式下选择 "一键运行" 会先列出全部部署目标供手动选择, 启动任何模拟器前都会确认:
      USB/局域网真机 (adb 已连接) | 雷电模拟器 (自动发现安装路径与多开实例, 未运行自动拉起)
      | Android AVD (多个 AVD 逐个列出)
    非交互命令 (dev/deploy/quick) 保持自动: 已连设备直接用, 否则自动启动第一个 AVD/雷电实例。

    测试环境统一入口 (脚本自动注入, 无需手填):
      http://192.168.1.27   (1.27 的 nginx 80, 与生产单域名同路径)
      注入方式: 安装 Debug 包后写入 shared_prefs 的 serverOverride,
      只影响 debug 包; Release 包仍用 MgmtEnv 的编译期默认值。
    接入码/测试连接仍在 App "设置 -> 管理平台" 内操作。

.EXAMPLE
    .\build-client.ps1                  # 交互菜单 (直接回车 = 编译安装并启动)
    .\build-client.ps1 dev              # 非交互: 增量安装 + 启动 + 全量日志
    .\build-client.ps1 dev -NoLog       # 安装启动, 不附着日志
    .\build-client.ps1 netlog           # 仅看 ManagementPlatform 联调日志
    .\build-client.ps1 reverse          # 真机 USB 联调: 设备 localhost:8080 -> 宿主机 8080
    .\build-client.ps1 build            # 仅构建 Debug APK
    .\build-client.ps1 test             # 单元测试
    .\build-client.ps1 help             # 全部命令
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet(
        'menu', 'help',
        'dev', 'deploy', 'quick',
        'log', 'netlog',
        'build', 'test',
        'reverse',
        'stop', 'clear-data', 'grant',
        'start-emu', 'kill-emu',
        'clean', 'rebuild', 'release', 'install-release',
        'apk'
    )]
    [string] $Command = 'menu',

    # 部署后不附着 logcat
    [switch] $NoLog,

    # 调试包安装后写入的管理平台地址（debug 专属 SP 覆盖；空字符串 = 跳过注入）
    [string] $ServerUrl = 'http://192.168.1.27'
)

# ======================== 项目配置 ========================

# AutoJs6 Gradle 工程目录:
#   脚本位于 fork 仓库根目录 (含 gradlew.bat + settings.gradle.kts) → 脚本目录自身;
#   脚本位于管理仓根目录 (管理仓也有 .git, 不能仅凭 .git 判断) → 同级的 AutoJs6-master/。
$IsForkRoot = (Test-Path (Join-Path $PSScriptRoot 'gradlew.bat')) -and
              (Test-Path (Join-Path $PSScriptRoot 'settings.gradle.kts'))
$ProjectDir = if ($IsForkRoot) {
    $PSScriptRoot
}
elseif (Test-Path (Join-Path $PSScriptRoot 'AutoJs6-master\gradlew.bat')) {
    Join-Path $PSScriptRoot 'AutoJs6-master'
}
else {
    Join-Path $PSScriptRoot 'AutoJs6-master'
}

# 客户端包名 (app flavor; debug 无 applicationIdSuffix)
$AppId = 'org.autojs.autojs6'

# 本机代理 (LibDeployer 需从 GitHub 下载 OpenCV/OnnxRuntime 等原生依赖)
# 不需要代理时改为 $false 即可
$UseProxy = $true
$ProxyHost = '127.0.0.1'
$ProxyPort = 10808

# Gradle 附加参数
$GradleExtraArgs = @('--console=plain')

# 模拟器冷启动超时 (秒), 首次开机可能较慢
$BootTimeoutSec = 300

# ======================== 联调网络配置 ========================

# 宿主机 dev 入口端口: 8080=nginx (推荐), 8081=NestJS 直连
# 仅用于 adb reverse (真机 USB 联调) 与菜单提示, App 侧地址在 App 设置页手动填写
$ServerPort = 8080

# 联调日志关注的 tag
$NetLogTags = @('ManagementPlatformClient:V', 'ManagementPlatformService:V')

# 保证 adb 输出 (exec-out 文本) 在 PS 5.1 下按 UTF-8 解码
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

# ======================== 客户端源码就绪检查 ========================

# AutoJs6-master 是 fork 的独立 clone (mgmt 分支), 不再是 git submodule。
# 目录缺失/为空时自动 clone; 走代理的克隆进程读不到主仓 http.proxy,
# 须经 HTTPS_PROXY 环境变量 (与 AGENTS.md §16 一致)。
function Ensure-ClientSource {
    $marker = Join-Path $ProjectDir 'gradlew.bat'
    if (Test-Path $marker) { return $true }

    $repoUrl = 'https://github.com/alwaysgot200/AutoJs6.git'
    if ((Test-Path $ProjectDir) -and (Get-ChildItem $ProjectDir -Force -ErrorAction SilentlyContinue)) {
        Write-Host "目录 $ProjectDir 非空但缺少 gradlew.bat, 无法自动 clone。" -ForegroundColor Red
        Write-Host '请确认这是 AutoJs6 fork 的完整 clone (mgmt 分支), 或清空该目录后重试。' -ForegroundColor Yellow
        return $false
    }

    Write-Host "未找到客户端工程文件 ($marker), 正在克隆 fork: $repoUrl (mgmt) ..." -ForegroundColor Cyan

    if ($UseProxy) {
        $proxyUrl = "http://${ProxyHost}:$ProxyPort"
        $env:HTTPS_PROXY = $proxyUrl
        $env:HTTP_PROXY = $proxyUrl
    }

    & git clone --branch mgmt $repoUrl $ProjectDir
    $ok = ($LASTEXITCODE -eq 0) -and (Test-Path $marker)

    if (-not $ok) {
        Write-Host '客户端源码克隆失败。请在脚本所在目录手动执行 (代理按需):' -ForegroundColor Red
        Write-Host ("  `$env:HTTPS_PROXY='http://{0}:{1}'" -f $ProxyHost, $ProxyPort) -ForegroundColor DarkGray
        Write-Host "  git clone --branch mgmt $repoUrl `"$ProjectDir`"" -ForegroundColor DarkGray
        return $false
    }

    Write-Host '客户端源码已就绪 (mgmt)。' -ForegroundColor Green
    return $true
}

# ======================== SDK / 设备解析 ========================

# 依次从 local.properties、环境变量、默认路径解析 Android SDK
function Get-SdkRoot {
    $localProps = Join-Path $ProjectDir 'local.properties'
    if (Test-Path $localProps) {
        $line = (Get-Content $localProps -ErrorAction SilentlyContinue | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1)
        if ($line) {
            $val = ($line -split '=', 2)[1].Trim()
            # Java properties 转义还原: \: -> :  \\ -> \
            $val = $val -replace '\\:', ':' -replace '\\\\', '\'
            if ($val -and (Test-Path $val)) { return $val }
        }
    }
    foreach ($envName in @('ANDROID_HOME', 'ANDROID_SDK_ROOT')) {
        $v = [Environment]::GetEnvironmentVariable($envName)
        if ($v -and (Test-Path $v)) { return $v }
    }
    $fallback = 'D:\programfiles\Android\SDK'
    if (Test-Path $fallback) { return $fallback }
    return $null
}

$SdkRoot = Get-SdkRoot
$Adb = if ($SdkRoot) { Join-Path $SdkRoot 'platform-tools\adb.exe' } else { 'adb' }
$Emulator = if ($SdkRoot) { Join-Path $SdkRoot 'emulator\emulator.exe' } else { 'emulator' }

function Get-AvdList {
    if (-not (Test-Path $Emulator)) { return }
    $list = & $Emulator -list-avds 2>$null
    if (-not $list) { return }
    # 直接让数组元素逐个流出函数 (不要用一元逗号包裹, 否则调用方拿到嵌套数组);
    # 需要数组语义的调用方用 @(Get-AvdList) 包裹。
    @($list | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' })
}

# ======================== 雷电模拟器 (LDPlayer) ========================

# 雷电模拟器控制台为 dnconsole.exe。安装路径优先从注册表卸载信息解析,
# 找不到再扫常见目录。结果缓存, 避免每次刷新菜单都重复扫描。
$script:LdDirCache = $null
$script:LdDirResolved = $false
function Get-LdPlayerDir {
    if ($script:LdDirResolved) { return $script:LdDirCache }
    $script:LdDirResolved = $true

    # 1) 注册表卸载信息 (DisplayName 含 雷电/LDPlayer)
    $regRoots = @(
        'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
        'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
        'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*'
    )
    foreach ($root in $regRoots) {
        $items = Get-ItemProperty $root -ErrorAction SilentlyContinue |
            Where-Object { $_.DisplayName -match '雷电|LDPlayer' }
        foreach ($item in $items) {
            $candidates = @($item.InstallLocation)
            if ($item.DisplayIcon) {
                # DisplayIcon 形如 "C:\...\dnplayer.exe,0", 去掉末尾参数再取目录
                $iconPath = $item.DisplayIcon -replace ',\d+\s*$', ''
                $candidates += Split-Path $iconPath -Parent
            }
            foreach ($cand in $candidates) {
                if ($cand -and ((Test-Path (Join-Path $cand 'ldconsole.exe')) -or (Test-Path (Join-Path $cand 'dnconsole.exe')))) {
                    $script:LdDirCache = $cand.TrimEnd('\')
                    return $script:LdDirCache
                }
            }
        }
    }

    # 2) 常见安装目录 (扫描各盘符)
    foreach ($drive in (Get-PSDrive -PSProvider FileSystem).Root) {
        foreach ($sub in @('LDPlayer\LDPlayer9', 'LDPlayer\LDPlayer4', 'leidian\LDPlayer9', 'leidian', 'ChangZhi\dnplayer2')) {
            $cand = Join-Path $drive $sub
            if ((Test-Path (Join-Path $cand 'ldconsole.exe')) -or (Test-Path (Join-Path $cand 'dnconsole.exe'))) {
                $script:LdDirCache = $cand.TrimEnd('\')
                return $script:LdDirCache
            }
        }
    }

    return $null
}

# 返回雷电控制台程序路径: 优先 ldconsole.exe (无需管理员权限), 退回旧版 dnconsole.exe
function Get-LdConsole {
    param([Parameter(Mandatory = $true)][string] $LdDir)
    $ld = Join-Path $LdDir 'ldconsole.exe'
    if (Test-Path $ld) { return $ld }
    $dn = Join-Path $LdDir 'dnconsole.exe'
    if (Test-Path $dn) { return $dn }
    return $null
}

# 列出雷电多开实例。控制台 list2 每行 (字段数随版本不同):
#   索引,标题,顶层窗口句柄,绑定窗口句柄,运行状态(1=运行中 0=已停止),PID,...
function Get-LdPlayerInstances {
    param([Parameter(Mandatory = $true)][string] $LdDir)

    $console = Get-LdConsole -LdDir $LdDir
    if (-not $console) { return }

    # 雷电控制台按系统 OEM 代码页输出 (中文 Windows=GBK/936),
    # 脚本全局把 [Console]::OutputEncoding 设为 UTF-8, 直接捕获会导致标题乱码, 临时切回 OEM 代码页。
    $prevEncoding = [Console]::OutputEncoding
    try {
        $oem = [System.Text.Encoding]::GetEncoding((Get-Culture).TextInfo.OEMCodePage)
        [Console]::OutputEncoding = $oem
        $rows = @(& $console list2 2>$null)
    }
    finally {
        [Console]::OutputEncoding = $prevEncoding
    }
    # 元素逐个流出, 调用方需要数组时用 @(...) 包裹 (避免一元逗号导致嵌套数组)
    @(foreach ($row in $rows) {
        $f = ($row -split ',')
        if ($f.Count -ge 5 -and ($f[0].Trim() -match '^\d+$')) {
            [pscustomobject]@{
                Index   = [int] $f[0].Trim()
                Title   = $f[1].Trim()
                Running = ($f[4].Trim() -eq '1')
            }
        }
    })
}

# 雷电实例 i 的固定端口: 控制台 5554+i*2, adb 5555+i*2
function Get-LdPorts {
    param([Parameter(Mandatory = $true)][int] $Index)
    return [pscustomobject]@{
        Console = 5554 + $Index * 2
        Adb     = 5555 + $Index * 2
    }
}

# ======================== 基础函数 ========================

function Write-Header {
    Clear-Host
    Write-Host '========================================='
    Write-Host '   AutoJs6 构建 / 部署 / 联调工具 (PS1)'
    Write-Host '========================================='
    $online = Get-ConnectedDevice
    if ($online) {
        $kind = if ($online -match '^emulator-') { '模拟器' } else { '实体设备' }
        Write-Host (" 当前设备: {0} ({1})" -f $online, $kind) -ForegroundColor Green
    }
    else {
        $avdCount = @(Get-AvdList).Count
        $ldNote = if (Get-LdPlayerDir) { ' | 雷电模拟器: 已检测' } else { '' }
        Write-Host " 当前设备: 无 (可用 AVD: $avdCount 个$ldNote)" -ForegroundColor Yellow
    }
    Write-Host " App 管理平台地址: $ServerUrl (脚本安装后自动注入 debug 包)" -ForegroundColor DarkGray
}

function Pause-ForMenu {
    Read-Host '按回车返回菜单' | Out-Null
}

# 配置 Gradle 所需的 JVM 代理 (Gradle/Groovy 的 HttpURLConnection 只认 JVM 参数, 不认 http_proxy 环境变量)
function Set-ProxyEnv {
    if ($UseProxy) {
        $env:JAVA_TOOL_OPTIONS = "-Dhttp.proxyHost=$ProxyHost -Dhttp.proxyPort=$ProxyPort -Dhttps.proxyHost=$ProxyHost -Dhttps.proxyPort=$ProxyPort"
    }
}

# 在工程目录中执行 gradlew.bat, 失败返回 $false
function Invoke-Gradle {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]] $Arguments)

    if (-not (Test-Path (Join-Path $ProjectDir 'gradlew.bat'))) {
        Write-Host "未找到 gradlew.bat, 请确认工程目录: $ProjectDir" -ForegroundColor Red
        return $false
    }

    Set-ProxyEnv
    Push-Location $ProjectDir
    try {
        & .\gradlew.bat @Arguments @GradleExtraArgs
        return ($LASTEXITCODE -eq 0)
    }
    finally {
        Pop-Location
    }
}

# 编译前 Kotlin 语法门禁: 只跑编译任务 (不做 dex/打包/安装), 快速拦截语法/类型错误。
# 客户端不适用 pnpm lint/typecheck (AGENTS.md §16), Gradle 编译即等价检查;
# 增量编译下, 之前编译过则近乎零成本 (UP-TO-DATE)。
function Invoke-KotlinCheck {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet('Debug', 'Release')]
        [string] $Variant
    )

    Write-Host "==> 前置语法检查: :app:compileApp$($Variant)Kotlin ..." -ForegroundColor Cyan
    if (-not (Invoke-Gradle ":app:compileApp$($Variant)Kotlin")) {
        Write-Host "语法检查未通过 (compileApp$($Variant)Kotlin), 已阻止本次构建/部署。请修复上方 Kotlin 报错。" -ForegroundColor Red
        return $false
    }
    Write-Host "语法检查通过 (compileApp$($Variant)Kotlin)" -ForegroundColor Green
    return $true
}

function Get-AdbDeviceSerials {
    # adb devices 行格式: "序列号`tdevice"; offline/unauthorized 不计入。
    # 元素逐个流出; 调用方用 @(Get-AdbDeviceSerials) 得到数组 (禁止一元逗号, 会产生嵌套数组)。
    @((& $Adb devices) -match "`tdevice`$" | ForEach-Object { ($_ -split "`t")[0] })
}

function Get-ConnectedDevice {
    # 优先返回实体设备 (USB/局域网真机), 否则取第一个模拟器
    $devices = Get-AdbDeviceSerials
    if (-not $devices) { return $null }
    $real = $devices | Where-Object { $_ -notmatch '^emulator-|^127\.0\.0\.1:' } | Select-Object -First 1
    if ($real) { return $real }
    return ($devices | Select-Object -First 1)
}

# 后台启动指定 AVD, 并等待系统开机完成 (sys.boot_completed=1), 返回设备序列号
function Start-Avd {
    param([Parameter(Mandatory = $true)][string] $AvdName)

    if (-not (Test-Path $Emulator)) {
        Write-Host "未找到 emulator: $Emulator" -ForegroundColor Red
        return $null
    }

    # 启动前清理上次异常退出残留的锁 (snapshot.lock.lock error:5 的常见原因)
    # 仅在当前没有任何模拟器实例时执行, 避免误删正在运行实例的锁
    $runningEmus = (& $Adb devices) -match '^emulator-\d+'
    if (-not $runningEmus) {
        $avdIni = Join-Path $env:USERPROFILE ".android\avd\$AvdName.ini"
        $avdPath = $null
        if (Test-Path $avdIni) {
            $p = ((Get-Content $avdIni -ErrorAction SilentlyContinue) -match '^path=' | Select-Object -First 1)
            if ($p) { $avdPath = ($p -split '=', 2)[1].Trim() }
        }
        if ($avdPath -and (Test-Path $avdPath)) {
            foreach ($lockName in @('multiinstance.lock', 'snapshot.lock.lock')) {
                $lf = Join-Path $avdPath $lockName
                if (Test-Path $lf -PathType Leaf) {
                    Remove-Item $lf -Force -Confirm:$false -ErrorAction SilentlyContinue
                    Write-Host "已清理残留锁文件: $lockName" -ForegroundColor DarkGray
                }
            }
            $ld = Join-Path $avdPath 'hardware-qemu.ini.lock'
            if (Test-Path $ld -PathType Container) {
                Remove-Item $ld -Recurse -Force -Confirm:$false -ErrorAction SilentlyContinue
                Write-Host '已清理残留锁目录: hardware-qemu.ini.lock' -ForegroundColor DarkGray
            }
        }
    }

    Write-Host "正在后台启动模拟器: $AvdName ..." -ForegroundColor Cyan
    # -accel auto: WHPX (与 WSL2/Docker 共存) 优先; AEHD 已禁用
    Start-Process -FilePath $Emulator -ArgumentList @('-avd', $AvdName, '-accel', 'auto', '-gpu', 'auto') | Out-Null

    $deadline = (Get-Date).AddSeconds($BootTimeoutSec)

    # 1) 等待 emulator 设备出现在 adb 列表
    $device = $null
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $device = ((& $Adb devices) -match '^emulator-\d+' | ForEach-Object { ($_ -split "`t")[0] } | Select-Object -First 1)
        if ($device) { break }
    }
    if (-not $device) {
        Write-Host "等待模拟器上线超时 (${BootTimeoutSec}s)。" -ForegroundColor Red
        return $null
    }
    Write-Host "设备已上线: $device, 等待系统启动完成 ..." -ForegroundColor Cyan

    & $Adb -s $device wait-for-device | Out-Null

    # 2) 等待 boot_completed
    while ((Get-Date) -lt $deadline) {
        # adb 输出为空/多行数组时直接 .Trim() 会报错, 先 -join '' 归一为字符串
        $booted = ((& $Adb -s $device shell getprop sys.boot_completed 2>$null) -join '').Trim()
        if ($booted -eq '1') {
            Write-Host "模拟器启动完成: $device" -ForegroundColor Green
            return $device
        }
        Start-Sleep -Seconds 2
    }

    Write-Host "等待系统开机完成超时 (${BootTimeoutSec}s)。" -ForegroundColor Red
    return $null
}

# 启动 (或连接已在运行的) 雷电模拟器实例, 等待 boot_completed, 返回 adb 设备序列号。
# 雷电实例通常被 SDK adb 自动发现为 emulator-5554; 未自动发现时用 adb connect 127.0.0.1:<port>。
function Start-LdPlayerInstance {
    param(
        [Parameter(Mandatory = $true)][int] $Index,
        [Parameter(Mandatory = $true)][string] $LdDir
    )

    $ports = Get-LdPorts -Index $Index
    $serialEmu = "emulator-$($ports.Console)"
    $serialNet = "127.0.0.1:$($ports.Adb)"

    $inst = Get-LdPlayerInstances -LdDir $LdDir | Where-Object { $_.Index -eq $Index } | Select-Object -First 1
    if (-not $inst -or -not $inst.Running) {
        Write-Host "正在启动雷电模拟器 (实例 $Index) ..." -ForegroundColor Cyan
        & (Get-LdConsole -LdDir $LdDir) launch --index $Index | Out-Null
    }
    else {
        Write-Host "雷电实例 $Index 已在运行, 尝试通过 adb 连接 ..." -ForegroundColor Cyan
    }

    $deadline = (Get-Date).AddSeconds($BootTimeoutSec)

    # 1) 等待设备上线 (自动发现 或 adb connect)
    $device = $null
    while ((Get-Date) -lt $deadline) {
        $device = Get-AdbDeviceSerials |
            Where-Object { $_ -eq $serialEmu -or $_ -eq $serialNet } |
            Select-Object -First 1
        if (-not $device) {
            & $Adb connect $serialNet 2>$null | Out-Null
            Start-Sleep -Seconds 2
            $device = Get-AdbDeviceSerials |
                Where-Object { $_ -eq $serialEmu -or $_ -eq $serialNet } |
                Select-Object -First 1
        }
        if ($device) { break }
        Start-Sleep -Seconds 2
    }
    if (-not $device) {
        Write-Host "等待雷电模拟器上线超时 (${BootTimeoutSec}s)。" -ForegroundColor Red
        return $null
    }
    Write-Host "设备已上线: $device, 等待系统启动完成 ..." -ForegroundColor Cyan

    & $Adb -s $device wait-for-device | Out-Null

    # 2) 等待 boot_completed
    while ((Get-Date) -lt $deadline) {
        $booted = ((& $Adb -s $device shell getprop sys.boot_completed 2>$null) -join '').Trim()
        if ($booted -eq '1') {
            Write-Host "雷电模拟器启动完成: $device" -ForegroundColor Green
            return $device
        }
        Start-Sleep -Seconds 2
    }

    Write-Host "等待雷电模拟器开机完成超时 (${BootTimeoutSec}s)。" -ForegroundColor Red
    return $null
}

# 查询运行中模拟器对应的 AVD 名 (用于列表去重); 失败或不支持返回 $null
function Get-RunningAvdName {
    param([Parameter(Mandatory = $true)][string] $Device)
    $name = ((& $Adb -s $Device emu avd name 2>$null | Select-Object -First 1) -join '').Trim()
    if ($name -ne '') { return $name }
    return $null
}

# 交互式统一部署目标选择:
#   已连接的 adb 设备 (USB 真机 / 网络设备 / 运行中的模拟器)
#   雷电模拟器多开实例 (未运行则先拉起并等待开机)
#   Android AVD (未运行则先拉起并等待开机)
# 返回选中/启动后的设备序列号; 取消或无可用目标返回 $null。
function Select-Device {
    $items = @()
    $serials = @(Get-AdbDeviceSerials)

    # -- 1) 已连接设备, 直接可用 (真机优先, 其次网络连接的模拟器, 最后 emulator-*) --
    $orderedSerials = $serials | Sort-Object {
        if ($_ -notmatch '^emulator-|^127\.0\.0\.1:') { 0 }
        elseif ($_ -match '^127\.0\.0\.1:') { 1 }
        else { 2 }
    }
    foreach ($s in $orderedSerials) {
        if ($s -match '^emulator-') { $kind = '模拟器' }
        elseif ($s -match '^127\.0\.0\.1:') { $kind = '模拟器(adb connect)' }
        else { $kind = '真机' }
        $model = ((& $Adb -s $s shell getprop ro.product.model 2>$null) -join '').Trim()
        $label = if ($model) { "{0} {1} [{2}]" -f $kind, $model, $s } else { "{0} [{1}]" -f $kind, $s }
        $items += [pscustomobject]@{ Kind = 'adb'; Label = $label; Device = $s }
    }

    # 雷电控制台输出多开实例; 端口仅在启动等待时使用, 不用做去重
    # (AVD 与雷电实例都可能占用 emulator-5554, 按端口无法区分身份)
    $ldDir = Get-LdPlayerDir
    if ($ldDir) {
        foreach ($ld in Get-LdPlayerInstances -LdDir $ldDir) {
            # -- 2) 雷电实例: 无论是否已在 adb 列表都列出 (多开实例的可靠入口) --
            $state = if ($ld.Running) { '运行中, adb 连接后部署' } else { '未运行, 启动后部署' }
            $items += [pscustomobject]@{
                Kind    = 'ld'
                Label   = ("雷电模拟器 {0} [实例{1}] ({2})" -f $ld.Title, $ld.Index, $state)
                LdIndex = $ld.Index
                LdDir   = $ldDir
            }
        }
    }

    # -- 3) Android AVD (跳过已在运行的; 雷电不识别 emu avd name 时返回空, 无害) --
    $runningAvds = @()
    foreach ($s in ($serials | Where-Object { $_ -match '^emulator-' })) {
        $n = Get-RunningAvdName -Device $s
        if ($n) { $runningAvds += $n }
    }
    foreach ($a in Get-AvdList) {
        if ($runningAvds -contains $a) { continue }
        $items += [pscustomobject]@{ Kind = 'avd'; Label = "Android AVD $a (启动后部署)"; AvdName = $a }
    }

    if ($items.Count -eq 0) {
        Write-Host '未找到任何可用目标: 请用 USB 连接手机并开启 USB 调试、启动/安装雷电模拟器, 或先创建 AVD。' -ForegroundColor Yellow
        return $null
    }

    Write-Host ''
    Write-Host '请选择部署目标:' -ForegroundColor Cyan
    for ($i = 0; $i -lt $items.Count; $i++) {
        $tail = ''
        if ($i -eq 0 -and $items[0].Kind -eq 'adb') { $tail = '  [回车默认]' }
        Write-Host (" {0}) {1}{2}" -f ($i + 1), $items[$i].Label, $tail)
    }
    Write-Host ' 0) 取消'
    $sel = Read-Host '选择目标'
    if ($sel -eq '') {
        if ($items[0].Kind -eq 'adb') { $sel = '1' } else { return $null }
    }
    if ($sel -eq '0' -or $sel -eq '') { return $null }

    $index = 0
    if (-not [int]::TryParse($sel, [ref]$index) -or $index -lt 1 -or $index -gt $items.Count) {
        Write-Host "无效选择: $sel" -ForegroundColor Yellow
        return $null
    }

    $item = $items[$index - 1]
    switch ($item.Kind) {
        'adb' { return $item.Device }
        'ld' { return Start-LdPlayerInstance -Index $item.LdIndex -LdDir $item.LdDir }
        'avd' { return Start-Avd -AvdName $item.AvdName }
    }
    return $null
}

# 确保有可用设备并返回序列号:
#   交互模式 (默认)   : 弹统一目标选择 (真机 / 雷电 / AVD), 启动任何模拟器前一律手动确认;
#   非交互 -AutoStartAvd (如 dev 命令): 已连设备直接用, 否则自动启动第一个 AVD, 再退而求其次启动雷电实例。
function Ensure-Device {
    param([switch] $AutoStartAvd)

    if (-not $AutoStartAvd) { return Select-Device }

    $device = Get-ConnectedDevice
    if ($device) { return $device }

    $avds = @(Get-AvdList)
    if ($avds) {
        Write-Host "未检测到设备, 自动启动 AVD: $($avds[0])" -ForegroundColor Yellow
        return Start-Avd -AvdName $avds[0]
    }

    $ldDir = Get-LdPlayerDir
    if ($ldDir) {
        $lds = @(Get-LdPlayerInstances -LdDir $ldDir)
        if ($lds.Count -gt 0) {
            $first = $lds | Sort-Object Index | Select-Object -First 1
            Write-Host "未检测到设备, 自动启动雷电模拟器实例 $($first.Index) ($($first.Title)) ..." -ForegroundColor Yellow
            return Start-LdPlayerInstance -Index $first.Index -LdDir $ldDir
        }
    }

    Write-Host '没有已连接的设备, 也没有找到 AVD 或雷电模拟器。请用 USB 连接手机 (开启 USB 调试)。' -ForegroundColor Yellow
    return $null
}

# ======================== 联调支持 ========================

# 建立 设备 localhost:$ServerPort -> 宿主机 $ServerPort 的反向转发。
# 主要用途: USB 真机联调 (真机没有 10.0.2.2 别名; 且本机 dev 端口仅绑定环回, 局域网 IP 不可达)。
# 模拟器日常无需使用, 直接在 App 填 10.0.2.2:8080 即可。
# 注意: 映射保存在 adb server 侧, adb kill-server / 重启后需重新执行。
function Enable-ServerReverse {
    param([Parameter(Mandatory = $true)][string] $Device)
    & $Adb -s $Device reverse "tcp:$ServerPort" "tcp:$ServerPort" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'adb reverse 失败, 请检查设备连接与 USB 调试授权。' -ForegroundColor Yellow
        return $false
    }
    Write-Host "已建立 adb reverse: 设备 localhost:$ServerPort -> 宿主机 :$ServerPort" -ForegroundColor Green
    Write-Host "现在可在 App 设置页把管理平台地址填为 localhost:$ServerPort" -ForegroundColor DarkGray
    return $true
}

# 增量编译 + 安装, 并用 pm list packages 做结果校验。
# 冷开机模拟器刚 boot_completed 时 PMS 可能尚未就绪, Gradle 偶发安装失败, 故带重试。
function Install-DebugApp {
    param(
        [Parameter(Mandatory = $true)][string] $Device,
        [int] $MaxAttempts = 3
    )
    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        Write-Host "增量编译并安装 Debug (第 $attempt/$MaxAttempts 次) ..." -ForegroundColor Cyan
        if (Invoke-Gradle ':app:installAppDebug') {
            $installed = ((& $Adb -s $Device shell pm list packages $AppId 2>$null) -join '')
            if ($installed -match [regex]::Escape("package:$AppId")) {
                Write-Host "设备上已确认安装: $AppId" -ForegroundColor Green
                return $true
            }
            Write-Host 'Gradle 报告成功但设备上未发现该包 (模拟器可能刚开机, PMS 未就绪)。' -ForegroundColor Yellow
        }
        if ($attempt -lt $MaxAttempts) {
            Write-Host '10 秒后重试 ...' -ForegroundColor DarkGray
            Start-Sleep -Seconds 10
        }
    }
    Write-Host "安装失败: 请查看上方 Gradle 输出。常见原因: 设备未解锁/授权调试、ADB 版本过低、ABI 不匹配。" -ForegroundColor Red
    return $false
}

# ======================== App 操作 ========================

function Start-App {
    param([Parameter(Mandatory = $true)][string] $Device)
    # monkey 的参数回显走 stderr, 需 2>&1 一并吞掉
    & $Adb -s $Device shell monkey -p $AppId -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null
}

function Stop-App {
    param([Parameter(Mandatory = $true)][string] $Device)
    & $Adb -s $Device shell am force-stop $AppId
}

# 调试包默认连局域网测试服务器: 把地址写入 debug 专属 SharedPreferences 覆盖。
# MgmtServerConfig 在 debug 下优先读 serverOverride, 因此公开 fork 源码无需硬编码内网 IP。
# 必须在 App 未运行时写, 否则进程退出会用内存值覆盖文件。
function Set-MgmtServerOverride {
    param(
        [Parameter(Mandatory = $true)][string] $Device,
        [string] $Url = $ServerUrl
    )
    if (-not $Url) {
        Write-Host '未指定 -ServerUrl, 跳过管理平台地址注入 (沿用编译期默认值)。' -ForegroundColor DarkGray
        return $true
    }

    $prefsFile = "$AppId`_preferences.xml"
    $key = 'key_$_management_platform_server_address'
    $remoteTmp = '/data/local/tmp/autojs6_mgmt_prefs.xml'
    $localTmp = Join-Path $env:TEMP "autojs6_mgmt_prefs_$PID.xml"
    $urlXml = [System.Security.SecurityElement]::Escape($Url)

    $existing = ((& $Adb -s $Device shell "run-as $AppId cat shared_prefs/$prefsFile" 2>$null) -join "`n")
    if ($existing -match '<\?xml') {
        $entry = '<string name="{0}">{1}</string>' -f $key, $urlXml
        if ($existing -match [regex]::Escape('name="' + $key + '"')) {
            $existing = [regex]::Replace($existing, '(?s)<string name="' + [regex]::Escape($key) + '">.*?</string>', $entry)
        }
        else {
            $existing = $existing -replace '(?s)</map>', "    $entry`n</map>"
        }
        $xml = $existing
    }
    else {
        $xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>`n<map>`n    <string name=`"$key`">$urlXml</string>`n</map>`n"
    }

    try {
        [IO.File]::WriteAllText($localTmp, $xml, (New-Object System.Text.UTF8Encoding($false)))
        & $Adb -s $Device push $localTmp $remoteTmp | Out-Null
        & $Adb -s $Device shell "run-as $AppId mkdir -p shared_prefs" | Out-Null
        & $Adb -s $Device shell "run-as $AppId cp $remoteTmp shared_prefs/$prefsFile" | Out-Null
        & $Adb -s $Device shell "rm -f $remoteTmp" | Out-Null
        $check = ((& $Adb -s $Device shell "run-as $AppId cat shared_prefs/$prefsFile" 2>$null) -join "`n")
        if ($check -notmatch [regex]::Escape($Url)) {
            Write-Host "管理平台地址写入校验失败 (仅 debug 包支持 run-as); 请在 App 设置页手动填写 $Url" -ForegroundColor Yellow
            return $false
        }
        Write-Host "管理平台地址已注入: $Url" -ForegroundColor Green
        return $true
    }
    finally {
        Remove-Item $localTmp -Force -ErrorAction SilentlyContinue
    }
}

# ======================== 权限授予 ========================

# 通过 adb 一次性授予远程控制必需权限（重装/pm clear 后无障碍与存储授权都会被系统重置）：
#   - 绑定并启用无障碍服务：截图/全局按键(back/home/任务/通知/亮息屏)/触摸手势前提
#   - 授予「所有文件访问」：PUSH_SCRIPT/远程输入落盘前提
#   - 预建脚本目录：避免写文件 ENOENT
# 无 root 的 USB 真机同样可用（adb shell 具备 WRITE_SECURE_SETTINGS 与 appops 能力）。
function Grant-DevicePermissions {
    param([Parameter(Mandatory = $true)][string] $Device)

    $a11yComponent = "$AppId/org.autojs.autojs.core.accessibility.AccessibilityServiceUsher"
    $scriptDir = '/storage/emulated/0/Scripts/management_pushed'

    Write-Host '授予远程控制必需权限（无障碍 + 所有文件访问 + 脚本目录）...' -ForegroundColor Cyan

    # 追加而非覆盖已有无障碍服务列表，避免冲掉设备上其他无障碍应用
    $existing = ((& $Adb -s $Device shell settings get secure enabled_accessibility_services 2>$null) -join '').Trim()
    if ($existing -and $existing -ne 'null' -and $existing -notlike "*$a11yComponent*") {
        $services = '{0}:{1}' -f $existing, $a11yComponent
    }
    else {
        $services = $a11yComponent
    }

    $r1 = (& $Adb -s $Device shell settings put secure enabled_accessibility_services $services 2>&1) -join ''
    $r2 = (& $Adb -s $Device shell settings put secure accessibility_enabled 1 2>&1) -join ''
    $r3 = (& $Adb -s $Device shell appops set $AppId MANAGE_EXTERNAL_STORAGE allow 2>&1) -join ''
    & $Adb -s $Device shell mkdir -p $scriptDir 2>&1 | Out-Null

    if ($r1 -or $r2 -or $r3) {
        Write-Host "授权命令异常: $r1 $r2 $r3" -ForegroundColor Yellow
        Write-Host '可稍后在「设备菜单」重新授权，或手动：设置 → 无障碍 → AutoJs6、设置 → 应用权限 → 所有文件访问。' -ForegroundColor Yellow
        return $false
    }

    $bound = ((& $Adb -s $Device shell settings get secure enabled_accessibility_services 2>$null) -join '')
    $enabled = ((& $Adb -s $Device shell settings get secure accessibility_enabled 2>$null) -join '').Trim()
    if ($bound -notlike "*$AppId*" -or $enabled -ne '1') {
        Write-Host "无障碍授权校验失败（services=$bound, enabled=$enabled），请手动在 设置 → 无障碍 开启 AutoJs6。" -ForegroundColor Yellow
        return $false
    }
    Write-Host '权限就绪：无障碍已开启、所有文件访问已允许、脚本目录已创建。' -ForegroundColor Green
    return $true
}

function Wait-AppPid {
    param(
        [Parameter(Mandatory = $true)][string] $Device,
        [int] $TimeoutSec = 20
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $pidValue = ((& $Adb -s $Device shell pidof $AppId 2>$null) -join '').Trim()
        if ($pidValue) { return ($pidValue -split '\s+')[0] }
        Start-Sleep -Milliseconds 800
    }
    return $null
}

# 附着日志: NetOnly 时只看联调 tag; 找不到 PID 时退化为全量
function Invoke-AppLogcat {
    param(
        [Parameter(Mandatory = $true)][string] $Device,
        [switch] $NetOnly
    )
    $tagArgs = @()
    if ($NetOnly) { $tagArgs = @('-s') + $NetLogTags }

    $pidValue = Wait-AppPid $Device
    if ($pidValue) {
        Write-Host "跟踪日志 (PID=$pidValue$(if ($NetOnly) { ', 仅联调 tag' }), Ctrl+C 退出) ..." -ForegroundColor Cyan
        & $Adb -s $Device logcat --pid=$pidValue @tagArgs
    }
    else {
        Write-Host '未能获取应用进程 PID, 显示全部日志 (Ctrl+C 退出):' -ForegroundColor Yellow
        & $Adb -s $Device logcat @tagArgs
    }
}

# ======================== 工作流 ========================

# 一键运行 (等价 Android Studio Run): 增量编译安装(带校验/重试) -> 启动 -> (可选)日志
# 不触碰任何 App 配置; 管理平台地址/接入码请在 App "设置 -> 管理平台" 手动填写。
function Invoke-RunWorkflow {
    param(
        [Parameter(Mandatory = $true)][string] $Device,
        [switch] $NetLog,
        [switch] $AttachLog
    )

    if (-not (Install-DebugApp -Device $Device)) { return $false }

    # 重装会重置无障碍绑定与存储授权，启动前先通过 adb 恢复，做到「装完即用」
    Grant-DevicePermissions -Device $Device | Out-Null

    # 装完即连测试环境: 先停进程再写 prefs, 避免 App 退出时用内存值覆盖
    Stop-App $Device
    Set-MgmtServerOverride -Device $Device | Out-Null

    & $Adb -s $Device logcat -c
    Start-App $Device
    Write-Host "$AppId 已安装并启动。" -ForegroundColor Green
    Write-Host "管理平台地址: $ServerUrl (如需修改: -ServerUrl 'http://x.x.x.x'; 接入码请在 App 设置页填写)" -ForegroundColor DarkGray

    if ($AttachLog) { Invoke-AppLogcat -Device $Device -NetOnly:$NetLog }
    return $true
}

# ======================== 菜单动作 ========================

function Do-BuildDebug { Invoke-Gradle ':app:assembleAppDebug' }
function Do-BuildRelease {
    # 必须先 inrt 后 app（与官方 CI / deploy.yml 一致）：inrt 构建末尾才会生成
    # app/src/main/assets-app/template.apk，跳过则 release 包缺 INRT 模板，
    # App 内"打包 APK"功能运行时 FileNotFoundException。-and 短路：前者失败不继续。
    (Invoke-Gradle ':app:assembleInrtRelease') -and (Invoke-Gradle ':app:assembleAppRelease')
}
function Do-RebuildDebug {
    if (Invoke-Gradle 'clean') { Invoke-Gradle ':app:assembleAppDebug' }
}
function Do-Clean { Invoke-Gradle 'clean' }
function Do-Test { Invoke-Gradle ':app:testAppDebugUnitTest' }

function Do-StartEmulator {
    $existing = Get-ConnectedDevice
    if ($existing) {
        Write-Host "已有在线设备: $existing, 无需重复启动。" -ForegroundColor Yellow
        return
    }

    # 汇总可启动目标: 先雷电多开实例, 再 AVD
    $targets = @()
    $ldDir = Get-LdPlayerDir
    if ($ldDir) {
        foreach ($ld in Get-LdPlayerInstances -LdDir $ldDir) {
            $state = if ($ld.Running) { ' (运行中, 仅连接)' } else { '' }
            $targets += [pscustomobject]@{
                Kind    = 'ld'
                Label   = "雷电模拟器 $($ld.Title) [实例$($ld.Index)]$state"
                LdIndex = $ld.Index
                LdDir   = $ldDir
            }
        }
    }
    foreach ($a in Get-AvdList) {
        $targets += [pscustomobject]@{ Kind = 'avd'; Label = "Android AVD $a"; AvdName = $a }
    }

    if ($targets.Count -eq 0) {
        Write-Host "未找到任何 AVD 或雷电模拟器实例 (emulator: $Emulator)。" -ForegroundColor Yellow
        return
    }
    if ($targets.Count -eq 1) {
        $t = $targets[0]
        if ($t.Kind -eq 'ld') { Start-LdPlayerInstance -Index $t.LdIndex -LdDir $t.LdDir | Out-Null }
        else { Start-Avd -AvdName $t.AvdName | Out-Null }
        return
    }
    for ($i = 0; $i -lt $targets.Count; $i++) {
        Write-Host (" {0}) {1}" -f ($i + 1), $targets[$i].Label)
    }
    Write-Host ' 0) 取消'
    $sel = Read-Host '选择要启动的模拟器'
    $index = 0
    if ([int]::TryParse($sel, [ref]$index) -and $index -ge 1 -and $index -le $targets.Count) {
        $t = $targets[$index - 1]
        if ($t.Kind -eq 'ld') { Start-LdPlayerInstance -Index $t.LdIndex -LdDir $t.LdDir | Out-Null }
        else { Start-Avd -AvdName $t.AvdName | Out-Null }
    }
    elseif ($sel -ne '0') {
        Write-Host "无效选择: $sel" -ForegroundColor Yellow
    }
}

function Do-KillEmulator {
    $emulators = (& $Adb devices) -match '^emulator-\d+' | ForEach-Object { ($_ -split "`t")[0] }
    if (-not $emulators) {
        Write-Host '未发现运行中的模拟器。' -ForegroundColor Yellow
        return
    }
    foreach ($emu in $emulators) {
        & $Adb -s $emu emu kill
        Write-Host "已关闭模拟器: $emu" -ForegroundColor Green
    }
}

function Do-OpenApkDir {
    $apkDir = Join-Path $ProjectDir 'app\build\outputs\apk\app\debug'
    if (Test-Path $apkDir) {
        Start-Process explorer.exe $apkDir
    }
    else {
        Write-Host "APK 输出目录尚不存在, 请先构建: $apkDir" -ForegroundColor Yellow
    }
}

# ---------- 子菜单: 设备 ----------

function Show-DeviceMenu {
    while ($true) {
        Write-Header
        Write-Host ' -- 设备 --' -ForegroundColor Cyan
        Write-Host " 1) 建立 adb reverse (USB 真机联调用: 设备 localhost:$ServerPort -> 宿主机)"
        Write-Host ' 2) 启动模拟器 (雷电 / AVD, 可选择)'
        Write-Host ' 3) 关闭模拟器'
        Write-Host ' 4) 停止 App'
        Write-Host ' 5) 清除 App 数据 (会清空 App 内管理平台配置，并自动重新授予远程控制权限)'
        Write-Host ' 6) 重新授予远程控制权限 (无障碍 + 所有文件访问)'
        Write-Host ' 0) 返回主菜单'
        Write-Host '========================================='
        $sel = Read-Host '选择 (0 返回)'
        switch ($sel) {
            '1' {
                $device = Ensure-Device
                if ($device) { Enable-ServerReverse $device | Out-Null }
            }
            '2' { Do-StartEmulator }
            '3' { Do-KillEmulator }
            '4' {
                $device = Ensure-Device
                if ($device) { Stop-App $device; Write-Host "已停止 $AppId" -ForegroundColor Green }
            }
            '5' {
                $device = Ensure-Device
                if ($device) {
                    $confirm = Read-Host "确认清除 $AppId 全部数据? (y/N)"
                    if ($confirm -eq 'y' -or $confirm -eq 'Y') {
                        & $Adb -s $device shell pm clear $AppId | Out-Null
                        Write-Host '已清除。' -ForegroundColor Green
                        Grant-DevicePermissions -Device $device | Out-Null
                    }
                }
            }
            '6' {
                $device = Ensure-Device
                if ($device) { Grant-DevicePermissions -Device $device | Out-Null }
            }
            '0' { return }
            default { Write-Host "无效选择: $sel" -ForegroundColor Yellow }
        }
        if ($sel -eq '0') { break }
        Pause-ForMenu
    }
}

# ---------- 子菜单: 高级 ----------

function Show-AdvancedMenu {
    while ($true) {
        Write-Header
        Write-Host ' -- 高级 (低频: 耗时长或仅发布前使用) --' -ForegroundColor Cyan
        Write-Host ' 1) clean 清理构建产物'
        Write-Host ' 2) 清理后全量重建 Debug (会重编全部 native, 耗时数分钟)'
        Write-Host ' 3) 构建 Release APK (先 inrt 生成模板, 再 app)'
        Write-Host ' 4) 安装 Release (:app:installAppRelease)'
        Write-Host ' 5) 打开 APK 输出目录'
        Write-Host ' 0) 返回主菜单'
        Write-Host '========================================='
        $sel = Read-Host '选择 (0 返回)'
        switch ($sel) {
            '1' { Do-Clean }
            '2' { Do-RebuildDebug }
            '3' { if (Invoke-KotlinCheck -Variant 'Release') { Do-BuildRelease } }
            '4' {
                if (-not (Invoke-KotlinCheck -Variant 'Release')) { break }
                $device = Ensure-Device
                if ($device) { Invoke-Gradle ':app:installAppRelease' | Out-Null }
            }
            '5' { Do-OpenApkDir }
            '0' { return }
            default { Write-Host "无效选择: $sel" -ForegroundColor Yellow }
        }
        if ($sel -eq '0') { break }
        Pause-ForMenu
    }
}

# ---------- 主菜单 ----------

function Show-MainMenu {
    # 仅 Test-Path 目录会被空目录骗过; Ensure-ClientSource 校验 gradlew 实际存在,
    # 缺失时自动 clone fork, 失败直接退出
    if (-not (Ensure-ClientSource)) { exit 1 }

    while ($true) {
        Write-Header
        Write-Host ' -- 日常 (高频) --' -ForegroundColor Cyan
        Write-Host ' 1) 一键运行: 增量编译安装 + 启动 + 全量日志  (回车默认, 等同 Android Studio Run)'
        Write-Host ' 2) 仅看日志: App 全量'
        Write-Host ' 3) 仅看日志: 联调相关 (ManagementPlatform tag)'
        Write-Host ' -- 构建 / 测试 --' -ForegroundColor Cyan
        Write-Host ' 4) 构建 Debug APK (仅打包, 不安装)'
        Write-Host ' 5) 单元测试 (:app:testAppDebugUnitTest)'
        Write-Host ' -- 其他 --' -ForegroundColor Cyan
        Write-Host ' 6) 设备 (adb reverse / 模拟器 / 停止 / 清数据)'
        Write-Host ' 7) 高级 (clean / 全量重建 / Release / APK 目录)'
        Write-Host ' 0) 退出'
        Write-Host '========================================='
        $choice = Read-Host '输入数字并回车 (0 退出)'
        if ($choice -eq '') { $choice = '1' }

        switch ($choice) {
            '1' {
                if (-not (Invoke-KotlinCheck -Variant 'Debug')) { break }
                $device = Ensure-Device
                if ($device) { Invoke-RunWorkflow -Device $device -AttachLog | Out-Null }
            }
            '2' {
                $device = Ensure-Device
                if ($device) {
                    Start-App $device
                    Invoke-AppLogcat -Device $device | Out-Null
                }
            }
            '3' {
                $device = Ensure-Device
                if ($device) {
                    Start-App $device
                    Invoke-AppLogcat -Device $device -NetOnly | Out-Null
                }
            }
            '4' { if (Invoke-KotlinCheck -Variant 'Debug') { Do-BuildDebug } }
            '5' { if (Invoke-KotlinCheck -Variant 'Debug') { Do-Test } }
            '6' { Show-DeviceMenu }
            '7' { Show-AdvancedMenu }
            '0' { break }
            default { Write-Host "无效选择: $choice" -ForegroundColor Yellow }
        }

        if ($choice -eq '0') { break }
        if ($choice -in @('6', '7')) { continue }
        Pause-ForMenu
    }
}

# ======================== 非交互命令分发 ========================

function Show-Help {
    Write-Host @'
用法:
  .\build-client.ps1 [命令] [-NoLog]

  构建类命令 (dev/build/test/release 等) 先过 Kotlin 编译门禁, 语法不合格立即终止;
  rebuild (clean+全量重编) 与 clean 本身即编译检查, 不设前置。
命令:
  dev / deploy / quick  增量编译安装 + 启动 + 全量日志 (等同 Android Studio Run)
  log                   附着 App 全量日志
  netlog                附着联调日志 (ManagementPlatform tag)
  reverse              建立 adb reverse (USB 真机: 设备 localhost:8080 -> 宿主机)
  stop                 停止 App
  clear-data           清除 App 数据 (随后自动重新授予远程控制权限)
  grant                重新授予远程控制权限 (无障碍 + 所有文件访问, 免手动点设置)
  start-emu            启动第一个 AVD (无设备时)
  kill-emu             关闭运行中的模拟器
  build                仅构建 Debug APK (:app:assembleAppDebug)
  test                 单元测试
  clean                gradle clean
  rebuild              clean + assembleAppDebug (全量, 慎用)
  release              构建 Release APK
  install-release      安装 Release
  apk                  打开 APK 输出目录
  menu / help          交互菜单 / 本帮助

测试环境 (默认, 脚本安装后自动注入 debug SharedPreferences):
  地址 http://192.168.1.27   (1.27 的 nginx 80, GitLab CI 构建部署, 与生产同路径)
  秘钥 租户接入码 matchCode, 然后点 "测试连接"
  (App 会自动补全 ws:// 前缀与 /ws/device 路径)
  改地址: .\build-client.ps1 dev -ServerUrl "http://x.x.x.x"
  跳过注入: .\build-client.ps1 dev -ServerUrl ""
  仅 debug 包生效; Release/正式包仍用 MgmtEnv 编译期默认值。

部署目标 (交互菜单选择 "一键运行" 时列出):
  真机       USB 连接并开启 USB 调试后自动出现; USB 联调先执行 reverse
  雷电模拟器 自动从注册表/常见目录发现, 多开实例逐个列出, 未运行自动启动
  Android AVD 多个 AVD 全部列出供选择

示例:
  .\build-client.ps1 dev
  .\build-client.ps1 dev -NoLog
  .\build-client.ps1 netlog
'@
}

function Invoke-CommandLine {
    switch ($Command) {
        'help' { Show-Help; return }
        'menu' { Show-MainMenu; return }
    }

    # 其余命令均依赖客户端工程; 源码缺失则先 clone fork, 失败退出
    if (-not (Ensure-ClientSource)) { exit 1 }

    # 构建类命令先过 Kotlin 语法门禁, 不合格立即停止 —— 且放在选设备/启动
    # 模拟器之前, 避免代码坏了还白白拉起模拟器。rebuild 本身即全量重编,
    # clean 无编译, 二者不设前置。
    $checkVariant = switch ($Command) {
        'dev' { 'Debug' }
        'deploy' { 'Debug' }
        'quick' { 'Debug' }
        'build' { 'Debug' }
        'test' { 'Debug' }
        'release' { 'Release' }
        'install-release' { 'Release' }
        default { $null }
    }
    if ($checkVariant -and -not (Invoke-KotlinCheck -Variant $checkVariant)) { exit 1 }

    # 以下命令需要设备
    $needsDevice = @('dev', 'deploy', 'quick', 'log', 'netlog', 'reverse', 'stop', 'clear-data', 'grant', 'start-emu')
    $device = $null
    if ($needsDevice -contains $Command) {
        $device = Ensure-Device -AutoStartAvd
        if (-not $device) { exit 1 }
    }

    switch ($Command) {
        { $_ -in @('dev', 'deploy', 'quick') } {
            $ok = Invoke-RunWorkflow -Device $device -AttachLog:(-not $NoLog)
            if (-not $ok) { exit 1 }
        }
        'log' { Start-App $device; Invoke-AppLogcat -Device $device }
        'netlog' { Start-App $device; Invoke-AppLogcat -Device $device -NetOnly }
        'reverse' { Enable-ServerReverse $device | Out-Null }
        'stop' { Stop-App $device }
        'clear-data' {
            & $Adb -s $device shell pm clear $AppId | Out-Null
            Grant-DevicePermissions -Device $device | Out-Null
        }
        'grant' { Grant-DevicePermissions -Device $device | Out-Null }
        'kill-emu' { Do-KillEmulator }
        'build' { if (-not (Do-BuildDebug)) { exit 1 } }
        'test' { if (-not (Do-Test)) { exit 1 } }
        'clean' { if (-not (Do-Clean)) { exit 1 } }
        'rebuild' { if (-not (Do-RebuildDebug)) { exit 1 } }
        'release' { if (-not (Do-BuildRelease)) { exit 1 } }
        'install-release' {
            # 同 release：先确保 inrt 模板已生成，再安装（否则设备上装的是缺模板的包）
            $ok = (Invoke-Gradle ':app:assembleInrtRelease') -and (Invoke-Gradle ':app:installAppRelease')
            if (-not $ok) { exit 1 }
        }
        'apk' { Do-OpenApkDir }
    }
}

# 被 dot-source 时 (单元测试) 不自动进入主流程
if ($MyInvocation.InvocationName -ne '.') {
    Invoke-CommandLine
}
