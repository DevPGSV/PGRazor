import gzip
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from research_sdk import Client, DiscoveryQuery, Job, ResearchError
from research_sdk.catalog import Catalog

TOKEN = "t" * 43


class TransportTests(unittest.TestCase):
    def test_authenticated_pages_and_redacted_errors(self):
        requests = []

        class Handler(BaseHTTPRequestHandler):
            def handle_query(self):
                requests.append((self.command, self.path, self.headers.get("Authorization")))
                if self.headers.get("Authorization") != "Bearer " + TOKEN:
                    status, body = 401, {"untrusted": "Bearer " + TOKEN}
                elif self.path.endswith("/status"):
                    status, body = 200, {"ready": True}
                elif self.path.endswith("/capabilities"):
                    status, body = 200, {"operations": ["live/discover"]}
                elif "/discover?" in self.path:
                    status = 202
                    body = {"id": "page-two" if "offset=5" in self.path else "page-one", "state": "queued"}
                else:
                    last = self.path.endswith("page-two")
                    status, body = 200, {"id": "page-two" if last else "page-one", "state": "completed",
                        "result": {"availability": "observed", "next_offset": None if last else 5,
                                   "provenance": "il2cpp_runtime", "items": []}}
                encoded = json.dumps(body).encode()
                self.send_response(status)
                self.send_header("Content-Length", str(len(encoded)))
                self.end_headers()
                self.wfile.write(encoded)

            do_GET = do_POST = handle_query

            def log_message(self, *_):
                pass

        with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
            worker = threading.Thread(target=server.serve_forever)
            worker.start()
            try:
                base = f"http://127.0.0.1:{server.server_port}"
                client = Client(base, TOKEN)
                self.assertTrue(client.status()["ready"])
                self.assertEqual(client.capabilities()["operations"], ["live/discover"])
                pages = list(client.pages(DiscoveryQuery("search", name="Fixture"), max_pages=2))
                self.assertEqual([page.offset for page, _ in pages], [None, 5])
                self.assertEqual([job.id for _, job in pages], ["page-one", "page-two"])
                self.assertEqual(len(list(client.pages(DiscoveryQuery("search", name="Fixture"), max_pages=1))), 1)
                self.assertFalse(any(TOKEN in path for _, path, _ in requests))
                with self.assertRaises(ResearchError) as failure:
                    Client(base, "x" * 43).status()
                self.assertEqual(failure.exception.status, 401)
                self.assertNotIn(TOKEN, str(failure.exception))
            finally:
                server.shutdown()
                worker.join()

    def test_reconnect_and_http_recovery(self):
        class WSFailure(Exception):
            pass

        class Rejected(WSFailure):
            pass

        class Closed(WSFailure):
            pass

        class Timeout(WSFailure):
            pass

        sends = []
        connections = []
        states = iter([Job("j", "queued", None, None), Job("j", "running", None, None),
                       Job("j", "completed", None, {"availability": "observed"})])

        def connect(url, **options):
            connections.append((url, options))
            index = len(connections)
            def recv():
                if index == 1:
                    raise Closed()
                return '{"type":"event","topic":"research.jobs"}'
            return SimpleNamespace(send=sends.append, recv=recv, settimeout=lambda _: None,
                                   close=lambda **_: None)

        fake = SimpleNamespace(create_connection=connect, WebSocketException=WSFailure,
                               WebSocketBadStatusException=Rejected, WebSocketTimeoutException=Timeout,
                               WebSocketConnectionClosedException=Closed)
        client = Client("http://127.0.0.1:8765", TOKEN)
        with patch.dict("sys.modules", {"websocket": fake}), patch.object(client, "job", side_effect=states):
            observed = list(client.job_updates(["j"]))
        self.assertEqual([job.state for job in observed], ["queued", "running", "completed"])
        self.assertEqual(len(sends), 2)
        self.assertTrue(all(json.loads(message)["topics"] == ["research.jobs"] for message in sends))
        self.assertTrue(all(TOKEN not in url and options["redirect_limit"] == 0
                            for url, options in connections))

    def test_cancel_and_poll_expiry(self):
        client = Client("http://127.0.0.1:8765", TOKEN)
        with patch.object(client, "_request", return_value={"id": "job", "state": "cancelled"}) as request:
            self.assertEqual(client.cancel("job").state, "cancelled")
            request.assert_called_once_with("live/cancel", "POST", id="job")
        expired = ResearchError("Research credential rejected or expired", 401)
        with patch.object(client, "job", side_effect=expired) as status:
            with self.assertRaises(ResearchError) as failure:
                client.wait(Job("job", "queued", None, None))
            self.assertEqual(failure.exception.status, 401)
            self.assertEqual(status.call_count, 1)

    def test_observation_projection_uses_registered_job(self):
        client = Client("http://127.0.0.1:8765", TOKEN)
        with patch.object(client, "_request", return_value={"id": "job", "state": "queued"}) as request:
            job = client.submit_observation("trainer.snapshot.v1", fields=("xp", "eggs_hatched"))
            self.assertEqual(job.state, "queued")
            request.assert_called_once_with("live/observe", "POST", contract="trainer.snapshot.v1",
                                            fields="xp.eggs_hatched", limit=1, offset=0)
        with self.assertRaises(ResearchError):
            client.submit_observation("trainer.snapshot.v1", fields=("xp", "xp"))

    def test_nonadvancing_cursor_rejected(self):
        client = Client("http://127.0.0.1:8765", TOKEN)
        job = Job("id", "completed", None, {"availability": "observed", "next_offset": 2})
        with patch.object(client, "observe", return_value=job):
            with self.assertRaisesRegex(ResearchError, "non-advancing"):
                list(client.pages(DiscoveryQuery("search", name="Fixture", offset=2)))


