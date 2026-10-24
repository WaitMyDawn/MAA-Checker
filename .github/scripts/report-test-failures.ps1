param(
    [Parameter(Mandatory = $true)][string]$ReportsRoot
)

$ErrorActionPreference = 'Stop'
$failures = [System.Collections.Generic.List[string]]::new()
$reports = @()
if (Test-Path -LiteralPath $ReportsRoot) {
    $reports = Get-ChildItem -LiteralPath $ReportsRoot -Filter 'TEST-*.xml' -File -Recurse
}

foreach ($report in $reports) {
    $xmlText = [IO.File]::ReadAllText($report.FullName, [Text.Encoding]::UTF8)
    [xml]$xml = $xmlText
    foreach ($case in $xml.testsuite.testcase) {
        $problem = if ($case.error) { $case.error } elseif ($case.failure) { $case.failure } else { $null }
        if ($null -eq $problem) { continue }
        $title = "$($case.classname).$($case.name)"
        $message = [string]$problem.message
        $message = $message.Replace('%', '%25').Replace("`r", '%0D').Replace("`n", '%0A')
        Write-Output "::error title=$title::$message"
        $failures.Add($title)
    }
}

if ($reports.Count -eq 0) {
    Write-Output '::error title=Maven test step::No JUnit XML reports were produced; inspect the uploaded test artifacts.'
} elseif ($failures.Count -eq 0) {
    Write-Output '::error title=Maven test step::Maven failed without a JUnit testcase failure; inspect the uploaded reports and Maven output.'
}
