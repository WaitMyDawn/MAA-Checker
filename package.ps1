<#
  MAA-Checker Windows 打包脚本。

  默认产物：
    MAA-Checker-<版本>-desktop-win-x64.zip
        jpackage app-image，包含 JavaFX、应用运行时、资源、图标和许可证。
    MAA-Checker-<版本>-light-win-x64.exe
        Launch4j 单文件启动器，不包含 JRE，使用本机 Java 21+。

  桌面版用户数据固定放在 %LOCALAPPDATA%\MAA-Checker，不在安装目录：
  升级、卸载或删除便携目录不得清除实例恢复资料。
#>
param(
    [string]$Version = '1.0.0',
    [string]$Mvn = 'mvn',
    [switch]$NoOffline
)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Split-Path -Parent $MyInvocation.MyCommand.Path))
$distRoot = [IO.Path]::GetFullPath((Join-Path $root 'dist'))
$buildRoot = [IO.Path]::GetFullPath((Join-Path $root 'build\packaging'))
Set-Location $root

function Assert-ChildPath([string]$path, [string]$parent) {
    $full = [IO.Path]::GetFullPath($path)
    $base = [IO.Path]::GetFullPath($parent).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $full.StartsWith($base, [StringComparison]::OrdinalIgnoreCase)) {
        throw "拒绝操作预期根目录之外的路径：$full（根：$parent）"
    }
    return $full
}

function Remove-SafeTree([string]$path, [string]$parent) {
    $full = Assert-ChildPath $path $parent
    if (-not (Test-Path -LiteralPath $full)) { return }
    $item = Get-Item -LiteralPath $full -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "拒绝递归清理重解析点：$full"
    }
    Remove-Item -LiteralPath $full -Recurse -Force
}

function New-SafeDir([string]$path, [string]$parent) {
    $full = Assert-ChildPath $path $parent
    Remove-SafeTree $full $parent
    New-Item -ItemType Directory -Path $full -Force | Out-Null
    return $full
}

function New-SafeZip([string]$source, [string]$zip, [string]$parent) {
    $target = Assert-ChildPath $zip $parent
    if (Test-Path -LiteralPath $target) {
        $item = Get-Item -LiteralPath $target -Force
        if ($item.PSIsContainer) { throw "ZIP 目标不是文件：$target" }
        Remove-Item -LiteralPath $target -Force
    }
    Compress-Archive -Path (Join-Path $source '*') -DestinationPath $target -CompressionLevel Optimal
}

function Invoke-MavenPackage([string]$profile) {
    $common = @('-B', '-ntp', '-DskipTests', "-Drevision=$Version", "-P$profile", 'package')
    if (-not $NoOffline) {
        Write-Host "  离线构建 $profile ..."
        & $Mvn @(@('-o') + $common)
        if ($LASTEXITCODE -eq 0) { return }
        Write-Host "  离线构建失败，改为联网构建 $profile ..." -ForegroundColor Yellow
    }
    & $Mvn @common
    if ($LASTEXITCODE -ne 0) { throw "Maven $profile 构建失败（退出码 $LASTEXITCODE）" }
}

function Invoke-Launch4j([string]$icon) {
    $arguments = @('-B', '-ntp', '-DskipTests', "-Drevision=$Version",
        '-Pdesktop-light', "-Dmaa.icon=$icon",
        'com.akathist.maven.plugins.launch4j:launch4j-maven-plugin:2.5.0:launch4j')
    if (-not $NoOffline) {
        Write-Host '  离线构建轻量 EXE ...'
        & $Mvn @(@('-o') + $arguments)
        if ($LASTEXITCODE -eq 0) { return }
        Write-Host '  离线构建失败，改为联网构建轻量 EXE ...' -ForegroundColor Yellow
    }
    & $Mvn @arguments
    if ($LASTEXITCODE -ne 0) { throw "Launch4j 构建失败（退出码 $LASTEXITCODE）" }
}

function Resolve-JdkTool([string]$name) {
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin\$name"
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    $command = Get-Command $name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw "找不到 $name；jpackage 桌面版需要 JDK 21。"
}

