"""Tests for upload-store-listing.py — parser, screenshot discovery, and CLI."""

from __future__ import annotations

import importlib.util
import sys
import textwrap
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

# Load the hyphenated script as a module (same pattern as test_verify_play_api.py).
_SCRIPT_PATH = str(Path(__file__).resolve().parent.parent / "upload-store-listing.py")
_spec = importlib.util.spec_from_file_location("upload_store_listing", _SCRIPT_PATH)
_mod = importlib.util.module_from_spec(_spec)
sys.modules["upload_store_listing"] = _mod
_spec.loader.exec_module(_mod)

discover_screenshots = _mod.discover_screenshots
parse_store_listing = _mod.parse_store_listing
build_parser = _mod.build_parser
main = _mod.main


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

SAMPLE_LISTING = textwrap.dedent("""\
    # Play Store Listing - Anti-Vocale

    ## Short Description (80 chars max)
    ```
    Transcribe voice messages offline. No internet required.
    ```

    ## Full Description (4000 chars max)

    Anti-Vocale transcribes voice messages from WhatsApp, Telegram, and other
    messaging apps directly on your Android device.

    **FEATURES**

    - Share voice messages from any app
    - Smart notifications

    ---

    ## Descrizione Breve (80 caratteri max)
    ```
    Trascrivi messaggi vocali offline. Nessuna connessione richiesta.
    ```

    Italiano / Italian:

    Anti-Vocale trascrive i messaggi vocali da WhatsApp e Telegram.

    **FUNZIONALITÀ**

    - Condividi messaggi vocali da qualsiasi app

    ---

    ### de-DE (German)
    ## Kurzbeschreibung (80 Zeichen max)
    ```
    Sprachnachrichten offline umwandeln. Kein Internet nötig.
    ```

    Anti-Vocale wandelt Sprachnachrichten aus WhatsApp und Telegram in Text um.

    **FUNKTIONEN**

    - Sprachnachrichten aus jeder App teilen

    ---

    ### fr-FR (French)
    ## Description courte (80 caractères max)
    ```
    Transcrivez les messages vocaux hors ligne. Sans connexion.
    ```

    Anti-Vocale transcrit les messages vocaux de WhatsApp et Telegram.

    **FONCTIONNALITÉS**

    - Partagez des messages vocaux
""")


@pytest.fixture
def listing_file(tmp_path: Path) -> Path:
    p = tmp_path / "store-listing.md"
    p.write_text(SAMPLE_LISTING, encoding="utf-8")
    return p


@pytest.fixture
def screenshots_dir(tmp_path: Path) -> Path:
    base = tmp_path / "screenshots"
    en = base / "en"
    en.mkdir(parents=True)
    (en / "screen1.png").write_bytes(b"\x89PNG")
    (en / "screen2.png").write_bytes(b"\x89PNG")
    it = base / "it"
    it.mkdir()
    (it / "schermo1.png").write_bytes(b"\x89PNG")
    return base


# ---------------------------------------------------------------------------
# parse_store_listing
# ---------------------------------------------------------------------------


class TestParseStoreListing:
    def test_extracts_english(self, listing_file: Path):
        listings = parse_store_listing(str(listing_file))
        assert "en-US" in listings
        assert listings["en-US"]["short"] == (
            "Transcribe voice messages offline. No internet required."
        )
        assert "Anti-Vocale transcribes" in listings["en-US"]["full"]

    def test_extracts_italian(self, listing_file: Path):
        listings = parse_store_listing(str(listing_file))
        assert "it-IT" in listings
        assert listings["it-IT"]["short"] == (
            "Trascrivi messaggi vocali offline. Nessuna connessione richiesta."
        )
        assert "Italiano / Italian:" not in listings["it-IT"]["full"]
        assert "Anti-Vocale trascrive" in listings["it-IT"]["full"]

    def test_extracts_other_locales(self, listing_file: Path):
        listings = parse_store_listing(str(listing_file))
        assert "de-DE" in listings
        assert listings["de-DE"]["short"] == (
            "Sprachnachrichten offline umwandeln. Kein Internet nötig."
        )
        assert "fr-FR" in listings
        assert listings["fr-FR"]["short"] == (
            "Transcrivez les messages vocaux hors ligne. Sans connexion."
        )

    def test_locale_count(self, listing_file: Path):
        listings = parse_store_listing(str(listing_file))
        assert len(listings) == 4

    def test_full_description_has_features(self, listing_file: Path):
        listings = parse_store_listing(str(listing_file))
        assert "**FEATURES**" in listings["en-US"]["full"]
        assert "**FUNKTIONEN**" in listings["de-DE"]["full"]

    def test_file_not_found(self):
        with pytest.raises(FileNotFoundError):
            parse_store_listing("/nonexistent/path.md")

    def test_real_store_listing(self):
        """Verify parsing against the actual store-listing.md."""
        real_path = Path("docs/play-store/store-listing.md")
        if not real_path.is_file():
            pytest.skip("Real store listing not found")
        listings = parse_store_listing(str(real_path))
        expected = [
            "en-US", "it-IT", "de-DE", "fr-FR", "es-ES", "pt-BR",
            "ja-JP", "ko-KR", "zh-CN", "ru-RU", "nl-NL", "pl-PL",
            "tr-TR", "ar", "hi-IN",
        ]
        for locale in expected:
            assert locale in listings, f"Missing locale: {locale}"
            assert len(listings[locale]["short"]) > 0
            assert len(listings[locale]["full"]) > 100

    def test_real_short_descriptions_under_80(self):
        """All short descriptions from real listing must be under 80 chars."""
        real_path = Path("docs/play-store/store-listing.md")
        if not real_path.is_file():
            pytest.skip("Real store listing not found")
        listings = parse_store_listing(str(real_path))
        for locale, desc in listings.items():
            assert len(desc["short"]) <= 80, (
                f"{locale} short desc is {len(desc['short'])} chars: {desc['short']}"
            )


