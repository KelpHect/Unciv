<#
.SYNOPSIS
  Full all-AI benchmark: 12 civilizations on a Huge Pangaea map, no humans.
  All non-hidden victory types enabled (Domination, Scientific, Cultural, Diplomatic).
  The owner is a spectator who calls end_turn each round to advance the AI.

.DESCRIPTION
  Creates a Huge Pangaea lobby with 12 major civilizations and 0 human slots.
  12 AI civilizations fight it out. The owner (spectator) calls end_turn to
  advance the game. A second account is added as spectator for alive-civs
  tracking via the spectator projection (not fog-of-war limited).
  Eliminations are logged in real time. Every turn is benchmarked.
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = "http://127.0.0.1:13000",
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

Write-Host "=== 12-Civ Huge All-AI Benchmark ===" -ForegroundColor Cyan
Write-Host "Map: Huge Pangaea | 12 AI civs | 0 humans | Quick speed"
Write-Host "Victory types: Domination, Scientific, Cultural, Diplomatic"
Write-Host "Max turns: $MaxTurns"
Write-Host ""

# 1. Register / login owner (spectator)
$suffix = Get-Random -Maximum 999999
$regBody = "{`"username`":`"bench12-$suffix`",`"password`":`"correct horse battery staple`"}"
Invoke-Api -Method POST -Path "/api/v3/auth/register" -Body $regBody | Out-Null
$login = Invoke-Api -Method POST -Path "/api/v3/auth/login" -Body $regBody
$token = $login.session_token
Write-Host "Owner account: bench12-$suffix"

# 1b. Register spectator for alive-civs tracking
Start-Sleep -Seconds 3
$specSuffix = Get-Random -Maximum 999999
$specRegBody = "{`"username`":`"spec12-$specSuffix`",`"password`":`"correct horse battery staple`"}"
$specRegistered = $false
for ($attempt = 0; $attempt -lt 5 -and -not $specRegistered; $attempt++) {
    try {
        Invoke-Api -Method POST -Path "/api/v3/auth/register" -Body $specRegBody | Out-Null
        $specRegistered = $true
    } catch {
        if ($_.Exception.Message -match "rate_limited") {
            Write-Host "Rate limited, waiting..."
            Start-Sleep -Seconds 10
        } else { throw }
    }
}
$specLogin = Invoke-Api -Method POST -Path "/api/v3/auth/login" -Body $specRegBody
$specToken = $specLogin.session_token
Write-Host "Spectator account: spec12-$specSuffix"

# 2. Get manifest
$manifests = Invoke-Api -Method GET -Path "/api/v3/ruleset-manifests" -Token $token
$manifestHash = $manifests.manifests[0].manifest_hash
Write-Host "Manifest: $manifestHash"

# 3. Create 12-civ all-AI game on Huge map
$opId = [guid]::NewGuid().ToString()
$aiCivs = @("Rome","Egypt","Greece","Persia","Aztecs","Mongolia","Arabia","China","England","France","Russia","Japan")
$aiArray = $aiCivs | ForEach-Object { "{`"civilization_id`":`"$_`",`"difficulty`":`"Chieftain`",`"personality`":`"`"}" }
$aiJson = $aiArray -join ","
$createJson = @"
{"operation_id":"$opId","ruleset_manifest_hash":"$manifestHash","display_name":"12-Civ Huge All-AI Benchmark","human_slots":0,"password":null,"available_civilizations":[],"setup":{"owner_civilization_id":"","difficulty":"Chieftain","speed":"Quick","starting_era":"Ancient era","victory_types":["Domination","Scientific","Cultural","Diplomatic"],"major_civilizations":12,"city_states":0,"max_turns":1500,"map_type":"pangaea","map_shape":"hexagonal","map_size":"huge","map_resources":"default","barbarians":"disabled","one_city_challenge":false,"nuclear_weapons_enabled":true,"espionage_enabled":false,"no_start_bias":false,"shuffle_player_order":false,"no_city_razing":false,"world_wrap":false,"strategic_balance":false,"legendary_start":false,"no_ruins":true,"no_natural_wonders":true,"ai_civilizations":[$aiJson]}}
"@

