param([int]$Port = 0, [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if (-not (Test-Path -LiteralPath '.env')) {
    python scripts/local_postgres.py start
    if ($LASTEXITCODE -ne 0) { throw 'Database is not configured. See README.md.' }
} elseif (Test-Path -LiteralPath '.local\postgres\PG_VERSION') {
    python scripts/local_postgres.py start
    if ($LASTEXITCODE -ne 0) { throw 'Failed to start the project database.' }
}
if ($Port -gt 0) { $env:APP_PORT = "$Port" }
$javaExe = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { (Get-Command java).Source }
$jarPath = Join-Path $PSScriptRoot 'target\systemlog-1.0.0.jar'
if (-not $SkipBuild -or -not (Test-Path -LiteralPath $jarPath)) {
    mvn -B -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Maven build failed. JDK 17+ and Maven 3.6.3+ are required.' }
}
& $javaExe '-Dfile.encoding=UTF-8' -jar $jarPath
