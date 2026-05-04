#!/usr/bin/env python3
"""Verify Google Play Developer API v3 access for an Android app.

Creates a temporary edit, lists all tracks with their releases, then
deletes the edit. Exits 0 on success, 1 on any failure.

Usage:
    python3 scripts/verify-play-api.py /path/to/service-account.json
    PLAY_SERVICE_ACCOUNT_JSON='<json>' python3 scripts/verify-play-api.py
    python3 scripts/verify-play-api.py --json --package-name com.example.app key.json
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError

SCOPES = ["https://www.googleapis.com/auth/androidpublisher"]
DEFAULT_PACKAGE = "com.antivocale.app"
API_NAME = "androidpublisher"
API_VERSION = "v3"


def load_credentials(key_source: str, *, is_json_string: bool = False) -> service_account.Credentials:
    """Load service-account credentials from a file path or raw JSON string."""
    if is_json_string:
        info = json.loads(key_source)
        return service_account.Credentials.from_service_account_info(info, scopes=SCOPES)
    key_path = Path(key_source)
    if not key_path.is_file():
        raise FileNotFoundError(f"Service account key file not found: {key_path}")
    return service_account.Credentials.from_service_account_file(
        str(key_path), scopes=SCOPES
    )


def resolve_key_source(args: argparse.Namespace) -> tuple[str, bool]:
    """Return (source, is_json_string) from CLI args or env var."""
    if args.key_file:
        return args.key_file, False
    env_json = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", "").strip()
    if env_json:
        return env_json, True
    raise ValueError(
        "No credentials provided. Pass a key file as argument or set "
        "PLAY_SERVICE_ACCOUNT_JSON environment variable."
    )


def list_tracks(package_name: str, creds: service_account.Credentials) -> list[dict]:
    """Create an edit, list tracks, then delete the edit. Returns track list."""
    service = build(API_NAME, API_VERSION, credentials=creds)
    try:
        edit = service.edits().insert(packageName=package_name).execute()
        edit_id = edit["id"]
        try:
            tracks_resp = service.edits().tracks().list(
                packageName=package_name, editId=edit_id
            ).execute()
            return tracks_resp.get("tracks", [])
        finally:
            service.edits().delete(
                packageName=package_name, editId=edit_id
            ).execute()
    finally:
        service.close()


def format_tracks_human(tracks: list[dict]) -> str:
    """Format tracks for human-readable output."""
    if not tracks:
        return "No tracks found."
    lines: list[str] = []
    for track in tracks:
        name = track.get("track", "unknown")
        lines.append(f"  Track: {name}")
        for release in track.get("releases", []):
            status = release.get("status", "unknown")
            version_codes = release.get("versionCodes", [])
            user_frac = release.get("userFraction")
            parts = [f"    Release: status={status}"]
            if version_codes:
                parts.append(f" versions={version_codes}")
            if user_frac is not None:
                parts.append(f" userFraction={user_frac}")
            lines.append("".join(parts))
    return "\n".join(lines)


def format_tracks_json(tracks: list[dict], package_name: str) -> str:
    """Format tracks as a JSON object."""
    payload = {
        "package_name": package_name,
        "tracks": [
            {
                "track": t.get("track", "unknown"),
                "releases": [
                    {
                        "status": r.get("status", "unknown"),
                        "versionCodes": r.get("versionCodes", []),
                        "userFraction": r.get("userFraction"),
                    }
                    for r in t.get("releases", [])
                ],
            }
            for t in tracks
        ],
    }
    return json.dumps(payload, indent=2)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Verify Google Play Developer API v3 access."
    )
    parser.add_argument(
        "key_file",
        nargs="?",
        default=None,
        help="Path to service account JSON key file",
    )
    parser.add_argument(
        "--package-name",
        default=DEFAULT_PACKAGE,
        help=f"Android package name (default: {DEFAULT_PACKAGE})",
    )
    parser.add_argument(
        "--json",
        dest="output_json",
        action="store_true",
        help="Output track data as JSON",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    try:
        key_source, is_json_string = resolve_key_source(args)
        creds = load_credentials(key_source, is_json_string=is_json_string)
    except (FileNotFoundError, ValueError, json.JSONDecodeError) as exc:
        print(f"Credentials error: {exc}", file=sys.stderr)
        return 1
    except Exception as exc:
        print(f"Unexpected credentials error: {exc}", file=sys.stderr)
        return 1

    try:
        tracks = list_tracks(args.package_name, creds)
    except HttpError as exc:
        print(f"API error: {exc}", file=sys.stderr)
        return 1
    except OSError as exc:
        print(f"Network error: {exc}", file=sys.stderr)
        return 1
    except Exception as exc:
        print(f"Unexpected error: {exc}", file=sys.stderr)
        return 1

    if args.output_json:
        print(format_tracks_json(tracks, args.package_name))
    else:
        print(f"Tracks for {args.package_name}:")
        print(format_tracks_human(tracks))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
