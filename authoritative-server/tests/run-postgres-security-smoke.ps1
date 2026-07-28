[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$image = (
    'postgres:19beta2-alpine@sha256:' +
    'bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5'
)
$suffix = [guid]::NewGuid().ToString('N').Substring(0, 12)
$container = "unciv-v3-postgres-security-$suffix"
$volume = "unciv-v3-postgres-security-data-$suffix"
$database = 'unciv_authoritative'
$adminPassword = 'qualification-admin-only'
$runtimePassword = 'qualification-runtime-old'
$runtimePasswordNew = 'qualification-runtime-new'
$migrationPassword = 'qualification-migration-only'
$backupPassword = 'qualification-backup-only'
$restorePassword = 'qualification-restore-only'
$auditPassword = 'qualification-audit-only'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$postgresqlRoot = (Resolve-Path (
    Join-Path $repositoryRoot 'authoritative-server\postgresql'
)).Path
$temporaryBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$certificateRoot = Join-Path $temporaryBase (
    "unciv-v3-postgres-security-$suffix"
)
$previousMigrationUrl = $env:UNCIV_V3_MIGRATION_DATABASE_URL
$previousDatabaseUrl = $env:UNCIV_V3_DATABASE_URL
$previousAuditUrl = $env:UNCIV_V3_AUDIT_DATABASE_URL
$containerCreated = $false
$volumeCreated = $false

function Invoke-External {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$Arguments
    )

    $output = & $FilePath @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "$FilePath failed:`n$($output -join "`n")"
    }
    return $output
}

function Invoke-Docker {
    param([Parameter(Mandatory)][string[]]$Arguments)

    return Invoke-External -FilePath 'docker' -Arguments $Arguments
}

function Wait-Postgres {
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        & docker exec $container pg_isready `
            -h 127.0.0.1 -U postgres -d $database *> $null
        if ($LASTEXITCODE -eq 0) {
            return
        }
        Start-Sleep -Milliseconds 500
    }
    throw 'PostgreSQL TCP readiness deadline expired'
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

function Invoke-RoleBootstrap {
    param([string]$RuntimeCredential = $runtimePassword)

    Invoke-Docker -Arguments @(
        'exec', '--user', 'postgres', $container,
        'psql', '-v', 'ON_ERROR_STOP=1',
        '--set', "runtime_password=$RuntimeCredential",
        '--set', "migration_password=$migrationPassword",
        '--set', "backup_password=$backupPassword",
        '--set', "restore_password=$restorePassword",
        '--set', "audit_password=$auditPassword",
        '-d', 'postgres',
        '-f', '/qualification/bootstrap-roles.sql'
    ) | Out-Null
}

function Invoke-RoleQuery {
    param(
        [Parameter(Mandatory)][string]$Role,
        [Parameter(Mandatory)][string]$Password,
        [Parameter(Mandatory)][string]$Query
    )

    return Invoke-Docker -Arguments @(
        'exec',
        '--env', "PGPASSWORD=$Password",
        '--env', 'PGSSLMODE=require',
        $container,
        'psql', '-v', 'ON_ERROR_STOP=1', '-At',
        '-h', '127.0.0.1', '-U', $Role, '-d', $database,
        '-c', $Query
    )
}

