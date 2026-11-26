import csv
from hashlib import sha256
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from research_sdk import ResearchError
from research_sdk.pokemon_export import complete_snapshot, save_export
from research_sdk.pokemon_normalization import normalize_export
from test_pokemon_export import FakeSnapshot


def row(mode=None, species=59, form=0, egg=False):
    return {"bread_mode_raw": mode, "species_id": species, "form_id": form,
            "is_egg": egg, "fast_move_id": 202}


def partial(*rows):
    return {"coverage": "partial", "observed_at": "synthetic", "items": list(rows)}


class PokemonNormalizationTest(unittest.TestCase):
    def test_category_rules_and_unknown_values(self):
        data = partial(row(0), row(1), row(2), row(3), row(4, 890, 2581),
                       row(4, 888, 2581), row(4, 890, 0), row(4, 890, None),
                       row(), row(egg=True), row(0, 888), row(True), row(-1))
        result = normalize_export(data, game_version="0.437.0")
        records = result["items"]
        self.assertEqual([r["max_category"] for r in records],
                         ["normal", "dynamax", "gigantamax", "unknown", "special_max",
                          "unknown", "unknown", "unknown", "unknown", "not_applicable",
                          "normal", "unknown", "unknown"])
        keys = ("can_dynamax", "can_gigantamax", "max_battle_eligible")
        self.assertEqual([tuple(r[k] for k in keys) for r in records[:5]],
                         [(False, False, False), (True, False, True), (False, True, True),
                          (None, None, None), (False, False, True)])
        self.assertIsNone(records[10]["max_battle_eligible"])
        self.assertEqual(records[8]["max_category_evidence"], "unavailable")
        self.assertIsNone(records[9]["species_name"])
        self.assertEqual(result["max_category_counts"]["special_max"], 1)
        self.assertEqual(records[0]["species_name"], "Arcanine")
        for raw, enriched in zip(data["items"], records):
            self.assertEqual(raw, {k: enriched[k] for k in raw})
        self.assertNotIn("max_category", data["items"][0])

    def test_unverified_levels_are_kept_separate(self):
        payload = partial(dict(row(1), cp_multiplier=0.5, alignment_id=1,
                               trained_level_raw=3, effective_level_raw=4))
        result = normalize_export(payload, game_version="0.437.0")["items"][0]
        self.assertEqual((result["trained_level_raw"], result["effective_level_raw"]), (3, 4))
        for key in ("pokemon_level", "max_move_level", "shadow", "purified"):
            self.assertNotIn(key, result)

    def test_pinned_move_labels_and_failure(self):
        with TemporaryDirectory() as directory:
            path = Path(directory)
            blobs = {"pvp_fast_moves.json": b'[{"move_id":202,"name":"Bite"}]',
                     "pvp_charged_moves.json": b'[{"move_id":24,"name":"Flamethrower"}]'}
            for name, raw in blobs.items():
                (path / name).write_bytes(raw)
            digest = {name: sha256(raw).hexdigest() for name, raw in blobs.items()}
            with patch("research_sdk.pokemon_normalization._MOVES", digest):
                data = partial(dict(row(0), charged_move_id=24, second_charged_move_id=9999))
                result = normalize_export(data, game_version="0.437.0", move_catalog=path)
                self.assertEqual(result["items"][0]["fast_move_name"], "Bite")
                self.assertEqual(result["items"][0]["charged_move_name"], "Flamethrower")
                self.assertIsNone(result["items"][0]["second_charged_move_name"])
                (path / "pvp_fast_moves.json").write_bytes(b"[]")
                with self.assertRaisesRegex(ResearchError, "checksum"):
                    normalize_export(data, game_version="0.437.0", move_catalog=path)
        with self.assertRaisesRegex(ResearchError, "game version"):
            normalize_export(partial(row(1)), game_version="0.0.0")

    def test_complete_csv_and_json_metadata(self):
        result = complete_snapshot(FakeSnapshot())
        result = normalize_export(result, game_version="0.437.0")
        with TemporaryDirectory() as directory:
            path = Path(directory)
            csv_file = save_export(result, path, "normalized", "csv")
            json_file = save_export(result, path, "normalized", "json")
            with csv_file.open() as stream:
                data = list(csv.DictReader(stream))
            self.assertEqual(data[0]["normalization_game_version"], "0.437.0")
            self.assertEqual(data[1]["max_category"], "not_applicable")
            parsed = json.loads(json_file.read_text())
            self.assertEqual(parsed["coverage"], "complete")
            self.assertEqual(parsed["normalization"]["locale"], "en")
            self.assertEqual(parsed["items"][0]["bread_mode_raw"], 0)


if __name__ == "__main__":
    unittest.main()