# ---------------------------------------------------------------------------
# discover_screenshots
# ---------------------------------------------------------------------------


class TestDiscoverScreenshots:
    def test_finds_pngs(self, screenshots_dir: Path):
        result = discover_screenshots(str(screenshots_dir))
        assert "en-US" in result
        assert len(result["en-US"]) == 2
        assert "it-IT" in result
        assert len(result["it-IT"]) == 1

    def test_sorted_order(self, screenshots_dir: Path):
        result = discover_screenshots(str(screenshots_dir))
        names = [p.name for p in result["en-US"]]
        assert names == ["screen1.png", "screen2.png"]

    def test_empty_dir(self, tmp_path: Path):
        result = discover_screenshots(str(tmp_path / "nonexistent"))
        assert result == {}

    def test_ignores_non_png(self, tmp_path: Path):
        d = tmp_path / "screenshots" / "en"
        d.mkdir(parents=True)
        (d / "image.jpg").write_bytes(b"\xff\xd8")
        (d / "image.png").write_bytes(b"\x89PNG")
        result = discover_screenshots(str(tmp_path / "screenshots"))
        assert len(result["en-US"]) == 1

    def test_real_screenshots(self):
        """Verify against actual screenshots directory."""
        real_dir = Path("docs/play-store/screenshots")
        if not real_dir.is_dir():
            pytest.skip("Real screenshots directory not found")
        result = discover_screenshots(str(real_dir))
        assert "en-US" in result
        assert "it-IT" in result
        assert all(p.suffix == ".png" for paths in result.values() for p in paths)


# ---------------------------------------------------------------------------
# CLI argument parsing
# ---------------------------------------------------------------------------


class TestBuildParser:
    def test_defaults(self):
        args = build_parser().parse_args(["key.json"])
        assert args.key_file == "key.json"
        assert args.package_name == "com.antivocale.app"
        assert args.dry_run is False
        assert args.no_screenshots is False
        assert args.output_json is False
        assert args.locales is None

    def test_all_flags(self):
        args = build_parser().parse_args([
            "--dry-run", "--no-screenshots", "--json",
            "--locales", "en-US,it-IT",
            "--package-name", "com.example.app",
            "key.json",
        ])
        assert args.dry_run is True
        assert args.no_screenshots is True
        assert args.output_json is True
        assert args.locales == "en-US,it-IT"

    def test_no_key_file_uses_env(self):
        args = build_parser().parse_args([])
        assert args.key_file is None


# ---------------------------------------------------------------------------
# Integration: main() with mocked API
# ---------------------------------------------------------------------------


class TestMainIntegration:
    def test_no_credentials_exits_1(self, listing_file: Path):
        rc = main([
            "--listing-file", str(listing_file),
            "--screenshots-dir", "/nonexistent",
        ])
        assert rc == 1

    def test_missing_listing_file_exits_1(self, tmp_path: Path):
        rc = main([
            "--listing-file", str(tmp_path / "nope.md"),
            "--screenshots-dir", "/nonexistent",
        ])
        assert rc == 1

    @patch("upload_store_listing.upload_listings")
    @patch("upload_store_listing.load_credentials")
    @patch("upload_store_listing.resolve_key_source")
    def test_dry_run_happy_path(
        self, mock_resolve: MagicMock, mock_creds: MagicMock,
        mock_upload: MagicMock, listing_file: Path, screenshots_dir: Path,
    ):
        mock_resolve.return_value = ("key.json", False)
        mock_creds.return_value = MagicMock()
        mock_upload.return_value = {
            "locales_uploaded": ["en-US", "it-IT"],
            "screenshots_uploaded": {"en-US": 2},
            "dry_run": True,
        }
        rc = main([
            "--dry-run",
            "--listing-file", str(listing_file),
            "--screenshots-dir", str(screenshots_dir),
            "key.json",
        ])
        assert rc == 0
        mock_upload.assert_called_once()
        call_kwargs = mock_upload.call_args
        assert call_kwargs[1]["dry_run"] is True
