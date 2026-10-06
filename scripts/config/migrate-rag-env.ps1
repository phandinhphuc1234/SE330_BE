[CmdletBinding()]
param(
    [string]$RootEnvironmentFile = (Join-Path $PSScriptRoot "..\..\.env"),
    [string]$RagEnvironmentFile = (Join-Path $PSScriptRoot "..\..\rag-service\.env")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Read-DotEnv {
    param([Parameter(Mandatory)][string]$Path)

    $values = [ordered]@{}
    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        if ($line -match '^\s*#' -or $line -notmatch '=') {
            continue
        }

        $separator = $line.IndexOf('=')
        $key = $line.Substring(0, $separator).Trim()
        if ($key) {
            $values[$key] = $line.Substring($separator + 1)
        }
    }

    return $values
}

$rootPath = [System.IO.Path]::GetFullPath($RootEnvironmentFile)
$ragPath = [System.IO.Path]::GetFullPath($RagEnvironmentFile)

if (-not [System.IO.File]::Exists($rootPath)) {
    throw "Root environment file does not exist: $rootPath"
}
if (-not [System.IO.File]::Exists($ragPath)) {
    throw "RAG environment file does not exist: $ragPath"
}

$rootValues = Read-DotEnv -Path $rootPath
$ragValues = Read-DotEnv -Path $ragPath

# The standalone RAG service historically used generic names. The repository
# Compose stack prefixes variables that would otherwise collide with Spring's
# PostgreSQL and application settings.
$nameMap = [ordered]@{
    APP_ENV = 'RAG_APP_ENV'
    APP_DEBUG = 'RAG_APP_DEBUG'
    LOG_LEVEL = 'RAG_LOG_LEVEL'
    LOG_JSON = 'RAG_LOG_JSON'
    ENABLE_API_DOCS = 'RAG_ENABLE_API_DOCS'
    STORAGE_BACKEND = 'RAG_STORAGE_BACKEND'
    STORAGE_LOCAL_PATH = 'RAG_STORAGE_LOCAL_PATH'
}

$sharedNames = @(
    'RAG_INTERNAL_API_KEY',
    'RAG_POSTGRES_PORT',
    'RAG_REDIS_PORT',
    'QDRANT_API_KEY',
    'QDRANT_COLLECTION_NAME',
    'EMBEDDING_PROVIDER',
    'EMBEDDING_MODEL',
    'EMBEDDING_DIM',
    'EMBEDDING_FALLBACK_MODEL',
    'EMBEDDING_VERSION',
    'EMBEDDING_TEXT_POLICY',
    'EMBEDDING_BATCH_SIZE',
    'EMBEDDING_MAX_RETRIES',
    'EMBEDDING_RETRY_BASE_DELAY_SECONDS',
    'EMBEDDING_RETRY_MAX_DELAY_SECONDS',
    'EMBEDDING_RETRY_JITTER_SECONDS',
    'EMBEDDING_DISTANCE_METRIC',
    'LLM_PROVIDER',
    'OPENAI_API_KEY',
    'GEMINI_API_KEY',
    'LLM_MODEL',
    'LLM_TEMPERATURE',
    'LLM_MAX_TOKENS',
    'RAG_ARTIFACT_BUCKET',
    'RAG_SOURCE_BUCKET',
    'CHUNK_SIZE',
    'CHUNK_OVERLAP',
    'MAX_CHUNKS_PER_DOCUMENT',
    'MAX_PDF_PAGES',
    'PDF_TEXT_SAMPLE_PAGES',
    'PDF_MIN_AVG_TEXT_CHARS',
    'MAX_UPLOAD_SIZE_MB',
    'INGESTION_ALLOWED_EXTENSIONS',
    'PDF_PARSER',
    'PDF_CLEANING_VERSION',
    'PDF_CLEAN_NORMALIZE_UNICODE',
    'PDF_CLEAN_COLLAPSE_SPACES',
    'PDF_CLEAN_MAX_BLANK_LINES',
    'PDF_CLEAN_HEADER_FOOTER_DETECTION_ENABLED',
    'PDF_CLEAN_HEADER_FOOTER_TOP_LINES',
    'PDF_CLEAN_HEADER_FOOTER_BOTTOM_LINES',
    'PDF_CLEAN_HEADER_FOOTER_MIN_REPEAT_RATIO',
    'PDF_CLEAN_HEADER_FOOTER_REMOVAL_ENABLED',
    'RETRIEVAL_TOP_K',
    'RERANKER_TOP_K',
    'HYBRID_VECTOR_WEIGHT',
    'HYBRID_KEYWORD_WEIGHT',
    'RAG_API_PORT',
    'QDRANT_HTTP_PORT',
    'QDRANT_GRPC_PORT',
    'SEAWEEDFS_S3_PORT'
)

$pending = [System.Collections.Generic.List[string]]::new()

foreach ($entry in $nameMap.GetEnumerator()) {
    if (-not $rootValues.Contains($entry.Value) -and $ragValues.Contains($entry.Key)) {
        $pending.Add("$($entry.Value)=$($ragValues[$entry.Key])")
    }
}

foreach ($name in $sharedNames) {
    if (-not $rootValues.Contains($name) -and $ragValues.Contains($name)) {
        $pending.Add("$name=$($ragValues[$name])")
    }
}

if ($pending.Count -eq 0) {
    Write-Output 'No RAG settings needed migration.'
    exit 0
}

$block = [Environment]::NewLine + '# RAG service values migrated from rag-service/.env' + [Environment]::NewLine
$block += ($pending -join [Environment]::NewLine) + [Environment]::NewLine
[System.IO.File]::AppendAllText($rootPath, $block, [System.Text.UTF8Encoding]::new($false))

# Deliberately report names/count only; never print secret values.
Write-Output "Migrated $($pending.Count) RAG settings into the root .env."

