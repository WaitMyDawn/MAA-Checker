<#
  MAA-Checker 打包脚本（Windows）

  默认产出两份，都在 dist\ 下：
    MAA-Checker-<版本>-win-x64.zip    exe 版：单个 MAA-Checker.exe（jar 嵌在 exe 里，2.5MB）
                                      不含 JRE，运行时自动找系统的 Java 21+，找不到会弹提示
    MAA-Checker-<版本>-lite.zip       lite 版：maa-checker.jar + launch.bat（2.2MB，同样不含 JRE）

  可选参数：
    -OnlyExe        只打 exe 版
    -OnlyLite       只打 lite 版
    -WithJre        额外再打一份自带运行时的版本（jpackage app-image，约 54MB / zip 39MB）
    -NoTrim         -WithJre 时用完整运行时（不裁剪，体积更大，排查用）
    -Version x.y.z  版本号（默认 0.1.0）
                    —— 它同时决定：zip 名、jpackage 的版本、以及 exe 右键属性里的版本
                    （通过 mvn -Drevision=... 传给 pom，见 pom 里的 <revision>）
    -Mvn <命令>     用什么跑 maven（默认用本地开发机的 ..\Minecraft-AI-Agent\mvnw.cmd；
                    CI 上那个目录不存在，所以 CI 传 -Mvn mvn）
    -NoOffline      跳过"先离线再联网"的第一轮（CI 首次没有本地仓库，离线必然失败白等一轮）

  典型用法：
    本地： powershell -ExecutionPolicy Bypass -File package.ps1 -Version 0.1.0 -WithJre
    CI ： powershell -ExecutionPolicy Bypass -File package.ps1 -Version 0.2.0 -WithJre -Mvn mvn -NoOffline

  为什么要"默认不带 JRE"：玩整合包的机器上必然有 Java（启动器会提醒），
  exe 版的 Launch4j 包装器会自己按 PATH/JAVA_HOME/注册表找 Java 21+，
  找不到时弹的提示里带 Java 下载指引。要"零依赖"就给 -WithJre。
