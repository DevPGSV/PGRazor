#!/usr/bin/env python3
"""Run a bounded, redacted device acceptance matrix for the two API realms."""
import argparse
import json
import math
import os
from pathlib import Path
import re
from time import monotonic, sleep

from research_sdk import Client, ResearchError
from research_sdk.client import InvalidResponse
from research_sdk.pokemon_export import complete_snapshot

PROVIDERS = ("pokemon", "inventory", "trainer", "pokedex", "eggs", "journal", "location")
MANUAL = (
    "Compare representative Pokémon, eggs and available trainer fields with the game UI.",
    "Compare inventory quantities and counters with the game UI.",
    "Verify configured, represented and device location labels against the app settings.",
    "Exercise token rotation, account changes and listener shutdown.",
    "During active development, use the Research APK with both APIs enabled for one combined matrix.",
)


class ProductionClient(Client):
    _prefix = "/api/v1/"
    _timeout_limit = 50
    _response_limit = 2 * 1024 * 1024
    _realm = "Production"


def coverage(value):
    candidate = value.get("coverage")
    if candidate not in ("complete", "partial", "unknown"):
        raise ValueError("Invalid coverage")
    return candidate


def pokemon(value):
    if coverage(value) != "complete" or value.get("collection_complete") is not True:
        raise ValueError("Complete Pokémon coverage required")
    rows = []
    for key, flag in (("pokemon", False), ("eggs", True)):
        group = value.get(key)
        if not isinstance(group, list):
            raise ValueError("Expected a row array")
        for row in group:
            if not isinstance(row, dict) or row.get("is_egg") is not flag:
                raise ValueError("Invalid row category")
            instance = row.get("instance_id")
            if not isinstance(instance, str) or not re.fullmatch(r"[1-9][0-9]{0,19}", instance) or int(instance) >= 2**64:
                raise ValueError("Invalid instance identifier")
            rows.append(instance)
    if len(set(rows)) != len(rows) or value.get("source_entry_count") != len(rows):
        raise ValueError("Collection does not reconcile")
    if any(value.get("classified_" + key + "_rows") != len(value[group])
           for key, group in (("pokemon", "pokemon"), ("egg", "eggs"))):
        raise ValueError("Classified counts do not reconcile")
    if not isinstance(value.get("snapshot_id"), str) or not value["snapshot_id"]:
        raise ValueError("Snapshot identity absent")
    return value["snapshot_id"]


