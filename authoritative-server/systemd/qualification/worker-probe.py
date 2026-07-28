#!/usr/bin/env python3
"""Authenticated protocol-v2 probe for the private qualification worker."""

import hashlib
import hmac
import json
import os
import socket
import struct
import sys
import uuid

ADDRESS = ("127.0.0.1", 43170)
KEY = bytes.fromhex(
    "5555555555555555555555555555555555555555555555555555555555555555"
)
REQUEST_DOMAIN = b"UNCIV-WORKER-V2\0request\0"
RESPONSE_DOMAIN = b"UNCIV-WORKER-V2\0response\0"


def exchange(request):
    payload = json.dumps(request, separators=(",", ":")).encode()
    nonce = os.urandom(16)
    size = struct.pack(">I", len(payload))
    tag = hmac.new(KEY, REQUEST_DOMAIN + nonce + size + payload, hashlib.sha256).digest()
    with socket.create_connection(ADDRESS, timeout=10) as connection:
        connection.sendall(size + nonce + tag + payload)
        response_size = receive_exact(connection, 4)
        response_length = struct.unpack(">I", response_size)[0]
        response_nonce = receive_exact(connection, 16)
        response_tag = receive_exact(connection, 32)
        response = receive_exact(connection, response_length)
    expected = hmac.new(
        KEY,
        RESPONSE_DOMAIN + response_nonce + response_size + response,
        hashlib.sha256,
    ).digest()
    if response_nonce != nonce or not hmac.compare_digest(response_tag, expected):
        raise RuntimeError("worker response authentication failed")
    return json.loads(response)


def receive_exact(connection, length):
    chunks = []
    remaining = length
    while remaining:
        chunk = connection.recv(remaining)
        if not chunk:
            raise RuntimeError("worker closed an incomplete response")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def handshake():
    response = exchange(
        {
            "protocolVersion": 2,
            "operation": {"type": "handshake"},
        }
    )
    if response.get("error") is not None:
        raise RuntimeError(f"worker handshake failed: {response['error']['code']}")
    if response.get("releaseBundleId") != "a" * 64:
        raise RuntimeError("worker returned the wrong release bundle identity")
    if not response.get("installedRulesets"):
        raise RuntimeError("worker returned no installed rulesets")
    return response


def create_huge():
    capabilities = handshake()
    base = next(
        ruleset
        for ruleset in capabilities["installedRulesets"]
        if ruleset["name"] == "Civ V - Gods & Kings"
    )
    response = exchange(
        {
            "protocolVersion": 2,
            "serverTimeMillis": 1700000000000,
            "actorId": "linux-qualification-owner",
            "rulesetManifest": {
                "engineBuild": capabilities["engineBuild"],
                "baseRuleset": base,
                "mods": [],
            },
            "operation": {
                "type": "create_game",
                "gameId": str(uuid.UUID("00000000-0000-4000-8000-000000009001")),
                "serverSeed": 918273645,
                "setup": {
                    "difficulty": "Prince",
                    "speed": "Standard",
                    "startingEra": "Ancient era",
                    "victoryTypes": ["Domination"],
                    "majorCivilizations": 16,
                    "cityStates": 32,
                    "maxTurns": 500,
                    "mapType": "fractal",
                    "mapShape": "hexagonal",
                    "mapSize": "huge",
                    "mapResources": "abundant",
                    "barbarians": "raging",
                    "oneCityChallenge": False,
                    "nuclearWeaponsEnabled": True,
                    "espionageEnabled": True,
                    "noStartBias": False,
                    "shufflePlayerOrder": True,
                    "noCityRazing": False,
                    "worldWrap": True,
                    "strategicBalance": True,
                    "legendaryStart": True,
                    "noRuins": False,
                    "noNaturalWonders": False,
                    "minutesUntilSkipTurn": 1440,
                    "minutesUntilForceResign": 4320,
                    "minutesRecoveredPerTurn": 1440,
                },
            },
        }
    )
    if response.get("error") is not None:
        raise RuntimeError(f"huge game creation failed: {response['error']['code']}")
    if not response.get("canonicalStateHash") or not response.get("snapshot"):
        raise RuntimeError("huge game creation returned no canonical state")


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "handshake"
    if mode == "handshake":
        result = handshake()
        print(
            json.dumps(
                {
                    "engineBuild": result["engineBuild"],
                    "releaseBundleId": result["releaseBundleId"],
                    "rulesets": len(result["installedRulesets"]),
                },
                separators=(",", ":"),
            )
        )
    elif mode == "create-huge":
        create_huge()
        print('{"createHuge":"accepted"}')
    else:
        raise SystemExit(f"unknown probe mode: {mode}")
