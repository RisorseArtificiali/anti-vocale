"""Unit tests for scripts/verify-play-api.py."""

from __future__ import annotations

import importlib.util
import json
import os
import sys
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

# Load the hyphenated script as module "verify_play_api" so @patch targets resolve.
_SCRIPT_PATH = str(Path(__file__).resolve().parent.parent / "verify-play-api.py")
_spec = importlib.util.spec_from_file_location("verify_play_api", _SCRIPT_PATH)
mod = importlib.util.module_from_spec(_spec)
sys.modules["verify_play_api"] = mod
_spec.loader.exec_module(mod)


_SAMPLE_TRACKS = [
    {
        "track": "production",
        "releases": [
            {"status": "completed", "versionCodes": [15]},
        ],
    },
    {
        "track": "beta",
        "releases": [
            {"status": "inProgress", "versionCodes": [16], "userFraction": 0.1},
        ],
    },
    {
        "track": "internal",
        "releases": [],
    },
]


def _mock_creds() -> MagicMock:
    """Return a mock credentials object."""
    return MagicMock()


def _mock_service(tracks: list[dict] | None = None) -> MagicMock:
    """Build a mock googleapiclient service with edit operations."""
    service = MagicMock()
    edits = service.edits.return_value
    edits.insert.return_value.execute.return_value = {"id": "edit-123"}
    tracks_resp = {"tracks": tracks if tracks is not None else _SAMPLE_TRACKS}
    edits.tracks.return_value.list.return_value.execute.return_value = tracks_resp
    edits.delete.return_value.execute.return_value = None
    return service


def _patch_file_creds():
    """Decorator: mock file-based credential loading so no real file is needed."""
    return patch(
        "verify_play_api.service_account.Credentials.from_service_account_file",
        return_value=_mock_creds(),
    )


