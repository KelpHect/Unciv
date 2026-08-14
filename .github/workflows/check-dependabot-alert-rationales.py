#!/usr/bin/env python3
"""Fail when a manually-dismissed Dependabot alert has no resolution rationale.

A dismissal is "manual" when `state == "dismissed"` and `auto_dismissed_at` is
null (auto-dismissals happen when a fix lands and carry no human rationale by
design). Every manual dismissal must record a non-whitespace `dismissed_comment`.

Authentication: the Dependabot alerts REST API is NOT readable with the default
Actions ``GITHUB_TOKEN`` (``Resource not accessible by integration``, see
github/community#60612). Export a token with the ``security_events`` scope (for
public repositories ``public_repo`` also works) as ``GH_TOKEN``. The workflow
supplies it from the ``DEPENDABOT_AUDIT_TOKEN`` secret.

Exit status:
    0  every manually-dismissed alert carries a rationale
    1  at least one manual dismissal lacks a rationale, or the API is unreachable
    2  usage error

``--self-test`` exercises the analysis logic against synthetic fixtures without
touching the network.
"""

import json
import os
import sys
import urllib.error
import urllib.request

API_ROOT = "https://api.github.com"
MAX_PAGES = 100  # safety cap against a malformed Link header


def main() -> int:
    if len(sys.argv) > 1 and sys.argv[1] == "--self-test":
        return _self_test()

    repo_slug = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("GITHUB_REPOSITORY", "")
    if not repo_slug or repo_slug.count("/") != 1:
        print(f"usage: {sys.argv[0]} <owner/repo>", file=sys.stderr)
        return 2

    token = os.environ.get("GH_TOKEN", "").strip()
    if not token:
        print(
            "error: GH_TOKEN is not set.\n"
            "       The Dependabot alerts API is not readable with GITHUB_TOKEN.\n"
            "       Configure the DEPENDABOT_AUDIT_TOKEN secret with a token that\n"
            "       has the 'security_events' scope (public repos: 'public_repo').",
            file=sys.stderr,
        )
        return 1

    dismissed, manual, offenders = _analyze(_fetch_alerts(repo_slug, token))

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
            f"Re-dismiss with a rationale, or reopen, at https://github.com/{repo_slug}/security/dependabot",
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


def _fetch_alerts(repo_slug: str, token: str) -> list:
    """Fetch every alert, following the API's cursor (Link header) pagination."""
    headers = {
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {token}",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "unciv-dependabot-rationale-check",
    }
    collected: list = []
    url = f"{API_ROOT}/repos/{repo_slug}/dependabot/alerts?per_page=100"
    for _ in range(MAX_PAGES):
        request = urllib.request.Request(url, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                body = json.loads(response.read().decode("utf-8"))
                link_header = response.headers.get("Link", "")
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            hint = ""
            if exc.code in (401, 403):
                hint = (
                    "       The token needs the 'security_events' scope (public repos:\n"
                    "       'public_repo'); GITHUB_TOKEN cannot read this endpoint.\n"
                )
            print(
                f"error: could not list Dependabot alerts for {repo_slug}: "
                f"HTTP {exc.code}\n{detail}\n{hint}",
                file=sys.stderr,
            )
            raise SystemExit(1)
        except urllib.error.URLError as exc:
            print(f"error: could not reach the GitHub API: {exc.reason}", file=sys.stderr)
            raise SystemExit(1)

        if not isinstance(body, list):
            print(f"error: unexpected API response (not a list): {body!r}", file=sys.stderr)
            raise SystemExit(1)

        collected.extend(body)
        next_url = _next_link(link_header)
        if not next_url:
            break
        url = next_url
    return collected


def _next_link(link_header: str) -> str | None:
    """Extract the rel="next" URL from a Link header, if present."""
    if not link_header:
        return None
    for part in link_header.split(","):
        if 'rel="next"' not in part:
            continue
        start = part.find("<")
        end = part.find(">")
        if start != -1 and end != -1:
            return part[start + 1 : end]
    return None


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
    assert _next_link('<https://api.github.com/x?after=abc>; rel="next", <https://api.github.com/x>; rel="prev"') == "https://api.github.com/x?after=abc"
    assert _next_link('<https://api.github.com/x>; rel="last"') is None
    assert _next_link("") is None
    print("self-test: OK (manual dismissals without a rationale are detected; auto-dismissals are exempt)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
