<#
.SYNOPSIS
  Quick end-to-end replay test: creates an all-AI game, plays a few turns,
  then tests the replay endpoints (revisions, replay projection, public matches).
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = "http://127.0.0.1:13000"
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

Write-Host "=== Replay E2E Test ===" -ForegroundColor Cyan
Write-Host ""

# 1. Register
$suffix = Get-Random -Maximum 999999
$regBody = "{`"username`":`"replay-$suffix`",`"password`":`"correct horse battery staple`"}"
Invoke-Api -Method POST -Path "/api/v3/auth/register" -Body $regBody | Out-Null
$login = Invoke-Api -Method POST -Path "/api/v3/auth/login" -Body $regBody
$token = $login.session_token
Write-Host "Account: replay-$suffix"

# 2. Get manifest
$manifests = Invoke-Api -Method GET -Path "/api/v3/ruleset-manifests" -Token $token
$manifestHash = $manifests.manifests[0].manifest_hash
Write-Host "Manifest: $manifestHash"

# 3. Create all-AI game (0 humans, 2 AI civs, Tiny map, Quick speed)
$opId = [guid]::NewGuid().ToString()
$aiArray = @("Egypt","Greece") | ForEach-Object { "{`"civilization_id`":`"$_`",`"difficulty`":`"Chieftain`",`"personality`":`"`"}" }
$aiJson = $aiArray -join ","
$createJson = @"
{"operation_id":"$opId","ruleset_manifest_hash":"$manifestHash","display_name":"Replay E2E Test","human_slots":0,"password":null,"available_civilizations":[],"setup":{"owner_civilization_id":"","difficulty":"Chieftain","speed":"Quick","starting_era":"Ancient era","victory_types":["Domination","Scientific","Cultural","Diplomatic"],"major_civilizations":2,"city_states":0,"max_turns":1500,"map_type":"pangaea","map_shape":"hexagonal","map_size":"tiny","map_resources":"default","barbarians":"disabled","one_city_challenge":false,"nuclear_weapons_enabled":true,"espionage_enabled":false,"no_start_bias":false,"shuffle_player_order":false,"no_city_razing":false,"world_wrap":false,"strategic_balance":false,"legendary_start":false,"no_ruins":true,"no_natural_wonders":true,"ai_civilizations":[$aiJson]}}
"@

Write-Host "Creating all-AI game (0 humans, 2 AI, Tiny)..."
$created = Invoke-Api -Method POST -Path "/api/v3/games" -Body $createJson -Token $token
$gameId = $created.game_id
Write-Host "Game: $gameId (rev $($created.committed_revision))"

# 4. Ready + start (owner is spectator, no ready needed for 0 humans)
# For 0 humans, the owner doesn't need to ready - the lobby auto-starts
# Actually we need to ready and start
try {
    $readyJson = "{`"expected_lobby_revision`":0,`"ready`":true}"
    $readyResp = Invoke-Api -Method PUT -Path "/api/v3/lobbies/$gameId/ready" -Body $readyJson -Token $token
    Write-Host "Owner ready: rev $($readyResp.lobby_revision)"
} catch {
    Write-Host "Ready failed (expected for 0 humans): $($_.Exception.Message)"
}

try {
    $startJson = "{`"expected_lobby_revision`":1}"
    $started = Invoke-Api -Method POST -Path "/api/v3/lobbies/$gameId/start" -Body $startJson -Token $token
    Write-Host "Match started! rev=$($started.committed_revision)"
} catch {
    Write-Host "Start failed: $($_.Exception.Message)"
    # Try with revision 0
    $startJson = "{`"expected_lobby_revision`":0}"
    $started = Invoke-Api -Method POST -Path "/api/v3/lobbies/$gameId/start" -Body $startJson -Token $token
    Write-Host "Match started (rev 0)! rev=$($started.committed_revision)"
}

# 5. Play a few turns via end-turn (owner is spectator, need to advance AI)
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

        # End turn to advance
        $endTurnBody = "{`"command_id`":`"$([guid]::NewGuid().ToString())`",`"expected_revision`":$($proj.committed_revision)}"
        $result = Invoke-Api -Method POST -Path "/api/v3/games/$gameId/commands/end-turn" -Body $endTurnBody -Token $token
        $turnsPlayed++
        Write-Host "  EndTurn accepted, rev=$($result.committed_revision)"
        Start-Sleep -Milliseconds 500
    } catch {
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
    Write-Host "FAILED: $($_.Exception.Message)"
}

# 7. Test replay projection for first revision
if ($revisions -and $revisions.revisions.Count -gt 0) {
    $firstRev = $revisions.revisions[0].revision
    Write-Host ""
    Write-Host "--- GET /api/v3/games/$gameId/revisions/$firstRev/replay ---"
    try {
        $replay = Invoke-Api -Method GET -Path "/api/v3/games/$gameId/revisions/$firstRev/replay" -Token $token
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
        Write-Host "FAILED: $($_.Exception.Message)"
    }

    # 7b. Test replay projection for last revision
    $lastRev = $revisions.revisions[-1].revision
    Write-Host ""
    Write-Host "--- GET /api/v3/games/$gameId/revisions/$lastRev/replay ---"
    try {
        $replayLast = Invoke-Api -Method GET -Path "/api/v3/games/$gameId/revisions/$lastRev/replay" -Token $token
        Write-Host "Replay projection (last rev) received:"
        Write-Host "  Turn: $($replayLast.projection.turn)"
        Write-Host "  Major civs: $($replayLast.projection.majorCivilizations.Count)"
        foreach ($civ in $replayLast.projection.majorCivilizations) {
            Write-Host "    $($civ.displayName): cities=$($civ.cityCount) units=$($civ.unitCount) gold=$($civ.gold) pop=$($civ.population) tech=$($civ.technologiesResearched) defeated=$($civ.defeated)"
        }
    } catch {
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
    Write-Host "FAILED: $($_.Exception.Message)"
}

Write-Host ""
Write-Host "=== Replay E2E Test Complete ===" -ForegroundColor Green