class CatalogTests(unittest.TestCase):
    def test_reference_runtime_and_unavailable_remain_separate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            reference = root / "types.jsonl.gz"
            with gzip.open(reference, "wt", encoding="utf-8") as output:
                output.write(json.dumps({
                    "type_def_index": 1, "image": "holo-fixture.dll", "namespace": "Fixture", "name": "Collection",
                    "kind": "class", "fields": [{"name": "Unresolved", "type": {"type_index": 17}},
                                               {"name": "Diff", "type": {"namespace": "System", "name": "Int32"}}],
                    "properties": [], "methods": [],
                    "interfaces": [{"namespace": "Fixture", "name": "IBag"}],
                }) + "\n")
            original = reference.read_bytes()
            query = DiscoveryQuery("type", ns="Fixture", name="Collection", kind="fields")
            result = {"availability": "observed", "provenance": "il2cpp_runtime",
                      "declaration": {"assembly": "holo-fixture.dll", "namespace": "Fixture", "name": "Collection"},
                      "items": [{"name": "Unresolved", "type": "System.Int32"},
                                {"name": "Diff", "type": "System.Int64"},
                                {"name": "Unlisted", "type": "System.Single"}],
                      "unrecognized": TOKEN}
            with Catalog(root / "catalog.sqlite3") as catalog:
                self.assertEqual(catalog.import_static(reference, version="fixture"), 1)
                first = catalog.record(query, Job("test", "completed", None, result), version="fixture")
                second = catalog.record(query, Job("none", "completed", None,
                    {"availability": "unavailable", "provenance": "il2cpp_runtime"}), version="fixture")
                evidence = {item["name"]: item["status"] for item in catalog.compare(first)}
                self.assertEqual(evidence["Unresolved"], "resolved_runtime_type")
                self.assertEqual(evidence["Diff"], "different")
                self.assertEqual(evidence["Unlisted"], "no_static_match")
                self.assertEqual(catalog.compare(second), [])
                interface = {**result, "items": [{"name": "Fixture.IBag"}]}
                relation = catalog.record(DiscoveryQuery("type", ns="Fixture", name="Collection", kind="interfaces"),
                                          Job("relation", "completed", None, interface), version="fixture")
                self.assertEqual(catalog.compare(relation)[-1]["status"], "confirmed")
                self.assertEqual(catalog.relationships("Fixture.IBag", version="fixture",
                                                       relation="implements")["items"][0]["owner"], "Fixture.Collection")
                self.assertEqual(catalog.members(version="fixture", source="runtime", kind="fields",
                                                 value_type="System.Int64")["items"][0]["name"], "Diff")
                self.assertEqual(catalog.members(version="fixture", namespace="Fixture", kind="fields",
                                                 limit=1)["next_offset"], 1)
                members = catalog.search("unresolved", version="fixture")
                self.assertIsNone(members["members"][0]["type"])
                self.assertEqual(members["runtime"][0]["observation_id"], first)
                self.assertEqual(catalog.import_static(reference, version="fixture"), 1)
                self.assertEqual(len(catalog.summary(version="fixture")["findings"]), 2)
                self.assertEqual(catalog.summary(version="fixture")["observations"][1]["coverage"], "unavailable")
                self.assertNotIn(TOKEN, "\n".join(catalog.db.iterdump()))
                self.assertEqual(catalog.summary(version="other")["observations"], [])
            self.assertEqual(reference.read_bytes(), original)
