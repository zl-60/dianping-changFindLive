$ErrorActionPreference = 'Stop'
$petProjectRoot = Split-Path -Parent $PSScriptRoot
$petFailedScripts = @()
foreach ($petScriptName in @('start-pet.ps1', 'verify-pet.ps1')) {
    $petScriptPath = Join-Path $petProjectRoot $petScriptName
    $petTokens = $null
    $petParseErrors = $null
    [System.Management.Automation.Language.Parser]::ParseFile(
        $petScriptPath, [ref]$petTokens, [ref]$petParseErrors
    ) | Out-Null
    if ($petParseErrors) {
        $petFailedScripts += $petScriptName
        foreach ($petParseError in $petParseErrors) {
            Write-Output ('{0}:{1}: {2}' -f $petScriptName,
                $petParseError.Extent.StartLineNumber, $petParseError.Message)
        }
    } else {
        Write-Output ('PASS: {0} (PowerShell {1})' -f $petScriptName, $PSVersionTable.PSVersion)
    }
}
if ($petFailedScripts.Count -gt 0) {
    throw ('PowerShell parsing failed: ' + ($petFailedScripts -join ', '))
}