#>
param(
    [string]$Version = '0.1.0',
    [switch]$OnlyExe,
    [switch]$OnlyLite,
    [switch]$WithJre,
    [switch]$NoTrim,
    [string]$Mvn = '',
    [switch]$NoOffline
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
$distRoot = Join-Path $root 'dist'

function New-CleanDir([string]$path) {
    if (Test-Path $path) {
        try { Remove-Item -LiteralPath $path -Recurse -Force -ErrorAction Stop }
        catch {
            # 目录被占用（上一次的界面还开着、资源管理器停在里面）时不硬失败，换时间戳名字
            $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
            $alt = "$path-$stamp"
            Write-Host "  原目录被占用，改为 $alt" -ForegroundColor Yellow
            New-Item -ItemType Directory -Path $alt -Force | Out-Null
            return $alt
        }
    }
    New-Item -ItemType Directory -Path $path -Force | Out-Null
    return $path
}

function New-Zip([string]$srcDir, [string]$zipPath) {
    if (Test-Path $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
    Compress-Archive -Path (Join-Path $srcDir '*') -DestinationPath $zipPath -CompressionLevel Optimal
}

Write-Host '=== 1/4 编译（含生成 exe）===' -ForegroundColor Cyan
# maven 用哪个：默认本地开发机的兄弟目录 wrapper；CI 传 -Mvn mvn
$mvnCmd = if ($Mvn) { $Mvn } else { Join-Path (Split-Path $root -Parent) 'Minecraft-AI-Agent\mvnw.cmd' }
# 版本号透给 pom 的 <revision>：zip 名、jpackage 版本、exe 属性三者一致
$verArg = "-Drevision=$Version"
if ($NoOffline) {
    Write-Host "  构建（联网）: $mvnCmd $verArg package"
    & $mvnCmd -q -DskipTests $verArg package
    if ($LASTEXITCODE -ne 0) { throw "maven 打包失败（退出码 $LASTEXITCODE）" }
} else {
    # 优先离线（依赖与 launch4j 插件通常已在本地仓库）；缺东西时自动联网再试一次
    & $mvnCmd -o -q -DskipTests $verArg package
    if ($LASTEXITCODE -ne 0) {
        Write-Host '  离线构建失败，改为联网构建（首次需要下载 launch4j 插件）…' -ForegroundColor Yellow
        & $mvnCmd -q -DskipTests $verArg package
        if ($LASTEXITCODE -ne 0) { throw "maven 打包失败（退出码 $LASTEXITCODE）" }
    }
}
$jar = Join-Path $root 'target\maa-checker.jar'
$exe = Join-Path $root 'target\MAA-Checker.exe'
if (-not (Test-Path $jar)) { throw "没有找到 $jar" }

$base = "MAA-Checker-$Version"
$made = @()

if (-not $OnlyLite) {
    Write-Host '=== 2/4 组装 exe 版 ===' -ForegroundColor Cyan
    if (-not (Test-Path $exe)) { throw "没有找到 $exe（launch4j 没有产出，检查 pom.xml 里的 launch4j 配置）" }
    $dir = Join-Path $distRoot "$base-win-x64"
    $dir = New-CleanDir $dir
    Copy-Item $exe (Join-Path $dir 'MAA-Checker.exe')
    Copy-Item (Join-Path $root 'packaging\README.txt') $dir
    $zip = Join-Path $distRoot "$base-win-x64.zip"
    New-Zip $dir $zip
    $made += $zip
    Write-Host ("  exe: {0}（{1:N2} MB）" -f $zip, ((Get-Item $zip).Length / 1MB)) -ForegroundColor Green
}

if (-not $OnlyExe) {
    Write-Host '=== 3/4 组装 lite 版 ===' -ForegroundColor Cyan
    $dir = Join-Path $distRoot "$base-lite"
    $dir = New-CleanDir $dir
    New-Item -ItemType Directory -Path (Join-Path $dir 'tools') -Force | Out-Null
    Copy-Item $jar (Join-Path $dir 'maa-checker.jar')
    Copy-Item (Join-Path $root 'packaging\launch.bat') $dir
    Copy-Item (Join-Path $root 'packaging\README.txt') $dir
    Copy-Item (Join-Path $root 'packaging\find-java.ps1') (Join-Path $dir 'tools')
    $configText = @'
# MAA-Checker 配置（界面或命令行会自动维护这个文件）
# proxy.url=http://127.0.0.1:7890   固定代理；留空/删掉则自动探测系统代理（Clash 打开"系统代理"即可）
# game.dir=D:\Minecraft\minecraft\.minecraft   上次使用的游戏目录（界面会自动写入）
'@
    # 必须不带 BOM（Java 读第一行时会把 BOM 当成 key 的一部分）
    [IO.File]::WriteAllText((Join-Path $dir 'config.properties'), $configText,
        (New-Object Text.UTF8Encoding($false)))
    $zip = Join-Path $distRoot "$base-lite.zip"
    New-Zip $dir $zip
    $made += $zip
    Write-Host ("  lite: {0}（{1:N2} MB）" -f $zip, ((Get-Item $zip).Length / 1MB)) -ForegroundColor Green
}

if ($WithJre) {
    Write-Host '=== 4/4 组装自带运行时版本（jlink + jpackage）===' -ForegroundColor Cyan
    $jdkBin = Split-Path -Parent (Get-Command java -ErrorAction SilentlyContinue).Source
    if (-not $jdkBin) { $jdkBin = Split-Path -Parent (Get-Command javac).Source }
    $jlink = Join-Path $jdkBin 'jlink.exe'
    $jpackage = Join-Path $jdkBin 'jpackage.exe'
    if (-not (Test-Path $jlink) -or -not (Test-Path $jpackage)) {
        Write-Host '  找不到 jlink/jpackage（需要 JDK 21 的 bin 在 PATH），跳过。' -ForegroundColor Yellow
    } else {
        $work = Join-Path $env:TEMP ("maa-checker-jre-" + [Guid]::NewGuid().ToString('N').Substring(0, 8))
        New-Item -ItemType Directory -Path $work -Force | Out-Null
        $runtime = Join-Path $work 'runtime'
        if ($NoTrim) {
            Write-Host '  使用完整运行时（未裁剪）…'
            & $jlink --add-modules ALL-MODULE-PATH --strip-debug --no-header-files --no-man-pages `
                --output $runtime | Out-Null
        } else {
            Write-Host '  裁剪运行时（只保留界面 + HTTP + TLS 需要的模块）…'
            & $jlink --add-modules java.base,java.desktop,java.logging,java.net.http,jdk.crypto.ec `
                --strip-debug --no-header-files --no-man-pages --compress=zip-9 --output $runtime | Out-Null
        }
        $appDir = Join-Path $work 'app'
        & $jpackage --type app-image --name 'MAA-Checker' --input (Join-Path $root 'target') `
            --main-jar 'maa-checker.jar' --main-class 'yagen.waitmydawn.checker.CheckerCli' `
            --runtime-image $runtime --app-version $Version --dest $appDir `
            --java-options '-Dstdout.encoding=UTF-8' | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Host '  jpackage 失败，跳过自带运行时版本。' -ForegroundColor Yellow
        } else {
            $dir = Join-Path $distRoot "$base-win-x64-jre"
            $dir = New-CleanDir $dir
            Copy-Item (Join-Path $appDir 'MAA-Checker\*') $dir -Recurse
            Copy-Item (Join-Path $root 'packaging\README.txt') $dir
            $zip = Join-Path $distRoot "$base-win-x64-jre.zip"
            New-Zip $dir $zip
            $made += $zip
            Write-Host ("  自带运行时: {0}（{1:N1} MB）" -f $zip, ((Get-Item $zip).Length / 1MB)) -ForegroundColor Green
        }
    }
}

Write-Host '=== 完成 ===' -ForegroundColor Cyan
$made | ForEach-Object { Write-Host ("  {0}  ({1:N2} MB)" -f $_, ((Get-Item $_).Length / 1MB)) }