function New-AppIcon([string]$target) {
    Add-Type -AssemblyName System.Drawing
    $bitmap = New-Object System.Drawing.Bitmap 256, 256
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.Clear([System.Drawing.Color]::Transparent)
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $radius = 44
    $diameter = $radius * 2
    $path.AddArc(8, 8, $diameter, $diameter, 180, 90)
    $path.AddArc(248 - $diameter, 8, $diameter, $diameter, 270, 90)
    $path.AddArc(248 - $diameter, 248 - $diameter, $diameter, $diameter, 0, 90)
    $path.AddArc(8, 248 - $diameter, $diameter, $diameter, 90, 90)
    $path.CloseFigure()
    $background = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 0, 134, 109))
    $graphics.FillPath($background, $path)
    $checkPen = New-Object System.Drawing.Pen ([System.Drawing.Color]::White), 25
    $checkPen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $checkPen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $graphics.DrawLines($checkPen, [System.Drawing.Point[]]@(
        [System.Drawing.Point]::new(66, 132),
        [System.Drawing.Point]::new(112, 178),
        [System.Drawing.Point]::new(194, 82)
    ))
    $png = New-Object IO.MemoryStream
    $bitmap.Save($png, [System.Drawing.Imaging.ImageFormat]::Png)
    $bytes = $png.ToArray()
    $stream = [IO.File]::Open($target, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
    $writer = New-Object IO.BinaryWriter($stream)
    try {
        $writer.Write([UInt16]0)
        $writer.Write([UInt16]1)
        $writer.Write([UInt16]1)
        $writer.Write([Byte]0)
        $writer.Write([Byte]0)
        $writer.Write([Byte]0)
        $writer.Write([Byte]0)
        $writer.Write([UInt16]1)
        $writer.Write([UInt16]32)
        $writer.Write([UInt32]$bytes.Length)
        $writer.Write([UInt32]22)
        $writer.Write($bytes)
    } finally {
        $writer.Dispose()
        $png.Dispose()
        $checkPen.Dispose()
        $background.Dispose()
        $path.Dispose()
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}

function Assert-JarContains([string]$jar, [string]$pattern, [string]$message) {
    $entries = & (Resolve-JdkTool 'jar.exe') tf $jar
    if ($LASTEXITCODE -ne 0) { throw "无法读取 jar：$jar" }
    if (-not ($entries | Where-Object { $_ -like $pattern })) { throw $message }
}

if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw "版本号必须是 x.y.z：$Version"
}

New-Item -ItemType Directory -Path $distRoot -Force | Out-Null
New-Item -ItemType Directory -Path $buildRoot -Force | Out-Null
$stage = New-SafeDir (Join-Path $buildRoot "stage-$PID") $buildRoot
$made = @()
$base = "MAA-Checker-$Version"
$icon = Join-Path $stage 'maa-checker.ico'

