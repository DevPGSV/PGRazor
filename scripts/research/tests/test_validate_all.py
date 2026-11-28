import json
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest.mock import patch

from research_sdk import Job, ResearchError
from research_sdk.client import InvalidResponse
from research_sdk.config import resolve_config

tool = runpy.run_path(str(Path(__file__).resolve().parents[1] / "validate-all.py"))
Matrix = tool["Matrix"]


class Production:
    response_bytes = 0

    def _request(self, endpoint, **query):
        self.response_bytes += 32
        if endpoint == "status":
            return {
                "account_ready": True,
                "capabilities": {provider: {"supported": provider in ("trainer", "pokemon")}
                                 for provider in tool["PROVIDERS"]},
            }
        if endpoint == "trainer":
            return {"coverage": "partial", "data": {"name": "private-name", "xp": 0}}
        if query.get("refresh") == "invalid":
            raise ResearchError("invalid", 400)
        return {"coverage": "complete", "collection_complete": True, "source_entry_count": 1,
                "classified_pokemon_rows": 1, "classified_egg_rows": 0, "eggs": [],
                "pokemon": [{"instance_id": "9938812", "is_egg": False}],
                "snapshot_id": "refresh-private-id" if query else "private-id"}


class Research:
    response_bytes = 0

    def status(self):
        return {"ready": True}

    def capabilities(self):
        return {"operations": ["collection/start"]}

    def observation_contracts(self):
        return {"contracts": [
            {"id": "trainer.snapshot.v1", "ready": True, "fields": {"xp": "integer"}},
            {"id": "inventory.bag.v1", "ready": False, "fields": {"raw_total_items": "integer"}},
            {"id": "incubators.v1", "ready": False, "fields": {}},
        ]}

    def submit_observation(self, contract, *, fields):
        if contract == "inventory.bag.v1":
            return Job("sample", "completed", None, {"availability": "unavailable"})
        assert fields == ("xp",)
        return Job("sample", "completed", None,
                   {"availability": "observed", "coverage": "partial", "contract": contract,
                    "items": [{"xp": 1234987}]})

    def wait(self, job):
        return job


class ValidationMatrixTest(unittest.TestCase):
    def test_independent_provider_results_and_redaction(self):
        matrix = Matrix()
        self.assertTrue(matrix.production(Production(), refresh=True))
        self.assertTrue(matrix.research(Research(), complete=False))
        actual = {row["check"]: row["status"] for row in matrix.results}
        self.assertEqual(actual["production.pokemon"], "PASS")
        self.assertEqual(actual["production.pokemon.reuse"], "PASS")
        self.assertEqual(actual["production.pokemon.refresh"], "PASS")
        self.assertEqual(actual["production.journal"], "UNSUPPORTED")
        self.assertEqual(actual["research.inventory.bag.v1"], "BLOCKED")
        self.assertEqual(actual["research.incubators.v1"], "UNSUPPORTED")
        self.assertEqual(actual["research.trainer.snapshot.v1"], "PARTIAL")
        report = json.dumps(matrix.results)
        for private in ("private-name", "private-id", "9938812", "1234987"):
            self.assertNotIn(private, report)

    def test_bad_response_is_regression_and_expected_error_passes(self):
        matrix = Matrix()
        client = Production()

        def bad():
            raise InvalidResponse("malformed")

        matrix.probe("invalid", client, bad)
        matrix.probe("negative", client, lambda: client._request("pokemon", refresh="invalid"), expected=400)
        def inactive():
            raise ResearchError("unavailable", 503, "RESOURCE_PROVIDER_INACTIVE")
        matrix.probe("inactive_ready", client, inactive, fail_codes=("RESOURCE_PROVIDER_INACTIVE",))
        self.assertEqual([row["status"] for row in matrix.results], ["FAIL", "PASS", "FAIL"])

    def test_retry_only_verified_snapshot_unavailable(self):
        matrix = Matrix()
        unavailable = Job("sample", "completed", None, {
            "availability": "unavailable", "reason": "verified_snapshot_unavailable",
        })
        observed = Job("sample", "completed", None, {"availability": "observed"})
        responses = iter((unavailable, observed))
        with patch.dict(Matrix.probe.__globals__, {"sleep": lambda seconds: self.assertEqual(seconds, 5.2)}):
            matrix.probe("snapshot", Production(), lambda: next(responses), retry_unavailable=True)
            def unauthorized():
                raise ResearchError("credential rejected", 401)
            matrix.probe("auth", Production(), unauthorized, retry_unavailable=True)
        self.assertEqual([row["attempts"] for row in matrix.results], [2, 1])
        self.assertEqual([row["status"] for row in matrix.results], ["PASS", "BLOCKED"])

    def test_invalid_counts_and_restrictive_credentials(self):
        invalid = Production()._request("pokemon")
        invalid["source_entry_count"] = 2
        with self.assertRaises(ValueError):
            tool["pokemon"](invalid)
        with tempfile.TemporaryDirectory() as temp:
            token = Path(temp) / "token"
            token.write_text("x" * 43)
            token.chmod(0o644)
            with self.assertRaises(ValueError):
                resolve_config("production", base="http://localhost", token_file=token)[1]
            token.chmod(0o600)
            self.assertEqual(resolve_config("production", base="http://localhost", token_file=token)[1], "x" * 43)
            link = Path(temp) / "link"
            link.symlink_to(token)
            with self.assertRaises(ValueError):
                resolve_config("production", base="http://localhost", token_file=link)[1]