class TestVerifyPlayAPI(unittest.TestCase):
    """Tests for verify_play_api module."""

    # ------------------------------------------------------------------
    # Track listing
    # ------------------------------------------------------------------

    @patch("verify_play_api.build", return_value=_mock_service())
    @patch("verify_play_api.service_account.Credentials.from_service_account_file")
    @patch("verify_play_api.Path.is_file", return_value=True)
    def test_successful_track_listing(self, mock_is_file, mock_creds, mock_build):
        mock_creds.return_value = _mock_creds()
        exit_code = mod.main(["fake-key.json"])
        self.assertEqual(exit_code, 0)
        mock_creds.assert_called_once()
        mock_build.assert_called_once()

    @patch("verify_play_api.build", return_value=_mock_service())
    @patch("verify_play_api.service_account.Credentials.from_service_account_file")
    @patch("verify_play_api.Path.is_file", return_value=True)
    def test_tracks_printed_human_readable(self, mock_is_file, mock_creds, mock_build):
        mock_creds.return_value = _mock_creds()

        with patch("builtins.print") as mock_print:
            mod.main(["fake-key.json"])

        output = " ".join(call.args[0] for call in mock_print.call_args_list)
        self.assertIn("production", output)
        self.assertIn("beta", output)
        self.assertIn("internal", output)

    # ------------------------------------------------------------------
    # Auth failure
    # ------------------------------------------------------------------

    @patch("verify_play_api.service_account.Credentials.from_service_account_file")
    @patch("verify_play_api.Path.is_file", return_value=True)
    def test_auth_failure_returns_1(self, mock_is_file, mock_creds):
        mock_creds.side_effect = Exception("Invalid key file")
        exit_code = mod.main(["bad-key.json"])
        self.assertEqual(exit_code, 1)

    # ------------------------------------------------------------------
    # Missing credentials
    # ------------------------------------------------------------------

    def test_missing_credentials_no_file_no_env(self):
        env = {"PLAY_SERVICE_ACCOUNT_JSON": ""}
        with patch.dict(os.environ, env, clear=True):
            exit_code = mod.main([])
        self.assertEqual(exit_code, 1)

    # ------------------------------------------------------------------
    # API error
    # ------------------------------------------------------------------

    @patch("verify_play_api.build")
    @patch("verify_play_api.service_account.Credentials.from_service_account_file")
    @patch("verify_play_api.Path.is_file", return_value=True)
    def test_api_error_handled(self, mock_is_file, mock_creds, mock_build):
        from googleapiclient.errors import HttpError

        mock_creds.return_value = _mock_creds()
        service = MagicMock()
        service.edits.return_value.insert.return_value.execute.side_effect = HttpError(
            resp=MagicMock(status=403), content=b'{"error": "forbidden"}'
        )
        mock_build.return_value = service

        exit_code = mod.main(["key.json"])
        self.assertEqual(exit_code, 1)

    # ------------------------------------------------------------------
    # JSON output mode
    # ------------------------------------------------------------------

    @patch("verify_play_api.build", return_value=_mock_service())
    @patch("verify_play_api.service_account.Credentials.from_service_account_file")
    @patch("verify_play_api.Path.is_file", return_value=True)
    def test_json_output_mode(self, mock_is_file, mock_creds, mock_build):
        mock_creds.return_value = _mock_creds()

        with patch("builtins.print") as mock_print:
            mod.main(["--json", "key.json"])

        printed = mock_print.call_args_list[0].args[0]
        parsed = json.loads(printed)
        self.assertIn("tracks", parsed)
        self.assertEqual(parsed["package_name"], "com.antivocale.app")
        self.assertEqual(len(parsed["tracks"]), 3)
        self.assertEqual(parsed["tracks"][0]["track"], "production")

    # ------------------------------------------------------------------
    # Custom package name
    # ------------------------------------------------------------------

    @patch("verify_play_api.build", return_value=_mock_service())
    @patch("verify_play_api.service_account.Credentials.from_service_account_file")
    @patch("verify_play_api.Path.is_file", return_value=True)
    def test_custom_package_name(self, mock_is_file, mock_creds, mock_build):
        mock_creds.return_value = _mock_creds()

        with patch("builtins.print") as mock_print:
            mod.main(["--package-name", "com.example.app", "--json", "key.json"])

        printed = mock_print.call_args_list[0].args[0]
        parsed = json.loads(printed)
        self.assertEqual(parsed["package_name"], "com.example.app")
        insert_call = mock_build.return_value.edits.return_value.insert.return_value
        insert_call.execute.assert_called_once()

    # ------------------------------------------------------------------
    # Env var JSON credentials
    # ------------------------------------------------------------------

    @patch("verify_play_api.build", return_value=_mock_service())
    @patch("verify_play_api.service_account.Credentials.from_service_account_info")
    def test_env_var_json_credentials(self, mock_creds_info, mock_build):
        mock_creds_info.return_value = _mock_creds()

        env = {"PLAY_SERVICE_ACCOUNT_JSON": '{"type": "service_account"}'}
        with patch.dict(os.environ, env, clear=True):
            exit_code = mod.main([])

        self.assertEqual(exit_code, 0)
        mock_creds_info.assert_called_once()

    # ------------------------------------------------------------------
    # Empty tracks
    # ------------------------------------------------------------------

    @patch("verify_play_api.build", return_value=_mock_service(tracks=[]))
    @patch("verify_play_api.service_account.Credentials.from_service_account_file")
    @patch("verify_play_api.Path.is_file", return_value=True)
    def test_empty_tracks_human_output(self, mock_is_file, mock_creds, mock_build):
        mock_creds.return_value = _mock_creds()

        with patch("builtins.print") as mock_print:
            mod.main(["key.json"])

        output = " ".join(call.args[0] for call in mock_print.call_args_list)
        self.assertIn("No tracks found", output)

    # ------------------------------------------------------------------
    # Format helpers (pure functions, no mocking needed)
    # ------------------------------------------------------------------

    def test_format_tracks_human_with_user_fraction(self):
        output = mod.format_tracks_human(_SAMPLE_TRACKS)
        self.assertIn("userFraction=0.1", output)

    def test_format_tracks_json_roundtrip(self):
        raw = mod.format_tracks_json(_SAMPLE_TRACKS, "com.test.app")
        parsed = json.loads(raw)
        self.assertEqual(parsed["tracks"][1]["releases"][0]["userFraction"], 0.1)

    # ------------------------------------------------------------------
    # Network error
    # ------------------------------------------------------------------

    @patch("verify_play_api.build")
    @patch("verify_play_api.service_account.Credentials.from_service_account_file")
    @patch("verify_play_api.Path.is_file", return_value=True)
    def test_network_error_returns_1(self, mock_is_file, mock_creds, mock_build):
        mock_creds.return_value = _mock_creds()
        mock_build.side_effect = OSError("Connection refused")

        exit_code = mod.main(["key.json"])
        self.assertEqual(exit_code, 1)


if __name__ == "__main__":
    unittest.main()
