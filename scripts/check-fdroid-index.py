#!/usr/bin/env python3
"""F-Droid index tripwire: did our release actually reach users?

The bot-MR check (check-fdroid-bot-mr.sh) only proves the metadata moved;
everything after the merge (buildserver queue, source builds, binary
verification, the index publish cycle) is invisible to us: GitLab sends
no notifications because the checkupdates bot opens its own MRs and we
are never participants. v1.14.0 sat in that blind spot (merged 2026-10-10,
still absent from the index a day later), which is fine while the normal
1-3 day lag runs, but nothing would have told us if it never landed.

This script checks the OUTCOME: the live index API. Exit codes:
  0  the index serves at least MIN(versionCode)        -> landed
  2  not landed yet, within the grace window           -> keep waiting
  3  not landed, past the window                       -> ALERT: escalate
  4  the index API itself is unreachable               -> infra, retry

Usage: check-fdroid-index.py <expectedMaxVersionCode> [--since YYYY-MM-DD]
  --since   the bot-MR merge date (default: 48h ago). The grace window is
            72h from that date: fdroid's own guidance is 1-3 days from
            metadata merge to index.
"""

import argparse
import datetime as dt
import json
import sys
import urllib.request

INDEX_API = "https://f-droid.org/api/v1/packages/com.antivocale.app"
GRACE_HOURS = 72
UA = "anti-vocale-release-watch (session tool)"


def fetch_index() -> dict:
    req = urllib.request.Request(INDEX_API, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("expected_code", type=int,
                    help="the max per-ABI versionCode of the release (e.g. 484)")
    ap.add_argument("--since",
                    help="bot-MR merge date YYYY-MM-DD (default: 48h ago)")
    args = ap.parse_args()

    merged = (
        dt.datetime.strptime(args.since, "%Y-%m-%d")
        if args.since
        else dt.datetime.now() - dt.timedelta(hours=48))
    try:
        index = fetch_index()
    except Exception as e:  # noqa: BLE001 - any transport error is "retry"
        print(f"INDEX UNREACHABLE ({e}); retry later")
        return 4

    codes = [p.get("versionCode", 0) for p in index.get("packages", [])]
    landed = max(codes) >= args.expected_code if codes else False
    suggested = index.get("suggestedVersionCode")
    age_h = (dt.datetime.now() - merged).total_seconds() / 3600

    if landed:
        print(f"LANDED: index max versionCode {max(codes)} (suggested {suggested})")
        return 0
    if age_h < GRACE_HOURS:
        print(f"NOT YET: index max {max(codes) if codes else 'n/a'} "
              f"(suggested {suggested}), {age_h:.0f}h since merge, "
              f"grace {GRACE_HOURS}h")
        return 2
    print(f"ALERT: {age_h:.0f}h since merge and the index still serves max "
          f"{max(codes) if codes else 'n/a'} (suggested {suggested}), "
          f"expected >= {args.expected_code}. The build or verification "
          f"may have failed silently: check the bot MR CI, then ask on "
          f"#fdroid / the fdroiddata merge request.")
    return 3


if __name__ == "__main__":
    sys.exit(main())