try {
    New-AppIcon $icon
    Write-Host '=== JavaFX 桌面构建 ===' -ForegroundColor Cyan
    Invoke-MavenPackage 'desktop'
        $desktopJar = Join-Path $root 'target\desktop\maa-checker-desktop.jar'
        if (-not (Test-Path -LiteralPath $desktopJar)) { throw "缺少桌面 jar：$desktopJar" }
        Assert-JarContains $desktopJar 'javafx/application/Application.class' '桌面 jar 未包含 JavaFX 类'
        Assert-JarContains $desktopJar '*.dll' '桌面 jar 未包含 JavaFX 本地库'
        Assert-JarContains $desktopJar 'yagen/waitmydawn/checker/desktop/DesktopLauncher.class' '桌面 jar 入口缺失'
        Assert-JarContains $desktopJar 'yagen/waitmydawn/checker/desktop/app-icon-256.png' '桌面 jar 未包含窗口图标'

        $payload = Join-Path $stage 'desktop-payload'
        New-Item -ItemType Directory -Path $payload | Out-Null
        Copy-Item $desktopJar (Join-Path $payload 'maa-checker-desktop.jar')
        $appOut = Join-Path $stage 'desktop-app'
        New-Item -ItemType Directory -Path $appOut | Out-Null
        $jpackage = Resolve-JdkTool 'jpackage.exe'
        $appArgs = @(
            '--type', 'app-image',
            '--name', 'MAA-Checker',
            '--dest', $appOut,
            '--input', $payload,
            '--main-jar', 'maa-checker-desktop.jar',
            '--main-class', 'yagen.waitmydawn.checker.desktop.DesktopLauncher',
            '--app-version', $Version,
            '--vendor', 'Yagen',
            '--description', 'Minecraft modpack feasibility checker',
            '--icon', $icon,
            '--add-modules', 'java.base,java.desktop,java.logging,java.management,jdk.management,java.net.http,jdk.crypto.ec,jdk.unsupported',
            '--java-options', '-Dfile.encoding=UTF-8',
            '--java-options', '-Dstdout.encoding=UTF-8',
            '--java-options', '-Dstderr.encoding=UTF-8'
        )
        & $jpackage @appArgs
        if ($LASTEXITCODE -ne 0) { throw "jpackage 桌面应用镜像失败（退出码 $LASTEXITCODE）" }
        $appSource = Join-Path $appOut 'MAA-Checker'
        $runtimeJvm = Get-ChildItem -LiteralPath (Join-Path $appSource 'runtime') `
            -Recurse -Filter 'jvm.dll' -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $runtimeJvm -or -not (Test-Path (Join-Path $appSource 'app\MAA-Checker.cfg'))) {
            throw "jpackage 产物缺少应用运行时"
        }
        Copy-Item 'packaging\README-DESKTOP.txt','packaging\DATA-AND-REMOVAL.txt','LICENSE',
            'src\desktop\resources\THIRD-PARTY-NOTICES.txt' $appSource

        $smokeOut = Join-Path $stage 'package-smoke.out.log'
        $smokeErr = Join-Path $stage 'package-smoke.err.log'
        $smoke = Start-Process -FilePath (Join-Path $appSource 'MAA-Checker.exe') `
            -ArgumentList '--package-smoke' -Wait -PassThru -WindowStyle Hidden `
            -RedirectStandardOutput $smokeOut -RedirectStandardError $smokeErr
        if ($smoke.ExitCode -ne 0) {
            $detail = @()
            if (Test-Path $smokeOut) { $detail += Get-Content $smokeOut -Tail 20 }
            if (Test-Path $smokeErr) { $detail += Get-Content $smokeErr -Tail 20 }
            throw "打包后 JavaFX 启动冒烟失败（退出码 $($smoke.ExitCode)）：$($detail -join ' | ')"
        }

        $desktopDir = Join-Path $stage 'desktop-portable'
        New-Item -ItemType Directory -Path $desktopDir | Out-Null
        Copy-Item (Join-Path $appSource '*') $desktopDir -Recurse
        $desktopZip = Join-Path $distRoot "$base-desktop-win-x64.zip"
        New-SafeZip $desktopDir $desktopZip $distRoot
        $made += $desktopZip

        Write-Host '=== 轻量 EXE 构建 ===' -ForegroundColor Cyan
        Invoke-Launch4j $icon
        $lightSource = Join-Path $root 'target\desktop\MAA-Checker-Light.exe'
        if (-not (Test-Path -LiteralPath $lightSource)) {
            throw "缺少轻量 EXE：$lightSource"
        }
        $lightSmoke = Start-Process -FilePath $lightSource `
            -ArgumentList '--package-smoke' -Wait -PassThru -WindowStyle Hidden
        if ($lightSmoke.ExitCode -ne 0) {
            throw "轻量 EXE 启动冒烟失败（退出码 $($lightSmoke.ExitCode)）"
        }
        $lightExe = Join-Path $distRoot "$base-light-win-x64.exe"
        Copy-Item -LiteralPath $lightSource -Destination $lightExe -Force
        $made += $lightExe

} finally {
    Remove-SafeTree $stage $buildRoot
}

Write-Host '=== 完成 ===' -ForegroundColor Cyan
$sumFile = Join-Path $distRoot 'SHA256SUMS.txt'
$sums = $made | Where-Object { $_ -match '\.(zip|exe)$' } | ForEach-Object {
    "$((Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLower())  $(Split-Path -Leaf $_)"
}
[IO.File]::WriteAllLines($sumFile, $sums, (New-Object Text.UTF8Encoding($false)))
$made += $sumFile
$made | ForEach-Object {
    Write-Host ("  {0}  ({1:N2} MB)" -f $_, ((Get-Item -LiteralPath $_).Length / 1MB))
}
