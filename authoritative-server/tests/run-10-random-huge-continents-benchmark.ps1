<#
.SYNOPSIS
  Full all-AI benchmark: 10 random civilizations on a Huge Continents map,
  6 city-states, no humans. The owner is a spectator who calls
  advance-ai-turn once per AI civilization to step the match.

.DESCRIPTION
  Creates a Huge Continents lobby with 10 major civilizations (all AI, all
  random nations), 6 city-states, and 0 human slots. Every non-hidden victory
  type is enabled (Domination, Scientific, Cultural, Diplomatic) so the match
  ends through whichever path the AI reaches first. The owner account holds
  the spectator membership created for 0-human lobbies and drives the game
  with one advance-ai-turn command per AI civilization per round; the turn
  number advances after the 10th AI move. Eliminations and victory are
  tracked through the spectator projection. Every round is benchmarked and
  appended to a CSV for crash-safe results.
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = "http://127.0.0.1:13000",
    # Driver-loop cap in turns. The game's own max_turns is always 1500 (the
    # API maximum), but with Domination-only victory types the Time-victory
    # milestone is never enabled, so the engine keeps playing past turn 1500
    # until a real victory. A high value here means "no practical turn limit".
    [int]$MaxTurns = 1500,
    [string[]]$VictoryTypes = @("Domination", "Scientific", "Cultural", "Diplomatic")
)

$ErrorActionPreference = 'Stop'

$AiCount = 10
$CityStates = 6
$AiDifficulty = "Chieftain"
$VictoryJson = ($VictoryTypes | ForEach-Object { "`"$_`"" }) -join ","

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
            throw "API $Method $Path -> $([int]$resp.StatusCode)`: $errBody"
        }
        throw
    }
}

Write-Host "=== 10-Random Huge Continents All-AI Benchmark ===" -ForegroundColor Cyan
Write-Host "Map: Huge Continents | 10 random AI civs | 0 humans | 6 city-states | Quick"
Write-Host "Victory types: $($VictoryTypes -join ', ')"
Write-Host "Driver loop max turns: $MaxTurns (game max_turns is 1500; ignored when Time victory is not enabled)"
Write-Host ""

# 1. Register / login owner (spectator)
$suffix = Get-Random -Maximum 999999
$regBody = "{`"username`":`"bench10-$suffix`",`"password`":`"correct horse battery staple`"}"
$registered = $false
for ($attempt = 0; $attempt -lt 6 -and -not $registered; $attempt++) {
    try {
        Invoke-Api -Method POST -Path "/api/v3/auth/register" -Body $regBody | Out-Null
        $registered = $true
    } catch {
        if ($_.Exception.Message -match "rate_limited") {
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

$resultsDir = Join-Path $PSScriptRoot "results"
New-Item -ItemType Directory -Force -Path $resultsDir | Out-Null
$csvPath = Join-Path $resultsDir ("benchmark-10random-huge-continents-{0:yyyyMMdd-HHmmss}.csv" -f (Get-Date))
"turn,round_ms,ai_avg_ms,ai_min_ms,ai_max_ms,revision,alive_civs,current_player" | Set-Content $csvPath

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

    for ($i = 0; $i -lt $AiCount; $i++) {
        $advStart = Get-Date
        $cmdId = [guid]::NewGuid().ToString()
        $body = "{`"command_id`":`"$cmdId`",`"expected_revision`":$roundRev,`"client_observed_state_hash`":`"$roundHash`"}"
        try {
            $resp = Invoke-Api -Method POST -Path "/api/v3/games/$gameId/commands/advance-ai-turn" -Body $body -Token $token
            $advMs = [int]((Get-Date) - $advStart).TotalMilliseconds
            $advanceTimes += $advMs
            $roundRev = $resp.committed_revision
        } catch {
            $advMs = [int]((Get-Date) - $advStart).TotalMilliseconds
            $advanceTimes += $advMs
            $roundError = $_.Exception.Message
            Write-Host "  advance $i failed after turn $turn : $roundError"
            break
        }
    }

    $roundMs = [int]((Get-Date) - $roundStart).TotalMilliseconds
    $roundCount++

    # Fetch projection to learn the new turn / victory / eliminations
    try {
        $proj = Invoke-Api -Method GET -Path "/api/v3/games/$gameId/spectator-projection" -Token $token
        $errorCount = 0
    } catch {
        $errorCount++
        Write-Host "Turn $turn : projection error: $($_.Exception.Message)"
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

    "{0},{1},{2},{3},{4},{5},{6},{7}" -f $turn, $roundMs, $aiAvg, $aiMin, $aiMax, $roundRev, $aliveCount, $currentCiv | Add-Content $csvPath

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
Write-Host "=== BENCHMARK COMPLETE ===" -ForegroundColor Green
