param()

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$serverJar = Join-Path $projectRoot 'server\build\libs\UncivServer.jar'
$postgresImage = 'postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5'
$container = 'unciv-v3-legacy-isolation-' + [guid]::NewGuid().ToString('N').Substring(0, 8)

try {
    & (Join-Path $projectRoot 'gradlew.bat') :server:dist --no-daemon
    if ($LASTEXITCODE -ne 0) {
        throw 'Legacy server packaging failed'
    }
    if (-not (Test-Path -LiteralPath $serverJar -PathType Leaf)) {
        throw "Legacy server jar was not produced at $serverJar"
    }

    docker run --detach --rm --name $container `
        -e POSTGRES_PASSWORD=unciv-test-password `
        -P $postgresImage | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'PostgreSQL 19 Beta 2 container failed to start'
    }
    $port = (docker port $container 5432/tcp).Split(':')[-1]
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        docker exec $container pg_isready -U postgres *> $null
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Milliseconds 500
    }
    if ($attempt -eq 30) {
        throw 'PostgreSQL 19 Beta 2 did not become ready'
    }

    $env:UNCIV_V3_DATABASE_URL =
        "postgres://postgres:unciv-test-password@127.0.0.1:$port/postgres"
    $env:UNCIV_LEGACY_SERVER_JAR = $serverJar
    cargo test --manifest-path (Join-Path $projectRoot 'authoritative-server\Cargo.toml') `
        --test legacy_v3_isolation `
        same_uuid_legacy_upload_cannot_read_or_mutate_v3_canonical_state `
        -- --ignored --exact --nocapture
    if ($LASTEXITCODE -ne 0) {
        throw 'Legacy/v3 live isolation test failed'
    }
}
finally {
    Remove-Item Env:UNCIV_V3_DATABASE_URL -ErrorAction SilentlyContinue
    Remove-Item Env:UNCIV_LEGACY_SERVER_JAR -ErrorAction SilentlyContinue
    docker rm --force $container *> $null
}
