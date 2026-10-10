param(
    [int]$Port = 8081,
    [switch]$MySql,
    [string]$JavaHome
)
$ErrorActionPreference = 'Stop'
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
if (-not (Get-Command mvn.cmd -ErrorAction SilentlyContinue)) { throw '请先安装 Maven 并加入 PATH。' }
$env:PET_PORT = [string]$Port
$env:PET_PROFILE = if ($MySql) { 'mysql' } else { 'demo' }
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
