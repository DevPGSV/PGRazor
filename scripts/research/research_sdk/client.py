from collections.abc import Iterator
from dataclasses import asdict, dataclass, field
import ipaddress
import json
import re
import time
from typing import Any, TypedDict, cast
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

_TOKEN = re.compile(r"[A-Za-z0-9_-]{43}")
_PARAM = re.compile(r"[A-Za-z0-9_.`-]{1,96}", re.ASCII)
_STATES = {"queued", "running", "completed", "failed", "cancelled"}
_OPEN = {"queued", "running"}
_MAX_RESPONSE = 9216


class ResearchStatus(TypedDict, total=False):
    api_version: str
    ready: bool
    remaining_ms: int | None
    expires: bool
    features: dict[str, bool]
    research_generation: str


class Capabilities(TypedDict, total=False):
    api_version: str
    features: dict[str, bool]
    operations: list[str]
    topics: list[str]


class ResearchError(RuntimeError):
    """Redacted failure with optional HTTP status."""

    def __init__(self, message: str, status: int | None = None, code: str | None = None):
        super().__init__(message)
        self.status = status
        self.code = code


class InvalidResponse(ResearchError):
    """JSON or schema rejected after a response arrived."""


class _PollingTimeout(ResearchError):
    pass


@dataclass(frozen=True)
class DiscoveryQuery:
    op: str
    assembly: str | None = None
    ns: str | None = None
    name: str | None = None
    kind: str | None = None
    filter: str | None = None
    offset: int | None = None
    limit: int | None = None

    def parameters(self) -> dict[str, str | int]:
        return {key: value for key, value in asdict(self).items() if value is not None}


