"""Version-scoped normalization for the registered inventory counter observation."""
from decimal import Decimal, ROUND_HALF_EVEN

from .client import ResearchError

GAME_VERSION = "0.437.0"
NORMALIZATION_VERSION = "pgr-inventory-storage-v1"


def normalize_counters(row: dict, *, game_version: str) -> dict:
    if game_version != GAME_VERSION:
        raise ResearchError("Inventory storage semantics have not been verified for this game version")
    usage = row.get("raw_total_items")
    capacity = row.get("raw_max_items")
    if usage is not None and (type(usage) is not int or not 0 <= usage <= 2**31 - 1):
        raise ResearchError("Invalid occupied storage counter")
    if capacity is not None and (type(capacity) is not int or not 0 < capacity <= 2**31 - 1):
        raise ResearchError("Invalid advertised storage capacity")
    verified = usage is not None and capacity is not None
    difference = usage - capacity if verified else None
    percent = (float((Decimal(usage) * 100 / capacity).quantize(Decimal("0.01"), rounding=ROUND_HALF_EVEN))
               if verified else None)
    return {
        **row,
        "storage_usage": usage,
        "storage_capacity": capacity,
        "storage_available": max(0, -difference) if verified else None,
        "storage_overflow": max(0, difference) if verified else None,
        "storage_full": difference >= 0 if verified else None,
        "storage_over_capacity": difference > 0 if verified else None,
        "storage_usage_percent": percent,
    }


def normalize_inventory_observation(observation: dict, *, game_version: str) -> dict:
    if game_version != GAME_VERSION:
        raise ResearchError("Inventory storage semantics have not been verified for this game version")
    if observation.get("contract") != "inventory.bag.v1":
        raise ResearchError("Expected inventory.bag.v1 counter observation")
    if observation.get("availability") != "observed":
        return observation
    items = observation.get("items")
    if (observation.get("provenance") != "registered_provider"
            or observation.get("coverage") != "partial"
            or observation.get("collection_complete") is not None
            or observation.get("sample_scope") != "raw_counters"
            or not isinstance(items, list) or len(items) > 1
            or any(not isinstance(row, dict) for row in items)):
        raise ResearchError("Unexpected inventory counter observation")
    return {
        **observation,
        "normalization": {
            "version": NORMALIZATION_VERSION,
            "game_version": game_version,
            "provenance": "owner_ui_corroborated",
        },
        "items": [normalize_counters(row, game_version=game_version) for row in items],
    }
