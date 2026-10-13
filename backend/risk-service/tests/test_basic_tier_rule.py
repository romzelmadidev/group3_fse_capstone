"""
Unit tests for ``app.basic_tier_rule.applies`` (Basic tier PHP 30,000 transfer flag).

A small stub ``ThreadingHTTPServer`` answers ``GET /internal/limits/tier`` and
``basic_tier_rule.ACCOUNT_SERVICE_URL`` is monkeypatched to point at it.

Covers: 2.4, 2.5, 3.1, 3.2.
"""

import json
import os
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

import pytest

_SERVICE_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _SERVICE_ROOT not in sys.path:
    sys.path.insert(0, _SERVICE_ROOT)

from app import basic_tier_rule  # noqa: E402

ACCOUNT = "1000-2000-3001"

_STATE = {"mode": "ok", "tier": "BASIC", "restricted": False, "requests": []}


class _TierStubHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urlparse(self.path)
        _STATE["requests"].append((parsed.path, parse_qs(parsed.query)))
        if parsed.path != "/internal/limits/tier":
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        try:
            mode = _STATE["mode"]
            if mode == "delay":
                time.sleep(1.5)  # well past the 0.5 s lookup timeout
            if mode == "http500":
                self.send_response(500)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if mode == "malformed":
                body = b"{not json"
            else:
                body = json.dumps(
                    {"tier": _STATE["tier"], "restricted": _STATE["restricted"]}
                ).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except OSError:
            pass  # client gave up (timeout)

    def log_message(self, *_args):
        pass


@pytest.fixture(scope="module")
def stub_url():
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), _TierStubHandler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{httpd.server_address[1]}"
    httpd.shutdown()
    httpd.server_close()


@pytest.fixture
def stub(stub_url, monkeypatch):
    monkeypatch.setattr(basic_tier_rule, "ACCOUNT_SERVICE_URL", stub_url)
    _STATE.update(mode="ok", tier="BASIC", restricted=False, requests=[])

    class Ctl:
        def set(self, tier="BASIC", restricted=False, mode="ok"):
            _STATE.update(tier=tier, restricted=restricted, mode=mode)

        @property
        def requests(self):
            return _STATE["requests"]

    yield Ctl()
    _STATE.update(mode="ok", tier="BASIC", restricted=False, requests=[])


def test_constants():
    assert basic_tier_rule.THRESHOLD == 30000.0
    assert basic_tier_rule.SCORE == 75
    assert basic_tier_rule.FLAG == "BASIC_TIER_HIGH_AMOUNT"
    assert basic_tier_rule.REASON == "Suspicious high amount for Basic tier"


@pytest.mark.parametrize(
    "amount, expected",
    [(29999.99, False), (30000, True), (30000.0, True), (30000.01, True), (500000, True)],
)
def test_threshold_is_inclusive_at_30000(stub, amount, expected):
    assert basic_tier_rule.applies(ACCOUNT, amount) is expected


def test_amount_under_threshold_makes_no_network_call(stub):
    assert basic_tier_rule.applies(ACCOUNT, 29999.99) is False
    assert basic_tier_rule.applies(ACCOUNT, 1) is False
    assert stub.requests == []


@pytest.mark.parametrize("account_id", ["", "   ", None])
def test_blank_account_id_is_false_without_network_call(stub, account_id):
    assert basic_tier_rule.applies(account_id, 30000) is False
    assert stub.requests == []


@pytest.mark.parametrize("amount", ["abc", "", None, float("nan"), float("inf"), [], {}])
def test_bad_amount_is_false_without_network_call(stub, amount):
    assert basic_tier_rule.applies(ACCOUNT, amount) is False
    assert stub.requests == []


def test_restricted_account_is_false(stub):
    stub.set(tier="BASIC", restricted=True)
    assert basic_tier_rule.applies(ACCOUNT, 30000) is False


def test_fully_verified_is_false(stub):
    stub.set(tier="FULLY_VERIFIED")
    assert basic_tier_rule.applies(ACCOUNT, 30000) is False


def test_account_id_is_url_encoded_in_query(stub):
    assert basic_tier_rule.applies("acc 1&x=y/#", 30000) is True
    path, query = stub.requests[-1]
    assert path == "/internal/limits/tier"
    assert query == {"accountId": ["acc 1&x=y/#"]}


@pytest.mark.parametrize("mode", ["http500", "malformed"])
def test_stub_error_is_false_no_exception(stub, mode):
    stub.set(mode=mode)
    assert basic_tier_rule.applies(ACCOUNT, 30000) is False


def test_timeout_is_false_no_exception(stub):
    stub.set(mode="delay")
    started = time.monotonic()
    assert basic_tier_rule.applies(ACCOUNT, 30000) is False
    assert time.monotonic() - started < 1.4  # gave up at ~0.5 s, did not wait for the stub


def test_connection_refused_is_false_no_exception(monkeypatch):
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        dead = f"http://127.0.0.1:{s.getsockname()[1]}"
    monkeypatch.setattr(basic_tier_rule, "ACCOUNT_SERVICE_URL", dead)
    assert basic_tier_rule.applies(ACCOUNT, 30000) is False


def test_failure_logs_one_warning_line(stub, caplog):
    stub.set(mode="http500")
    with caplog.at_level("WARNING"):
        assert basic_tier_rule.applies(ACCOUNT, 30000) is False
    warnings = [r for r in caplog.records if r.levelname == "WARNING"]
    assert len(warnings) == 1