Write-Host "Creating 12-civ Huge Pangaea lobby..."
try {
    $created = Invoke-Api -Method POST -Path "/api/v3/games" -Body $createJson -Token $token
    $gameId = $created.game_id
    Write-Host "Game: $gameId (rev $($created.committed_revision))"
} catch {
    Write-Host "Create failed: $($_.Exception.Message)"
    exit 1
}

# 3b. Add spectator account
$addSpecBody = "{`"username`":`"spec12-$specSuffix`"}"
try {
    Invoke-Api -Method PUT -Path "/api/v3/games/$gameId/spectators" -Body $addSpecBody -Token $token | Out-Null
    Write-Host "Spectator added"
} catch {
    Write-Host "Warning: could not add spectator: $($_.Exception.Message)"
}

# 4. Start the lobby (0 humans = no ready needed, just start)
try {
    $startBody = '{"expected_lobby_revision":0}'
    $started = Invoke-Api -Method POST -Path "/api/v3/lobbies/$gameId/start" -Body $startBody -Token $token
    Write-Host "Match started! rev=$($started.committed_revision)"
} catch {
    Write-Host "Start with rev 0 failed: $($_.Exception.Message)"
    try {
        $startBody = '{"expected_lobby_revision":1}'
        $started = Invoke-Api -Method POST -Path "/api/v3/lobbies/$gameId/start" -Body $startBody -Token $token
        Write-Host "Match started (rev 1)! rev=$($started.committed_revision)"
    } catch {
        Write-Host "Start failed completely: $($_.Exception.Message)"
        exit 1
    }
}
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
$maxErrors = 10
$prevAliveCivs = @{}

# Track initial alive civs
try {
    $initSpec = Invoke-Api -Method GET -Path "/api/v3/games/$gameId/spectator-projection" -Token $specToken
    foreach ($civ in $initSpec.projection.majorCivilizations) {
        $prevAliveCivs[$civ.civilizationId] = -not $civ.defeated
    }
    Write-Host "Initial civs: $($prevAliveCivs.Count) alive"
} catch {
    Write-Host "Warning: could not get initial spectator projection: $($_.Exception.Message)"
}

Write-Host ""
Write-Host "Turn  | EndTurn+AI(ms) | Rev   | Current Civ      | Alive | Defeated Status"
Write-Host "------|---------------|-------|------------------|-------|----------------"

while ($turn -lt $MaxTurns -and -not $victory) {
    $turnStart = Get-Date
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

    # Check victory
    if ($proj.projection.victory) {
        $victory = $true
        $victoryType = $proj.projection.victory.victoryType
        $winner = $proj.projection.victory.winningCivilizationId
        $victoryTurn = $turn
        $elapsed = [int]((Get-Date) - $turnStart).TotalMilliseconds
        Write-Host ("{0,5} | {1,13} | {2,5} | {3,-16} | {4,5} | VICTORY" -f $turn, $elapsed, $proj.committed_revision, $currentCiv, "-")
        break
    }

    # End turn to advance AI
    $endTurnBody = "{`"command_id`":`"$([guid]::NewGuid().ToString())`",`"expected_revision`":$($proj.committed_revision)}"
    try {
        $result = Invoke-Api -Method POST -Path "/api/v3/games/$gameId/commands/end-turn" -Body $endTurnBody -Token $token
        $elapsed = [int]((Get-Date) - $turnStart).TotalMilliseconds
    } catch {
        $errorCount++
        $elapsed = [int]((Get-Date) - $turnStart).TotalMilliseconds
        Write-Host ("{0,5} | {1,13} | {2,5} | {3,-16} | ERR   | {4}" -f $turn, $elapsed, "ERR", $currentCiv, $_.Exception.Message)
        if ($errorCount -ge $maxErrors) { throw "Too many consecutive errors" }
        Start-Sleep -Seconds 1
        continue
    }
    $errorCount = 0

    # Track alive civs via spectator projection every 10 turns
    $aliveCount = "?"
    $defeatedStatus = ""
    if ($turn % 10 -eq 0 -or $turn -lt 5) {
        try {
            $spec = Invoke-Api -Method GET -Path "/api/v3/games/$gameId/spectator-projection" -Token $specToken
            $aliveCivs = @()
            $newEliminations = @()
            foreach ($civ in $spec.projection.majorCivilizations) {
                $isAlive = -not $civ.defeated
                $aliveCivs += $civ
                if ($prevAliveCivs.ContainsKey($civ.civilizationId) -and $prevAliveCivs[$civ.civilizationId] -and -not $isAlive) {
                    $newEliminations += $civ.civilizationId
                    $eliminations += [PSCustomObject]@{ Turn=$turn; Civ=$civ.civilizationId; Name=$civ.displayName }
                }
                $prevAliveCivs[$civ.civilizationId] = $isAlive
            }
            $aliveCount = ($aliveCivs | Where-Object { -not $_.defeated }).Count
            if ($newEliminations.Count -gt 0) {
                $defeatedStatus = "ELIMINATED: " + ($newEliminations -join ", ")
            }
        } catch {
            $defeatedStatus = "spec error"
        }
    }

    $turnData += [PSCustomObject]@{ Turn=$turn; ElapsedMs=$elapsed; Revision=$result.committed_revision }

    Write-Host ("{0,5} | {1,13} | {2,5} | {3,-16} | {4,5} | {5}" -f $turn, $elapsed, $result.committed_revision, $currentCiv, $aliveCount, $defeatedStatus)
}

