"""Private SQLite storage for static reference data and independent live observations."""
from collections import Counter
from datetime import datetime, timezone
import gzip
import json
import os
import re
from pathlib import Path
import sqlite3

from .client import DiscoveryQuery, Job, ResearchError

_ROOT = Path(__file__).resolve().parents[3]
_KINDS = ("fields", "properties", "methods", "interfaces")
_SCHEMA = """
CREATE TABLE IF NOT EXISTS static_types (
  id INTEGER PRIMARY KEY, version TEXT NOT NULL, assembly TEXT NOT NULL,
  namespace TEXT NOT NULL, name TEXT NOT NULL, ordinal INTEGER NOT NULL, kind TEXT NOT NULL,
  UNIQUE(version, ordinal)
);
CREATE INDEX IF NOT EXISTS static_identity ON static_types(version, assembly, namespace, name);
CREATE TABLE IF NOT EXISTS static_members (
  type_id INTEGER NOT NULL REFERENCES static_types(id) ON DELETE CASCADE,
  kind TEXT NOT NULL, name TEXT NOT NULL, type TEXT, parameters TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS static_member ON static_members(type_id, kind, name);
CREATE INDEX IF NOT EXISTS static_reverse_type ON static_members(type, kind, type_id);
CREATE TABLE IF NOT EXISTS static_owners (
  type_id INTEGER PRIMARY KEY REFERENCES static_types(id) ON DELETE CASCADE,
  qualified_name TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS observations (
  id INTEGER PRIMARY KEY, version TEXT NOT NULL, observed_at TEXT NOT NULL,
  provenance TEXT NOT NULL, query TEXT NOT NULL, coverage TEXT NOT NULL,
  availability TEXT NOT NULL, comparison TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS runtime_declarations (
  observation_id INTEGER NOT NULL REFERENCES observations(id),
  assembly TEXT, namespace TEXT, name TEXT NOT NULL, kind TEXT NOT NULL, declaration TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS runtime_observation ON runtime_declarations(observation_id);
CREATE TABLE IF NOT EXISTS observation_progress (
  observation_id INTEGER PRIMARY KEY REFERENCES observations(id),
  progress TEXT NOT NULL
);
"""


def _type(ref):
    if isinstance(ref, dict) and ref.get("name"):
        return ".".join(filter(None, (ref.get("namespace"), ref["name"])))
    return None