function Assert-RoleQueryFails {
    param(
        [Parameter(Mandatory)][string]$Role,
        [Parameter(Mandatory)][string]$Password,
        [Parameter(Mandatory)][string]$Query,
        [string]$SslMode = 'require'
    )

    & docker exec `
        --env "PGPASSWORD=$Password" `
        --env "PGSSLMODE=$SslMode" `
        $container `
        psql -v ON_ERROR_STOP=1 -At `
        -h 127.0.0.1 -U $Role -d $database -c $Query *> $null
    if ($LASTEXITCODE -eq 0) {
        throw "expected $Role query to fail: $Query"
    }
}

try {
    New-Item -ItemType Directory -Path $certificateRoot | Out-Null
    $openssl = (Get-Command openssl -ErrorAction Stop).Source
    $caKey = Join-Path $certificateRoot 'ca.key'
    $caCertificate = Join-Path $certificateRoot 'ca.crt'
    $serverKey = Join-Path $certificateRoot 'server.key'
    $serverRequest = Join-Path $certificateRoot 'server.csr'
    $serverCertificate = Join-Path $certificateRoot 'server.crt'
    $extensions = Join-Path $certificateRoot 'server.ext'
    [IO.File]::WriteAllText(
        $extensions,
        "subjectAltName=DNS:localhost,IP:127.0.0.1`n" +
        "extendedKeyUsage=serverAuth`n"
    )
    Invoke-External -FilePath $openssl -Arguments @(
        'req', '-x509', '-newkey', 'rsa:2048', '-sha256', '-nodes',
        '-days', '1', '-subj', '/CN=Unciv qualification CA',
        '-keyout', $caKey, '-out', $caCertificate
    ) | Out-Null
    Invoke-External -FilePath $openssl -Arguments @(
        'req', '-newkey', 'rsa:2048', '-sha256', '-nodes',
        '-subj', '/CN=localhost',
        '-keyout', $serverKey, '-out', $serverRequest
    ) | Out-Null
    Invoke-External -FilePath $openssl -Arguments @(
        'x509', '-req', '-sha256', '-days', '1',
        '-in', $serverRequest,
        '-CA', $caCertificate, '-CAkey', $caKey, '-CAcreateserial',
        '-extfile', $extensions,
        '-out', $serverCertificate
    ) | Out-Null

    Invoke-Docker -Arguments @('volume', 'create', $volume) | Out-Null
    $volumeCreated = $true
    Invoke-Docker -Arguments @(
        'run', '--detach',
        '--name', $container,
        '--publish', '127.0.0.1::5432',
        '--env', "POSTGRES_PASSWORD=$adminPassword",
        '--env', "POSTGRES_DB=$database",
        '--env', 'PGDATA=/var/lib/postgresql/data',
        '--mount', "type=volume,source=$volume,target=/var/lib/postgresql",
        '--mount', "type=bind,source=$postgresqlRoot,target=/qualification,readonly",
        $image
    ) | Out-Null
    $containerCreated = $true
    Wait-Postgres

    Invoke-Docker -Arguments @(
        'exec', $container, 'mkdir', '-p', '/var/lib/postgresql/tls'
    ) | Out-Null
    foreach ($file in @($caCertificate, $serverCertificate, $serverKey)) {
        Invoke-Docker -Arguments @(
            'cp', $file, "$container`:/var/lib/postgresql/tls/"
        ) | Out-Null
    }
    Invoke-Docker -Arguments @(
        'exec', $container, 'chown', '-R', 'postgres:postgres',
        '/var/lib/postgresql/tls'
    ) | Out-Null
    Invoke-Docker -Arguments @(
        'exec', $container, 'chmod', '0600',
        '/var/lib/postgresql/tls/server.key'
    ) | Out-Null

    Invoke-RoleBootstrap
    Invoke-Docker -Arguments @(
        'cp',
        (Join-Path $postgresqlRoot 'production-pg_hba.conf'),
        "$container`:/var/lib/postgresql/data/pg_hba.conf"
    ) | Out-Null
    Invoke-Docker -Arguments @(
        'exec', $container, '/bin/sh', '-c', (
            'printf "%s\n" ' +
            '"hostssl unciv_authoritative unciv_runtime all scram-sha-256" ' +
            '"hostssl unciv_authoritative unciv_migrate all scram-sha-256" ' +
            '"hostssl unciv_authoritative unciv_audit all scram-sha-256" ' +
            '"hostssl replication unciv_backup all scram-sha-256" ' +
            '>> /var/lib/postgresql/data/pg_hba.conf'
        )
    ) | Out-Null
    Invoke-Docker -Arguments @(
        'exec', $container, 'chown', 'postgres:postgres',
        '/var/lib/postgresql/data/pg_hba.conf'
    ) | Out-Null
    foreach ($tlsSetting in @(
        "ALTER SYSTEM SET ssl = 'on'",
        (
            "ALTER SYSTEM SET ssl_cert_file = " +
            "'/var/lib/postgresql/tls/server.crt'"
        ),
        (
            "ALTER SYSTEM SET ssl_key_file = " +
            "'/var/lib/postgresql/tls/server.key'"
        ),
        "ALTER SYSTEM SET ssl_ca_file = '/var/lib/postgresql/tls/ca.crt'",
        "ALTER SYSTEM SET ssl_min_protocol_version = 'TLSv1.2'",
        "ALTER SYSTEM SET password_encryption = 'scram-sha-256'"
    )) {
        Invoke-Docker -Arguments @(
            'exec', '--user', 'postgres', $container,
            'psql', '-v', 'ON_ERROR_STOP=1', '-d', 'postgres',
            '-c', $tlsSetting
        ) | Out-Null
    }
    Invoke-Docker -Arguments @('restart', $container) | Out-Null
    Wait-Postgres

    $port = Get-PublishedPort
    $env:UNCIV_V3_MIGRATION_DATABASE_URL = (
        "postgresql://unciv_migrate:$migrationPassword@127.0.0.1:" +
        "$port/$database`?sslmode=require"
    )
    Invoke-External -FilePath 'cargo' -Arguments @(
        'run', '--quiet',
        '--manifest-path', 'authoritative-server/Cargo.toml',
        '--bin', 'unciv-v3-migrate'
    ) | Out-Null
    Invoke-RoleBootstrap

    $runtimeState = Invoke-RoleQuery `
        -Role 'unciv_runtime' `
        -Password $runtimePassword `
        -Query (
            "SELECT ssl, version, cipher FROM pg_stat_ssl " +
            "WHERE pid=pg_backend_pid();" +
            "SELECT has_schema_privilege(current_user,'public','USAGE')," +
            "has_schema_privilege(current_user,'public','CREATE')," +
            "has_table_privilege(current_user,'accounts'," +
            "'SELECT,INSERT,UPDATE,DELETE');"
        )
    $runtimeLines = @($runtimeState)
    if (
        $runtimeLines[0] -notmatch '^t\|TLSv1\.[23]\|' -or
        $runtimeLines[-1].ToString().Trim() -ne 't|f|t'
    ) {
        throw "runtime TLS/privilege shape was not least privilege"
    }
    Assert-RoleQueryFails `
        -Role 'unciv_runtime' `
        -Password $runtimePassword `
        -Query 'CREATE TABLE runtime_must_not_create (id integer)'
    Assert-RoleQueryFails `
        -Role 'unciv_runtime' `
        -Password $runtimePassword `
        -Query 'SELECT 1' `
        -SslMode 'disable'

    Invoke-RoleQuery `
        -Role 'unciv_migrate' `
        -Password $migrationPassword `
        -Query (
            'CREATE TABLE migration_qualification (id integer);' +
            'DROP TABLE migration_qualification;'
        ) | Out-Null
    $auditRows = Invoke-RoleQuery `
        -Role 'unciv_audit' `
        -Password $auditPassword `
        -Query 'SELECT count(*) FROM accounts'
    if ((@($auditRows)[-1]).ToString().Trim() -ne '0') {
        throw 'audit role returned an unexpected account count'
    }
    Assert-RoleQueryFails `
        -Role 'unciv_audit' `
        -Password $auditPassword `
        -Query (
            "INSERT INTO accounts " +
            "(id,username_normalized,password_hash) " +
            "VALUES ('00000000-0000-0000-0000-000000000001','denied','denied')"
        )
    Invoke-RoleQuery `
        -Role 'unciv_runtime' `
        -Password $runtimePassword `
        -Query (
            "INSERT INTO security_audit_events " +
            "(event_type,outcome,source_ip_prefix,identity_hash) VALUES " +
            "('login','rejected','192.0.2.0/24',repeat('a',64))"
        ) | Out-Null
    Assert-RoleQueryFails `
        -Role 'unciv_runtime' `
        -Password $runtimePassword `
        -Query "UPDATE security_audit_events SET outcome='success'"
    Assert-RoleQueryFails `
        -Role 'unciv_runtime' `
        -Password $runtimePassword `
        -Query 'DELETE FROM security_audit_events'
    Assert-RoleQueryFails `
        -Role 'unciv_restore' `
        -Password $restorePassword `
        -Query 'SELECT 1'

    Invoke-Docker -Arguments @(
        'exec',
        '--env', "PGPASSWORD=$backupPassword",
        '--env', 'PGSSLMODE=require',
        $container,
        'pg_basebackup',
        '-h', '127.0.0.1', '-U', 'unciv_backup',
        '-D', '/tmp/backup-qualification',
        '--checkpoint=fast', '--wal-method=stream',
        '--manifest-checksums=SHA256', '--no-password'
    ) | Out-Null
    Invoke-Docker -Arguments @(
        'exec', $container, 'pg_verifybackup',
        '--exit-on-error', '/tmp/backup-qualification'
    ) | Out-Null

    $env:UNCIV_V3_DATABASE_URL = (
        "postgresql://unciv_audit:$auditPassword@127.0.0.1:" +
        "$port/$database`?sslmode=require"
    )
    $env:UNCIV_V3_AUDIT_DATABASE_URL = $env:UNCIV_V3_DATABASE_URL
    $reconciliation = Invoke-External -FilePath 'cargo' -Arguments @(
        'run', '--quiet',
        '--manifest-path', 'authoritative-server/Cargo.toml',
        '--bin', 'unciv-v3-reconcile'
    )
    if (($reconciliation -join "`n") -notmatch '"total_findings": 0') {
        throw 'audit-only reconciliation was not clean'
    }
    $auditExportPath = Join-Path $certificateRoot 'security-audit.ndjson'
    Invoke-External -FilePath 'cargo' -Arguments @(
        'run', '--quiet',
        '--manifest-path', 'authoritative-server/Cargo.toml',
        '--bin', 'unciv-v3-export-security-audit',
        '--', '--output', $auditExportPath
    ) | Out-Null
    $auditExport = Get-Content -LiteralPath $auditExportPath
    if ($auditExport.Count -ne 2) {
        throw 'security audit export did not contain one event plus its manifest'
    }
    $eventRecord = $auditExport[0] | ConvertFrom-Json
    $manifestRecord = $auditExport[1] | ConvertFrom-Json
    if (
        $eventRecord.record_type -ne 'security_audit_event' -or
        $eventRecord.event.identity_hash -ne ('a' * 64) -or
        $eventRecord.record_hash.Length -ne 64 -or
        $manifestRecord.record_type -ne 'security_audit_manifest' -or
        $manifestRecord.event_count -ne 1 -or
        $manifestRecord.final_record_hash -ne $eventRecord.record_hash
    ) {
        throw 'security audit export chain or manifest was invalid'
    }

    Invoke-Docker -Arguments @(
        'exec', '--user', 'postgres', $container,
        'psql', '-v', 'ON_ERROR_STOP=1',
        '--set', 'role_name=unciv_runtime',
        '--set', "new_password=$runtimePasswordNew",
        '-d', 'postgres',
        '-f', '/qualification/rotate-role-password.sql'
    ) | Out-Null
    Assert-RoleQueryFails `
        -Role 'unciv_runtime' `
        -Password $runtimePassword `
        -Query 'SELECT 1'
    $rotated = Invoke-RoleQuery `
        -Role 'unciv_runtime' `
        -Password $runtimePasswordNew `
        -Query 'SELECT 1'
    if ((@($rotated)[-1]).ToString().Trim() -ne '1') {
        throw 'rotated runtime credential did not authenticate'
    }

    $roleState = Invoke-Docker -Arguments @(
        'exec', '--user', 'postgres', $container,
        'psql', '-At', '-d', 'postgres',
        '-c', (
            "SELECT rolname, rolsuper, rolcreatedb, rolcreaterole, " +
            "rolreplication, rolbypassrls, rolpassword LIKE 'SCRAM-SHA-256$%' " +
            "FROM pg_authid WHERE rolname LIKE 'unciv_%' ORDER BY rolname"
        )
    )

    [pscustomobject]@{
        postgres = '19beta2'
        image = $image
        tls = 'required'
        tls_protocol = $runtimeLines[0].ToString().Split('|')[1]
        runtime_dml = 'allowed'
        runtime_ddl = 'denied'
        migration_ddl = 'allowed'
        audit_select = 'allowed'
        audit_write = 'denied'
        backup_replication = 'verified'
        restore_production_connect = 'denied'
        non_tls_connect = 'denied'
        runtime_rotation_old_credential = 'denied'
        runtime_rotation_new_credential = 'accepted'
        role_state = @($roleState)
        audit_reconciliation = 'clean'
    } | ConvertTo-Json -Depth 3
}
catch {
    if ($containerCreated) {
        & docker logs $container 2>&1 | Write-Host
    }
    throw
}
finally {
    if ($null -eq $previousMigrationUrl) {
        Remove-Item Env:UNCIV_V3_MIGRATION_DATABASE_URL -ErrorAction SilentlyContinue
    }
    else {
        $env:UNCIV_V3_MIGRATION_DATABASE_URL = $previousMigrationUrl
    }
    if ($null -eq $previousDatabaseUrl) {
        Remove-Item Env:UNCIV_V3_DATABASE_URL -ErrorAction SilentlyContinue
    }
    else {
        $env:UNCIV_V3_DATABASE_URL = $previousDatabaseUrl
    }
    if ($null -eq $previousAuditUrl) {
        Remove-Item Env:UNCIV_V3_AUDIT_DATABASE_URL -ErrorAction SilentlyContinue
    }
    else {
        $env:UNCIV_V3_AUDIT_DATABASE_URL = $previousAuditUrl
    }
    if ($containerCreated) {
        & docker rm --force $container *> $null
    }
    if ($volumeCreated) {
        & docker volume rm --force $volume *> $null
    }
    if (Test-Path -LiteralPath $certificateRoot) {
        $resolvedCertificateRoot = [IO.Path]::GetFullPath($certificateRoot)
        $expectedPrefix = $temporaryBase.TrimEnd(
            [IO.Path]::DirectorySeparatorChar
        ) + [IO.Path]::DirectorySeparatorChar
        if (
            -not $resolvedCertificateRoot.StartsWith(
                $expectedPrefix,
                [StringComparison]::OrdinalIgnoreCase
            ) -or
            -not ([IO.Path]::GetFileName($resolvedCertificateRoot)).StartsWith(
                'unciv-v3-postgres-security-',
                [StringComparison]::Ordinal
            )
        ) {
            throw "refusing to remove unexpected certificate path"
        }
        Remove-Item -LiteralPath $certificateRoot -Recurse -Force
    }
}
