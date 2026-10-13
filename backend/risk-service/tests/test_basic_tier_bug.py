"""
Bug condition exploration tests for the Basic tier PHP 30,000 transfer flag fix.

These encode the EXPECTED (fixed) behaviour of both risk paths for a BASIC-tier,
non-restricted customer sending PHP 30,000 or more, and are expected to FAIL on
unfixed code:

  * ``POST /api/v1/risk/analyze`` (``RiskRequestHandler._handle_analyze``)
  * ``POST /api/v1/risk/score``   (``do_POST`` /score branch)

The tier comes from a stub account-service answering ``GET /internal/limits/tier``.

Covers: 1.2, 1.3, 1.4 (and 2.2, 2.3, 2.4 once fixed).
"""

import json
import os
import random
import sys
import threading
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

_SERVICE_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _SERVICE_ROOT not in sys.path:
    sys.path.insert(0, _SERVICE_ROOT)


class _TierStubHandler(BaseHTTPRequestHandler):
    """Always answers the tier lookup with BASIC / not restricted."""

    def do_GET(self):
        if self.path.split("?")[0] == "/internal/limits/tier":
            body = json.dumps({"tier": "BASIC", "restricted": False}).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, *_args):
        pass


@pytest.fixture(scope="module")
def stub_url():
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), _TierStubHandler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{httpd.server_address[1]}"
    httpd.shutdown()
    httpd.server_close()


@pytest.fixture(scope="module")
def base_url(stub_url):
    # Must be set before app.server (and thus app.basic_tier_rule) is imported.
    os.environ["ACCOUNT_SERVICE_URL"] = stub_url
    from app import server as risk_server  # noqa: E402

    # If the module was already imported earlier in the session, or exists after the fix,
    # point its constant at the stub too. On unfixed code the module does not exist.
    mp = pytest.MonkeyPatch()
    try:
        from app import basic_tier_rule  # noqa: E402

        mp.setattr(basic_tier_rule, "ACCOUNT_SERVICE_URL", stub_url)
    except ImportError:
        pass

    httpd = ThreadingHTTPServer(("127.0.0.1", 0), risk_server.RiskRequestHandler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{httpd.server_address[1]}"
    httpd.shutdown()
    httpd.server_close()
    mp.undo()


def _post(base_url, path, payload):
    req = urllib.request.Request(
        f"{base_url}{path}",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as res:
            return res.status, json.loads(res.read().decode("utf-8"))
    except Exception as exc:
        pytest.fail(f"no HTTP 200 response from {path}: {exc!r}")


def _analyze(base_url, amount):
    _, data = _post(
        base_url,
        "/api/v1/risk/analyze",
        {"amount": amount, "account_id": "1000-2000-3001"},
    )
    return data


def _score(base_url, amount):
    _, data = _post(
        base_url,
        "/api/v1/risk/score",
        {"amount": amount, "sourceAccountId": "1000-2000-3001"},
    )
    return data


def test_analyze_flags_basic_tier_30000(base_url):
    """2.2: /analyze 30000 on a BASIC account must be ADVISORY_WARNING, score >= 75, Basic-tier flag."""
    data = _analyze(base_url, 30000)
    assert data["decision"] == "ADVISORY_WARNING", (
        f"/analyze 30000 -> {data['decision']} score {data.get('fraud_score')} "
        f"flag {data.get('primary_flag')!r}"
    )
    assert data["fraud_score"] >= 75
    assert data["primary_flag"] == "BASIC_TIER_HIGH_AMOUNT"
    cause = str(data["cause_of_suspicion"]).lower()
    assert "high amount" in cause
    assert "basic" in cause


def test_score_flags_basic_tier_30000(base_url):
    """2.3: /score 30000 on a BASIC account must be ADVISORY_WARNING, score >= 75, Basic-tier reason."""
    data = _score(base_url, 30000)
    assert data["decision"] == "ADVISORY_WARNING", (
        f"/score 30000 -> {data['decision']} score {data.get('score')} "
        f"reason {data.get('riskReason')!r}"
    )
    assert data["score"] >= 75
    reason = str(data["riskReason"]).lower()
    assert "high amount" in reason
    assert "basic" in reason


def test_both_paths_flag_and_agree_for_amounts_30000_to_50000(base_url):
    """2.4: seeded loop over 30000..50000, both paths ADVISORY_WARNING and in agreement."""
    rnd = random.Random(0)
    amounts = [30000, 50000] + [rnd.randint(30000, 50000) for _ in range(20)]
    for amount in amounts:
        a = _analyze(base_url, amount)
        s = _score(base_url, amount)
        assert a["decision"] == "ADVISORY_WARNING", (
            f"/analyze {amount} -> {a['decision']} score {a.get('fraud_score')}"
        )
        assert s["decision"] == "ADVISORY_WARNING", (
            f"/score {amount} -> {s['decision']} score {s.get('score')}"
        )
        assert a["decision"] == s["decision"]


def test_basic_29999_is_allow_on_both_paths(base_url):
    """Boundary: threshold is inclusive at 30000 only; 29999 keeps today's ALLOW (passes on unfixed code)."""
    assert _analyze(base_url, 29999)["decision"] == "ALLOW"
    assert _score(base_url, 29999)["decision"] == "ALLOW"


@pytest.mark.parametrize("amount", [30000, 35000, 40000, 45000, 50000])
def test_analyze_and_score_return_same_decision_for_basic(base_url, amount):
    """2.4: same Basic input -> same decision on /analyze and /score, with the Basic-tier reason on /score."""
    a = _analyze(base_url, amount)
    s = _score(base_url, amount)
    assert a["decision"] == s["decision"] == "ADVISORY_WARNING"
    assert s["score"] == 75
    assert s["riskReason"] == "Suspicious high amount for Basic tier"
