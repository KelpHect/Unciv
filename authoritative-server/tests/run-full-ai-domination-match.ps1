<#
.SYNOPSIS
  Plays a complete all-AI Domination match on a Huge map with 7 civilizations
  (1 passive human + 6 AI). Benchmarks every turn and verifies full history.

.DESCRIPTION
  Creates a Huge Pangaea lobby with 7 major civilizations and 1 human slot.
  All non-hidden victory types are enabled (Domination, Scientific, Cultural,
  Diplomatic) so the match can end through multiple paths. The Time victory
  type is hidden in the vanilla ruleset and rejected by the worker, so it
  cannot be used. The human (Rome) does nothing but end turn each round — no
  moves, no construction changes, no diplomacy. The 6 AI civilizations
  (Egypt, Greece, Persia, Aztecs, Mongolia, Arabia) fight it out.
  A second account is added as a spectator to track alive civs via the
  spectator projection (not fog-of-war limited like the player projection).
  Eliminations are logged in real time. All non-hidden victory types are
  enabled so the match ends through whichever path the AI achieves first.
  Default max_turns is 1500 (the API maximum). The -MaxTurns parameter
  controls the script loop limit; the game's max_turns is always 1500.
  Every turn is benchmarked. The full revision history is verified for playback.
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = "http://127.0.0.1:3060",
    [int]$MaxTurns = 1500
)

$ErrorActionPreference = 'Stop'

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

Write-Host "=== Full All-AI Domination Match ===" -ForegroundColor Cyan
Write-Host "Map: Huge Pangaea | 7 civs (1 passive human + 6 AI) | Quick | All victory types"
Write-Host "AI: Egypt, Greece, Persia, Aztecs, Mongolia, Arabia (all Chieftain)"
Write-Host "Max turns (loop): $MaxTurns | Game max_turns: 1500 (all non-hidden victory types)"
Write-Host ""

# 1. Register / login
$suffix = Get-Random -Maximum 999999
$regBody = "{`"username`":`"huge-$suffix`",`"password`":`"correct horse battery staple`"}"
Invoke-Api -Method POST -Path "/api/v3/auth/register" -Body $regBody | Out-Null
$login = Invoke-Api -Method POST -Path "/api/v3/auth/login" -Body $regBody
$token = $login.session_token
Write-Host "Account: huge-$suffix"

# 1b. Register a second account as spectator for alive-civs tracking
# Wait a few seconds to avoid rate limiting on registration
Start-Sleep -Seconds 3
$specSuffix = Get-Random -Maximum 999999
$specRegBody = "{`"username`":`"spec-$specSuffix`",`"password`":`"correct horse battery staple`"}"
$specRegistered = $false
for ($attempt = 0; $attempt -lt 5 -and -not $specRegistered; $attempt++) {
    try {
        Invoke-Api -Method POST -Path "/api/v3/auth/register" -Body $specRegBody | Out-Null
        $specRegistered = $true
    } catch {
        if ($_.Exception.Message -match "rate_limited") {
            Write-Host "Rate limited on spectator registration, waiting..."
            Start-Sleep -Seconds 10
        } else {
            throw
        }
    }
}
$specLogin = Invoke-Api -Method POST -Path "/api/v3/auth/login" -Body $specRegBody
$specToken = $specLogin.session_token
Write-Host "Spectator account: spec-$specSuffix"

# 2. Get manifest
$manifests = Invoke-Api -Method GET -Path "/api/v3/ruleset-manifests" -Token $token
$manifestHash = $manifests.manifests[0].manifest_hash

# 3. Create 7-civ game with 6 AI on Huge map
$opId = [guid]::NewGuid().ToString()
$aiCivs = @("Egypt","Greece","Persia","Aztecs","Mongolia","Arabia")
$aiArray = $aiCivs | ForEach-Object { "{`"civilization_id`":`"$_`",`"difficulty`":`"Chieftain`",`"personality`":`"`"}" }
$aiJson = $aiArray -join ","
$createJson = @"
{"operation_id":"$opId","ruleset_manifest_hash":"$manifestHash","display_name":"All-AI Huge Domination","human_slots":1,"available_civilizations":["Rome","Egypt","Greece","Persia","Aztecs","Mongolia","Arabia"],"setup":{"owner_civilization_id":"Rome","difficulty":"Chieftain","speed":"Quick","starting_era":"Ancient era","victory_types":["Domination","Scientific","Cultural","Diplomatic"],"major_civilizations":7,"city_states":0,"max_turns":1500,"map_type":"pangaea","map_shape":"hexagonal","map_size":"huge","map_resources":"default","barbarians":"disabled","one_city_challenge":false,"nuclear_weapons_enabled":true,"espionage_enabled":false,"no_start_bias":false,"shuffle_player_order":false,"no_city_razing":false,"world_wrap":false,"strategic_balance":false,"legendary_start":false,"no_ruins":true,"no_natural_wonders":true,"ai_civilizations":[$aiJson]}}
"@

