param([string]$JavaHome)
$ErrorActionPreference = 'Stop'
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
Push-Location (Join-Path $PSScriptRoot 'dianping-master')
try {
    & mvn.cmd -s (Join-Path $PSScriptRoot 'maven-settings.xml') '-Dtest=com.petcare.PetBookingIntegrationTest' package
    if ($LASTEXITCODE -ne 0) { throw '宠物业务验证失败。' }
    foreach ($petScript in @('pet-common.js', 'pet.js', 'pet-owner.js')) {
        & node --check (Join-Path 'src/main/resources/static' $petScript)
        if ($LASTEXITCODE -ne 0) { throw "$petScript 语法验证失败。" }
    }
} finally { Pop-Location }
