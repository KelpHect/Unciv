<#
.SYNOPSIS
  Verifies API-v3 session rotation and stale-token invalidation against a live server.
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = "http://127.0.0.1:13000"
)

$ErrorActionPreference = 'Stop'

function Invoke-RawApi {
    param(
        [string]$Method,
        [string]$Path,
        [string]$Body,
        [string]$Token
    )

    $headers = @{}
    if ($Token) { $headers['Authorization'] = "Bearer $Token" }
    $requestParameters = @{
        Uri = "$BaseUrl$Path"
        Method = $Method
        Headers = $headers
        SkipHttpErrorCheck = $true
        UseBasicParsing = $true
        TimeoutSec = 10
    }
    if ($Body) {
        $requestParameters['Body'] = $Body
        $requestParameters['ContentType'] = 'application/json'
    }
    $response = Invoke-WebRequest @requestParameters
    $json = $null
    if ($response.Content -and $response.Content.Trim()) {
        $json = $response.Content | ConvertFrom-Json
    }
    [pscustomobject]@{
        StatusCode = [int]$response.StatusCode
        Body = $json
    }
}

function Assert-Status {
    param([object]$Response, [int]$Expected, [string]$Operation)
    if ($Response.StatusCode -ne $Expected) {
        throw "$Operation returned HTTP $($Response.StatusCode), expected $Expected"
    }
}

$suffix = Get-Random -Maximum 999999
$credentials = "{`"username`":`"refresh-$suffix`",`"password`":`"correct horse battery staple`"}"

$registered = Invoke-RawApi POST "/api/v3/auth/register" $credentials $null
Assert-Status $registered 201 'register'

$login = Invoke-RawApi POST "/api/v3/auth/login" $credentials $null
Assert-Status $login 200 'login'
$firstToken = $login.Body.session_token
$firstRefreshToken = $login.Body.refresh_token
if ([string]::IsNullOrWhiteSpace($firstRefreshToken)) {
    throw 'login did not return a refresh token'
}

$refreshBody = "{`"refresh_token`":`"$firstRefreshToken`"}"
$firstRefresh = Invoke-RawApi POST "/api/v3/auth/refresh" $refreshBody $null
Assert-Status $firstRefresh 200 'first refresh'
$secondToken = $firstRefresh.Body.session_token
$secondRefreshToken = $firstRefresh.Body.refresh_token
if ([string]::IsNullOrWhiteSpace($secondToken) -or $secondToken -eq $firstToken) {
    throw 'first refresh did not rotate the session token'
}
if ([string]::IsNullOrWhiteSpace($secondRefreshToken) -or $secondRefreshToken -eq $firstRefreshToken) {
    throw 'first refresh did not rotate the refresh token'
}

$staleRead = Invoke-RawApi GET "/api/v3/ruleset-manifests" $null $firstToken
Assert-Status $staleRead 401 'stale-token read'

$currentRead = Invoke-RawApi GET "/api/v3/ruleset-manifests" $null $secondToken
Assert-Status $currentRead 200 'rotated-token read'

$secondRefreshBody = "{`"refresh_token`":`"$secondRefreshToken`"}"
$secondRefresh = Invoke-RawApi POST "/api/v3/auth/refresh" $secondRefreshBody $null
Assert-Status $secondRefresh 200 'second refresh'
$thirdToken = $secondRefresh.Body.session_token
if ([string]::IsNullOrWhiteSpace($thirdToken) -or $thirdToken -eq $secondToken) {
    throw 'second refresh did not rotate the session token'
}

$logout = Invoke-RawApi POST "/api/v3/auth/logout" $null $thirdToken
Assert-Status $logout 204 'logout'
$revokedRead = Invoke-RawApi GET "/api/v3/ruleset-manifests" $null $thirdToken
Assert-Status $revokedRead 401 'post-logout read'

[pscustomobject]@{
    username = "refresh-$suffix"
    first_refresh_rotated = $true
    stale_token_rejected = $true
    rotated_token_accepted = $true
    second_refresh_rotated = $true
    logout_invalidated_token = $true
} | ConvertTo-Json -Compress
