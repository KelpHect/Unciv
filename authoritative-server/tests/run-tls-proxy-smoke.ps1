[CmdletBinding()]
param(
    [int]$UpstreamPort = 13001,
    [int]$HttpPort = 18080,
    [int]$HttpsPort = 18443
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$temporaryRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$outputDirectory = Join-Path $temporaryRoot (
    'unciv-v3-tls-smoke-' + [guid]::NewGuid().ToString('N')
)
$containerName = 'unciv-v3-tls-smoke-' + [guid]::NewGuid().ToString('N')
$caddyImage = 'caddy@sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648'
$upstream = $null

try {
    New-Item -ItemType Directory -Path $outputDirectory | Out-Null
    $upstream = Start-Process -FilePath 'python' -ArgumentList (
        (Join-Path $PSScriptRoot 'tls_echo_server.py'),
        '--port',
        $UpstreamPort
    ) -RedirectStandardOutput (
        Join-Path $outputDirectory 'upstream.out'
    ) -RedirectStandardError (
        Join-Path $outputDirectory 'upstream.err'
    ) -WindowStyle Hidden -PassThru

    & docker run --detach --name $containerName `
        --publish "127.0.0.1:${HttpPort}:80" `
        --publish "127.0.0.1:${HttpsPort}:443" `
        --env UNCIV_V3_DOMAIN=localhost `
        --env UNCIV_V3_ACME_EMAIL=test@example.com `
        --env "UNCIV_V3_UPSTREAM=host.docker.internal:${UpstreamPort}" `
        --volume (
            (Join-Path $repositoryRoot 'authoritative-server\caddy\Caddyfile') +
            ':/etc/caddy/Caddyfile:ro'
        ) `
        $caddyImage caddy run --config /etc/caddy/Caddyfile --adapter caddyfile |
        Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Pinned Caddy TLS container failed to start'
    }

    $response = $null
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        Start-Sleep -Milliseconds 500
        try {
            $response = Invoke-WebRequest -Uri (
                "https://localhost:$HttpsPort/"
            ) -Headers @{
                'X-Forwarded-For' = '203.0.113.99'
                'Forwarded' = 'for=198.51.100.7'
                'X-Real-IP' = '192.0.2.8'
            } -SkipCertificateCheck -TimeoutSec 3
            break
        }
        catch {
            continue
        }
    }
    if ($null -eq $response) {
        & docker logs $containerName
        throw 'Pinned Caddy TLS endpoint did not become ready'
    }

    $observed = $response.Content | ConvertFrom-Json
    $forwardedAddress = $null
    if (
        -not [Net.IPAddress]::TryParse(
            $observed.x_forwarded_for,
            [ref]$forwardedAddress
        ) -or
        $observed.x_forwarded_for -eq '203.0.113.99' -or
        $null -ne $observed.forwarded -or
        $null -ne $observed.x_real_ip -or
        $observed.x_forwarded_proto -ne 'https'
    ) {
        throw 'Caddy forwarded an ambiguous or client-spoofed network identity'
    }
    if (
        $response.Headers['Strict-Transport-Security'] -ne 'max-age=31536000' -or
        $response.Headers.ContainsKey('Server')
    ) {
        throw 'TLS response headers do not match the hardened policy'
    }

    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.AllowAutoRedirect = $false
    $client = [Net.Http.HttpClient]::new($handler)
    try {
        $redirect = $client.GetAsync(
            "http://localhost:$HttpPort/healthz"
        ).GetAwaiter().GetResult()
        $location = $redirect.Headers.Location.ToString()
        if (
            [int]$redirect.StatusCode -ne 308 -or
            -not $location.StartsWith('https://')
        ) {
            throw 'Plain HTTP was not redirected to HTTPS'
        }
    }
    finally {
        $client.Dispose()
        $handler.Dispose()
    }

    [pscustomobject]@{
        tls = 'passed'
        hsts = $response.Headers['Strict-Transport-Security']
        server_header_absent = -not $response.Headers.ContainsKey('Server')
        spoofed_forwarding_removed = $true
        forwarded_address = $observed.x_forwarded_for
        http_redirect = [int]$redirect.StatusCode
        caddy_image = $caddyImage
    } | ConvertTo-Json -Compress
}
finally {
    & docker rm --force $containerName 2>$null | Out-Null
    if ($null -ne $upstream -and -not $upstream.HasExited) {
        Stop-Process -Id $upstream.Id -Force
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
                'unciv-v3-tls-smoke-',
                [System.StringComparison]::Ordinal
            )
        ) {
            throw "Refusing to remove unexpected TLS smoke path: $resolvedOutput"
        }
        Remove-Item -LiteralPath $outputDirectory -Recurse -Force
    }
}
