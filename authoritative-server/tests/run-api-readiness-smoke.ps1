[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$DatabaseUrl,
    [int]$ApiPort = 13000,
    [int]$WorkerPort = 43171
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$temporaryRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$outputDirectory = Join-Path $temporaryRoot (
    'unciv-v3-api-readiness-' + [guid]::NewGuid().ToString('N')
)
$worker = $null
$api = $null

try {
    New-Item -ItemType Directory -Path $outputDirectory | Out-Null
    $identity = [Convert]::ToHexString(
        [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
    ).ToLowerInvariant()
    $env:UNCIV_ENGINE_WORKER_PORT = $WorkerPort.ToString()
    $env:UNCIV_ENGINE_WORKER_SECRET = $identity
    $env:UNCIV_V3_RELEASE_BUNDLE_ID = 'dev-unpackaged'
    $env:UNCIV_ENGINE_WORKER_SOCKET_TIMEOUT_MS = '5000'
    $env:UNCIV_ENGINE_WORKER_COMMAND_TIMEOUT_MS = '30000'
    $worker = Start-Process -FilePath 'java' -ArgumentList (
        '-Djava.awt.headless=true',
        '-jar',
        (Resolve-Path (
            Join-Path $repositoryRoot 'server\build\libs\UncivAuthoritativeWorker.jar'
        ))
    ) -WorkingDirectory (
        Resolve-Path (Join-Path $repositoryRoot 'android\assets')
    ) -RedirectStandardOutput (
        Join-Path $outputDirectory 'worker.out'
    ) -RedirectStandardError (
        Join-Path $outputDirectory 'worker.err'
    ) -WindowStyle Hidden -PassThru

    $env:UNCIV_V3_UNPACKAGED_DEV = '1'
    $env:UNCIV_V3_BIND = "127.0.0.1:$ApiPort"
    $env:UNCIV_V3_DATABASE_URL = $DatabaseUrl
    $env:UNCIV_ENGINE_WORKER_ADDR = "127.0.0.1:$WorkerPort"
    $api = Start-Process -FilePath (
        Resolve-Path (
            Join-Path $repositoryRoot (
                'authoritative-server\target\debug\unciv-authoritative-server.exe'
            )
        )
    ) -WorkingDirectory (
        Resolve-Path (Join-Path $repositoryRoot 'authoritative-server')
    ) -RedirectStandardOutput (
        Join-Path $outputDirectory 'api.out'
    ) -RedirectStandardError (
        Join-Path $outputDirectory 'api.err'
    ) -WindowStyle Hidden -PassThru

    $health = $null
    $readiness = $null
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        Start-Sleep -Milliseconds 500
        if ($api.HasExited) {
            throw "API exited before readiness: $(
                Get-Content (Join-Path $outputDirectory 'api.err') -Raw
            )"
        }
        try {
            $health = Invoke-RestMethod -Uri (
                "http://127.0.0.1:$ApiPort/healthz"
            ) -TimeoutSec 2
            $readiness = Invoke-RestMethod -Uri (
                "http://127.0.0.1:$ApiPort/readyz"
            ) -TimeoutSec 5
            break
        }
        catch {
            continue
        }
    }
    if ($null -eq $readiness) {
        throw "API readiness deadline expired: $(
            Get-Content (Join-Path $outputDirectory 'api.err') -Raw
        )"
    }
    if (
        $health.status -ne 'ok' -or
        $readiness.status -ne 'ready' -or
        $readiness.postgres -ne 'ready' -or
        $readiness.engine_worker -ne 'ready'
    ) {
        throw 'API returned an unexpected liveness/readiness shape'
    }
    Stop-Process -Id $worker.Id -Force
    $worker.WaitForExit()
    $workerUnavailable = $null
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $response = Invoke-WebRequest -Uri (
            "http://127.0.0.1:$ApiPort/readyz"
        ) -SkipHttpErrorCheck -TimeoutSec 5
        if ($response.StatusCode -eq 503) {
            $workerUnavailable = $response.Content | ConvertFrom-Json
            break
        }
        Start-Sleep -Milliseconds 250
    }
    if (
        $null -eq $workerUnavailable -or
        $workerUnavailable.status -ne 'unready' -or
        $workerUnavailable.postgres -ne 'ready' -or
        $workerUnavailable.engine_worker -ne 'unready'
    ) {
        throw 'API did not remove a failed worker from readiness'
    }
    [pscustomobject]@{
        health_status = $health.status
        protocol = $health.protocol_version
        readiness_status = $readiness.status
        postgres = $readiness.postgres
        engine_worker = $readiness.engine_worker
        worker_failure_status = $workerUnavailable.status
    } | ConvertTo-Json -Compress
}
finally {
    if ($null -ne $api -and -not $api.HasExited) {
        Stop-Process -Id $api.Id -Force
    }
    if ($null -ne $worker -and -not $worker.HasExited) {
        Stop-Process -Id $worker.Id -Force
    }
    if (Test-Path -LiteralPath $outputDirectory) {
        $resolvedOutput = [System.IO.Path]::GetFullPath($outputDirectory)
        $expectedPrefix = $temporaryRoot.TrimEnd(
            [System.IO.Path]::DirectorySeparatorChar
        ) + [System.IO.Path]::DirectorySeparatorChar
        if (
            -not $resolvedOutput.StartsWith(
                $expectedPrefix,
                [System.StringComparison]::OrdinalIgnoreCase
            ) -or
            -not ([System.IO.Path]::GetFileName($resolvedOutput)).StartsWith(
                'unciv-v3-api-readiness-',
                [System.StringComparison]::Ordinal
            )
        ) {
            throw "Refusing to remove unexpected smoke path: $resolvedOutput"
        }
        Remove-Item -LiteralPath $outputDirectory -Recurse -Force
    }
}
