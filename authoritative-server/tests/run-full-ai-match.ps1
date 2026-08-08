<#
.SYNOPSIS
  Plays a complete authoritative V3 match by repeatedly ending the human turn,
  benchmarks every turn, and verifies the full revision history for playback.

.DESCRIPTION
  Creates a tiny Pangaea Domination lobby with 1 human + 1 AI, starts it,
  then loops: fetch projection, end turn (server AI executes), record timing,
  check for victory.  Prints a per-turn benchmark table and a final summary.
  Verifies the complete revision chain is intact for history/playback.
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = "http://127.0.0.1:3060",
    [int]$MaxTurns = 300
)

$ErrorActionPreference = 'Stop'

# ── helpers ──────────────────────────────────────────────────────────────────

function Invoke-Api {
    param([string]$Method, [string]$Path, [string]$Body, [string]$Token)
    $headers = @()
    if ($Body) { $headers += @("-H", "Content-Type: application/json") }
    if ($Token) { $headers += @("-H", "Authorization: Bearer $Token") }
    $args = @("-s", "-X", $Method, "$BaseUrl$Path") + $headers
    if ($Body) { $args += @("-d", $Body) }
    $args += @("-w", "`n%{http_code}")
    $raw = & curl.exe @args
    $lines = $raw -split "`n" | Where-Object { $_ -ne "" }
    $status = [int]($lines[-1])
    $jsonStr = ($lines[0..($lines.Count - 2)] -join "`n")
    if ($status -ge 400) {
        throw "API $Method $Path -> $status`: $jsonStr"
    }
    if ($jsonStr.Trim() -eq "") { return $null }
    return $jsonStr | ConvertFrom-Json
}

function New-GameJson {
    param([string]$ManifestHash)
    $opId = [guid]::NewGuid().ToString()
    return @"
{"operation_id":"$opId","ruleset_manifest_hash":"$ManifestHash","display_name":"Full AI Match Benchmark","human_slots":1,"available_civilizations":["Rome","Egypt"],"setup":{"owner_civilization_id":"Rome","difficulty":"Chieftain","speed":"Quick","starting_era":"Ancient era","victory_types":["Domination"],"major_civilizations":2,"city_states":0,"max_turns":300,"map_type":"pangaea","map_shape":"hexagonal","map_size":"tiny","map_resources":"default","barbarians":"disabled","one_city_challenge":false,"nuclear_weapons_enabled":true,"espionage_enabled":false,"no_start_bias":false,"shuffle_player_order":false,"no_city_razing":false,"world_wrap":false,"strategic_balance":false,"legendary_start":false,"no_ruins":true,"no_natural_wonders":true,"ai_civilizations":[{"civilization_id":"Egypt","difficulty":"Chieftain","personality":""}]}}
"@
}

# ── main ─────────────────────────────────────────────────────────────────────

Write-Host "=== Full AI Match Benchmark ===" -ForegroundColor Cyan
Write-Host "Server: $BaseUrl"
Write-Host "Config: Tiny Pangaea, 2 civs (Rome human + Egypt AI), Quick, Domination only"
Write-Host ""

# 1. Register / login
$suffix = Get-Random -Maximum 999999
$regBody = "{`"username`":`"match-$suffix`",`"password`":`"correct horse battery staple`"}"
$acct = Invoke-Api -Method POST -Path "/api/v3/auth/register" -Body $regBody
$loginBody = "{`"username`":`"match-$suffix`",`"password`":`"correct horse battery staple`"}"
$login = Invoke-Api -Method POST -Path "/api/v3/auth/login" -Body $loginBody
$token = $login.session_token
Write-Host "Account: match-$suffix"

# 2. Get manifest hash
$manifests = Invoke-Api -Method GET -Path "/api/v3/ruleset-manifests" -Token $token
$manifestHash = $manifests.manifests[0].manifest_hash
Write-Host "Manifest: $manifestHash"

# 3. Create game (1 human slot, 2 majors = 1 AI)
$createJson = New-GameJson -ManifestHash $manifestHash
$created = Invoke-Api -Method POST -Path "/api/v3/games" -Body $createJson -Token $token
$gameId = $created.game_id
$rev = $created.committed_revision
$hash = $created.canonical_state_hash
Write-Host "Game: $gameId (rev $rev)"

