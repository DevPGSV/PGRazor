from copy import deepcopy
import csv
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from research_sdk import Job, ResearchError
from research_sdk.pokemon_export import _FIELDS, complete_snapshot, partial_sample, save_export


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


class FakeSnapshot:
    id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    revision = "20"

    def __init__(self):
        self.rows = [dict.fromkeys(sorted(_FIELDS)) | row for row in observed()["items"]]
        self.rows[0]["is_egg"] = False
        self.rows[1]["is_egg"] = True
        self.timeout = 4
        self.expected = len(self.rows)
        self.edit = lambda page, offset: None

    def _request(self, endpoint, method="GET", **params):
        if endpoint == "collection/start":
            self.page_size = params["page_size"]
            return {"id": self.id, "revision": self.revision, "state": "completed",
                    "captured_entry_count": self.expected, "source_entry_count": self.expected,
                    "cursor": f"{self.id}.{self.revision}.0"}
        offset = int(params["cursor"].split(".")[-1])
        end = min(len(self.rows), offset + self.page_size)
        page = {"snapshot_id": self.id, "revision": self.revision, "coverage": "complete",
                "collection_complete": True, "consistency": "non_atomic", "immutable": True,
                "structural_consistency": "verified", "freshness": "retained",
                "source_entry_count": self.expected, "captured_entry_count": self.expected,
                "capture_started_at": "2026-11-26T12:00:00Z", "capture_finished_at": "2026-11-26T12:00:01Z",
                "capture_elapsed_ms": 1000, "classified_egg_rows": 1, "classified_pokemon_rows": self.expected - 1,
                "offset": offset, "items": self.rows[offset:end],
                "next_cursor": f"{self.id}.{self.revision}.{end}" if end < len(self.rows) else None}
        self.edit(page, offset)
        return page


class CompleteExportTest(unittest.TestCase):
    def test_snapshot_and_selection(self):
        result = complete_snapshot(FakeSnapshot(), page_size=1, selection="eggs")
        self.assertEqual(result["source_entry_count"], 2)
        self.assertEqual(result["exported_rows"], 1)
        self.assertIs(result["items"][0]["is_egg"], True)
        with TemporaryDirectory() as temp:
            path = save_export(result, Path(temp), "sample", "csv")
            self.assertEqual(path.name, "sample-complete.csv")
            with path.open() as file:
                row = next(csv.DictReader(file))
            self.assertEqual(row["export_consistency"], "non_atomic")
            self.assertEqual(row["instance_id"], "2")

    def test_large_fixture(self):
        fixture = FakeSnapshot()
        fixture.rows += [dict(fixture.rows[0], instance_id=str(n)) for n in range(3, 2049)]
        fixture.expected = len(fixture.rows)
        result = complete_snapshot(fixture)
        self.assertEqual(result["exported_rows"], 2048)

    def test_changed_identity_revision_counts_or_cursor_rejected(self):
        for key, value in (("snapshot_id", "changed"), ("revision", "21"),
                           ("source_entry_count", 3), ("next_cursor", "unexpected"),
                           ("consistency", "atomic"), ("collection_complete", False)):
            with self.subTest(key=key), self.assertRaises(ResearchError):
                fixture = FakeSnapshot()
                fixture.edit = lambda page, offset: page.update({key: value}) if offset else None
                complete_snapshot(fixture, page_size=1)

    def test_duplicate_or_missing_rows_rejected(self):
        fixture = FakeSnapshot()
        fixture.rows[1]["instance_id"] = fixture.rows[0]["instance_id"]
        with self.assertRaises(ResearchError):
            complete_snapshot(fixture)
        fixture.expected = 3
        with self.assertRaises(ResearchError):
            complete_snapshot(fixture)


if __name__ == "__main__":
    unittest.main()