@dataclass(frozen=True)
class Job:
    id: str
    state: str
    error: str | None
    result: dict[str, Any] | None = field(repr=False)

    @classmethod
    def parse(cls, value: dict[str, Any]) -> "Job":
        job_id, state, error, result = (value.get(key) for key in ("id", "state", "error", "result"))
        if (not isinstance(job_id, str) or not _PARAM.fullmatch(job_id)
                or not isinstance(state, str) or state not in _STATES
                or (error is not None and (not isinstance(error, str) or not _PARAM.fullmatch(error)))
                or (result is not None and not isinstance(result, dict))):
            raise ResearchError("Invalid job response")
        return cls(job_id, state, error, result)


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class Client:
    _prefix = "/research/v1/"
    _timeout_limit = 30
    _response_limit = _MAX_RESPONSE
    _realm = "Research"

    @classmethod
    def configured(cls, *, base=None, token=None, token_file=None, interactive=False, **options):
        from .config import resolve_config
        base, token = resolve_config(cls._realm.lower(), base=base, token=token,
                                     token_file=token_file, interactive=interactive)
        return cls(base, token, **options)

    def __init__(self, base: str, token: str, *, timeout: float = 4):
        try:
            parsed = urlsplit(base)
            if (parsed.scheme != "http" or not parsed.hostname or parsed.username is not None
                    or parsed.password is not None or parsed.query or parsed.fragment
                    or parsed.path not in ("", "/") or not parsed.port):
                raise ValueError()
            self.host = str(ipaddress.IPv4Address(parsed.hostname))
        except ValueError:
            raise ResearchError("Expected http://IPv4:port") from None
        if not isinstance(token, str) or not _TOKEN.fullmatch(token):
            raise ResearchError(f"Invalid {self._realm} bearer format")
        if not 0 < timeout <= self._timeout_limit:
            raise ResearchError(f"HTTP timeout must be between zero and {self._timeout_limit} seconds")
        self.base = f"http://{self.host}:{parsed.port}"
        self._token = token
        self.timeout = timeout
        self.last_response_bytes = 0
        self.response_bytes = 0
        self._opener = build_opener(ProxyHandler({}), _NoRedirect())

    def _request(self, endpoint: str, method="GET", *, timeout=None, **arguments) -> dict[str, Any]:
        self.last_response_bytes = 0
        pairs = [(key, str(value)) for key, value in arguments.items() if value is not None]
        if any(not _PARAM.fullmatch(key) or self._token in value
               or not (_PARAM.fullmatch(value) or key == "fields"
                       and re.fullmatch(r"[a-z_.]{1,128}", value)) for key, value in pairs):
            raise ResearchError("Invalid Research parameter")
        query = "&".join(f"{key}={value}" for key, value in pairs)
        path = self.base + self._prefix + endpoint + ("?" + query if query else "")
        request = Request(path, method=method, headers={"Authorization": "Bearer " + self._token})
        try:
            with self._opener.open(request, timeout=self.timeout if timeout is None else timeout) as reply:
                payload = reply.read(self._response_limit + 1)
                self.last_response_bytes = len(payload)
                self.response_bytes += len(payload)
            if len(payload) > self._response_limit:
                raise InvalidResponse(f"{self._realm} response exceeds the byte limit")
            value = json.loads(payload)
        except HTTPError as error:
            code = None
            try:
                payload = error.read(2049)
                self.last_response_bytes = len(payload)
                self.response_bytes += len(payload)
                if len(payload) <= 2048:
                    envelope = json.loads(payload)
                    if isinstance(envelope, dict):
                        detail = envelope.get("error")
                        candidate = detail.get("code") if isinstance(detail, dict) else None
                        if isinstance(candidate, str) and re.fullmatch(r"[A-Z_]{1,32}", candidate):
                            code = candidate
            except (OSError, ValueError, UnicodeError):
                pass
            finally:
                error.close()
            message = f"{self._realm} credential rejected or expired" if error.code == 401 else f"{self._realm} request rejected"
            raise ResearchError(message, error.code, code) from None
        except (OSError, URLError):
            raise ResearchError(f"{self._realm} endpoint unavailable") from None
        except (ValueError, UnicodeError):
            raise InvalidResponse(f"{self._realm} response is not valid JSON") from None
        if not isinstance(value, dict) or self._token in json.dumps(value):
            raise InvalidResponse(f"Invalid or unsafe {self._realm} response")
        return value

    def status(self) -> ResearchStatus:
        value = self._request("status")
        if type(value.get("ready")) is not bool:
            raise InvalidResponse("Invalid Research status response")
        return cast(ResearchStatus, value)

    def capabilities(self) -> Capabilities:
        value = self._request("capabilities")
        ops = value.get("operations")
        if not isinstance(ops, list) or any(not isinstance(op, str) for op in ops):
            raise InvalidResponse("Invalid Research capabilities response")
        return cast(Capabilities, value)

    def offline_type(self, declaration_id: str) -> dict[str, Any]:
        return self._request("type", id=declaration_id)

    def offline_types(self, *, offset=0, limit=10, q=None) -> dict[str, Any]:
        return self._request("types", offset=offset, limit=limit, q=q)

    def static_assemblies(self) -> dict[str, Any]:
        return self._request("assemblies", limit=12)

    def runtime(self) -> dict[str, Any]:
        return self._request("runtime")

    def diagnostics(self) -> dict[str, Any]:
        return self._request("diagnostics")

    def contracts(self) -> dict[str, Any]:
        return self._request("contracts")

    def validate_contract(self, contract: str) -> dict[str, Any]:
        return self._request("validate", contract=contract)

    def observation_contracts(self) -> dict[str, Any]:
        return self._request("observations/contracts")

    def submit_observation(self, contract: str, *, fields: tuple[str, ...] = (), limit: int = 1,
                           offset: int = 0) -> Job:
        if any(not isinstance(name, str) or not re.fullmatch(r"[a-z_]{1,32}", name) for name in fields):
            raise ResearchError("Invalid observation projection")
        if len(set(fields)) != len(fields):
            raise ResearchError("Duplicate observation field")
        return Job.parse(self._request("live/observe", "POST", contract=contract,
                                      fields=".".join(fields) if fields else None, limit=limit, offset=offset))

    def submit(self, query: DiscoveryQuery) -> Job:
        return Job.parse(self._request("live/discover", "POST", **query.parameters()))

    def job(self, job_id: str, *, timeout=None) -> Job:
        return Job.parse(self._request("live/job", id=job_id, timeout=timeout))

    def cancel(self, job_id: str) -> Job:
        return Job.parse(self._request("live/cancel", "POST", id=job_id))

    def wait(self, job: Job, *, seconds=6) -> Job:
        if not 0 < seconds <= 30:
            raise ResearchError("Invalid job wait budget")
        deadline = time.monotonic() + seconds
        while job.state in _OPEN:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise _PollingTimeout("Job polling deadline reached")
            job = self.job(job.id, timeout=min(self.timeout, remaining))
            if job.state in _OPEN:
                time.sleep(min(0.1, max(0, deadline - time.monotonic())))
        return job

    def observe(self, query: DiscoveryQuery) -> Job:
        job = self.submit(query)
        try:
            return self.wait(job)
        except (KeyboardInterrupt, _PollingTimeout):
            try:
                self.cancel(job.id)
            except ResearchError:
                pass
            raise

    def pages(self, query: DiscoveryQuery, *, max_pages=5) -> Iterator[tuple[DiscoveryQuery, Job]]:
        if not 1 <= max_pages <= 50:
            raise ResearchError("Page budget must be between 1 and 50")
        for _ in range(max_pages):
            job = self.observe(query)
            yield query, job
            result = job.result
            if job.state != "completed" or not result or result.get("availability") != "observed":
                return
            cursor = result.get("next_offset")
            if cursor is None:
                return
            if type(cursor) is not int or not (query.offset or 0) < cursor <= 51_200_000:
                raise ResearchError("Invalid or non-advancing continuation")
            query = DiscoveryQuery(**{**query.parameters(), "offset": cursor})

    def job_updates(self, job_ids: list[str], *, seconds=12, reconnects=2) -> Iterator[Job]:
        """Recover tracked jobs over HTTP at connect/reconnect and on job notifications."""
        if not 1 <= len(job_ids) <= 8 or not 0 < seconds <= 30 or not 0 <= reconnects <= 4:
            raise ResearchError("Invalid job monitoring budget")
        try:
            import websocket
        except ImportError:
            raise ResearchError("websocket-client is required for job subscriptions") from None
        pending = set(job_ids)
        previous = {}
        deadline = time.monotonic() + seconds
        for attempt in range(reconnects + 1):
            stream = None
            try:
                while pending and time.monotonic() < deadline:
                    for job_id in sorted(pending):
                        remaining = deadline - time.monotonic()
                        if remaining <= 0:
                            break
                        job = self.job(job_id, timeout=min(self.timeout, remaining))
                        if job != previous.get(job_id):
                            previous[job_id] = job
                            yield job
                        if job.state not in _OPEN:
                            pending.remove(job_id)
                    if not pending or time.monotonic() >= deadline:
                        return
                    remaining = min(self.timeout, deadline - time.monotonic())
                    if stream is None:
                        stream = websocket.create_connection(
                            "ws://" + self.base.removeprefix("http://") + "/research/v1/events",
                            header={"Authorization": "Bearer " + self._token}, suppress_origin=True,
                            http_no_proxy=[self.host], timeout=remaining, redirect_limit=0,
                        )
                        stream.send(json.dumps({"version": 1, "id": "jobs", "op": "subscribe",
                                                "topics": ["research.jobs"]}))
                    stream.settimeout(min(remaining, 1))
                    try:
                        message = stream.recv()
                    except websocket.WebSocketTimeoutException:
                        continue  # HTTP polling recovers coalesced or missed events.
                    if not message:
                        raise websocket.WebSocketConnectionClosedException()
                    if not isinstance(message, str) or len(message) > 2048 or self._token in message:
                        raise ResearchError("Invalid or unsafe Research event")
            except websocket.WebSocketBadStatusException as error:
                if error.status_code in (401, 403) or 300 <= error.status_code < 400:
                    raise ResearchError("Research WebSocket handshake rejected", error.status_code) from None
                if attempt == reconnects:
                    break
            except (OSError, websocket.WebSocketException):
                if attempt == reconnects:
                    break
            finally:
                if stream is not None:
                    stream.close(timeout=0)
        # Last HTTP recovery still works when WebSocket transport is unavailable.
        for job_id in sorted(pending):
            job = self.job(job_id)
            if job != previous.get(job_id):
                yield job
            if job.state not in _OPEN:
                pending.remove(job_id)
        if pending:
            raise ResearchError("Job monitoring budget exhausted")
