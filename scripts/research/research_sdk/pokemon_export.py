"""Validate and serialize private Pokémon observations and collection snapshots."""
import csv
from datetime import datetime
import json
import os
from pathlib import Path
import re
import stat
import tempfile
import time

from .client import Client, ResearchError

_REPO = Path(__file__).resolve().parents[3]
_FIELDS = frozenset("""
instance_id species_id cp attack_iv defense_iv stamina_iv favorite is_egg nickname hp max_hp
fast_move_id charged_move_id second_charged_move_id lucky cp_multiplier creation_time_ms
shiny form_id costume_id gender_id alignment_id bread_mode_raw
""".split())


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

    rows, bread_modes = _validated_rows(items, iv_percent=iv_percent)

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


def _validated_rows(items, *, iv_percent):
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

    return rows, bread_modes


def complete_snapshot(client: Client, *, page_size: int = 10, selection: str = "all",
                      iv_percent: bool = False) -> dict:
    if (type(page_size) is not int or not 1 <= page_size <= 10
            or selection not in {"all", "pokemon", "eggs"}):
        raise ResearchError("Invalid collection export options")
    job = client._request("collection/start", "POST", page_size=page_size)
    job_id = job.get("id")
    revision = job.get("revision")
    if (not isinstance(job_id, str) or not re.fullmatch(r"[0-9a-f-]{36}", job_id)
            or not isinstance(revision, str) or not re.fullmatch(r"[1-9][0-9]{0,18}", revision)):
        raise ResearchError("Invalid collection job identity")
    deadline = time.monotonic() + 38
    try:
        while job.get("state") == "capturing":
            remaining = deadline - time.monotonic()
            if remaining <= 0.25:
                raise ResearchError("Collection acquisition deadline reached")
            time.sleep(0.25)
            job = client._request("collection/status", id=job_id,
                                  timeout=min(client.timeout, remaining - 0.25))
            if job.get("id") != job_id or job.get("revision") != revision:
                raise ResearchError("Collection job identity changed")
        if job.get("state") != "completed":
            raise ResearchError("Complete collection capture did not finish")
    except (ResearchError, KeyboardInterrupt):
        try:
            client._request("collection/cancel", "POST", id=job_id)
        except ResearchError:
            pass
        raise

    count = job.get("captured_entry_count")
    if type(count) is not int or not 1 <= count <= 2048 or job.get("source_entry_count") != count:
        raise ResearchError("Invalid complete collection count")
    metadata = None
    items = []
    token = job.get("cursor")
    while len(items) < count:
        expected = f"{job_id}.{revision}.{len(items)}"
        if token != expected:
            raise ResearchError("Invalid or non-advancing collection cursor")
        page = client._request("collection/page", cursor=token)
        fields = page.get("items")
        if (page.get("snapshot_id") != job_id or page.get("revision") != revision
                or page.get("coverage") != "complete" or page.get("consistency") != "non_atomic"
                or page.get("structural_consistency") != "verified" or page.get("immutable") is not True
                or page.get("collection_complete") is not True or page.get("freshness") != "retained"
                or page.get("offset") != len(items) or page.get("source_entry_count") != count
                or page.get("captured_entry_count") != count or not isinstance(fields, list)
                or len(fields) != min(page_size, count - len(items))
                or "next_cursor" not in page):
            raise ResearchError("Unexpected complete collection page")
        current = {k: v for k, v in page.items() if k not in {"offset", "next_cursor", "items"}}
        if metadata is None:
            metadata = current
        elif metadata != current:
            raise ResearchError("Collection snapshot metadata changed")
        items.extend(fields)
        token = page["next_cursor"]
    if token is not None:
        raise ResearchError("Collection end was not confirmed")

    try:
        start = datetime.fromisoformat(metadata["capture_started_at"])
        end = datetime.fromisoformat(metadata["capture_finished_at"])
        elapsed = metadata["capture_elapsed_ms"]
        if (start.tzinfo is None or end.tzinfo is None or type(elapsed) is not int
                or not 0 <= elapsed < 35_000 or abs((end - start).total_seconds() * 1000 - elapsed) > 1):
            raise ValueError()
    except (KeyError, TypeError, ValueError):
        raise ResearchError("Invalid collection capture interval") from None
    rows, bread_modes = _validated_rows(items, iv_percent=iv_percent)
    if any(row.keys() != _FIELDS or type(row["is_egg"]) is not bool for row in items):
        raise ResearchError("Unexpected complete collection field projection")
    eggs = sum(row["is_egg"] for row in rows)
    if metadata.get("classified_egg_rows") != eggs or metadata.get("classified_pokemon_rows") != count - eggs:
        raise ResearchError("Collection classification totals differ")
    selected = rows if selection == "all" else [row for row in rows if row["is_egg"] == (selection == "eggs")]
    return {**metadata, "selection": selection, "exported_rows": len(selected),
            "bread_mode_raw_counts": bread_modes, "fields": list(rows[0]), "items": selected}


def save_export(result: dict, directory: Path, name: str, format: str) -> Path:
    """Publish one owner-only file atomically, with no replacement of existing files."""
    if format not in {"json", "csv"} or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", name):
        raise ResearchError("Invalid export name or format")
    directory = directory.resolve(strict=True)
    info = directory.stat()
    if (not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077
            or directory == _REPO or _REPO in directory.parents):
        raise ResearchError("Choose an existing owner-only directory outside the repository")
    coverage = result.get("coverage")
    if coverage not in {"partial", "complete"}:
        raise ResearchError("Invalid export coverage")
    target = directory / f"{name}-{coverage}.{format}"
    fd, temporary = tempfile.mkstemp(prefix=".pokemon-export-", dir=directory)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as stream:
            if format == "json":
                json.dump(result, stream, ensure_ascii=False, allow_nan=False, indent=2)
                stream.write("\n")
            else:
                if coverage == "complete":
                    metadata = {"export_coverage": coverage, "export_consistency": result["consistency"],
                                "snapshot_id": result["snapshot_id"], "snapshot_revision": result["revision"],
                                "capture_started_at": result["capture_started_at"],
                                "capture_finished_at": result["capture_finished_at"],
                                "export_selection": result["selection"]}
                    fields = result["fields"]
                else:
                    metadata = {"export_coverage": coverage, "observed_at": result["observed_at"]}
                    fields = list(result["items"][0])
                writer = csv.DictWriter(stream, fieldnames=[*metadata, *fields])
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
