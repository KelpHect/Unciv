<#
.SYNOPSIS
  Full all-AI benchmark: 10 random civilizations on a Huge Continents map,
  6 city-states, no humans. The owner is a spectator who calls
  advance-ai-turn once per AI civilization to step the match.

.DESCRIPTION
  Creates a Huge Continents lobby with 10 major civilizations (all AI, all
  random nations), 6 city-states, and 0 human slots. Victory types are selected
  by -VictoryTypes (Domination by default), so the benchmark can run without a
  practical Time-victory cutoff. The owner account holds
  the spectator membership created for 0-human lobbies and drives the game
  with one advance-ai-turn command per AI civilization per round; the turn
  number advances after the 10th AI move. Eliminations and victory are
  tracked through the spectator projection. Every round is benchmarked and
  appended to a CSV for crash-safe results. A matching NDJSON telemetry file
  records retryable HTTP failures, stale conflicts, projection failures, and
  recovered idempotent requests without storing bearer tokens or response
  bodies.
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = "http://127.0.0.1:13000",
    # Driver-loop cap in turns. The game's own max_turns is always 1500 (the
    # API maximum), but with Domination-only victory types the Time-victory
    # milestone is never enabled, so the engine keeps playing past turn 1500
    # until a real victory. A high value here means "no practical turn limit".
    [int]$MaxTurns = 100000,
    [string[]]$VictoryTypes = @("Domination"),
    [ValidateNotNullOrEmpty()]
    [string]$AiDifficulty = "Deity",
    [int]$MetricsPort = 0,
    [string]$DatabaseContainer = "",
    [int]$ResourceSampleEveryRounds = 10
)

$ErrorActionPreference = 'Stop'

