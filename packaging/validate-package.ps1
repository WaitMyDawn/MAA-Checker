param(
    [Parameter(Mandatory = $true)][string]$DesktopZip,
    [string]$LightExe = ''
)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)))
$zip = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $DesktopZip).Path)
$validationRoot = [IO.Path]::GetFullPath((Join-Path $root 'build\package-validation'))
$stage = Join-Path $validationRoot ('run-' + [Guid]::NewGuid().ToString('N'))
$lightStage = Join-Path $validationRoot ('light-' + [Guid]::NewGuid().ToString('N'))
$lightCopy = $null

function Assert-ChildPath([string]$path, [string]$parent) {
    $full = [IO.Path]::GetFullPath($path)
    $base = [IO.Path]::GetFullPath($parent).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $full.StartsWith($base, [StringComparison]::OrdinalIgnoreCase)) {
        throw "拒绝操作预期目录之外的路径：$full"
    }
}

Assert-ChildPath $stage $validationRoot
Assert-ChildPath $lightStage $validationRoot
New-Item -ItemType Directory -Path $stage -Force | Out-Null
$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:PATH
try {
    if ($LightExe) {
        $light = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $LightExe).Path)
        New-Item -ItemType Directory -Path $lightStage -Force | Out-Null
        $lightCopy = Join-Path $lightStage 'MAA-Checker-Light.exe'
        Copy-Item -LiteralPath $light -Destination $lightCopy
        $stream = [IO.File]::OpenRead($lightCopy)
        try {
            if ($stream.ReadByte() -ne 0x4d -or $stream.ReadByte() -ne 0x5a) {
                throw "轻量版不是有效的 Windows EXE：$light"
            }
        } finally {
            $stream.Dispose()
        }
        if ((Get-Item -LiteralPath $lightCopy).Length -ge (Get-Item -LiteralPath $zip).Length) {
            throw "轻量版体积不小于自带运行时桌面包：$lightCopy"
        }
        $lightSmoke = Start-Process -FilePath $lightCopy `
            -ArgumentList '--package-smoke' -Wait -PassThru -WindowStyle Hidden
        if ($lightSmoke.ExitCode -ne 0) {
            throw "轻量版 JavaFX 启动冒烟失败（退出码 $($lightSmoke.ExitCode)）"
        }
        Write-Host "PASS: 轻量版单文件 EXE 可使用本机 Java 21 启动。"
    }

    Expand-Archive -LiteralPath $zip -DestinationPath $stage
    $required = @(
        'MAA-Checker.exe', 'app\maa-checker-desktop.jar', 'app\MAA-Checker.cfg',
        'runtime\bin\server\jvm.dll', 'runtime\legal', 'LICENSE',
        'README-DESKTOP.txt', 'THIRD-PARTY-NOTICES.txt', 'DATA-AND-REMOVAL.txt'
    )
    foreach ($relative in $required) {
        if (-not (Test-Path -LiteralPath (Join-Path $stage $relative))) {
            throw "桌面包缺少：$relative"
        }
    }

    # 模拟用户机器没有开发 JDK/Maven：清空 JAVA_HOME，只保留 Windows 系统命令。
    $env:JAVA_HOME = $null
    $env:PATH = "$env:SystemRoot\System32;$env:SystemRoot"
    $smoke = Start-Process -FilePath (Join-Path $stage 'MAA-Checker.exe') `
        -ArgumentList '--package-smoke' -Wait -PassThru -WindowStyle Hidden
    if ($smoke.ExitCode -ne 0) {
        throw "无开发 JDK 环境的 JavaFX 启动冒烟失败（退出码 $($smoke.ExitCode)）"
    }
    Write-Host "PASS: 无 JAVA_HOME/Maven/开发 JDK PATH 时桌面包可启动 JavaFX 工具包。"
    Write-Host "PASS: runtime、app、许可证与卸载说明完整。"
} finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:PATH = $oldPath
    if (Test-Path -LiteralPath $stage) {
        $item = Get-Item -LiteralPath $stage -Force
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0) {
            Remove-Item -LiteralPath $stage -Recurse -Force
        }
    }
    if ($lightCopy -and (Test-Path -LiteralPath $lightCopy)) {
        Remove-Item -LiteralPath $lightCopy -Force
    }
    if (Test-Path -LiteralPath $lightStage) {
        $lightItem = Get-Item -LiteralPath $lightStage -Force
        if (($lightItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0) {
            Remove-Item -LiteralPath $lightStage -Recurse -Force
        }
    }
}
