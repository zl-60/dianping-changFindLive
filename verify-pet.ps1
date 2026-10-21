param([string]$JavaHome, [switch]$Platform, [int]$RedisPort = 16379, [switch]$Broker, [int]$RabbitPort = 5672, [string]$RabbitVhost = '/petcare-test')
$ErrorActionPreference = 'Stop'
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
$petWindowsPowerShell = Get-Command powershell.exe -ErrorAction SilentlyContinue
if ($petWindowsPowerShell) {
    & $petWindowsPowerShell.Source -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'qa/check-powershell.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'PowerShell 脚本兼容性验证失败。' }
}
Push-Location (Join-Path $PSScriptRoot 'dianping-master')
try {
    $petChecks = @('-Dtest=com.petcare.Pet*IntegrationTest')
    if ($Platform -or $Broker) { $petChecks += '-Dpet.platform.tests=true'; $petChecks += "-Dpet.test.redis-port=$RedisPort" }
    if ($Broker) { $petChecks += '-Dpet.broker.tests=true'; $petChecks += "-Dpet.test.rabbit-port=$RabbitPort"; $petChecks += "-Dpet.test.rabbit-vhost=$RabbitVhost" }
    & mvn.cmd -s (Join-Path $PSScriptRoot 'maven-settings.xml') @petChecks package
    if ($LASTEXITCODE -ne 0) { throw '宠物业务验证失败。' }
    foreach ($petScript in @('pet-common.js', 'pet.js', 'pet-owner.js', 'pet-life.js', 'pet-owner-coupons.js')) {
        & node --check (Join-Path 'src/main/resources/static' $petScript)
        if ($LASTEXITCODE -ne 0) { throw "$petScript 语法验证失败。" }
    }
} finally { Pop-Location }
