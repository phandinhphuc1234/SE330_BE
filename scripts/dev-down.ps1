[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
& docker compose `
    -f (Join-Path $repositoryRoot 'compose.yaml') `
    -f (Join-Path $repositoryRoot 'compose.rag.yaml') `
    down

if ($LASTEXITCODE -ne 0) {
    throw "Docker Compose failed with exit code $LASTEXITCODE."
}

