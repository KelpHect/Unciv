<#
.SYNOPSIS
  Quick end-to-end replay test: creates a passive-human/AI game, plays a few
  turns, then tests revisions, replay projection, and public-match discovery.
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = "http://127.0.0.1:13000"
)

$ErrorActionPreference = 'Stop'
$failures = 0

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

Write-Host "=== Replay E2E Test ===" -ForegroundColor Cyan
Write-Host ""

# 1. Register
$suffix = Get-Random -Maximum 999999
$regBody = "{`"username`":`"replay-$suffix`",`"password`":`"correct horse battery staple`"}"
Invoke-Api -Method POST -Path "/api/v3/auth/register" -Body $regBody | Out-Null
$login = Invoke-Api -Method POST -Path "/api/v3/auth/login" -Body $regBody
$token = $login.session_token
Write-Host "Account: replay-$suffix"

# Add a second account as an explicit spectator so replay reads exercise the
# same read-only path used by the client spectator screen.
Start-Sleep -Seconds 3
$specSuffix = Get-Random -Maximum 999999
$specRegBody = "{`"username`":`"replay-spec-$specSuffix`",`"password`":`"correct horse battery staple`"}"
Invoke-Api -Method POST -Path "/api/v3/auth/register" -Body $specRegBody | Out-Null
$specLogin = Invoke-Api -Method POST -Path "/api/v3/auth/login" -Body $specRegBody
$specToken = $specLogin.session_token
Write-Host "Spectator account: replay-spec-$specSuffix"

# 2. Get manifest
$manifests = Invoke-Api -Method GET -Path "/api/v3/ruleset-manifests" -Token $token
$manifestHash = $manifests.manifests[0].manifest_hash
Write-Host "Manifest: $manifestHash"

# 3. Create a passive-human game with 2 AI civs on a Tiny map.
$opId = [guid]::NewGuid().ToString()
$aiArray = @("Egypt","Greece") | ForEach-Object { "{`"civilization_id`":`"$_`",`"difficulty`":`"Chieftain`",`"personality`":`"`"}" }
$aiJson = $aiArray -join ","
$createJson = @"
{"operation_id":"$opId","ruleset_manifest_hash":"$manifestHash","display_name":"Replay E2E Test","human_slots":1,"password":null,"available_civilizations":["Rome","Egypt","Greece"],"setup":{"owner_civilization_id":"Rome","difficulty":"Chieftain","speed":"Quick","starting_era":"Ancient era","victory_types":["Domination","Scientific","Cultural","Diplomatic"],"major_civilizations":3,"city_states":0,"max_turns":1500,"map_type":"pangaea","map_shape":"hexagonal","map_size":"tiny","map_resources":"default","barbarians":"disabled","one_city_challenge":false,"nuclear_weapons_enabled":true,"espionage_enabled":false,"no_start_bias":false,"shuffle_player_order":false,"no_city_razing":false,"world_wrap":false,"strategic_balance":false,"legendary_start":false,"no_ruins":true,"no_natural_wonders":true,"ai_civilizations":[$aiJson]}}
"@

Write-Host "Creating passive-human game (1 human, 2 AI, Tiny)..."
$created = Invoke-Api -Method POST -Path "/api/v3/games" -Body $createJson -Token $token
$gameId = $created.game_id
Write-Host "Game: $gameId (rev $($created.committed_revision))"

# 4. Add the explicit spectator, ready the passive human owner, and start.
$addSpecBody = "{`"username`":`"replay-spec-$specSuffix`"}"
Invoke-Api -Method PUT -Path "/api/v3/games/$gameId/spectators" -Body $addSpecBody -Token $token | Out-Null
Write-Host "Spectator added to game"
$readyJson = "{`"expected_lobby_revision`":0,`"ready`":true}"
$readyResp = Invoke-Api -Method PUT -Path "/api/v3/lobbies/$gameId/ready" -Body $readyJson -Token $token
$startJson = "{`"expected_lobby_revision`":$($readyResp.lobby_revision)}"
$started = Invoke-Api -Method POST -Path "/api/v3/lobbies/$gameId/start" -Body $startJson -Token $token
Write-Host "Match started! rev=$($started.committed_revision)"

# 5. Play a few turns via the passive human owner; the worker advances AI turns.
Write-Host ""
Write-Host "Playing turns..."
$turnsPlayed = 0
$maxTestTurns = 5

for ($i = 0; $i -lt $maxTestTurns; $i++) {
    try {
        $proj = Invoke-Api -Method GET -Path "/api/v3/games/$gameId/projection" -Token $token
        $turn = $proj.projection.turn
        Write-Host "  Turn $turn, current: $($proj.projection.currentPlayerCivilizationId)"

        if ($proj.projection.victory) {
            Write-Host "  Victory: $($proj.projection.victory.winningCivilizationId) by $($proj.projection.victory.victoryType)"
            break
        }

        $endTurnBody = "{`"command_id`":`"$([guid]::NewGuid().ToString())`",`"expected_revision`":$($proj.committed_revision),`"client_observed_state_hash`":`"$($proj.canonical_state_hash)`"}"
        $result = Invoke-Api -Method POST -Path "/api/v3/games/$gameId/commands/end-turn" -Body $endTurnBody -Token $token
        $turnsPlayed++
        Write-Host "  EndTurn accepted, rev=$($result.committed_revision)"
        Start-Sleep -Milliseconds 500
    } catch {
        $failures++
        Write-Host "  Turn error: $($_.Exception.Message)"
        Start-Sleep -Seconds 1
    }
}

