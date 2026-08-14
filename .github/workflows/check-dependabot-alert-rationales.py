#!/usr/bin/env python3
"""Fail when a manually-dismissed Dependabot alert has no resolution rationale.

A dismissal is "manual" when `state == "dismissed"` and `auto_dismissed_at` is
null (auto-dismissals happen when a fix lands and carry no human rationale by
design). Every manual dismissal must record a non-whitespace `dismissed_comment`.

Transport: this script only *analyzes* alerts. Fetch them with the `gh` CLI,
which handles authentication and cursor pagination natively:

    gh api --paginate \
      "repos/{owner}/{repo}/dependabot/alerts?state=dismissed" \
      > alerts.json
    python3 check-dependabot-alert-rationales.py alerts.json

In GitHub Actions, `gh` authenticates with the workflow `GITHUB_TOKEN`; the
workflow declares `vulnerability-alerts: read`, which grants the token the
"Read Dependabot alerts" permission. No PAT/secret is required.

Exit status:
    0  every manually-dismissed alert carries a rationale
    1  at least one manual dismissal lacks a rationale, or input is malformed
    2  usage error

``--self-test`` exercises the analysis logic against synthetic fixtures without
touching the network.
"""

import json
import sys


def main() -> int:
    if len(sys.argv) > 1 and sys.argv[1] == "--self-test":
        return _self_test()

    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} <alerts.json|-|--self-test>", file=sys.stderr)
        return 2

    if sys.argv[1] == "-":
        raw = sys.stdin.read()
    else:
        with open(sys.argv[1], encoding="utf-8") as f:
            raw = f.read()

    try:
        alerts = json.loads(raw)
    except json.JSONDecodeError as exc:
        print(f"error: could not parse alert JSON: {exc}", file=sys.stderr)
        return 1

    if not isinstance(alerts, list):
        print(f"error: expected a JSON array of alerts, got {type(alerts).__name__}", file=sys.stderr)
        return 1

    dismissed, manual, offenders = _analyze(alerts)

    if offenders:
        print(
            f"FAIL: {len(manual)} manually-dismissed Dependabot alert(s); "
            f"the following have no resolution rationale:",
            file=sys.stderr,
        )
        for a in offenders:
            advisory = (a.get("security_advisory") or {}).get("ghsa_id", "?")
            package = ((a.get("dependency") or {}).get("package") or {}).get("name", "?")
            print(f"  #{a.get('number', '?')} {advisory} {package}", file=sys.stderr)
        print(
            "Every manual dismissal must record why it was dismissed (dismissed_comment).\n"
            "Re-dismiss with a rationale, or reopen, at "
            "https://github.com/{owner}/{repo}/security/dependabot",
            file=sys.stderr,
        )
        return 1

    print(
        f"OK: {len(dismissed)} dismissed Dependabot alert(s) "
        f"({len(manual)} manual), all carry a resolution rationale."
    )
    return 0


def _analyze(alerts: list) -> tuple[list, list, list]:
    """Return (dismissed, manual, offenders) from a list of raw alert dicts."""
    dismissed = [a for a in alerts if a.get("state") == "dismissed"]
    manual = [a for a in dismissed if a.get("auto_dismissed_at") is None]
    offenders = [a for a in manual if not (a.get("dismissed_comment") or "").strip()]
    return dismissed, manual, offenders


def _alert(number, *, state, auto_dismissed=False, comment=""):
    return {
        "number": number,
        "state": state,
        "auto_dismissed_at": "2026-01-01T00:00:00Z" if auto_dismissed else None,
        "dismissed_comment": comment,
        "security_advisory": {"ghsa_id": f"GHSA-{number:04d}"},
        "dependency": {"package": {"name": "example.pkg"}},
    }


def _self_test() -> int:
    fixtures = [
        _alert(1, state="dismissed", comment="Resolved via force to a patched version."),
        _alert(2, state="dismissed", comment="   tolerable risk; build-time only.   "),
        _alert(3, state="dismissed", auto_dismissed=True),  # auto-dismissal: exempt
        _alert(4, state="open"),
        _alert(5, state="fixed"),
        _alert(6, state="dismissed"),  # manual, no rationale -> must fail
        _alert(7, state="dismissed", comment="   "),  # whitespace-only -> must fail
    ]
    dismissed, manual, offenders = _analyze(fixtures)
    offender_numbers = sorted(a["number"] for a in offenders)
    assert sorted(a["number"] for a in dismissed) == [1, 2, 3, 6, 7], dismissed
    assert sorted(a["number"] for a in manual) == [1, 2, 6, 7], manual
    assert offender_numbers == [6, 7], offender_numbers
    print("self-test: OK (manual dismissals without a rationale are detected; auto-dismissals are exempt)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