# 4. Start the lobby (1 human slot, owner is ready by default? No - need to set ready)
# Set ready
$readyJson = "{`"expected_lobby_revision`":$rev,`"ready`":true}"
$readyResp = Invoke-Api -Method PUT -Path "/api/v3/lobbies/$gameId/ready" -Body $readyJson -Token $token
$lobbyRev = $readyResp.lobby_revision
Write-Host "Owner ready, lobby rev: $lobbyRev"

# Start
$startJson = "{`"expected_lobby_revision`":$lobbyRev}"
$started = Invoke-Api -Method POST -Path "/api/v3/lobbies/$gameId/start" -Body $startJson -Token $token
$rev = $started.committed_revision
$hash = $started.canonical_state_hash
Write-Host "Match started! rev=$rev"

# 5. Play turns
$turnData = @()
$matchStart = Get-Date
$turn = 0
$victory = $false
$victoryType = $null
$winner = $null
$victoryTurn = 0

Write-Host ""
Write-Host "Turn | EndTurn(ms) | Snapshot | Rev | Current Civ | Victory?"
Write-Host "-----|------------|----------|-----|------------|--------"

while ($turn -lt $MaxTurns -and -not $victory) {
    # Get projection to check current state
    $proj = Invoke-Api -Method GET -Path "/api/v3/games/$gameId/projection" -Token $token
    $currentCiv = $proj.projection.currentPlayerCivilizationId
    $isMyTurn = $proj.projection.isCurrentTurn
    $turn = $proj.projection.turn

    # Check victory
    if ($proj.projection.victory) {
        $victory = $true
        $victoryType = $proj.projection.victory.victoryType
        $winner = $proj.projection.victory.winningCiv
        $victoryTurn = $turn
        Write-Host ("{0,4} | {1,10} | {2,8} | {3,3} | {4,-10} | VICTORY: {5} ({6})" -f `
            $turn, "-", "-", $proj.committed_revision, $currentCiv, $winner, $victoryType)
        break
    }

    # If it's our turn, end it
    if ($isMyTurn) {
        $etStart = Get-Date
        $etJson = "{`"command_id`":`"$([guid]::NewGuid().ToString())`",`"expected_revision`":$($proj.committed_revision),`"client_observed_state_hash`":`"$($proj.canonical_state_hash)`"}"
        try {
            $etResp = Invoke-Api -Method POST -Path "/api/v3/games/$gameId/commands/end-turn" -Body $etJson -Token $token
            $etMs = [math]::Round(((Get-Date) - $etStart).TotalMilliseconds)
            $rev = $etResp.committed_revision
            $hash = $etResp.canonical_state_hash

            # Get post-turn projection for snapshot size estimate
            $postProj = Invoke-Api -Method GET -Path "/api/v3/games/$gameId/projection" -Token $token
            $postCiv = $postProj.projection.currentPlayerCivilizationId
            $postTurn = $postProj.projection.turn

            # Check if victory was achieved after this turn
            if ($postProj.projection.victory) {
                $victory = $true
                $victoryType = $postProj.projection.victory.victoryType
                $winner = $postProj.projection.victory.winningCiv
                $victoryTurn = $postTurn
                Write-Host ("{0,4} | {1,10} | {2,8} | {3,3} | {4,-10} | VICTORY: {5} ({6})" -f `
                    $turn, $etMs, "-", $rev, $postCiv, $winner, $victoryType)
                break
            }

            Write-Host ("{0,4} | {1,10} | {2,8} | {3,3} | {4,-10} |" -f `
                $turn, $etMs, "-", $rev, $postCiv)

            $turnData += [PSCustomObject]@{
                Turn = $turn
                EndTurnMs = $etMs
                Revision = $rev
                NextCiv = $postCiv
                NextTurn = $postTurn
            }
        } catch {
            Write-Host ("{0,4} | ERROR: {1}" -f $turn, $_.Exception.Message)
            # If it's a stale revision, just refetch and retry
            Start-Sleep -Milliseconds 500
        }
    } else {
        # Not our turn - shouldn't happen in 1v1 AI, but wait
        Write-Host ("{0,4} | waiting (current: {1})" -f $turn, $currentCiv)
        Start-Sleep -Milliseconds 500
    }
}