Write-Host ""
Write-Host "=== Testing Replay Endpoints ===" -ForegroundColor Cyan

# 6. Test list revisions
Write-Host ""
Write-Host "--- GET /api/v3/games/$gameId/revisions ---"
try {
    $revisions = Invoke-Api -Method GET -Path "/api/v3/games/$gameId/revisions" -Token $token
    Write-Host "Revisions: $($revisions.revisions.Count)"
    if ($revisions.revisions.Count -gt 0) {
        Write-Host "  First: rev=$($revisions.revisions[0].revision) kind=$($revisions.revisions[0].revision_kind)"
        Write-Host "  Last:  rev=$($revisions.revisions[-1].revision) kind=$($revisions.revisions[-1].revision_kind)"
    }
} catch {
    $failures++
    Write-Host "FAILED: $($_.Exception.Message)"
}

# 7. Test replay projection for first revision
if ($revisions -and $revisions.revisions.Count -gt 0) {
    $firstRev = $revisions.revisions[0].revision
    Write-Host ""
    Write-Host "--- GET /api/v3/games/$gameId/revisions/$firstRev/replay ---"
    try {
        $replay = Invoke-Api -Method GET -Path "/api/v3/games/$gameId/revisions/$firstRev/replay" -Token $specToken
        Write-Host "Replay projection received:"
        Write-Host "  Turn: $($replay.projection.turn)"
        Write-Host "  Current player: $($replay.projection.currentPlayerCivilizationId)"
        Write-Host "  Major civs: $($replay.projection.majorCivilizations.Count)"
        foreach ($civ in $replay.projection.majorCivilizations) {
            Write-Host "    $($civ.displayName): cities=$($civ.cityCount) units=$($civ.unitCount) gold=$($civ.gold) defeated=$($civ.defeated)"
        }
        Write-Host "  Map tiles: $($replay.projection.map.tiles.Count)"
        Write-Host "  World wrap: $($replay.projection.map.worldWrap)"
    } catch {
        $failures++
        Write-Host "FAILED: $($_.Exception.Message)"
    }

    # 7b. Test replay projection for last revision
    $lastRev = $revisions.revisions[-1].revision
    Write-Host ""
    Write-Host "--- GET /api/v3/games/$gameId/revisions/$lastRev/replay ---"
    try {
        $replayLast = Invoke-Api -Method GET -Path "/api/v3/games/$gameId/revisions/$lastRev/replay" -Token $specToken
        Write-Host "Replay projection (last rev) received:"
        Write-Host "  Turn: $($replayLast.projection.turn)"
        Write-Host "  Major civs: $($replayLast.projection.majorCivilizations.Count)"
        foreach ($civ in $replayLast.projection.majorCivilizations) {
            Write-Host "    $($civ.displayName): cities=$($civ.cityCount) units=$($civ.unitCount) gold=$($civ.gold) pop=$($civ.population) tech=$($civ.technologiesResearched) defeated=$($civ.defeated)"
        }
    } catch {
        $failures++
        Write-Host "FAILED: $($_.Exception.Message)"
    }
}

# 8. Test public matches list
Write-Host ""
Write-Host "--- GET /api/v3/public-matches ---"
try {
    $publicMatches = Invoke-Api -Method GET -Path "/api/v3/public-matches" -Token $token
    Write-Host "Public matches: $($publicMatches.Count)"
    if ($publicMatches.Count -gt 0) {
        Write-Host "  First: $($publicMatches[0].displayName) (rev $($publicMatches[0].headRevision))"
    }
} catch {
    $failures++
    Write-Host "FAILED: $($_.Exception.Message)"
}

if ($failures -gt 0) {
    throw "Replay E2E test failed with $failures verification error(s)"
}

Write-Host ""
Write-Host "=== Replay E2E Test Complete ===" -ForegroundColor Green