class Catalog:
    def __init__(self, path: Path | str):
        path = Path(path).expanduser().resolve()
        if path.is_relative_to(_ROOT):
            raise ResearchError("Keep the research database outside the Git checkout")
        try:
            fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        except FileExistsError:
            pass
        else:
            os.close(fd)
        self.db = sqlite3.connect(path)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA foreign_keys = ON")
        self.db.executescript(_SCHEMA)
        self.db.create_function("references_type", 2, self._references, deterministic=True)

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.db.close()

    def import_static(self, source: Path | str, *, version: str) -> int:
        """Replace only the selected version's reference. Runtime observations stay intact."""
        count = 0
        owners = {}
        with self.db, gzip.open(source, "rt", encoding="utf-8") as stream:
            self.db.execute("DELETE FROM static_types WHERE version=?", (version,))
            for line in stream:
                row = json.loads(line)
                key = self.db.execute(
                    "INSERT INTO static_types(version,assembly,namespace,name,ordinal,kind) VALUES(?,?,?,?,?,?)",
                    (version, row["image"], row["namespace"], row["name"], row["type_def_index"], row["kind"]),
                ).lastrowid
                declaring = row.get("declaring_type") or {}
                owners[row["type_def_index"]] = (key, row["namespace"], row["name"], declaring.get("type_def_index"))
                members = []
                for kind in _KINDS[:3]:
                    for member in row[kind]:
                        params = [_type(p.get("type")) for p in member.get("parameters", [])]
                        members.append((key, kind, member["name"],
                                        _type(member.get("type", member.get("return_type"))), json.dumps(params)))
                for member in row.get("enum_members", []):
                    members.append((key, "enumerators", member, None, "[]"))
                for ref in row.get("interfaces", []):
                    name = _type(ref)
                    if name:
                        members.append((key, "interfaces", name, name, "[]"))
                parent = _type(row.get("parent"))
                if parent:
                    members.append((key, "inheritance", parent, parent, "[]"))
                self.db.executemany("INSERT INTO static_members VALUES(?,?,?,?,?)", members)
                count += 1
            def qualified(index, seen=()):
                _, ns, name, parent = owners[index]
                if parent in owners and parent not in (*seen, index):
                    return qualified(parent, (*seen, index)) + "." + name
                return ".".join(filter(None, (ns, name)))

            self.db.executemany("INSERT INTO static_owners VALUES(?,?)",
                                ((key[0], qualified(index)) for index, key in owners.items()))
            if not count:
                raise ResearchError("The static catalog is empty")
        return count

    def record(self, query: DiscoveryQuery, job: Job, *, version: str) -> int:
        result = job.result
        if (job.state != "completed" or not result or result.get("provenance") != "il2cpp_runtime"
                or result.get("availability") not in ("observed", "unavailable")):
            raise ResearchError("A completed live result is required")
        rows = []
        if result["availability"] == "observed":
            declaration = result.get("declaration")
            if declaration:
                enum_info = {key: result[key] for key in ("enum_underlying_type", "enum_constant_values") if key in result}
                self._append(rows, declaration, "type", {**declaration, **enum_info})
                for item in result.get("items", []):
                    self._append(rows, declaration, query.kind or "methods", item)
                parent = result.get("parent")
                if parent:
                    self._append(rows, declaration, "inheritance", {"name": parent, "type": parent})
            elif query.op == "search":
                for item in result.get("items", []):
                    self._append(rows, item, "type", item)
            elif query.op == "assemblies":
                for assembly in result.get("items", []):
                    if isinstance(assembly, str):
                        rows.append((assembly, None, assembly, "assembly", "{}"))
        coverage = ("unavailable" if result["availability"] == "unavailable"
                    else "scan_limit" if result.get("scan_limit_reached")
                    else "continuation_available" if result.get("next_offset") is not None
                    else "bounded_query")
        with self.db:
            key = self.db.execute(
                """INSERT INTO observations(version,observed_at,provenance,query,coverage,availability,comparison)
                   VALUES(?,?,?,?,?,?,?)""",
                (version, datetime.now(timezone.utc).isoformat(), result["provenance"],
                 json.dumps(query.parameters(), sort_keys=True), coverage, result["availability"], "pending"),
            ).lastrowid
            self.db.executemany("INSERT INTO runtime_declarations VALUES(?,?,?,?,?,?)",
                                ((key, *row) for row in rows))
            progress = result.get("scan_progress")
            if isinstance(progress, dict):
                self.db.execute("INSERT INTO observation_progress VALUES(?,?)",
                                (key, json.dumps({field: progress[field]
                                                 for field in ("examined", "termination", "coverage")
                                                 if field in progress})))
            statuses = {item["status"] for item in self.compare(key)}
            summary = ",".join(sorted(statuses)) if statuses else "inconclusive"
            self.db.execute("UPDATE observations SET comparison=? WHERE id=?", (summary, key))
        return key

    @staticmethod
    def _append(rows, owner, kind, item):
        name = item.get("name")
        if not isinstance(name, str) or not name:
            return
        fields = ("name", "type", "returns", "parameters", "parameters_truncated", "enum", "literal",
                  "constant_value", "enum_underlying_type", "enum_constant_values")
        structure = {key: item[key] for key in fields if key in item}
        structure["declaring_type"] = owner.get("type")
        rows.append((owner.get("assembly"), owner.get("namespace"), name, kind,
                     json.dumps({"owner": owner.get("name"), **structure})))

    @staticmethod
    def _references(signature, target):
        if not isinstance(signature, str):
            return False
        return target in re.findall(r"[A-Za-z_][A-Za-z0-9_.+`]*", signature)

    def members(self, *, version, source="static", assembly=None, namespace=None, owner=None,
                name=None, kind=None, value_type=None, returns=None, parameter=None,
                reference=None, limit=50, offset=0):
        """Search the local evidence only. Returned rows do not imply exhaustive runtime coverage."""
        if source not in ("static", "runtime") or not 1 <= limit <= 200 or not 0 <= offset <= 10_000:
            raise ResearchError("Invalid local member search bounds")
        filters = (assembly, namespace, owner, name, kind, value_type, returns, parameter, reference)
        if not any(filters) or any(v is not None and (not isinstance(v, str) or len(v) > 250) for v in filters):
            raise ResearchError("Bound the search with at least one structural filter")
        if source == "static":
            base = """SELECT t.id AS evidence_id,t.assembly,t.namespace,
                      coalesce(n.qualified_name,ltrim(t.namespace||'.'||t.name,'.')) AS owner,
                      m.name,m.kind,m.type AS signature,m.parameters,m.rowid AS record_id
                      FROM static_members m JOIN static_types t ON t.id=m.type_id
                      LEFT JOIN static_owners n ON n.type_id=t.id WHERE t.version=?"""
        else:
            base = """SELECT o.id AS evidence_id,d.assembly,d.namespace,
                      coalesce(json_extract(d.declaration,'$.declaring_type'),
                      ltrim(coalesce(d.namespace,'')||'.'||json_extract(d.declaration,'$.owner'),'.')) AS owner,
                      d.name,d.kind,coalesce(json_extract(d.declaration,'$.type'),
                      json_extract(d.declaration,'$.returns'),CASE WHEN d.kind='interfaces' THEN d.name END) AS signature,
                      coalesce(json_extract(d.declaration,'$.parameters'),'[]') AS parameters,d.rowid AS record_id
                      FROM runtime_declarations d JOIN observations o ON o.id=d.observation_id
                      WHERE o.version=? AND d.kind NOT IN ('type','assembly')"""
        sql = ["SELECT evidence_id,assembly,namespace,owner,name,kind,signature,parameters FROM (" + base + ") WHERE 1"]
        args = [version]
        for key, value in (("assembly", assembly), ("namespace", namespace), ("owner", owner), ("kind", kind)):
            if value is not None:
                sql.append(f"AND {key}=?")
                args.append(value)
        if name is not None:
            sql.append("AND instr(lower(name),lower(?))>0")
            args.append(name)
        if value_type is not None:
            sql.append("AND kind IN ('fields','properties') AND signature=?")
            args.append(value_type)
        if returns is not None:
            sql.append("AND kind='methods' AND signature=?")
            args.append(returns)
        if parameter is not None:
            sql.append("AND kind='methods' AND EXISTS (SELECT 1 FROM json_each(parameters) WHERE value=?)")
            args.append(parameter)
        if reference is not None:
            sql.append("""AND (references_type(signature,?) OR EXISTS
                           (SELECT 1 FROM json_each(parameters) WHERE references_type(value,?)))""")
            args.extend((reference, reference))
        sql.append("ORDER BY assembly,namespace,owner,kind,name,record_id LIMIT ? OFFSET ?")
        args.extend((limit + 1, offset))
        rows = [dict(row) for row in self.db.execute(" ".join(sql), args)]
        return {"provenance": "offline_catalog" if source == "static" else "indexed_runtime_observations",
                "coverage": "indexed_evidence", "items": rows[:limit],
                "next_offset": offset + limit if len(rows) > limit and offset + limit <= 10_000 else None}

    def relationships(self, target, *, version, relation="reference", source="static", **bounds):
        if relation == "reference":
            return self.members(version=version, source=source, reference=target, **bounds)
        kind = {"implements": "interfaces", "inherits": "inheritance"}.get(relation)
        if kind is None:
            raise ResearchError("Invalid relationship")
        return self.members(version=version, source=source, kind=kind, reference=target, **bounds)

    def compare(self, observation: int) -> list[dict]:
        result = []
        query = """SELECT d.*, o.version FROM runtime_declarations d
                   JOIN observations o ON o.id=d.observation_id WHERE o.id=? ORDER BY d.rowid"""
        for row in self.db.execute(query, (observation,)):
            live = json.loads(row["declaration"])
            kind = row["kind"]
            if kind == "assembly":
                found = self.db.execute("SELECT 1 FROM static_types WHERE version=? AND assembly=? LIMIT 1",
                                        (row["version"], row["assembly"])).fetchone()
                result.append(self._comparison(row, live, "confirmed" if found else "no_static_match"))
                continue
            declaring = live.get("declaring_type")
            owners = self.db.execute("""SELECT t.id FROM static_types t JOIN static_owners n ON t.id=n.type_id
                WHERE t.version=? AND t.assembly=? AND n.qualified_name=? LIMIT 2""",
                (row["version"], row["assembly"], declaring)).fetchall() if declaring else []
            if not owners:
                owners = self.db.execute(
                    "SELECT id FROM static_types WHERE version=? AND assembly=? AND namespace=? AND name=? LIMIT 2",
                    (row["version"], row["assembly"], row["namespace"], live.get("owner")),
                ).fetchall()
            if len(owners) != 1 or kind == "type":
                status = "confirmed" if len(owners) == 1 else "ambiguous" if owners else "no_static_match"
                result.append(self._comparison(row, live, status))
                continue
            members = self.db.execute("SELECT type,parameters FROM static_members WHERE type_id=? AND kind=? AND name=?",
                                      (owners[0]["id"], kind, row["name"])).fetchall()
            if kind == "methods":
                if live.get("parameters_truncated"):
                    result.append(self._comparison(row, live, "inconclusive"))
                    continue
                members = [member for member in members if
                           len(json.loads(member["parameters"])) == len(live.get("parameters", []))]
                parameters = live.get("parameters", [])
                if len(members) > 1 and all(isinstance(p, str) for p in parameters):
                    exact = [member for member in members if json.loads(member["parameters"]) == parameters]
                    unresolved = any(None in json.loads(member["parameters"]) for member in members)
                    if len(exact) == 1 and not unresolved:
                        members = exact
            if len(members) != 1:
                result.append(self._comparison(row, live, "ambiguous" if members else "no_static_match"))
                continue
            static = members[0]
            observed_type = live["name"] if kind == "interfaces" else live.get("type", live.get("returns"))
            values = [("return" if kind == "methods" else "type", static["type"], observed_type)]
            if kind == "methods":
                values.extend((f"parameter_{i}", expected, observed) for i, (expected, observed)
                              in enumerate(zip(json.loads(static["parameters"]), live.get("parameters", []))))
            for position, expected, observed in values:
                # Enum literals and interface declarations may supply a name alone.
                status = ("inconclusive" if observed is None else "resolved_runtime_type" if expected is None
                          else "confirmed" if expected == observed else "different")
                if status == "different" and expected and "." not in expected and observed.endswith("." + expected):
                    candidates = self.db.execute("""SELECT DISTINCT n.qualified_name FROM static_owners n
                         JOIN static_types t ON t.id=n.type_id WHERE t.version=? AND t.name=? LIMIT 2""",
                                                 (row["version"], expected)).fetchall()
                    total = self.db.execute("SELECT count(*) FROM static_types WHERE version=? AND name=?",
                                            (row["version"], expected)).fetchone()[0]
                    if total == 1 and len(candidates) == 1 and candidates[0][0] == observed:
                        status = "qualified_name_confirmed"
                result.append(self._comparison(row, live, status, position=position, static_type=expected,
                                               runtime_type=observed))
        return result

    @staticmethod
    def _comparison(row, live, status, **details):
        return {"assembly": row["assembly"], "namespace": row["namespace"], "owner": live.get("owner"),
                "name": row["name"], "kind": row["kind"], "status": status, **details}

    def search(self, text: str, *, version: str, limit=30) -> dict:
        """Search static and live evidence separately. The limit applies to each result group."""
        if not text or len(text) > 96 or not 1 <= limit <= 200:
            raise ResearchError("Invalid catalog search bounds")
        value = text.lower()
        return {key: [dict(row) for row in self.db.execute(sql, (version, value, limit))]
                for key, sql in {
                    "assemblies": """SELECT DISTINCT assembly FROM static_types
                        WHERE version=? AND instr(lower(assembly),?) ORDER BY assembly LIMIT ?""",
                    "types": """SELECT assembly,namespace,name,kind FROM static_types
                        WHERE version=? AND instr(lower(namespace||'.'||name),?) ORDER BY assembly,name LIMIT ?""",
                    "members": """SELECT t.assembly,t.namespace,t.name AS owner,m.name,m.kind,m.type FROM static_members m
                        JOIN static_types t ON m.type_id=t.id WHERE t.version=? AND instr(lower(m.name),?)
                        ORDER BY m.name,t.assembly,t.name LIMIT ?""",
                    "runtime": """SELECT d.observation_id,o.observed_at,o.provenance,d.assembly,d.namespace,d.name,d.kind
                        FROM runtime_declarations d JOIN observations o ON o.id=d.observation_id
                        WHERE o.version=? AND instr(lower(coalesce(d.assembly,'')||'.'||coalesce(d.namespace,'')||'.'||d.name),?)
                        ORDER BY d.observation_id DESC LIMIT ?""",
                }.items()}

    def summary(self, *, version: str, limit=100) -> dict:
        if not 1 <= limit <= 200:
            raise ResearchError("Invalid summary limit")
        observations = self.db.execute("""SELECT o.*,p.progress AS scan_progress FROM observations o
            LEFT JOIN observation_progress p ON p.observation_id=o.id WHERE o.version=? ORDER BY o.id DESC LIMIT ?""",
                                       (version, limit)).fetchall()
        findings = []
        counts = Counter()
        for observation in observations:
            for item in self.compare(observation["id"]):
                counts[item["status"]] += 1
                if item["status"] in ("resolved_runtime_type", "different"):
                    findings.append({"observation_id": observation["id"], **item})
        return {"version": version, "observation_count": len(observations), "comparison_counts": dict(counts),
                "observations": [{**dict(o), "query": json.loads(o["query"]),
                  "scan_progress": json.loads(o["scan_progress"]) if o["scan_progress"] else None}
                 for o in observations], "findings": findings}
