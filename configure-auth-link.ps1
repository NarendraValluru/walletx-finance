[CmdletBinding()]
param([string]$AuthPath = (Join-Path $PSScriptRoot '..\walletx-auth'))
$ErrorActionPreference = 'Stop'
$authRoot = (Resolve-Path -LiteralPath $AuthPath).Path
if (-not (Test-Path -LiteralPath (Join-Path $authRoot 'pom.xml'))) { throw 'Clone walletx-auth next to walletx-finance first.' }
$authFile = Join-Path $authRoot '.local\service-client.properties'
$financeFile = Join-Path $PSScriptRoot '.local\service-client.properties'
$key = $null
foreach ($file in @($authFile, $financeFile)) {
    if (Test-Path -LiteralPath $file) {
        $line = Get-Content -LiteralPath $file | Where-Object { $_ -match '^walletx.local.introspection-client-secret=' } | Select-Object -First 1
        if (-not $line) { throw 'Existing service-client configuration needs manual review.' }
        $found = $line.Substring($line.IndexOf('=') + 1)
        if ($key -and $key -ne $found) { throw 'Auth and finance secrets differ; refusing to overwrite them.' }
        $key = $found
    }
}
if (-not $key) {
    $bytes = New-Object byte[] 48
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    $key = [Convert]::ToBase64String($bytes)
}
if ($key.Length -lt 32) { throw 'Service secret must have at least 32 characters.' }
foreach ($file in @($authFile, $financeFile)) {
    if (-not (Test-Path -LiteralPath $file)) {
        New-Item -ItemType Directory -Path (Split-Path -Parent $file) -Force | Out-Null
        [System.IO.File]::WriteAllText($file, "# Private service credential. Never commit.`nwalletx.local.introspection-client-secret=$key`n")
    }
}
Write-Host 'Private auth/finance credentials configured. Restart both services. No JWT signing key is shared.'