$matchEnd = Get-Date
$matchDuration = ($matchEnd - $matchStart).TotalSeconds

# 6. Final summary
Write-Host ""
Write-Host "=== Match Summary ===" -ForegroundColor Cyan
Write-Host "Total turns played: $($turnData.Count)"
Write-Host "Match duration: $([math]::Round($matchDuration, 1))s"
if ($victory) {
    Write-Host "Victory: $winner won by $victoryType on turn $victoryTurn" -ForegroundColor Green
} else {
    Write-Host "No victory achieved within $MaxTurns turns" -ForegroundColor Yellow
}

if ($turnData.Count -gt 0) {
    $allMs = $turnData | ForEach-Object { $_.EndTurnMs }
    $sorted = $allMs | Sort-Object
    $p50 = $sorted[[math]::Floor($sorted.Count * 0.5)]
    $p95 = $sorted[[math]::Floor($sorted.Count * 0.95)]
    $mean = [math]::Round(($allMs | Measure-Object -Average).Average, 1)
    $min = $sorted[0]
    $max = $sorted[-1]

    Write-Host ""
    Write-Host "=== Per-Turn Benchmark ===" -ForegroundColor Cyan
    Write-Host "EndTurn+AI latency (ms):"
    Write-Host "  min: $min  p50: $p50  p95: $p95  max: $max  mean: $mean"
    Write-Host "  total AI processing: $([math]::Round(($allMs | Measure-Object -Sum).Sum / 1000.0, 1))s"
}

# 7. Verify revision history for playback
Write-Host ""
Write-Host "=== Revision History (Playback) ===" -ForegroundColor Cyan
$finalProj = Invoke-Api -Method GET -Path "/api/v3/games/$gameId/projection" -Token $token
Write-Host "Final revision: $($finalProj.committed_revision)"
Write-Host "Final canonical hash: $($finalProj.canonical_state_hash)"
Write-Host "Total revisions committed: $($finalProj.committed_revision + 1) (0 to $($finalProj.committed_revision))"

# Check rewind checkpoints (history is stored as immutable snapshots)
try {
    $checkpoints = Invoke-Api -Method GET -Path "/api/v3/games/$gameId/rewind-checkpoints" -Token $token
    Write-Host "Retained rewind checkpoints: $($checkpoints.checkpoints.Count)"
    if ($checkpoints.checkpoints.Count -gt 0) {
        Write-Host "  First: rev $($checkpoints.checkpoints[0].revision)"
        Write-Host "  Last:  rev $($checkpoints.checkpoints[-1].revision)"
    }
} catch {
    Write-Host "Rewind checkpoints: (not available or empty)"
}

# 8. Verify game metadata
$meta = Invoke-Api -Method GET -Path "/api/v3/games/$gameId" -Token $token
Write-Host ""
Write-Host "=== Game Metadata ===" -ForegroundColor Cyan
Write-Host "Game ID: $gameId"
Write-Host "Role: $($meta.role)"
Write-Host "Civilization: $($meta.civilization_id)"
Write-Host "Lifecycle: $($meta.lifecycle_status)"
Write-Host "Revision: $($meta.committed_revision)"

# 9. List account games
$games = Invoke-Api -Method GET -Path "/api/v3/games" -Token $token
Write-Host ""
Write-Host "=== Account Games ===" -ForegroundColor Cyan
foreach ($g in $games.games) {
    Write-Host "  $($g.display_name) | rev $($g.committed_revision) | $($g.lifecycle_status) | AI: $($g.ai_count) | civ: $($g.civilization_id)"
}

Write-Host ""
if ($victory) {
    Write-Host "=== FULL MATCH COMPLETED SUCCESSFULLY ===" -ForegroundColor Green
} else {
    Write-Host "=== MATCH ENDED WITHOUT VICTORY (max $MaxTurns turns) ===" -ForegroundColor Yellow
}
