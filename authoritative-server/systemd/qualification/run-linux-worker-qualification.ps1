[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repositoryRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..\..')
$temporaryRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$context = Join-Path $temporaryRoot (
    'unciv-worker-qualification-' + [guid]::NewGuid().ToString('N')
)
$containerName = 'unciv-worker-qualification-' + [guid]::NewGuid().ToString('N')
$imageName = 'unciv-worker-systemd-qualification:local'

try {
    & (Join-Path $repositoryRoot 'gradlew.bat') ':server:authoritativeWorkerDist' `
        '--no-parallel' '--console=plain'
    if ($LASTEXITCODE -ne 0) {
        throw 'The packaged authoritative worker build failed.'
    }

    New-Item -ItemType Directory -Path $context | Out-Null
    Copy-Item (Join-Path $PSScriptRoot 'Dockerfile') $context
    Copy-Item (Join-Path $PSScriptRoot 'qualify-worker.sh') $context
    Copy-Item (Join-Path $PSScriptRoot 'worker-probe.py') $context
    Copy-Item (
        Join-Path $repositoryRoot 'server\build\libs\UncivAuthoritativeWorker.jar'
    ) $context
    Copy-Item (
        Join-Path $repositoryRoot 'authoritative-server\systemd\unciv-authoritative-worker.service'
    ) $context
    Copy-Item (
        Join-Path $repositoryRoot 'docs\operations\authoritative-worker-systemd.md'
    ) $context
    Copy-Item (Join-Path $repositoryRoot 'android\assets') (
        Join-Path $context 'assets'
    ) -Recurse

    & docker build --tag $imageName $context
    if ($LASTEXITCODE -ne 0) {
        throw 'The Linux qualification image build failed.'
    }

    & docker run --detach --privileged --cgroupns=private `
        --name $containerName --tmpfs '/run:rw,exec' --tmpfs /run/lock `
        --tmpfs '/tmp:rw,noexec,nosuid,nodev' $imageName
    if ($LASTEXITCODE -ne 0) {
        throw 'The Linux systemd qualification container failed to start.'
    }

    & docker exec $containerName /usr/local/libexec/unciv/qualify-worker.sh
    if ($LASTEXITCODE -ne 0) {
        & docker exec $containerName systemctl status `
            unciv-authoritative-worker.service --no-pager
        & docker exec $containerName journalctl `
            -u unciv-authoritative-worker.service --no-pager -n 120
        throw 'The live Linux worker qualification failed.'
    }
}
finally {
    & docker rm --force $containerName 2>$null | Out-Null
    if (Test-Path -LiteralPath $context) {
        $resolvedContext = [System.IO.Path]::GetFullPath($context)
        $expectedPrefix = $temporaryRoot.TrimEnd(
            [System.IO.Path]::DirectorySeparatorChar
        ) + [System.IO.Path]::DirectorySeparatorChar
        if (
            -not $resolvedContext.StartsWith(
                $expectedPrefix,
                [System.StringComparison]::OrdinalIgnoreCase
            ) -or
            -not ([System.IO.Path]::GetFileName($resolvedContext)).StartsWith(
                'unciv-worker-qualification-',
                [System.StringComparison]::Ordinal
            )
        ) {
            throw "Refusing to remove unexpected qualification path: $resolvedContext"
        }
        Remove-Item -LiteralPath $context -Recurse -Force
    }
}
