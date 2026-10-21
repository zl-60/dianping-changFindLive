param(
    [int]$Port = 8081,
    [switch]$MySql,
    [switch]$Demo,
    [string]$JavaHome
)
$ErrorActionPreference = 'Stop'
# Only literal PET_* assignments are loaded; never execute the contents of .env.
$petEnvFile = Join-Path $PSScriptRoot '.env'
if (Test-Path -LiteralPath $petEnvFile) {
    foreach ($petLine in [IO.File]::ReadAllLines($petEnvFile)) {
        if ($petLine -match '^\s*(PET_[A-Z0-9_]+)\s*=(.*)$') {
            $petName = $Matches[1]
            $petValue = $Matches[2].Trim().Trim('"', "'")
            if (-not [Environment]::GetEnvironmentVariable($petName)) {
                [Environment]::SetEnvironmentVariable($petName, $petValue, 'Process')
            }
        }
    }
}
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
if (-not (Get-Command mvn.cmd -ErrorAction SilentlyContinue)) { throw '请先安装 Maven 并加入 PATH。' }
$env:PET_PORT = [string]$Port
if ($Demo -and $MySql) { throw '-Demo 与 -MySql 不可同时使用。' }
$env:PET_PROFILE = if ($Demo) { 'demo' } elseif ($MySql) { 'mysql,platform' } else { 'demo,platform' }
if ($MySql -and (-not $env:PET_DB_PASSWORD -or -not $env:PET_ADMIN_PASSWORD)) {
    throw 'MySQL 模式需要先设置 PET_DB_PASSWORD 和 PET_ADMIN_PASSWORD。'
}
Push-Location (Join-Path $PSScriptRoot 'dianping-master')
try {
    Write-Host "顾客网页：http://127.0.0.1:$Port/"
    Write-Host "店主工作台：http://127.0.0.1:$Port/owner.html"
    & mvn.cmd -s (Join-Path $PSScriptRoot 'maven-settings.xml') -DskipTests spring-boot:run
    if ($LASTEXITCODE -ne 0) { throw '启动失败，请查看上面的 Maven 日志。' }
} finally { Pop-Location }
