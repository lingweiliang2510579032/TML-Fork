$ErrorActionPreference = 'Stop'
$dependencyRoot = Join-Path $PSScriptRoot 'libs'
New-Item -ItemType Directory -Path $dependencyRoot -Force | Out-Null
Get-Content -LiteralPath (Join-Path $PSScriptRoot 'dependencies.json') -Raw | ConvertFrom-Json | ForEach-Object {
    $dependencyPath = Join-Path $dependencyRoot $_.name
    if (!(Test-Path -LiteralPath $dependencyPath) -or (Get-FileHash -LiteralPath $dependencyPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $_.sha256) {
        Invoke-WebRequest -Uri $_.url -OutFile $dependencyPath
    }
    if ((Get-FileHash -LiteralPath $dependencyPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $_.sha256) { throw 'Dependency SHA256 mismatch' }
}
