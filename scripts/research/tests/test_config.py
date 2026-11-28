import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from research_sdk.config import resolve_config


class ConfigurationTest(unittest.TestCase):
    @patch.dict(os.environ, {
        "PGRAZOR_BASE": "http://127.0.0.1:8765",
        "PGRAZOR_HTTP_TOKEN": "p" * 43,
        "PGRAZOR_RESEARCH_TOKEN": "r" * 43,
    }, clear=True)
    def test_realm_and_explicit_override(self):
        self.assertEqual(resolve_config("production")[1], "p" * 43)
        self.assertEqual(resolve_config("research")[1], "r" * 43)
        with tempfile.TemporaryDirectory() as directory:
            credential = Path(directory) / "credential"
            credential.touch(mode=0o600)
            credential.write_text("x" * 43 + "\n")
            self.assertEqual(resolve_config("research", base="http://localhost:1234",
                                            token_file=credential),
                             ("http://localhost:1234", "x" * 43))
            credential.write_text("invalid")
            with self.assertRaisesRegex(ValueError, "Invalid protected"):
                resolve_config("research", token_file=credential)

    @patch.dict(os.environ, {"PGRAZOR_HTTP_TOKEN": "p" * 43}, clear=True)
    @patch("research_sdk.config.getpass.getpass", side_effect=AssertionError("unexpected prompt"))
    def test_no_cross_realm_fallback_or_noninteractive_prompt(self, prompt):
        with self.assertRaisesRegex(ValueError, "PGRAZOR_RESEARCH_TOKEN"):
            resolve_config("research", base="http://localhost:8765")
