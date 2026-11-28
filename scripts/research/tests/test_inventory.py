import unittest

from research_sdk import ResearchError
from research_sdk.inventory import normalize_counters, normalize_inventory_observation


class InventoryNormalizationTest(unittest.TestCase):
    def counters(self, usage, capacity):
        return normalize_counters(
            {"raw_total_items": usage, "raw_max_items": capacity}, game_version="0.437.0")

    def test_owner_comparisons_and_overflow(self):
        before = self.counters(1056, 1075)
        self.assertEqual((before["storage_usage"], before["storage_capacity"]), (1056, 1075))
        self.assertEqual(before["storage_available"], 19)
        self.assertEqual(before["storage_usage_percent"], 98.23)
        self.assertFalse(before["storage_full"])
        after = self.counters(1076, 1075)
        self.assertEqual(after, dict(raw_total_items=1076, raw_max_items=1075,
                                     storage_usage=1076, storage_capacity=1075,
                                     storage_available=0, storage_overflow=1, storage_full=True,
                                     storage_over_capacity=True, storage_usage_percent=100.09))

    def test_zero_equal_missing_and_invalid(self):
        equal = self.counters(1075, 1075)
        self.assertTrue(equal["storage_full"])
        self.assertFalse(equal["storage_over_capacity"])
        self.assertEqual(equal["storage_available"], 0)
        self.assertEqual(equal["storage_usage_percent"], 100)
        zero = self.counters(0, 1075)
        self.assertEqual(zero["storage_usage_percent"], 0)
        self.assertFalse(zero["storage_full"])
        no_capacity = self.counters(0, None)
        self.assertEqual(no_capacity["storage_usage"], 0)
        self.assertIsNone(no_capacity["storage_full"])
        self.assertIsNone(self.counters(None, 1075)["storage_available"])
        for usage, capacity in [(-1, 1075), (1, 0), (0, -1), (False, 1)]:
            with self.subTest(usage=usage, capacity=capacity), self.assertRaises(ResearchError):
                self.counters(usage, capacity)

    def test_coverage_and_version(self):
        obs = {"contract": "inventory.bag.v1", "provenance": "registered_provider",
               "availability": "observed", "coverage": "partial", "collection_complete": None,
               "sample_scope": "raw_counters", "items": [{"raw_total_items": 0}]}
        result = normalize_inventory_observation(obs, game_version="0.437.0")
        self.assertEqual(result["coverage"], "partial")
        self.assertIsNone(result["collection_complete"])
        self.assertIsNone(result["items"][0]["storage_capacity"])
        with self.assertRaises(ResearchError):
            normalize_inventory_observation(obs, game_version="0.0.0")
        with self.assertRaises(ResearchError):
            normalize_inventory_observation({**obs, "coverage": "complete"}, game_version="0.437.0")