$AiCount = 10
$CityStates = 6
$VictoryJson = ($VictoryTypes | ForEach-Object { "`"$_`"" }) -join ","

$resultsDir = Join-Path $PSScriptRoot "results"
New-Item -ItemType Directory -Force -Path $resultsDir | Out-Null
$runStamp = Get-Date
$csvPath = Join-Path $resultsDir ("benchmark-10random-huge-continents-{0:yyyyMMdd-HHmmss}.csv" -f $runStamp)
$telemetryPath = [System.IO.Path]::ChangeExtension($csvPath, ".ndjson")
$script:BenchmarkTelemetryPath = $telemetryPath

function Write-BenchmarkTelemetry {
    param(
        [string]$Event,
        [string]$Phase,
        [int]$Turn = 0,
        [int]$Round = 0,
        [int]$Attempt = 1,
        [int]$Status = 0,
        [string]$Code = "",
        [int]$ElapsedMs = 0,
        [string]$Message = ""
    )
    $record = [ordered]@{
        timestamp = (Get-Date).ToUniversalTime().ToString("o")
        event = $Event
        phase = $Phase
        turn = $Turn
        round = $Round
        attempt = $Attempt
        status = $Status
        code = $Code
        elapsed_ms = $ElapsedMs
        message = $Message
    }
    ($record | ConvertTo-Json -Compress) | Add-Content -Path $script:BenchmarkTelemetryPath
}

function Get-ApiErrorStatus {
    param($ErrorRecord)
    $value = $ErrorRecord.Exception.Data["status"]
    if ($null -ne $value) { return [int]$value }
    return 0
}

function Get-ApiErrorCode {
    param($ErrorRecord)
    $body = $ErrorRecord.Exception.Data["body"]
    if ($body) {
        try {
            $parsed = $body | ConvertFrom-Json
            if ($parsed.code) { return [string]$parsed.code }
        } catch { }
    }
    switch (Get-ApiErrorStatus $ErrorRecord) {
        409 { return "conflict" }
        429 { return "rate_limited" }
        502 { return "bad_gateway" }
        503 { return "service_unavailable" }
        504 { return "gateway_timeout" }
        default { return "http_error" }
    }
}

function Add-BenchmarkMetric {
    param([hashtable]$Metrics, [string]$Name, [int]$Amount = 1)
    if ($null -ne $Metrics) {
        $Metrics[$Name] = [int]($Metrics[$Name] + $Amount)
    }
}

function Invoke-Api {
    param([string]$Method, [string]$Path, [string]$Body, [string]$Token)
    $uri = "$BaseUrl$Path"
    $headers = @{}
    if ($Body) { $headers["Content-Type"] = "application/json" }
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }
    try {
        if ($Body) {
            $response = Invoke-WebRequest -Uri $uri -Method $Method -Headers $headers -Body $Body -UseBasicParsing -ErrorAction Stop
        } else {
            $response = Invoke-WebRequest -Uri $uri -Method $Method -Headers $headers -UseBasicParsing -ErrorAction Stop
        }
        if ($response.Content -and $response.Content.Trim() -ne "") {
            return $response.Content | ConvertFrom-Json
        }
        return $null
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            try {
                $reader = [System.IO.StreamReader]::new($resp.GetResponseStream())
                $errBody = $reader.ReadToEnd()
                $reader.Close()
            } catch { $errBody = "" }
            $apiError = [System.InvalidOperationException]::new(
                "API $Method $Path -> $([int]$resp.StatusCode)"
            )
            $apiError.Data["status"] = [int]$resp.StatusCode
            $apiError.Data["body"] = $errBody
            $retryAfter = $resp.Headers["Retry-After"]
            if ($retryAfter) { $apiError.Data["retry_after"] = $retryAfter }
            throw $apiError
        }
        throw
    }
}

function Write-BenchmarkResourceSample {
    param(
        [int]$Turn,
        [int]$Round
    )
    if ($MetricsPort -le 0 -and [string]::IsNullOrWhiteSpace($DatabaseContainer)) {
        return
    }
    $details = [ordered]@{}
    if ($MetricsPort -gt 0) {
        try {
            $metricsResponse = Invoke-WebRequest -Uri "http://127.0.0.1:$MetricsPort/metrics" -UseBasicParsing -TimeoutSec 5
            $metricsText = $metricsResponse.Content
            $details.metrics_bytes = $metricsText.Length
            foreach ($metric in @("unciv_v3_http_requests_total", "unciv_v3_commands_committed_total", "unciv_v3_worker_failures_total")) {
                $match = [regex]::Match($metricsText, "(?m)^$metric(?:\\{[^}]*\\})?\\s+([0-9.eE+\\-]+)$")
                if ($match.Success) {
                    $details[$metric] = $match.Groups[1].Value
                }
            }
        } catch {
            $details.metrics_error = $_.Exception.GetType().Name
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($DatabaseContainer)) {
        try {
            $storage = & docker exec $DatabaseContainer psql -U unciv_authoritative -d unciv_authoritative -Atc "select pg_database_size(current_database()), pg_total_relation_size('game_snapshot_blobs')" 2>$null
            $parts = ($storage -join '').Trim() -split '\\|'
            if ($parts.Count -eq 2) {
                $details.database_bytes = [int64]$parts[0]
                $details.snapshot_blob_bytes = [int64]$parts[1]
            }
        } catch {
            $details.storage_error = $_.Exception.GetType().Name
        }
    }
    Write-BenchmarkTelemetry -Event "resource_sample" -Phase "resources" -Turn $Turn -Round $Round -Message (($details | ConvertTo-Json -Compress))
}

function Invoke-ApiWithRetry {
    param(
        [string]$Method,
        [string]$Path,
        [string]$Body,
        [string]$Token,
        [string]$Phase,
        [int]$Turn = 0,
        [int]$Round = 0,
        [hashtable]$Metrics = $null,
        [int]$MaxAttempts = 5,
        [int[]]$RetryStatuses = @(429, 502, 503, 504)
    )
    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        $started = Get-Date
        try {
            $result = Invoke-Api -Method $Method -Path $Path -Body $Body -Token $Token
            $elapsed = [int]((Get-Date) - $started).TotalMilliseconds
            if ($attempt -gt 1) {
                Add-BenchmarkMetric $Metrics "recovered_requests"
                Write-BenchmarkTelemetry -Event "request_recovered" -Phase $Phase -Turn $Turn -Round $Round -Attempt $attempt -ElapsedMs $elapsed -Message "same idempotent request succeeded after transient failure"
            }
            return $result
        } catch {
            $status = Get-ApiErrorStatus $_
            $code = Get-ApiErrorCode $_
            $elapsed = [int]((Get-Date) - $started).TotalMilliseconds
            Add-BenchmarkMetric $Metrics "api_errors"
            if ($status -eq 429) { Add-BenchmarkMetric $Metrics "http_429" }
            if ($status -eq 409) { Add-BenchmarkMetric $Metrics "http_409" }
            if ($status -ge 500 -and $status -le 599) { Add-BenchmarkMetric $Metrics "http_5xx" }
            $retryable = $RetryStatuses -contains $status
            if ($retryable -and $attempt -lt $MaxAttempts) {
                Add-BenchmarkMetric $Metrics "transient_retries"
                Write-BenchmarkTelemetry -Event "request_retry" -Phase $Phase -Turn $Turn -Round $Round -Attempt $attempt -Status $status -Code $code -ElapsedMs $elapsed -Message "retrying idempotent request"
                $retryAfter = $_.Exception.Data["retry_after"]
                $delayMs = if ($retryAfter -as [int]) {
                    [math]::Min(10000, [int]$retryAfter * 1000)
                } else {
                    [math]::Min(5000, 250 * [math]::Pow(2, $attempt - 1)) + (Get-Random -Maximum 101)
                }
                Start-Sleep -Milliseconds ([int]$delayMs)
                continue
            }
            Write-BenchmarkTelemetry -Event "request_failed" -Phase $Phase -Turn $Turn -Round $Round -Attempt $attempt -Status $status -Code $code -ElapsedMs $elapsed -Message "request was not retried or retry budget was exhausted"
            throw
        }
    }
    throw "API retry loop exhausted for $Method $Path"
}

Write-BenchmarkTelemetry -Event "run_started" -Phase "setup" -Message "Huge Continents, 10 random AI, 6 city-states, $AiDifficulty difficulty, Domination-only"

Write-Host "=== 10-Random Huge Continents All-AI Benchmark ===" -ForegroundColor Cyan
Write-Host "Map: Huge Continents | 10 random AI civs | 0 humans | 6 city-states | Quick | Difficulty: $AiDifficulty"
Write-Host "Victory types: $($VictoryTypes -join ', ')"
Write-Host "Driver loop max turns: $MaxTurns (no practical limit; game max_turns is 1500 and is ignored when Time victory is not enabled)"
if ($MetricsPort -gt 0 -or -not [string]::IsNullOrWhiteSpace($DatabaseContainer)) {
    Write-Host "Resource telemetry: metrics=$MetricsPort database_container=$DatabaseContainer every $ResourceSampleEveryRounds rounds"
}
Write-Host ""

# 1. Register / login owner (spectator)
$suffix = Get-Random -Maximum 999999
$regBody = "{`"username`":`"bench10-$suffix`",`"password`":`"correct horse battery staple`"}"
$registered = $false
for ($attempt = 0; $attempt -lt 6 -and -not $registered; $attempt++) {
    try {
        Invoke-ApiWithRetry -Method POST -Path "/api/v3/auth/register" -Body $regBody -Phase "register" -MaxAttempts 6 -RetryStatuses @(429) | Out-Null
        $registered = $true
    } catch {
        if ($_.Exception.Message -match "rate_limited|HTTP 429|-> 429") {
            Write-Host "Rate limited on registration, waiting 10s..."
            Start-Sleep -Seconds 10
        } else { throw }
    }
}
$login = Invoke-Api -Method POST -Path "/api/v3/auth/login" -Body $regBody
$token = $login.session_token
Write-Host "Owner account: bench10-$suffix"

# 2. Get manifest
$manifests = Invoke-Api -Method GET -Path "/api/v3/ruleset-manifests" -Token $token
$manifestHash = $manifests.manifests[0].manifest_hash
Write-Host "Manifest: $manifestHash"

# 3. Create 10-random-AI game: Huge Continents, 6 city-states, 0 humans
$opId = [guid]::NewGuid().ToString()
# Blank civilization_id = the engine draws a random nation for that seat.
$aiArray = @()
for ($i = 0; $i -lt $AiCount; $i++) {
    $aiArray += "{`"civilization_id`":`"`",`"difficulty`":`"$AiDifficulty`",`"personality`":`"`"}"
}
$aiJson = $aiArray -join ","
$createJson = @"
{"operation_id":"$opId","ruleset_manifest_hash":"$manifestHash","display_name":"10-Random Huge Continents All-AI","human_slots":0,"password":null,"available_civilizations":[],"setup":{"owner_civilization_id":"","difficulty":"$AiDifficulty","speed":"Quick","starting_era":"Ancient era","victory_types":[$VictoryJson],"major_civilizations":$AiCount,"city_states":$CityStates,"max_turns":1500,"map_type":"small_continents","map_shape":"hexagonal","map_size":"huge","map_resources":"default","barbarians":"disabled","one_city_challenge":false,"nuclear_weapons_enabled":true,"espionage_enabled":false,"no_start_bias":false,"shuffle_player_order":false,"no_city_razing":false,"world_wrap":false,"strategic_balance":false,"legendary_start":false,"no_ruins":true,"no_natural_wonders":true,"ai_civilizations":[$aiJson]}}
"@

Write-Host "Creating 10-AI Huge Continents lobby (6 city-states)..."
$created = Invoke-Api -Method POST -Path "/api/v3/games" -Body $createJson -Token $token
$gameId = $created.game_id
Write-Host "Game: $gameId (rev $($created.committed_revision))"

# 4. Start the lobby (0 humans = no ready needed)
$started = $null
foreach ($tryRev in @(0, 1)) {
    try {
        $startBody = "{`"expected_lobby_revision`":$tryRev}"
        $started = Invoke-Api -Method POST -Path "/api/v3/lobbies/$gameId/start" -Body $startBody -Token $token
        Write-Host "Match started (rev $tryRev)! lobby_rev=$($started.lobby_revision) head_rev=$($started.committed_revision)"
        break
    } catch {
        Write-Host "Start with rev $tryRev failed: $($_.Exception.Message)"
    }
}
if (-not $started) { throw "Could not start the lobby" }

# 5. Play rounds (one round = $AiCount advance-ai-turn calls)
$rounds = @()
$eliminations = @()
$matchStart = Get-Date
$victory = $false
$victoryType = $null
$winner = $null
$victoryTurn = 0
$prevAlive = @{}

# Initial spectator projection for alive tracking
$proj = Invoke-Api -Method GET -Path "/api/v3/games/$gameId/spectator-projection" -Token $token
foreach ($civ in $proj.projection.majorCivilizations) {
    $prevAlive[$civ.civilizationId] = -not $civ.defeated
}
Write-Host "Initial majors: $($prevAlive.Count) ($((($prevAlive.Values | Where-Object { $_ })).Count) alive)"
Write-Host ""

"turn,round_ms,ai_avg_ms,ai_min_ms,ai_max_ms,revision,alive_civs,current_player,transient_retries,recovered_requests,api_errors,http_429,http_409,http_5xx,projection_errors,advance_errors,error_code" | Set-Content $csvPath

Write-Host "Turn  | Round(ms) | Avg/AI(ms) | Rev    | Alive | Notes"
Write-Host "------|-----------|------------|--------|-------|-----"

$turn = $proj.projection.turn
$roundCount = 0
$errorCount = 0
$maxErrors = 10

while ($turn -lt $MaxTurns -and -not $victory) {
    $roundStart = Get-Date
    $roundRev = $proj.committed_revision
    $roundHash = $proj.canonical_state_hash
    $advanceTimes = @()
    $roundError = $null
    $roundErrorCode = ""
    $metrics = @{
        transient_retries = 0
        recovered_requests = 0
        api_errors = 0
        http_429 = 0
        http_409 = 0
        http_5xx = 0
        projection_errors = 0
        advance_errors = 0
    }

    for ($i = 0; $i -lt $AiCount; $i++) {
        $advStart = Get-Date
        $cmdId = [guid]::NewGuid().ToString()
        $body = "{`"command_id`":`"$cmdId`",`"expected_revision`":$roundRev,`"client_observed_state_hash`":`"$roundHash`"}"
        try {
            $resp = Invoke-ApiWithRetry -Method POST -Path "/api/v3/games/$gameId/commands/advance-ai-turn" -Body $body -Token $token -Phase "advance_ai" -Turn $turn -Round $roundCount -Metrics $metrics
            $advMs = [int]((Get-Date) - $advStart).TotalMilliseconds
            $advanceTimes += $advMs
            $roundRev = $resp.committed_revision
            $roundHash = $resp.canonical_state_hash
        } catch {
            $advMs = [int]((Get-Date) - $advStart).TotalMilliseconds
            $advanceTimes += $advMs
            $roundError = $_.Exception.Message
            $roundErrorCode = Get-ApiErrorCode $_
            $metrics.advance_errors++
            Write-Host "  advance $i failed after turn $turn : $roundError ($roundErrorCode)"
            break
        }
    }

    $roundMs = [int]((Get-Date) - $roundStart).TotalMilliseconds
    $roundCount++

    # Fetch projection to learn the new turn / victory / eliminations
    try {
        $proj = Invoke-ApiWithRetry -Method GET -Path "/api/v3/games/$gameId/spectator-projection" -Token $token -Phase "projection" -Turn $turn -Round $roundCount -Metrics $metrics
        $errorCount = 0
    } catch {
        $errorCount++
        $metrics.projection_errors++
        $roundErrorCode = Get-ApiErrorCode $_
        Write-Host "Turn $turn : projection error: $($_.Exception.Message) ($roundErrorCode)"
        if ($errorCount -ge $maxErrors) { throw "Too many consecutive projection errors" }
        Start-Sleep -Seconds 2
        continue
    }

    $newTurn = $proj.projection.turn
    $currentCiv = $proj.projection.currentPlayerCivilizationId

    # Victory check
    if ($proj.projection.victory) {
        $victory = $true
        $victoryType = $proj.projection.victory.victoryType
        $winner = $proj.projection.victory.winningCivilizationId
        $victoryTurn = $newTurn
        $turn = $newTurn
        break
    }

    # Eliminations via defeated flags
    $aliveCount = 0
    $newElims = @()
    foreach ($civ in $proj.projection.majorCivilizations) {
        $isAlive = -not $civ.defeated
        if ($isAlive) { $aliveCount++ }
        if ($prevAlive.ContainsKey($civ.civilizationId) -and $prevAlive[$civ.civilizationId] -and -not $isAlive) {
            $newElims += $civ.civilizationId
            $eliminations += [PSCustomObject]@{ Turn = $newTurn; Civilization = $civ.civilizationId }
            Write-Host ("  >>> ELIMINATION: {0} defeated on turn {1}" -f $civ.civilizationId, $newTurn) -ForegroundColor Red
        }
        $prevAlive[$civ.civilizationId] = $isAlive
    }

    $turn = $newTurn
    $aiAvg = if ($advanceTimes.Count -gt 0) { [int](($advanceTimes | Measure-Object -Average).Average) } else { 0 }
    $aiMin = if ($advanceTimes.Count -gt 0) { ($advanceTimes | Measure-Object -Minimum).Minimum } else { 0 }
    $aiMax = if ($advanceTimes.Count -gt 0) { ($advanceTimes | Measure-Object -Maximum).Maximum } else { 0 }

    $rounds += [PSCustomObject]@{
        Turn = $turn
        RoundMs = $roundMs
        AiAvgMs = $aiAvg
        AiMinMs = $aiMin
        AiMaxMs = $aiMax
        Revision = $roundRev
        Alive = $aliveCount
        Current = $currentCiv
        Error = $roundError
    }

    "{0},{1},{2},{3},{4},{5},{6},{7},{8},{9},{10},{11},{12},{13},{14},{15},{16}" -f $turn, $roundMs, $aiAvg, $aiMin, $aiMax, $roundRev, $aliveCount, $currentCiv, $metrics.transient_retries, $metrics.recovered_requests, $metrics.api_errors, $metrics.http_429, $metrics.http_409, $metrics.http_5xx, $metrics.projection_errors, $metrics.advance_errors, $roundErrorCode | Add-Content $csvPath
    Write-BenchmarkTelemetry -Event "round_complete" -Phase "round" -Turn $turn -Round $roundCount -Message (([ordered]@{ revision = $roundRev; alive_civs = $aliveCount; transient_retries = $metrics.transient_retries; recovered_requests = $metrics.recovered_requests; api_errors = $metrics.api_errors; projection_errors = $metrics.projection_errors; advance_errors = $metrics.advance_errors; error_code = $roundErrorCode } | ConvertTo-Json -Compress))
    if ($ResourceSampleEveryRounds -gt 0 -and ($roundCount % $ResourceSampleEveryRounds) -eq 0) {
        Write-BenchmarkResourceSample -Turn $turn -Round $roundCount
    }

    $shouldPrint = ($turn -le 10) -or ($turn % 10 -eq 0) -or ($turn -gt $MaxTurns - 10)
    if ($shouldPrint) {
        $note = if ($roundError) { "advance error: $roundError" } else { "" }
        Write-Host ("{0,5} | {1,9} | {2,10} | {3,6} | {4,5} | {5}" -f $turn, $roundMs, $aiAvg, $roundRev, $aliveCount, $note)
    }

    if ($roundError) {
        # A rejected advance after a completed round usually means the match ended.
        $errorCount++
        if ($errorCount -ge $maxErrors) { throw "Too many consecutive advance errors: $roundError" }
        Start-Sleep -Milliseconds 500
    }
}

$matchEnd = Get-Date
$matchElapsed = [math]::Round(($matchEnd - $matchStart).TotalSeconds, 1)

# Final projection for status
try {
    $final = Invoke-Api -Method GET -Path "/api/v3/games/$gameId/spectator-projection" -Token $token
} catch {
    $final = $proj
}

Write-Host ""
Write-Host "=== Match Summary ===" -ForegroundColor Cyan
Write-Host "Total turns: $turn"
Write-Host "Total time: $matchElapsed seconds ($([math]::Round($matchElapsed / 60, 1)) min)"
Write-Host "Rounds benchmarked: $($rounds.Count)"
Write-Host "Final revision: $($final.committed_revision)"
Write-Host "Results CSV: $csvPath"

if ($victory) {
    Write-Host "Victory: $winner won by $victoryType on turn $victoryTurn" -ForegroundColor Green
} else {
    Write-Host "No victory (hit $MaxTurns turn limit or benchmark loop end)" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "=== Eliminations ===" -ForegroundColor Cyan
if ($eliminations.Count -gt 0) {
    foreach ($e in $eliminations) { Write-Host "  Turn $($e.Turn): $($e.Civilization) eliminated" }
} else {
    Write-Host "  No eliminations recorded"
}

Write-Host ""
Write-Host "=== Final Civilization Status ===" -ForegroundColor Cyan
foreach ($civ in $final.projection.majorCivilizations) {
    $status = if ($civ.defeated) { "DEFEATED" } else { "ALIVE" }
    $color = if ($civ.defeated) { "Red" } else { "Green" }
    Write-Host ("  {0,-20} {1}" -f $civ.displayName, $status) -ForegroundColor $color
}

Write-Host ""
Write-Host "=== Performance (full rounds of $AiCount AI) ===" -ForegroundColor Cyan
if ($rounds.Count -gt 0) {
    $roundMs = $rounds | ForEach-Object { $_.RoundMs }
    $aiMs = @()
    foreach ($r in $rounds) { $aiMs += $r.AiAvgMs }
    $sorted = $roundMs | Sort-Object
    $p50 = $sorted[[int]($sorted.Count * 0.5)]
    $p90 = $sorted[[int]($sorted.Count * 0.9)]
    $p99 = $sorted[[int]($sorted.Count * 0.99)]
    $avg = [math]::Round(($roundMs | Measure-Object -Average).Average, 1)
    $min = ($roundMs | Measure-Object -Minimum).Minimum
    $max = ($roundMs | Measure-Object -Maximum).Maximum
    Write-Host "Round (10 AI) latency ms: p50=$p50 p90=$p90 p99=$p99 avg=$avg min=$min max=$max"
    $aiSorted = $aiMs | Sort-Object
    $aiP50 = $aiSorted[[int]($aiSorted.Count * 0.5)]
    $aiAvg = [math]::Round(($aiMs | Measure-Object -Average).Average, 1)
    Write-Host "Per-AI move latency ms:   p50=$aiP50 avg=$aiAvg"
    Write-Host "Total AI processing: $([math]::Round(($roundMs | Measure-Object -Sum).Sum / 1000.0, 1))s"
}

Write-Host ""
Write-Host "Game ID: $gameId"
Write-BenchmarkTelemetry -Event "run_complete" -Phase "summary" -Turn $turn -Message (([ordered]@{ victory = $victory; winner = $winner; victory_type = $victoryType; victory_turn = $victoryTurn; final_revision = $final.committed_revision; csv = $csvPath; telemetry = $telemetryPath } | ConvertTo-Json -Compress))
Write-Host "Telemetry NDJSON: $telemetryPath"
Write-Host "=== BENCHMARK COMPLETE ===" -ForegroundColor Green
