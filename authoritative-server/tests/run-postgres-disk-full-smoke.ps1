[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$image = (
    'postgres:19beta2-alpine@sha256:' +
    'bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5'
)
$suffix = [guid]::NewGuid().ToString('N').Substring(0, 12)
$container = "unciv-v3-disk-full-$suffix"
$password = 'qualification-disk-full-only'
$databaseUrl = $null
$previousDatabaseUrl = $env:UNCIV_V3_DATABASE_URL
$containerCreated = $false
$postgresqlRoot = (Resolve-Path (
    Join-Path $PSScriptRoot '..\postgresql'
)).Path

function Invoke-Docker {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $output = & docker @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "docker $($Arguments -join ' ') failed:`n$($output -join "`n")"
    }
    return $output
}

function Wait-Postgres {
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        & docker exec $container pg_isready `
            -h 127.0.0.1 -U postgres -d postgres *> $null
        if ($LASTEXITCODE -eq 0) {
            return
        }
        Start-Sleep -Milliseconds 500
    }
    throw 'PostgreSQL disk-full fixture did not become ready'
}

function Get-PublishedPort {
    $mapping = (Invoke-Docker -Arguments @(
        'port', $container, '5432/tcp'
    ) | Select-Object -First 1).ToString().Trim()
    if ($mapping -notmatch ':(\d+)$') {
        throw "unexpected Docker port mapping: $mapping"
    }
    return [int]$Matches[1]
}

function Invoke-QualificationTest {
    param([Parameter(Mandatory)][string]$Name)

    & cargo test `
        --manifest-path authoritative-server/Cargo.toml `
        --lib $Name -- --ignored --exact
    if ($LASTEXITCODE -ne 0) {
        throw "disk-full qualification test failed: $Name"
    }
}

