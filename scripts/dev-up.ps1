[CmdletBinding()]
param(
    [switch]$ScheduledJobs,
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$arguments = @(
    'compose',
    '-f', (Join-Path $repositoryRoot 'compose.yaml'),
    '-f', (Join-Path $repositoryRoot 'compose.rag.yaml')
)

if ($ScheduledJobs) {
    $arguments += @('--profile', 'rag-scheduled-jobs')
}

$arguments += @('up', '-d')
if (-not $SkipBuild) {
    $arguments += '--build'
}

& docker @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Docker Compose failed with exit code $LASTEXITCODE."
}

