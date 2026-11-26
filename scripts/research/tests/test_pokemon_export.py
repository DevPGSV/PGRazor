from copy import deepcopy
import csv
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from research_sdk import Job, ResearchError
from research_sdk.pokemon_export import partial_sample, save_export


def observed():
    return {"contract": "pokemon.bag.v1", "provenance": "registered_provider",
            "coverage": "partial", "collection_complete": None, "availability": "observed",
            "freshness": "fresh", "observed_at": "2026-11-26T10:00:00Z", "next_offset": None,
            "sample_scope": "backing_list_prefix", "counts": {"total_collection_entries": 621},
            "items": [{"instance_id": "18446744073709551615", "attack_iv": 0, "defense_iv": 15,
                       "stamina_iv": 15, "bread_mode_raw": 0, "nickname": "", "favorite": False},
                      {"instance_id": "2", "attack_iv": None, "defense_iv": None,
                       "stamina_iv": None, "bread_mode_raw": None, "nickname": "=3+4", "favorite": True}]}


class FakeClient:
    def __init__(self, result, state="completed"):
        self.job = Job("job", state, None, result)

    def submit_observation(self, contract, *, limit):
        return self.job

    def wait(self, job):
        return job


class PokemonExportTest(unittest.TestCase):
    def test_validation_preserves_types_and_groups_raw_values(self):
        result = partial_sample(FakeClient(observed()), limit=5, iv_percent=True)
        self.assertEqual(result["bread_mode_raw_counts"], {"0": 1, "unknown": 1})
        first, second = result["items"]
        self.assertEqual(first["instance_id"], "18446744073709551615")
        self.assertEqual(first["nickname"], "")
        self.assertIs(first["favorite"], False)
        self.assertEqual(first["attack_iv"], 0)
        self.assertEqual(first["iv_percent"], 66.67)
        self.assertIsNone(second["iv_percent"])

    def test_rejects_unavailable_cancelled_stale_and_malformed(self):
        for changes in ({"availability": "unavailable"}, {"freshness": "stale"},
                        {"collection_complete": True}, {"items": []},
                        {"counts": {"total_collection_entries": 1}},
                        {"next_offset": 1}):
            with self.subTest(changes=changes), self.assertRaises(ResearchError):
                partial_sample(FakeClient({**observed(), **changes}), limit=5)
        with self.assertRaises(ResearchError):
            partial_sample(FakeClient(observed(), "cancelled"), limit=5)
        for limit in (0, 11, True):
            with self.subTest(limit=limit), self.assertRaises(ResearchError):
                partial_sample(FakeClient(observed()), limit=limit)

    def test_rejects_duplicate_and_noncanonical_ids(self):
        for invalid in ("18446744073709551615", "18446744073709551616", "02", 2):
            with self.subTest(invalid=invalid), self.assertRaises(ResearchError):
                reply = deepcopy(observed())
                reply["items"][1]["instance_id"] = invalid
                partial_sample(FakeClient(reply), limit=5)

    def test_json_and_csv_are_explicitly_partial_and_exclusive(self):
        result = partial_sample(FakeClient(observed()), limit=2)
        with TemporaryDirectory() as temp:
            directory = Path(temp)
            json_path = save_export(result, directory, "test", "json")
            self.assertEqual(json.loads(json_path.read_text()), result)
            self.assertEqual(json_path.stat().st_mode & 0o777, 0o600)
            with self.assertRaisesRegex(ResearchError, "already exists"):
                save_export(result, directory, "test", "json")
            csv_path = save_export(result, directory, "test", "csv")
            with csv_path.open(newline="") as stream:
                first, second = csv.DictReader(stream)
            self.assertEqual(first["export_coverage"], "partial")
            self.assertEqual(first["instance_id"], "18446744073709551615")
            self.assertEqual(first["nickname"], "")
            self.assertEqual(second["attack_iv"], "null")
            self.assertEqual(second["nickname"], "'=3+4")
            self.assertEqual(sorted(p.name for p in directory.iterdir()), ["test-partial.csv", "test-partial.json"])

    def test_rejects_insecure_directory_and_path_traversal(self):
        result = partial_sample(FakeClient(observed()), limit=2)
        with TemporaryDirectory() as temp:
            directory = Path(temp)
            with self.assertRaises(ResearchError):
                save_export(result, directory, "../escape", "json")
            directory.chmod(0o755)
            with self.assertRaises(ResearchError):
                save_export(result, directory, "test", "json")


if __name__ == "__main__":
    unittest.main()
