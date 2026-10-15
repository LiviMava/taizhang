param([switch]$Database)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$runtimePath = Join-Path $PSScriptRoot '.local\api-runtime.json'
if (Test-Path -LiteralPath $runtimePath) {
    $runtime = Get-Content -LiteralPath $runtimePath -Raw | ConvertFrom-Json
    $apiProcess = Get-Process -Id $runtime.pid -ErrorAction SilentlyContinue
    if ($apiProcess) {
        $recordedStart = [DateTimeOffset]::Parse($runtime.started_at).UtcDateTime
        $ageDifference = [Math]::Abs(($recordedStart - $apiProcess.StartTime.ToUniversalTime()).TotalSeconds)
        if ($ageDifference -gt 10 -or $apiProcess.Path -ine $runtime.executable) {
            throw 'Process identity does not match the startup record. Refusing to stop another process.'
        }
        Stop-Process -Id $apiProcess.Id
        Wait-Process -Id $apiProcess.Id -Timeout 10 -ErrorAction SilentlyContinue
        Write-Output 'API stopped.'
    } else {
        Write-Output 'API is not running.'
    }
    Remove-Item -LiteralPath $runtimePath -Force -ErrorAction SilentlyContinue
} else {
    Write-Output 'No API startup record found.'
}
if ($Database) {
    python scripts/local_postgres.py stop
    if ($LASTEXITCODE -ne 0) { throw 'Failed to stop the project database. Please check its status.' }
}
