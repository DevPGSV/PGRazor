"""Validate and serialize an explicitly partial Pokémon observation."""
import csv
import json
import os
from pathlib import Path
import re
import stat
import tempfile

from .client import Client, ResearchError

_REPO = Path(__file__).resolve().parents[3]


def partial_sample(client: Client, *, limit: int, iv_percent: bool = False) -> dict:
    if type(limit) is not int or not 1 <= limit <= 10:
        raise ResearchError("Sample limit must be between 1 and 10")
    job = client.wait(client.submit_observation("pokemon.bag.v1", limit=limit))
    result = job.result or {}
    if job.state != "completed" or result.get("availability") != "observed":
        raise ResearchError("Pokémon observation unavailable")
    items = result.get("items")
    counts = result.get("counts")
    total = counts.get("total_collection_entries") if isinstance(counts, dict) else None
    if (result.get("contract") != "pokemon.bag.v1" or result.get("provenance") != "registered_provider"
            or result.get("coverage") != "partial" or result.get("collection_complete") is not None
            or result.get("freshness") != "fresh" or result.get("next_offset") is not None
            or not isinstance(result.get("observed_at"), str)
            or not isinstance(items, list) or not 1 <= len(items) <= limit
            or type(total) is not int or not len(items) <= total <= 30_000):
        raise ResearchError("Unexpected Pokémon observation metadata")

    rows = []
    seen = set()
    bread_modes: dict[str, int] = {}
    for item in items:
        if (not isinstance(item, dict) or any(
                not isinstance(k, str) or not re.fullmatch(r"[a-z_]{1,40}", k)
                or v is not None and type(v) not in (str, bool, int, float)
                for k, v in item.items())):
            raise ResearchError("Invalid Pokémon row")
        instance = item.get("instance_id")
        if (not isinstance(instance, str) or not re.fullmatch(r"[1-9][0-9]{0,19}", instance)
                or int(instance) > 2**64 - 1 or instance in seen):
            raise ResearchError("Invalid or duplicate Pokémon instance identifier")
        if rows and item.keys() != items[0].keys():
            raise ResearchError("Inconsistent Pokémon field projection")
        seen.add(instance)
        raw_mode = item.get("bread_mode_raw")
        if raw_mode is not None and (type(raw_mode) is not int or raw_mode < 0):
            raise ResearchError("Invalid raw Bread mode")
        mode = "unknown" if raw_mode is None else str(raw_mode)
        bread_modes[mode] = bread_modes.get(mode, 0) + 1
        row = dict(item)
        if iv_percent:
            ivs = [row.get(k) for k in ("attack_iv", "defense_iv", "stamina_iv")]
            row["iv_percent"] = (round(sum(ivs) * 100 / 45, 2)
                                 if all(type(v) is int and 0 <= v <= 15 for v in ivs) else None)
        rows.append(row)

    return {
        "coverage": "partial",
        "collection_complete": None,
        "contract": result["contract"],
        "provenance": result["provenance"],
        "observed_at": result["observed_at"],
        "sample_scope": result.get("sample_scope"),
        "total_backing_entries": total,
        "exported_rows": len(rows),
        "bread_mode_raw_counts": bread_modes,
        "items": rows,
    }


def save_export(result: dict, directory: Path, name: str, format: str) -> Path:
    """Publish one owner-only file atomically, with no replacement of existing files."""
    if format not in {"json", "csv"} or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", name):
        raise ResearchError("Invalid export name or format")
    directory = directory.resolve(strict=True)
    info = directory.stat()
    if (not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077
            or directory == _REPO or _REPO in directory.parents):
        raise ResearchError("Choose an existing owner-only directory outside the repository")
    target = directory / f"{name}-partial.{format}"
    fd, temporary = tempfile.mkstemp(prefix=".pokemon-export-", dir=directory)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as stream:
            if format == "json":
                json.dump(result, stream, ensure_ascii=False, allow_nan=False, indent=2)
                stream.write("\n")
            else:
                metadata = {"export_coverage": "partial", "observed_at": result["observed_at"]}
                writer = csv.DictWriter(stream, fieldnames=[*metadata, *result["items"][0]])
                writer.writeheader()
                for row in result["items"]:
                    writer.writerow({**metadata, **{k: _csv_cell(v) for k, v in row.items()}})
        os.link(temporary, target)
    except FileExistsError:
        raise ResearchError("Export file already exists") from None
    finally:
        os.unlink(temporary)
    return target


def _csv_cell(value):
    if value is None:
        return "null"
    if type(value) is bool:
        return str(value).lower()
    if isinstance(value, str) and value.lstrip().startswith(("=", "+", "-", "@")):
        return "'" + value
    return value