$matchEnd = Get-Date
$matchElapsed = [math]::Round(($matchEnd - $matchStart).TotalSeconds, 1)

Write-Host ""
Write-Host "=== Match Summary ===" -ForegroundColor Cyan
Write-Host "Total turns: $turn"
Write-Host "Total time: $matchElapsed seconds"
Write-Host "Total revisions: $($turnData.Count)"
Write-Host ""

if ($victory) {
    Write-Host "Victory: $winner won by $victoryType on turn $victoryTurn" -ForegroundColor Green
} else {
    Write-Host "No victory (hit $MaxTurns turn limit)" -ForegroundColor Yellow
}

# Eliminations
Write-Host ""
Write-Host "=== Eliminations ===" -ForegroundColor Cyan
if ($eliminations.Count -gt 0) {
    foreach ($e in $eliminations) {
        Write-Host "  Turn $($e.Turn): $($e.Name) ($($e.Civ)) eliminated"
    }
} else {
    Write-Host "  No eliminations recorded"
}

# Final alive civs
Write-Host ""
Write-Host "=== Final Civilization Status ===" -ForegroundColor Cyan
try {
    $finalSpec = Invoke-Api -Method GET -Path "/api/v3/games/$gameId/spectator-projection" -Token $specToken
    foreach ($civ in $finalSpec.projection.majorCivilizations) {
        $status = if ($civ.defeated) { "DEFEATED" } else { "ALIVE" }
        Write-Host ("  {0,-12} {1,-10} cities={2,3} units={3,4} gold={4,8} pop={5,6} tech={6,3}" -f `
            $civ.displayName, $status, $civ.cityCount, $civ.unitCount, $civ.gold, $civ.population, $civ.technologiesResearched)
    }
} catch {
    Write-Host "  Could not get final spectator projection: $($_.Exception.Message)"
}

# Performance stats
Write-Host ""
Write-Host "=== Performance ===" -ForegroundColor Cyan
if ($turnData.Count -gt 0) {
    $allTimes = $turnData | ForEach-Object { $_.ElapsedMs }
    $sorted = $allTimes | Sort-Object
    $p50 = $sorted[[int]($sorted.Count * 0.5)]
    $p90 = $sorted[[int]($sorted.Count * 0.9)]
    $p99 = $sorted[[int]($sorted.Count * 0.99)]
    $avg = [math]::Round(($allTimes | Measure-Object -Average).Average, 1)
    $min = ($allTimes | Measure-Object -Minimum).Minimum
    $max = ($allTimes | Measure-Object -Maximum).Maximum
    Write-Host "  p50: $p50 ms | p90: $p90 ms | p99: $p99 ms"
    Write-Host "  avg: $avg ms | min: $min ms | max: $max ms"
    Write-Host "  Total turns: $($turnData.Count) | Total time: $matchElapsed s"
}

Write-Host ""
Write-Host "=== Benchmark Complete ===" -ForegroundColor Green
Write-Host "Game ID: $gameId"
