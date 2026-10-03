[CmdletBinding()]
param(
    [string[]]$Service,
    [int]$Tail = 200
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$arguments = @(
    'compose',
    '-f', (Join-Path $repositoryRoot 'compose.yaml'),
    '-f', (Join-Path $repositoryRoot 'compose.rag.yaml'),
    'logs',
    '--follow',
    '--tail', $Tail
)

if ($Service) {
    $arguments += $Service
}

& docker @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Docker Compose failed with exit code $LASTEXITCODE."
}
