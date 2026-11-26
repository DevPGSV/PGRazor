"""Versioned, evidence-scoped normalization for exported Pokémon rows."""
from collections import Counter
from hashlib import sha256
import json
from pathlib import Path
import re

from .client import ResearchError

NORMALIZATION_VERSION = "pgr-pokemon-en-v1"
GAME_VERSION = "0.437.0"
_SPECIES = Path(__file__).resolve().parents[3] / "pgrazor/assets/map-icons/pokemon-names.json"
_SPECIES_SHA = "769d9d90207def665020023acaa134851228d057b31ceafc2ab9aad42f8d3d5c"
_MOVES = {
    "pvp_fast_moves.json": "0b3921f424d42adef758cfc5b249cab9f96dae420a9318cea5bdabb67d32877c",
    "pvp_charged_moves.json": "dde3ebc9f45389dbdc1a58533ac627845b638c897b1a214807e22b07b4d7c46a",
}
_MAX_MODES = {0: ("normal", False, False, False),
              1: ("dynamax", True, False, True),
              2: ("gigantamax", False, True, True)}
_CATEGORIES = ("normal", "dynamax", "gigantamax", "special_max", "unknown", "not_applicable")


def _read_labels(path: Path, checksum: str):
    try:
        with path.open("rb") as stream:
            raw = stream.read(512 * 1024 + 1)
        if len(raw) > 512 * 1024 or sha256(raw).hexdigest() != checksum:
            raise ResearchError("Pokémon label catalog checksum mismatch")
        return json.loads(raw)
    except (OSError, ValueError):
        raise ResearchError("Could not load Pokémon label catalog") from None


def _label(value):
    if not isinstance(value, str) or not 1 <= len(value) <= 100 or any(ord(ch) < 32 for ch in value):
        raise ResearchError("Invalid Pokémon label")
    return value


def _moves(path: Path, checksum: str) -> dict[int, str]:
    rows = _read_labels(path, checksum)
    if not isinstance(rows, list):
        raise ResearchError("Invalid move label catalog")
    result = {}
    for row in rows:
        key = row.get("move_id") if isinstance(row, dict) else None
        if type(key) is not int or not 0 < key <= 65535 or key in result:
            raise ResearchError("Invalid or duplicate move identifier")
        result[key] = _label(row.get("name"))
    return result


def _category(row):
    egg = row.get("is_egg")
    mode = row.get("bread_mode_raw")
    species = row.get("species_id")
    if egg is True:
        return "not_applicable", None, None, None, "verified_egg"
    if egg is not False or mode is None:
        return "unknown", None, None, None, "unavailable"
    if type(mode) is not int:
        return "unknown", None, None, None, "unverified_mode"
    if mode == 4 and (species, row.get("form_id")) == (890, 2581):
        return "special_max", False, False, True, "owner_ui_eternatus_v1"
    if mode in _MAX_MODES:
        category, dynamax, gigantamax, eligible = _MAX_MODES[mode]
        # Eligibility of Crowned forms is independently documented. Their raw mode is unverified.
        if mode == 0 and species in (888, 889, 890):
            eligible = None
        return category, dynamax, gigantamax, eligible, "owner_ui_v1"
    return "unknown", None, None, None, "unverified_mode"


def normalize_export(result: dict, *, game_version: str, move_catalog: Path | None = None) -> dict:
    """Enrich validated export rows with English labels and scoped Max interpretations."""
    if game_version != GAME_VERSION:
        raise ResearchError("No Pokémon normalization rules for this game version")
    names = _read_labels(_SPECIES, _SPECIES_SHA)
    if not isinstance(names, dict) or any(not re.fullmatch(r"0|[1-9][0-9]{0,3}", key) for key in names):
        raise ResearchError("Invalid species label catalog")
    names = {int(key): _label(label) for key, label in names.items() if key != "0"}
    fast, charged = ({}, {})
    if move_catalog is not None:
        fast, charged = (_moves(move_catalog / filename, sha) for filename, sha in _MOVES.items())

    def lookup(mapping, raw):
        return mapping.get(raw) if type(raw) is int else None

    def labels(row):
        category, dynamax, gigantamax, eligible, evidence = _category(row)
        pokemon = row.get("is_egg") is False
        return {
            "species_name": lookup(names, row.get("species_id")) if pokemon else None,
            "fast_move_name": lookup(fast, row.get("fast_move_id")) if pokemon else None,
            "charged_move_name": lookup(charged, row.get("charged_move_id")) if pokemon else None,
            "second_charged_move_name": lookup(charged, row.get("second_charged_move_id")) if pokemon else None,
            "max_category": category,
            "can_dynamax": dynamax,
            "can_gigantamax": gigantamax,
            "max_battle_eligible": eligible,
            "max_category_evidence": evidence,
        }

    rows = [{**row, **labels(row)} for row in result["items"]]
    totals = Counter(row["max_category"] for row in rows)
    fields = result.get("fields") or list(result["items"][0])
    return {**result, "items": rows, "fields": [*fields, *labels({})],
            "max_category_counts": {key: totals[key] for key in _CATEGORIES},
            "normalization": {"version": NORMALIZATION_VERSION, "game_version": game_version,
                              "locale": "en", "species_sha256": _SPECIES_SHA,
                              "move_catalog_sha256": dict(_MOVES) if move_catalog is not None else {}}}
