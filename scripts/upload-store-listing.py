#!/usr/bin/env python3
"""Upload localized Play Store listings and screenshots via the Publishing API v3.

Parses store-listing.md for per-locale descriptions, discovers screenshots in
docs/play-store/screenshots/{en,it,...}, and upserts everything through an
edit transaction.  Supports --dry-run to validate without committing.

Usage:
    python3 scripts/upload-store-listing.py /path/to/service-account.json
    PLAY_SERVICE_ACCOUNT_JSON='<json>' python3 scripts/upload-store-listing.py
    python3 scripts/upload-store-listing.py --dry-run key.json
    python3 scripts/upload-store-listing.py --locales en-US,it-IT key.json
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path

from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError
from googleapiclient.http import MediaFileUpload

SCOPES = ["https://www.googleapis.com/auth/androidpublisher"]
DEFAULT_PACKAGE = "com.antivocale.app"
API_NAME = "androidpublisher"
API_VERSION = "v3"
APP_TITLE = "Anti-Vocale"

DIR_TO_LOCALE: dict[str, str] = {
    "en": "en-US",
    "it": "it-IT",
}

IMAGE_TYPES = ["phoneScreenshots"]


# ---------------------------------------------------------------------------
# Auth helpers (same pattern as verify-play-api.py)
# ---------------------------------------------------------------------------

def load_credentials(
    key_source: str, *, is_json_string: bool = False
) -> service_account.Credentials:
    """Load service-account credentials from a file path or raw JSON string."""
    if is_json_string:
        info = json.loads(key_source)
        return service_account.Credentials.from_service_account_info(
            info, scopes=SCOPES
        )
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


# ---------------------------------------------------------------------------
# Store listing parser
# ---------------------------------------------------------------------------

def parse_store_listing(path: str) -> dict[str, dict[str, str]]:
    """Parse store-listing.md into {locale: {"short": ..., "full": ...}}."""
    text = Path(path).read_text(encoding="utf-8")
    sections = re.split(r"^---\s*$", text, flags=re.MULTILINE)
    listings: dict[str, dict[str, str]] = {}

    for section in sections:
        section = section.strip()
        if not section:
            continue

        locale: str | None = None

        # English (default) section — no locale heading
        if re.search(r"^## Short Description", section, re.MULTILINE):
            locale = "en-US"
        # Italian section — identified by the Italian short-desc heading
        elif re.search(r"^## Descrizione Breve", section, re.MULTILINE):
            locale = "it-IT"
        # Other locales — identified by "### <locale> (<Language>)"
        else:
            locale_match = re.search(
                r"^### ([a-z]{2}-[A-Z]{2})\s+\(", section, re.MULTILINE
            )
            if locale_match:
                locale = locale_match.group(1)

        if locale is None:
            continue

        # Extract short description from the first code block
        code_block = re.search(r"```\n(.*?)\n```", section, re.DOTALL)
        if not code_block:
            print(
                f"Warning: no short-description code block found for {locale}",
                file=sys.stderr,
            )
            continue
        short_desc = code_block.group(1).strip()

        # Full description: everything after the code block, stripped of
        # the heading lines that precede the short description code block.
        after_code = section[code_block.end():]
        # Remove the locale heading and short-desc heading lines that may
        # appear before the code block — we want only what comes after.
        # Also remove any leading "Italiano / Italian:" marker.
        full_desc = re.sub(r"^Italiano\s*/\s*Italian:\s*\n", "", after_code.lstrip("\n"))
        full_desc = full_desc.strip()

        listings[locale] = {"short": short_desc, "full": full_desc}

    return listings


# ---------------------------------------------------------------------------
# Screenshot discovery
# ---------------------------------------------------------------------------

def discover_screenshots(base_dir: str) -> dict[str, list[Path]]:
    """Return {locale: [sorted PNG paths]} from screenshots/<2-letter>/ dirs."""
    base = Path(base_dir)
    result: dict[str, list[Path]] = {}
    if not base.is_dir():
        return result

    for child in sorted(base.iterdir()):
        if not child.is_dir():
            continue
        locale = DIR_TO_LOCALE.get(child.name)
        if locale is None:
            continue
        pngs = sorted(p for p in child.iterdir() if p.suffix.lower() == ".png")
        if pngs:
            result[locale] = pngs

    return result


# ---------------------------------------------------------------------------
# API upload
# ---------------------------------------------------------------------------

def _upload_screenshots(
    service,
    package_name: str,
    edit_id: str,
    locale: str,
    paths: list[Path],
) -> int:
    """Upload screenshots for one locale, replacing any existing images."""
    for image_type in IMAGE_TYPES:
        try:
            service.edits().images().deleteall(
                packageName=package_name,
                editId=edit_id,
                language=locale,
                imageType=image_type,
            ).execute()
        except HttpError as exc:
            if exc.status_code != 404:  # type: ignore[attr-defined]
                raise
        for img_path in paths:
            media = MediaFileUpload(
                str(img_path), mimetype="image/png", resumable=False
            )
            service.edits().images().upload(
                packageName=package_name,
                editId=edit_id,
                language=locale,
                imageType=image_type,
                media_body=media,
            ).execute()
    return len(paths)


def upload_listings(
    package_name: str,
    creds: service_account.Credentials,
    listings: dict[str, dict[str, str]],
    screenshots: dict[str, list[Path]],
    *,
    dry_run: bool = False,
    no_screenshots: bool = False,
    locales: list[str] | None = None,
) -> dict:
    """Run an edit transaction to upsert listings and screenshots.

    Returns a summary dict for JSON output.
    """
    if locales:
        listings = {k: v for k, v in listings.items() if k in locales}
        screenshots = {k: v for k, v in screenshots.items() if k in locales}

    screenshots_uploaded: dict[str, int] = {}

    service = build(API_NAME, API_VERSION, credentials=creds)
    try:
        edit = service.edits().insert(packageName=package_name).execute()
        edit_id = edit["id"]

        try:
            for locale in sorted(listings):
                desc = listings[locale]
                service.edits().listings().update(
                    packageName=package_name,
                    editId=edit_id,
                    language=locale,
                    body={
                        "language": locale,
                        "title": APP_TITLE,
                        "fullDescription": desc["full"],
                        "shortDescription": desc["short"],
                    },
                ).execute()

            if not no_screenshots:
                for locale in sorted(screenshots):
                    screenshots_uploaded[locale] = _upload_screenshots(
                        service, package_name, edit_id, locale, screenshots[locale]
                    )

            validation = service.edits().validate(
                packageName=package_name, editId=edit_id
            ).execute()
            warnings = validation.get("errors", [])
            if warnings:
                for w in warnings:
                    print(
                        f"  Validation warning: {w.get('message', w)}",
                        file=sys.stderr,
                    )

            if dry_run:
                service.edits().delete(
                    packageName=package_name, editId=edit_id
                ).execute()
            else:
                service.edits().commit(
                    packageName=package_name, editId=edit_id
                ).execute()

        except HttpError:
            try:
                service.edits().delete(
                    packageName=package_name, editId=edit_id
                ).execute()
            except Exception:
                pass
            raise

    finally:
        service.close()

    return {
        "locales_uploaded": sorted(listings),
        "screenshots_uploaded": screenshots_uploaded,
        "dry_run": dry_run,
    }


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Upload localized Play Store listings via the Publishing API v3.",
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
        "--listing-file",
        default="docs/play-store/store-listing.md",
        help="Path to store-listing.md (default: docs/play-store/store-listing.md)",
    )
    parser.add_argument(
        "--screenshots-dir",
        default="docs/play-store/screenshots",
        help="Path to screenshots dir (default: docs/play-store/screenshots)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Validate only, don't commit changes",
    )
    parser.add_argument(
        "--no-screenshots",
        action="store_true",
        help="Skip screenshot uploads",
    )
    parser.add_argument(
        "--locales",
        default=None,
        help="Comma-separated list of locales to upload (default: all found)",
    )
    parser.add_argument(
        "--json",
        dest="output_json",
        action="store_true",
        help="Output results as JSON",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    # Resolve credentials
    try:
        key_source, is_json_string = resolve_key_source(args)
        creds = load_credentials(key_source, is_json_string=is_json_string)
    except (FileNotFoundError, ValueError, json.JSONDecodeError) as exc:
        print(f"Credentials error: {exc}", file=sys.stderr)
        return 1

    # Parse listing file
    try:
        listings = parse_store_listing(args.listing_file)
    except FileNotFoundError:
        print(f"Error: listing file not found: {args.listing_file}", file=sys.stderr)
        return 1

    if not listings:
        print("Error: no locale sections found in listing file", file=sys.stderr)
        return 1

    # Discover screenshots
    screenshots = discover_screenshots(args.screenshots_dir)

    # Parse --locales filter
    locales: list[str] | None = None
    if args.locales:
        locales = [l.strip() for l in args.locales.split(",") if l.strip()]
        unknown = [l for l in locales if l not in listings]
        if unknown:
            print(
                f"Warning: requested locales not found in listing file: {unknown}",
                file=sys.stderr,
            )

    if not args.output_json:
        print(f"Parsed {len(listings)} locale(s) from {args.listing_file}")
        for loc in sorted(listings):
            short = listings[loc]["short"]
            print(f"  {loc}: short={len(short)} chars")
        if screenshots:
            for loc in sorted(screenshots):
                print(f"  {loc}: {len(screenshots[loc])} screenshot(s)")
        mode = "Validating (dry run)" if args.dry_run else "Uploading"
        print(f"{mode} to {args.package_name}...")

    # Upload
    try:
        result = upload_listings(
            args.package_name,
            creds,
            listings,
            screenshots,
            dry_run=args.dry_run,
            no_screenshots=args.no_screenshots,
            locales=locales,
        )
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
        print(json.dumps(result, indent=2))
    else:
        if args.dry_run:
            n_screens = sum(result["screenshots_uploaded"].values())
            print(
                f"DRY RUN: {len(result['locales_uploaded'])} locale(s) would be updated, "
                f"{n_screens} screenshot(s) would be uploaded"
            )
        else:
            for loc in result["locales_uploaded"]:
                short_len = len(listings[loc]["short"])
                n_img = result["screenshots_uploaded"].get(loc, 0)
                img_info = f", {n_img} screenshot(s)" if n_img else ""
                print(f"  {loc}: uploaded (short={short_len} chars{img_info})")
            print(f"Done: {len(result['locales_uploaded'])} locale(s) committed")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