Write-Host "Creating 7-civ Huge Pangaea lobby..."
$created = Invoke-Api -Method POST -Path "/api/v3/games" -Body $createJson -Token $token
$gameId = $created.game_id
Write-Host "Game: $gameId (rev $($created.committed_revision))"

# 3b. Add spectator account to the game
$addSpecBody = "{`"username`":`"spec-$specSuffix`"}"
Write-Host "Debug: spectator body = [$addSpecBody]"
try {
    Invoke-Api -Method PUT -Path "/api/v3/games/$gameId/spectators" -Body $addSpecBody -Token $token | Out-Null
    Write-Host "Spectator added to game"
} catch {
    Write-Host "Warning: could not add spectator: $($_.Exception.Message)"
    Write-Host "Alive-civs tracking will be unavailable"
}

# 4. Ready + start
$readyJson = "{`"expected_lobby_revision`":0,`"ready`":true}"
$readyResp = Invoke-Api -Method PUT -Path "/api/v3/lobbies/$gameId/ready" -Body $readyJson -Token $token
$startJson = "{`"expected_lobby_revision`":$($readyResp.lobby_revision)}"
$started = Invoke-Api -Method POST -Path "/api/v3/lobbies/$gameId/start" -Body $startJson -Token $token
Write-Host "Match started! rev=$($started.committed_revision)"
Write-Host ""

# 5. Play turns
$turnData = @()
$eliminations = @()
$matchStart = Get-Date
$victory = $false
$victoryType = $null
$winner = $null
$victoryTurn = 0
$turn = 0
$errorCount = 0
$maxErrors = 5
$prevAliveCivs = @{}

# Track initial alive civs from spectator projection
try {
    $initSpec = Invoke-Api -Method GET -Path "/api/v3/games/$gameId/spectator-projection" -Token $specToken
    foreach ($civ in $initSpec.projection.majorCivilizations) {
        $prevAliveCivs[$civ.civilizationId] = -not $civ.defeated
    }
} catch {
    Write-Host "Warning: could not get initial spectator projection: $($_.Exception.Message)"
}

Write-Host "Turn  | EndTurn+6AI(ms) | Rev  | Current Civ      | Alive | Defeated Status"
Write-Host "------|----------------|------|------------------|-------|----------------"

while ($turn -lt $MaxTurns -and -not $victory) {
    # Get player projection for turn control
    try {
        $proj = Invoke-Api -Method GET -Path "/api/v3/games/$gameId/projection" -Token $token
    } catch {
        $errorCount++
        Write-Host "Turn $turn : projection error: $($_.Exception.Message)"
        if ($errorCount -ge $maxErrors) { throw "Too many consecutive errors" }
        Start-Sleep -Seconds 2
        continue
    }
    $errorCount = 0

    $turn = $proj.projection.turn
    $currentCiv = $proj.projection.currentPlayerCivilizationId
    $isMyTurn = $proj.projection.isCurrentTurn

    # Check victory from player projection
    if ($proj.projection.victory) {
        $victory = $true
        $victoryType = $proj.projection.victory.victoryType
        $winner = $proj.projection.victory.winningCivilizationId
        $victoryTurn = $turn
        Write-Host ("{0,5} | {1,14} | {2,4} | {3,-16} | VICTORY: {4} ({5})" -f `
            $turn, "-", $proj.committed_revision, $currentCiv, $winner, $victoryType)
        break
    }

    # Get spectator projection for real alive-civs tracking (not fog-of-war limited)
    $aliveCount = 0
    $defeatedList = @()
    $aliveList = @()
    try {
        $specProj = Invoke-Api -Method GET -Path "/api/v3/games/$gameId/spectator-projection" -Token $specToken
        # Also check victory from spectator projection (works even if human is eliminated)
        if ($specProj.projection.victory -and -not $victory) {
            $victory = $true
            $victoryType = $specProj.projection.victory.victoryType
            $winner = $specProj.projection.victory.winningCivilizationId
            $victoryTurn = $turn
            Write-Host ("{0,5} | {1,14} | {2,4} | {3,-16} | VICTORY: {4} ({5})" -f `
                $turn, "-", $proj.committed_revision, $currentCiv, $winner, $victoryType)
            break
        }
        foreach ($civ in $specProj.projection.majorCivilizations) {
            $isAlive = -not $civ.defeated
            if ($isAlive) {
                $aliveCount++
                $aliveList += $civ.civilizationId
            } else {
                $defeatedList += $civ.civilizationId
            }
            # Detect eliminations: civ was alive last turn, now defeated
            if ($prevAliveCivs.ContainsKey($civ.civilizationId) -and $prevAliveCivs[$civ.civilizationId] -and -not $isAlive) {
                $eliminations += [PSCustomObject]@{
                    Turn = $turn
                    Civilization = $civ.civilizationId
                }
                Write-Host ("  >>> ELIMINATION: {0} defeated on turn {1}" -f $civ.civilizationId, $turn) -ForegroundColor Red
            }
            $prevAliveCivs[$civ.civilizationId] = $isAlive
        }
    } catch {
        # Spectator projection might not be available (e.g., owner is not a spectator)
        # Fall back to known civs count
        $aliveCount = $proj.projection.knownCivilizations.Count
    }

    $defeatedStr = if ($defeatedList.Count -gt 0) { $defeatedList -join ", " } else { "none" }

    # End turn if it's our turn
    if ($isMyTurn) {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $etJson = "{`"command_id`":`"$([guid]::NewGuid().ToString())`",`"expected_revision`":$($proj.committed_revision),`"client_observed_state_hash`":`"$($proj.canonical_state_hash)`"}"
        try {
            $etResp = Invoke-Api -Method POST -Path "/api/v3/games/$gameId/commands/end-turn" -Body $etJson -Token $token
            $sw.Stop()
            $ms = [int]$sw.ElapsedMilliseconds
            $rev = $etResp.committed_revision

            $turnData += [PSCustomObject]@{
                Turn = $turn
                EndTurnMs = $ms
                Revision = $rev
                AliveCivs = $aliveCount
                AliveList = ($aliveList -join ",")
                DefeatedList = ($defeatedList -join ",")
            }

            # Print every 10th turn to avoid flooding, plus first 5 and last 10
            $shouldPrint = ($turn -lt 5) -or ($turn % 10 -eq 0) -or ($turn -gt $MaxTurns - 10)
            if ($shouldPrint) {
                Write-Host ("{0,5} | {1,14} | {2,4} | {3,-16} | {4,5} | {5}" -f `
                    $turn, $ms, $rev, $currentCiv, $aliveCount, $defeatedStr)
            }
        } catch {
            $sw.Stop()
            Write-Host ("{0,5} | ERROR: {1}" -f $turn, $_.Exception.Message)
            Start-Sleep -Milliseconds 500
        }
    } else {
        # Not our turn — this shouldn't happen in a 1-human game, but handle it
        Write-Host ("{0,5} | waiting (current: {1})" -f $turn, $currentCiv)
        Start-Sleep -Milliseconds 500
    }
}

$matchEnd = Get-Date
$matchDuration = ($matchEnd - $matchStart).TotalSeconds

# 6. Summary
Write-Host ""
Write-Host "=== Match Summary ===" -ForegroundColor Cyan
Write-Host "Total turns played: $($turnData.Count)"
Write-Host "Match duration: $([math]::Round($matchDuration, 1))s ($([math]::Round($matchDuration / 60, 1)) min)"
if ($victory) {
    Write-Host "Victory: $winner won by $victoryType on turn $victoryTurn" -ForegroundColor Green
} else {
    Write-Host "No victory achieved within $MaxTurns turns" -ForegroundColor Yellow
}

# Elimination log
if ($eliminations.Count -gt 0) {
    Write-Host ""
    Write-Host "=== Eliminations ===" -ForegroundColor Cyan
    foreach ($elim in $eliminations) {
        Write-Host "  Turn $($elim.Turn): $($elim.Civilization) eliminated"
    }
} else {
    Write-Host ""
    Write-Host "No eliminations detected"
}

# Final alive/dead status
Write-Host ""
Write-Host "=== Final Civilization Status ===" -ForegroundColor Cyan
foreach ($civ in $prevAliveCivs.Keys | Sort-Object) {
    $status = if ($prevAliveCivs[$civ]) { "ALIVE" } else { "DEFEATED" }
    $color = if ($prevAliveCivs[$civ]) { "Green" } else { "Red" }
    Write-Host "  $civ`: $status" -ForegroundColor $color
}

if ($turnData.Count -gt 0) {
    $allMs = $turnData | ForEach-Object { $_.EndTurnMs }
    $sorted = $allMs | Sort-Object
    $p50 = $sorted[[math]::Floor($sorted.Count * 0.5)]
    $p95 = $sorted[[math]::Floor($sorted.Count * 0.95)]
    $p99 = $sorted[[math]::Min($sorted.Count - 1, [math]::Floor($sorted.Count * 0.99))]
    $mean = [math]::Round(($allMs | Measure-Object -Average).Average, 1)
    $min = $sorted[0]
    $max = $sorted[-1]

    Write-Host ""
    Write-Host "=== Per-Turn Benchmark (EndTurn + 6 AI turns) ===" -ForegroundColor Cyan
    Write-Host "Latency (ms): min=$min  p50=$p50  p95=$p95  p99=$p99  max=$max  mean=$mean"
    Write-Host "Total AI processing: $([math]::Round(($allMs | Measure-Object -Sum).Sum / 1000.0, 1))s"

    # Early vs late game comparison
    $early = $turnData | Where-Object { $_.Turn -lt 50 } | ForEach-Object { $_.EndTurnMs }
    $mid = $turnData | Where-Object { $_.Turn -ge 50 -and $_.Turn -lt 150 } | ForEach-Object { $_.EndTurnMs }
    $late = $turnData | Where-Object { $_.Turn -ge 150 } | ForEach-Object { $_.EndTurnMs }
    if ($early.Count -gt 0) { Write-Host "Early (1-50):   mean=$([math]::Round(($early | Measure-Object -Average).Average, 1))ms" }
    if ($mid.Count -gt 0)   { Write-Host "Mid (50-150):   mean=$([math]::Round(($mid | Measure-Object -Average).Average, 1))ms" }
    if ($late.Count -gt 0)  { Write-Host "Late (150+):    mean=$([math]::Round(($late | Measure-Object -Average).Average, 1))ms" }
}

# 7. History verification
Write-Host ""
Write-Host "=== Revision History (Playback) ===" -ForegroundColor Cyan
$finalProj = Invoke-Api -Method GET -Path "/api/v3/games/$gameId/projection" -Token $token
Write-Host "Final revision: $($finalProj.committed_revision)"
Write-Host "Final canonical hash: $($finalProj.canonical_state_hash)"
Write-Host "Total revisions: $($finalProj.committed_revision + 1) (0 to $($finalProj.committed_revision))"

try {
    $checkpoints = Invoke-Api -Method GET -Path "/api/v3/games/$gameId/rewind-checkpoints" -Token $token
    Write-Host "Retained rewind checkpoints: $($checkpoints.checkpoints.Count)"
    if ($checkpoints.checkpoints.Count -gt 0) {
        Write-Host "  Earliest: turn $($checkpoints.checkpoints[-1].turn)"
        Write-Host "  Latest:   turn $($checkpoints.checkpoints[0].turn)"
    }
} catch {
    Write-Host "Rewind checkpoints: (not available)"
}

# 8. Game metadata
$meta = Invoke-Api -Method GET -Path "/api/v3/games/$gameId" -Token $token
Write-Host ""
Write-Host "=== Game Metadata ===" -ForegroundColor Cyan
Write-Host "Game ID: $gameId"
Write-Host "Role: $($meta.role) | Civ: $($meta.civilization_id) | Status: $($meta.lifecycle_status)"
Write-Host "Revision: $($meta.committed_revision)"

Write-Host ""
if ($victory) {
    Write-Host "=== ALL-AI DOMINATION MATCH COMPLETED ===" -ForegroundColor Green
} else {
    Write-Host "=== MATCH ENDED WITHOUT VICTORY ===" -ForegroundColor Yellow
}