class Matrix:
    def __init__(self):
        self.results = []

    def record(self, name, status, **details):
        reasons = {
            "BLOCKED": "request prerequisites unavailable",
            "PARTIAL": "endpoint reports partial or unknown coverage",
            "UNSUPPORTED": "provider declares no supported operation",
            "FAIL": "operation or response did not meet the contract",
            "NOT_TESTED": "check was skipped or a prerequisite failed",
        }
        if status in reasons:
            details.setdefault("reason", reasons[status])
        self.results.append({"check": name, "status": status, **details})

    def probe(self, name, client, request, inspect=lambda value: "PASS", expected=None, fail_codes=(), retry_unavailable=False):
        began, before = monotonic(), client.response_bytes
        value = None
        http = code = reason = None
        attempts = 1
        try:
            value = request()
            if retry_unavailable and self.snapshot_unavailable(value):
                sleep(5.2)
                attempts += 1
                value = request()
            if self.snapshot_unavailable(value):
                reason = "verified snapshot unavailable after bounded observation"
            if expected is None:
                outcome = inspect(value)
            else:
                outcome = "FAIL"
        except ResearchError as error:
            http, code = error.status, error.code
            if isinstance(error, InvalidResponse) or error.code in fail_codes:
                outcome = "FAIL"
            elif expected is not None:
                outcome = "PASS" if http == expected else "BLOCKED" if http is None else "FAIL"
            else:
                outcome = (
                    "BLOCKED" if http in (None, 401, 409, 429, 503, 504) else "FAIL"
                )
            if outcome == "BLOCKED":
                reason = {
                    None: "LAN endpoint unavailable or request timed out",
                    401: "check the credential and activation for this realm",
                    409: "request conflicts with the current provider or session state",
                    429: "server request budget exhausted",
                    503: "provider or verified account not ready",
                    504: "server acquisition deadline reached",
                }[http]
            value = None
        except (KeyError, TypeError, ValueError):
            outcome = "FAIL"
            value = None
        self.record(name, outcome, latency_ms=round((monotonic() - began) * 1000, 1),
                    response_bytes=client.response_bytes - before, http_error=http,
                    error_code=code, attempts=attempts,
                    **({"retried_after": "verified_snapshot_unavailable"} if attempts == 2 else {}),
                    **({"reason": reason} if reason else {}))
        return value if outcome in ("PASS", "PARTIAL") else None

    @staticmethod
    def snapshot_unavailable(value):
        result = getattr(value, "result", None)
        return (isinstance(result, dict) and result.get("availability") == "unavailable"
                and result.get("reason") == "verified_snapshot_unavailable")

    def production(self, client, refresh):
        def status_check(value):
            caps = value.get("capabilities")
            if type(value.get("account_ready")) is not bool or not isinstance(caps, dict):
                raise ValueError("Invalid Production status")
            return "PASS"
        status = self.probe("production.status", client, lambda: client._request("status"), status_check)
        if status is None:
            for provider in PROVIDERS:
                self.record("production." + provider, "NOT_TESTED")
            return False
        for provider in PROVIDERS:
            advertised = status["capabilities"].get(provider, {})
            name = "production." + provider
            if not isinstance(advertised, dict) or type(advertised.get("supported")) is not bool:
                self.record(name, "FAIL")
                continue
            if not advertised["supported"]:
                self.record(name, "UNSUPPORTED")
                continue

            def inspect(value):
                if provider == "pokemon":
                    pokemon(value)
                    return "PASS"
                if provider == "trainer":
                    data = value.get("data")
                    if not isinstance(data, dict) or not any(
                        field in data for field in ("name", "level", "xp", "pokemon_caught")
                    ):
                        raise ValueError("Missing trainer data")
                    for field in ("level", "xp", "pokemon_caught", "pokemon_encountered",
                                  "pokestops_visited", "eggs_hatched", "xp_in_level_bracket",
                                  "xp_remaining_to_next_threshold"):
                        if data.get(field) is not None and (type(data[field]) is not int or data[field] < 0):
                            raise ValueError("Invalid trainer counter")
                    if data.get("name") is not None and not isinstance(data["name"], str):
                        raise ValueError("Invalid trainer name")
                elif provider == "location":
                    data = value.get("data")
                    if not isinstance(data, dict):
                        raise ValueError("Invalid location payload")
                    simulated = data.get("configured_simulated")
                    if not isinstance(simulated, dict) or type(simulated.get("override_requested")) is not bool:
                        raise ValueError("Invalid simulated location")
                    for key, bound in (("latitude", 90), ("longitude", 180)):
                        coordinate = simulated.get(key)
                        if type(coordinate) not in (int, float) or not math.isfinite(coordinate) or abs(coordinate) > bound:
                            raise ValueError("Invalid coordinate")
                    speed = simulated.get("configured_speed_kmh")
                    if speed is not None and (type(speed) not in (int, float) or not math.isfinite(speed) or speed < 0):
                        raise ValueError("Invalid configured speed")
                    if data.get("actual_device") is not None or data.get("represented_to_game") is not None:
                        raise ValueError("Unexpected location source")
                elif provider == "eggs":
                    eggs = value.get("eggs")
                    if (not isinstance(eggs, list) or value.get("coverage_scope") != "pokemon_bag_egg_membership"
                            or value.get("classified_egg_rows") != len(eggs) or value.get("incubators") is not None):
                        raise ValueError("Invalid egg collection")
                    identifiers = set()
                    for row in eggs:
                        if not isinstance(row, dict) or row.get("is_egg") is not True:
                            raise ValueError("Invalid egg row")
                        instance = row.get("instance_id")
                        if (not isinstance(instance, str) or not re.fullmatch(r"[1-9][0-9]{0,19}", instance)
                                or int(instance) >= 2**64 or instance in identifiers):
                            raise ValueError("Invalid or duplicate egg identifier")
                        identifiers.add(instance)
                        expected = {"egg_type_id": int, "assignment_present": bool}
                        for key, kind in expected.items():
                            if row.get(key) is not None and type(row[key]) is not kind:
                                raise ValueError("Invalid egg field")
                        distance = row.get("required_distance_km")
                        if distance is not None and (type(distance) not in (int, float)
                                                      or not math.isfinite(distance) or distance <= 0):
                            raise ValueError("Invalid egg distance")
                        for key in ("raw_start_distance_km", "raw_target_distance_km"):
                            raw = row.get(key)
                            if raw is not None and (type(raw) not in (int, float) or not math.isfinite(raw) or raw < 0):
                                raise ValueError("Invalid raw egg distance")
                return "PASS" if coverage(value) == "complete" else "PARTIAL"

            fail_codes = ("RESOURCE_PROVIDER_INACTIVE", "RESOURCE_SCHEDULING_FAILED") if status["account_ready"] else ()
            value = self.probe(name, client, lambda: client._request(provider), inspect, fail_codes=fail_codes)
            if self.results[-1]["status"] == "BLOCKED":
                self.results[-1]["account_ready_at_status"] = status["account_ready"]
                state = advertised.get("provider_state")
                if state in ("inactive", "account_unverified", "no_snapshot", "ready", "acquiring",
                             "source_unavailable", "scheduling_failed", "stale"):
                    self.results[-1]["provider_state_at_status"] = state
            if provider == "pokemon" and value is not None:
                previous = pokemon(value)
                self.probe(name + ".reuse", client, lambda: client._request(provider),
                           lambda latest: "PASS" if pokemon(latest) == previous else "FAIL")
                if refresh:
                    self.probe(name + ".refresh", client, lambda: client._request(provider, refresh="true"),
                               lambda latest: "PASS" if pokemon(latest) != previous else "FAIL")
                else:
                    self.record(name + ".refresh", "NOT_TESTED")
                self.probe(name + ".invalid_query", client,
                           lambda: client._request(provider, refresh="invalid"), expected=400)
        return True

    def research(self, client, complete):
        status = self.probe("research.status", client, client.status)
        if status is None:
            self.record("research.observations", "NOT_TESTED")
            return False
        capabilities = self.probe("research.capabilities", client, client.capabilities)

        def registry_check(value):
            contracts = value.get("contracts")
            if not isinstance(contracts, list) or len(contracts) > 24 or any(
                    not isinstance(row, dict) or not isinstance(row.get("id"), str)
                    or not re.fullmatch(r"[a-z0-9_.]{1,80}", row["id"])
                    or type(row.get("ready")) is not bool or not isinstance(row.get("fields"), dict)
                    for row in contracts):
                raise ValueError("Invalid Research registry")
            return "PASS"

        contracts = self.probe("research.registry", client, client.observation_contracts, registry_check)
        if contracts is not None:
            for contract in contracts["contracts"]:
                identifier, fields = contract["id"], contract["fields"]
                name = "research." + identifier if identifier in {
                    "trainer.snapshot.v1", "trainer.progression.v1", "pokemon.bag.v1",
                    "inventory.bag.v1", "incubators.v1", "eggs.bag.v1", "pokedex.v1", "journal.v1", "location.v1"
                } else "research.additional_observation"
                if not fields:
                    self.record(name, "UNSUPPORTED")
                    continue

                def inspect_job(job):
                    if job.state != "completed" or not isinstance(job.result, dict):
                        return "FAIL"
                    result = job.result
                    if result.get("availability") == "unavailable":
                        return "BLOCKED"
                    if (result.get("availability") != "observed"
                            or result.get("contract") != identifier
                            or not isinstance(result.get("items"), list)
                            or len(result["items"]) != 1 or not isinstance(result["items"][0], dict)
                            or not set(selected).issubset(result["items"][0])):
                        return "FAIL"
                    typed = {"integer": (int,), "decimal": (int, float), "boolean": (bool,), "string": (str,)}
                    for field in selected:
                        observed = result["items"][0][field]
                        if observed is not None and type(observed) not in typed.get(fields[field], ()):
                            return "FAIL"
                    if identifier == "inventory.bag.v1" and any(
                            result["items"][0][field] is not None
                            and result["items"][0][field] < (1 if field == "raw_max_items" else 0)
                            for field in selected):
                        return "FAIL"
                    return "PASS" if coverage(result) == "complete" else "PARTIAL"

                # Inventory counters and the fixed-ID quantity fit in one bounded projection.
                selected = (tuple(field for field in
                                  ("raw_total_items", "raw_max_items", "raw_poke_ball_quantity")
                                  if field in fields)
                            if identifier == "inventory.bag.v1" else ())
                selected = selected or (sorted(fields)[0],)
                self.probe(name, client,
                           lambda: client.wait(client.submit_observation(identifier, fields=selected)),
                           inspect_job, retry_unavailable=True)
        if contracts is not None:
            known = next((row for row in contracts["contracts"] if row["id"] == "pokemon.bag.v1" and row["fields"]), None)
            if known is not None:
                self.probe("research.pokemon.invalid_limit", client,
                           lambda: client.submit_observation("pokemon.bag.v1", limit=0), expected=400)
        if capabilities is not None and "collection/start" in capabilities["operations"]:
            if complete:
                self.probe("research.pokemon.complete", client, lambda: complete_snapshot(client))
            else:
                self.record("research.pokemon.complete", "NOT_TESTED")
        return True

    def isolation(self, base, production_token, research_token, realm):
        if realm != "both" or not (production_token and research_token):
            self.record("authorization.cross_realm", "NOT_TESTED")
            return
        if production_token == research_token:
            self.record("authorization.cross_realm", "FAIL")
            return
        prod = ProductionClient(base, research_token)
        research = Client(base, production_token)
        self.probe("authorization.research_to_production", prod, lambda: prod._request("status"), expected=401)
        self.probe("authorization.production_to_research", research, research.status, expected=401)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base")
    parser.add_argument("--realm", choices=("production", "research", "both"), default="both")
    parser.add_argument("--production-token-file", type=Path)
    parser.add_argument("--research-token-file", type=Path)
    parser.add_argument("--refresh", action="store_true", help="exercise explicit Production Pokémon refresh")
    parser.add_argument("--research-complete", action="store_true", help="run a bounded full Research capture")
    args = parser.parse_args()
    matrix = Matrix()
    tokens = {}
    base = None
    for name, path, kind in (("production", args.production_token_file, ProductionClient),
                             ("research", args.research_token_file, Client)):
        if args.realm not in (name, "both"):
            continue
        try:
            client = kind.configured(base=args.base, token_file=path, timeout=48 if name == "production" else 4)
        except (OSError, ValueError, ResearchError):
            matrix.record(name + ".credential_or_endpoint", "BLOCKED",
                          reason="set the base URL and a valid credential for this realm")
            continue
        active = (matrix.production(client, args.refresh) if name == "production"
                  else matrix.research(client, args.research_complete))
        if active:
            base = client.base
            tokens[name] = client._token
    matrix.isolation(base, tokens.get("production"), tokens.get("research"), args.realm)
    for check in matrix.results:
        reason = check.get("reason")
        detail = f" ({reason})" if reason else ""
        print(f"{check['status']:12} {check['check']}{detail}", file=os.sys.stderr)
    print(json.dumps({"schema_version": 1, "checks": matrix.results, "manual": MANUAL}, indent=2))
    if any(check["status"] == "FAIL" for check in matrix.results):
        return 1
    return 0 if any(check["status"] in ("PASS", "PARTIAL") for check in matrix.results) else 2


if __name__ == "__main__":
    raise SystemExit(main())