try {
    Invoke-Docker -Arguments @(
        'run', '--detach',
        '--name', $container,
        '--publish', '127.0.0.1::5432',
        '--env', "POSTGRES_PASSWORD=$password",
        '--env', 'PGDATA=/var/lib/postgresql/data',
        '--tmpfs', '/var/lib/postgresql:rw,size=160m',
        '--mount', "type=bind,source=$postgresqlRoot,target=/qualification,readonly",
        $image
    ) | Out-Null
    $containerCreated = $true
    Wait-Postgres
    # Match the production bootstrap's ACL target roles before the migrator
    # applies grants in this disposable qualification cluster.
    Invoke-Docker -Arguments @(
        'exec', $container,
        'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'postgres',
        '-c', (
            "DO `$`$ BEGIN " +
            "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='unciv_runtime') THEN CREATE ROLE unciv_runtime; END IF; " +
            "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='unciv_migrate') THEN CREATE ROLE unciv_migrate; END IF; " +
            "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='unciv_restore') THEN CREATE ROLE unciv_restore; END IF; " +
            "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='unciv_audit') THEN CREATE ROLE unciv_audit; END IF; " +
            "END `$`$;"
        )
    ) | Out-Null
    $port = Get-PublishedPort
    $databaseUrl = (
        "postgresql://postgres:$password@127.0.0.1:" +
        "$port/postgres?sslmode=disable"
    )
    $env:UNCIV_V3_DATABASE_URL = $databaseUrl

    Invoke-QualificationTest -Name (
        'postgres::integration_tests::disk_full::' +
        'seed_disk_full_qualification_fixture'
    )
    Invoke-Docker -Arguments @(
        'exec', $container, 'mkdir', '-p',
        '/var/lib/postgresql/wal-qualification',
        '/var/lib/postgresql/backup-qualification'
    ) | Out-Null
    $healthyCapacity = Invoke-Docker -Arguments @(
        'exec',
        '--env', "PGPASSWORD=$password",
        '--env', (
            'UNCIV_V3_AUDIT_DATABASE_URL=' +
            'postgresql://postgres@127.0.0.1:5432/postgres?sslmode=disable'
        ),
        '--env', 'UNCIV_V3_POSTGRES_DATA_ROOT=/var/lib/postgresql',
        '--env', (
            'UNCIV_V3_WAL_ARCHIVE_ROOT=' +
            '/var/lib/postgresql/wal-qualification'
        ),
        '--env', (
            'UNCIV_V3_BASE_BACKUP_ROOT=' +
            '/var/lib/postgresql/backup-qualification'
        ),
        '--env', 'UNCIV_V3_CAPACITY_WARN_PERCENT=80',
        '--env', 'UNCIV_V3_CAPACITY_CRITICAL_PERCENT=95',
        $container,
        '/bin/sh', '/qualification/check-capacity.sh'
    )
    if ((@($healthyCapacity)[-1]).ToString() -notmatch '"status":"ok"') {
        throw 'healthy capacity check did not report ok'
    }
    $before = Invoke-Docker -Arguments @(
        'exec', $container, '/bin/sh', '-c',
        'df -Pk "$PGDATA" | tail -n 1'
    )

    Invoke-Docker -Arguments @(
        'exec', $container, '/bin/sh', '-c', (
            'set -eu; available=$(df -Pk "$PGDATA" | ' +
            "awk 'NR==2 {print `$4}'); " +
            'reserve=1024; count=$((available-reserve)); ' +
            'test "$count" -gt 0; ' +
            'dd if=/dev/zero of="$PGDATA/qualification.fill" ' +
            'bs=1024 count="$count" conv=fsync 2>/dev/null'
        )
    ) | Out-Null
    $constrained = Invoke-Docker -Arguments @(
        'exec', $container, '/bin/sh', '-c',
        'set -- $(df -Pk "$PGDATA" | tail -n 1); printf "%s\n" "$4"'
    )
    if ([int64](@($constrained)[-1].ToString().Trim()) -gt 1536) {
        throw 'disk-full fixture did not constrain free space sufficiently'
    }
    $criticalCapacity = & docker exec `
        --env "PGPASSWORD=$password" `
        --env (
            'UNCIV_V3_AUDIT_DATABASE_URL=' +
            'postgresql://postgres@127.0.0.1:5432/postgres?sslmode=disable'
        ) `
        --env 'UNCIV_V3_POSTGRES_DATA_ROOT=/var/lib/postgresql' `
        --env (
            'UNCIV_V3_WAL_ARCHIVE_ROOT=' +
            '/var/lib/postgresql/wal-qualification'
        ) `
        --env (
            'UNCIV_V3_BASE_BACKUP_ROOT=' +
            '/var/lib/postgresql/backup-qualification'
        ) `
        --env 'UNCIV_V3_CAPACITY_WARN_PERCENT=80' `
        --env 'UNCIV_V3_CAPACITY_CRITICAL_PERCENT=95' `
        $container /bin/sh /qualification/check-capacity.sh
    $criticalExit = $LASTEXITCODE
    if (
        $criticalExit -ne 2 -or
        (@($criticalCapacity)[-1]).ToString() -notmatch '"status":"critical"'
    ) {
        throw 'constrained capacity check did not report critical/exit 2'
    }

    Invoke-QualificationTest -Name (
        'postgres::integration_tests::disk_full::' +
        'disk_full_commit_leaves_no_phantom_revision'
    )

    Invoke-Docker -Arguments @(
        'exec', $container, 'rm',
        '-f', '/var/lib/postgresql/data/qualification.fill'
    ) | Out-Null
    Invoke-Docker -Arguments @(
        'exec', '--env', "PGPASSWORD=$password", $container,
        'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'postgres',
        '-c', 'CHECKPOINT'
    ) | Out-Null

    Invoke-QualificationTest -Name (
        'postgres::integration_tests::disk_full::' +
        'recovered_space_allows_one_idempotent_retry'
    )
    $reconciliation = & cargo run `
        --quiet `
        --manifest-path authoritative-server/Cargo.toml `
        --bin unciv-v3-reconcile
    if ($LASTEXITCODE -ne 0) {
        throw 'post-disk-full reconciliation failed'
    }
    $reconciliationText = $reconciliation -join "`n"
    if ($reconciliationText -notmatch '"total_findings": 0') {
        throw 'post-disk-full reconciliation reported findings'
    }

    $after = Invoke-Docker -Arguments @(
        'exec', $container, '/bin/sh', '-c',
        'df -Pk "$PGDATA" | tail -n 1'
    )
    [pscustomobject]@{
        postgres = '19beta2'
        image = $image
        tmpfs_megabytes = 160
        filesystem_before_kb = @($before)[-1].ToString().Trim()
        constrained_free_kb = [int64](@($constrained)[-1].ToString().Trim())
        healthy_capacity_status = 'ok'
        constrained_capacity_status = 'critical'
        failed_commit = 'storage_error'
        phantom_head_revision = $false
        phantom_command = $false
        retry_revision = 1
        duplicate_retry_commits = 1
        canonical_reconciliation = 'clean'
        filesystem_after_kb = @($after)[-1].ToString().Trim()
    } | ConvertTo-Json
}
catch {
    if ($containerCreated) {
        & docker logs $container 2>&1 | Write-Host
    }
    throw
}
finally {
    if ($null -eq $previousDatabaseUrl) {
        Remove-Item Env:UNCIV_V3_DATABASE_URL -ErrorAction SilentlyContinue
    }
    else {
        $env:UNCIV_V3_DATABASE_URL = $previousDatabaseUrl
    }
    if ($containerCreated) {
        & docker rm --force $container *> $null
    }
}
