[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$image = (
    'postgres:19beta2-alpine@sha256:' +
    'bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5'
)
$suffix = [guid]::NewGuid().ToString('N').Substring(0, 12)
$network = "unciv-v3-pitr-$suffix"
$source = "unciv-v3-pitr-source-$suffix"
$restored = "unciv-v3-pitr-restored-$suffix"
$sourceData = "unciv-v3-pitr-source-data-$suffix"
$restoreData = "unciv-v3-pitr-restore-data-$suffix"
$archive = "unciv-v3-pitr-archive-$suffix"
$backups = "unciv-v3-pitr-backups-$suffix"
$databasePassword = 'qualification-database-only'
$backupPassword = 'qualification-backup-only'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$postgresqlFiles = (Resolve-Path (
    Join-Path $repositoryRoot 'authoritative-server\postgresql'
)).Path
$previousDatabaseUrl = $env:UNCIV_V3_DATABASE_URL
$createdContainers = [Collections.Generic.List[string]]::new()
$createdVolumes = [Collections.Generic.List[string]]::new()
$networkCreated = $false

function Invoke-Docker {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $output = & docker @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "docker $($Arguments -join ' ') failed:`n$($output -join "`n")"
    }
    return $output
}

function Wait-Postgres {
    param([Parameter(Mandatory)][string]$Container)

    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        & docker exec $Container pg_isready `
            -h 127.0.0.1 -U postgres -d postgres *> $null
        if ($LASTEXITCODE -eq 0) {
            return
        }
        Start-Sleep -Milliseconds 500
    }
    $logs = & docker logs $Container 2>&1
    throw "PostgreSQL did not become ready:`n$($logs -join "`n")"
}

function Get-PublishedPort {
    param([Parameter(Mandatory)][string]$Container)

    $mapping = (Invoke-Docker -Arguments @(
        'port', $Container, '5432/tcp'
    ) | Select-Object -First 1).ToString().Trim()
    if ($mapping -notmatch ':(\d+)$') {
        throw "unexpected Docker port mapping: $mapping"
    }
    return [int]$Matches[1]
}

function Invoke-Cargo {
    param([Parameter(Mandatory)][string[]]$Arguments)

    & cargo @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "cargo $($Arguments -join ' ') failed"
    }
}

try {
    Invoke-Docker -Arguments @('network', 'create', $network) | Out-Null
    $networkCreated = $true
    foreach ($volume in @($sourceData, $restoreData, $archive, $backups)) {
        Invoke-Docker -Arguments @('volume', 'create', $volume) | Out-Null
        $createdVolumes.Add($volume)
    }
    Invoke-Docker -Arguments @(
        'run', '--rm',
        '--mount', "type=volume,source=$archive,target=/archive",
        $image,
        '/bin/sh', '-c', 'chown postgres:postgres /archive'
    ) | Out-Null

    $archiveCommand = (
        'archive_command=UNCIV_V3_WAL_ARCHIVE_ROOT=/archive ' +
        '/bin/sh /qualification/archive-wal.sh "%p" "%f"'
    )
    Invoke-Docker -Arguments @(
        'run', '--detach',
        '--name', $source,
        '--network', $network,
        '--publish', '127.0.0.1::5432',
        '--env', "POSTGRES_PASSWORD=$databasePassword",
        '--env', 'PGDATA=/var/lib/postgresql/data',
        '--mount', "type=volume,source=$sourceData,target=/var/lib/postgresql",
        '--mount', "type=volume,source=$archive,target=/archive",
        '--mount', "type=bind,source=$postgresqlFiles,target=/qualification,readonly",
        $image,
        'postgres',
        '-c', 'archive_mode=on',
        '-c', 'archive_timeout=1s',
        '-c', $archiveCommand
    ) | Out-Null
    $createdContainers.Add($source)
    Wait-Postgres -Container $source

    $sourcePort = Get-PublishedPort -Container $source
    Start-Sleep -Seconds 1
    $sourceUrl = (
        "postgresql://postgres:$databasePassword@127.0.0.1:" +
        "$sourcePort/postgres?sslmode=disable"
    )
    $env:UNCIV_V3_DATABASE_URL = $sourceUrl
    Invoke-Cargo -Arguments @(
        'test',
        '--manifest-path', 'authoritative-server/Cargo.toml',
        '--lib',
        'postgres::integration_tests::backup_restore::seed_backup_restore_qualification_fixture',
        '--',
        '--ignored',
        '--exact'
    )

    Invoke-Docker -Arguments @(
        'exec', '--env', "PGPASSWORD=$databasePassword", $source,
        'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'postgres',
        '-c', (
            "CREATE ROLE unciv_backup WITH LOGIN REPLICATION PASSWORD " +
            "'$backupPassword';"
        )
    ) | Out-Null
    Invoke-Docker -Arguments @(
        'exec', $source,
        '/bin/sh', '-c', (
            'printf "%s\n" ' +
            '"host replication unciv_backup all scram-sha-256" ' +
            '>> "$PGDATA/pg_hba.conf"'
        )
    ) | Out-Null
    Invoke-Docker -Arguments @(
        'exec', '--env', "PGPASSWORD=$databasePassword", $source,
        'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'postgres',
        '-c', 'SELECT pg_reload_conf();'
    ) | Out-Null

    $backupOutput = Invoke-Docker -Arguments @(
        'run', '--rm',
        '--network', $network,
        '--env', (
            'UNCIV_V3_BACKUP_DATABASE_URL=' +
            'postgresql://unciv_backup@' + $source +
            ':5432/postgres?sslmode=disable'
        ),
        '--env', "PGPASSWORD=$backupPassword",
        '--env', 'UNCIV_V3_BASE_BACKUP_ROOT=/backups',
        '--mount', "type=volume,source=$backups,target=/backups",
        '--mount', "type=bind,source=$postgresqlFiles,target=/qualification,readonly",
        $image,
        '/bin/sh', '/qualification/base-backup.sh'
    )
    $backupPath = $backupOutput |
        Where-Object { $_.ToString().Trim() -match '^/backups/base-' } |
        Select-Object -Last 1
    if ($null -eq $backupPath) {
        throw "base backup did not report its verified destination"
    }
    $backupPath = $backupPath.ToString().Trim()

    Invoke-Docker -Arguments @(
        'exec', '--env', "PGPASSWORD=$databasePassword", $source,
        'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'postgres',
        '-c', (
            "CREATE TABLE pitr_qualification (marker text PRIMARY KEY);" +
            "INSERT INTO pitr_qualification VALUES ('included');"
        )
    ) | Out-Null
    Invoke-Docker -Arguments @(
        'exec', '--env', "PGPASSWORD=$databasePassword", $source,
        'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'postgres',
        '-c', "SELECT pg_create_restore_point('unciv_v3_backup_qualification');"
    ) | Out-Null
    Invoke-Docker -Arguments @(
        'exec', '--env', "PGPASSWORD=$databasePassword", $source,
        'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'postgres',
        '-c', (
            "INSERT INTO pitr_qualification VALUES ('excluded');" +
            "SELECT pg_switch_wal();"
        )
    ) | Out-Null

    $archived = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        $archiveState = Invoke-Docker -Arguments @(
            'exec', '--env', "PGPASSWORD=$databasePassword", $source,
            'psql', '-At', '-U', 'postgres', '-d', 'postgres',
            '-c', (
                "SELECT archived_count > 0 AND last_archived_wal IS NOT NULL " +
                "AND failed_count = 0 FROM pg_stat_archiver"
            )
        )
        if ((@($archiveState)[-1]).ToString().Trim() -eq 't') {
            $archived = $true
            break
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $archived) {
        throw 'WAL archive did not reach a clean observable state'
    }
    Invoke-Docker -Arguments @('stop', '--time', '30', $source) | Out-Null

    $restoreCommand = (
        "set -eu; mkdir -p /restore/data; cp -a '$backupPath/.' /restore/data/; " +
        'cat /qualification/qualification-recovery.conf ' +
        '>> /restore/data/postgresql.auto.conf; ' +
        'touch /restore/data/recovery.signal; ' +
        'chown -R postgres:postgres /restore'
    )
    Invoke-Docker -Arguments @(
        'run', '--rm',
        '--mount', "type=volume,source=$backups,target=/backups,readonly",
        '--mount', "type=volume,source=$restoreData,target=/restore",
        '--mount', "type=bind,source=$postgresqlFiles,target=/qualification,readonly",
        $image,
        '/bin/sh', '-c', $restoreCommand
    ) | Out-Null

    Invoke-Docker -Arguments @(
        'run', '--detach',
        '--name', $restored,
        '--network', $network,
        '--publish', '127.0.0.1::5432',
        '--env', "POSTGRES_PASSWORD=$databasePassword",
        '--env', 'PGDATA=/var/lib/postgresql/data',
        '--mount', "type=volume,source=$restoreData,target=/var/lib/postgresql",
        '--mount', "type=volume,source=$archive,target=/archive,readonly",
        $image
    ) | Out-Null
    $createdContainers.Add($restored)
    Wait-Postgres -Container $restored

    $restorePort = Get-PublishedPort -Container $restored
    $restoreUrl = (
        "postgresql://postgres:$databasePassword@127.0.0.1:" +
        "$restorePort/postgres?sslmode=disable"
    )
    $recoveryState = Invoke-Docker -Arguments @(
        'exec', '--env', "PGPASSWORD=$databasePassword", $restored,
        'psql', '-At', '-U', 'postgres', '-d', 'postgres',
        '-c', (
            "SELECT current_setting('server_version'), pg_is_in_recovery(), " +
            "(SELECT count(*) FROM pitr_qualification WHERE marker='included'), " +
            "(SELECT count(*) FROM pitr_qualification WHERE marker='excluded')"
        )
    )
    $recoveryFields = (@($recoveryState)[-1]).ToString().Trim().Split('|')
    if (
        $recoveryFields.Count -ne 4 -or
        $recoveryFields[0] -notmatch '^19beta2' -or
        $recoveryFields[1] -ne 'f' -or
        $recoveryFields[2] -ne '1' -or
        $recoveryFields[3] -ne '0'
    ) {
        throw "unexpected recovered PITR state: $($recoveryFields -join '|')"
    }

    $env:UNCIV_V3_DATABASE_URL = $restoreUrl
    Invoke-Cargo -Arguments @(
        'test',
        '--manifest-path', 'authoritative-server/Cargo.toml',
        '--lib',
        'postgres::integration_tests::backup_restore::restored_backup_fixture_preserves_every_required_invariant',
        '--',
        '--ignored',
        '--exact'
    )
    Invoke-Cargo -Arguments @(
        'run',
        '--quiet',
        '--manifest-path', 'authoritative-server/Cargo.toml',
        '--bin', 'unciv-v3-reconcile'
    )

    [pscustomobject]@{
        postgres = $recoveryFields[0]
        image = $image
        backup = [IO.Path]::GetFileName($backupPath)
        manifest = 'SHA256'
        pg_verifybackup = 'passed'
        wal_archive_failures = 0
        recovery_target = 'unciv_v3_backup_qualification'
        recovery_promoted = $true
        included_marker = 1
        excluded_marker = 0
        canonical_reconciliation = 'clean'
        restored_invariants = @(
            'head revision',
            'snapshot and payload hashes',
            'revision and command journal',
            'membership',
            'session',
            'security audit',
            'transactional outbox'
        )
    } | ConvertTo-Json -Depth 3
}
catch {
    foreach ($container in $createdContainers) {
        Write-Host "Docker logs for $container`:" -ForegroundColor Red
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
    foreach ($container in $createdContainers) {
        & docker rm --force $container *> $null
    }
    foreach ($volume in $createdVolumes) {
        & docker volume rm --force $volume *> $null
    }
    if ($networkCreated) {
        & docker network rm $network *> $null
    }
}
