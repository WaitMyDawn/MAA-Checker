# 找一个 Java 21+ 的 java.exe，把路径打到 stdout（找不到则退出码 2）。
# 为什么由 PowerShell 做这件事：批处理里判断 java -version 的字符串太脆弱，
# 而 Windows 10+ 一定自带 PowerShell。候选顺序与 Java 代码里的 JavaLocator 保持一致。
# 注意：这里绝不能设 $ErrorActionPreference='SilentlyContinue'——
# java -version 的版本信息写在 **stderr**，全局静默会把它吞掉，导致永远匹配不到版本号
# （实测踩过：脚本永远返回"没找到 Java"）。需要静默的地方逐条用 -ErrorAction。

$candidates = New-Object System.Collections.Generic.List[string]
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$candidates.Add((Join-Path $here '..\jre\bin\java.exe'))          # 便携式 JRE（可选）
if ($env:JAVA_HOME) { $candidates.Add((Join-Path $env:JAVA_HOME 'bin\java.exe')) }
if ($env:APPDATA) {
    $hmcl = Join-Path $env:APPDATA '.hmcl\java\windows-x86_64'
    if (Test-Path $hmcl) {
        Get-ChildItem $hmcl -Directory -ErrorAction SilentlyContinue | Sort-Object Name | ForEach-Object {
            $candidates.Add((Join-Path $_.FullName 'bin\java.exe'))
        }
    }
}
foreach ($base in @('C:\Program Files\Microsoft', 'C:\Program Files\Java',
        'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Zulu',
        'C:\Program Files\Amazon Corretto')) {
    if (Test-Path $base) {
        Get-ChildItem $base -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | ForEach-Object {
            $candidates.Add((Join-Path $_.FullName 'bin\java.exe'))
        }
    }
}
$pathJava = (Get-Command java -ErrorAction SilentlyContinue | Select-Object -First 1).Source
if ($pathJava) { $candidates.Add($pathJava) }

foreach ($c in $candidates) {
    if (-not (Test-Path $c)) { continue }
    $out = & $c -version 2>&1 | Out-String
    if ($out -match 'version\s+"(\d+)') {
        $major = [int]$Matches[1]
        if ($major -eq 1 -and $out -match 'version\s+"1\.(\d+)') { $major = [int]$Matches[1] }
        if ($major -ge 21) {
            Write-Output $c
            exit 0
        }
    }
}
exit 2
