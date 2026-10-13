"""
Bug condition exploration tests for the risk-engine high-risk flag fix.

These encode the EXPECTED (fixed) behaviour of ``RiskRequestHandler._handle_analyze``
in ``app/server.py`` and are expected to FAIL on unfixed code.

Covers: 1.1 (Gate 0 REQUIRE_2FA crash) and Property 3 (score/decision agreement).
"""

import json
import os
import sys
import threading
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

import numpy as np
import pytest

_SERVICE_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _SERVICE_ROOT not in sys.path:
    sys.path.insert(0, _SERVICE_ROOT)

from app import server as risk_server  # noqa: E402


@pytest.fixture(scope="module")
def base_url():
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), risk_server.RiskRequestHandler)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    yield f"http://127.0.0.1:{httpd.server_address[1]}"
    httpd.shutdown()
    httpd.server_close()


def _post_analyze(base_url, payload):
    req = urllib.request.Request(
        f"{base_url}/api/v1/risk/analyze",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as res:
            return res.status, json.loads(res.read().decode("utf-8"))
    except Exception as exc:  # dropped connection / non-200
        pytest.fail(f"no HTTP 200 response from /analyze: {exc!r}")


def test_gate0_require_2fa_returns_200(base_url):
    """1.1: rooted + new_payee + amount >= 10000 must return 200 REQUIRE_2FA score 75."""
    status, data = _post_analyze(
        base_url, {"rooted": True, "new_payee": True, "amount": 20000}
    )
    assert status == 200
    assert data["decision"] == "REQUIRE_2FA"
    assert data["fraud_score"] == 75


class _FixedProbModel:
    def __init__(self, p):
        self.p = p

    def predict_proba(self, _x):
        return np.array([[1.0 - self.p, self.p]])


class _IdentityPipeline:
    def transform(self, df):
        return df


@pytest.mark.parametrize("p", [0.395, 0.495])
def test_s2_decision_agrees_with_rounded_score(base_url, monkeypatch, p):
    """Property 3: fraud_score = round(p*100) and decision derives from that score."""
    monkeypatch.setattr(risk_server, "s2_model", _FixedProbModel(p))
    monkeypatch.setattr(risk_server, "s2_pipeline", _IdentityPipeline())

    _, data = _post_analyze(base_url, {"amount": 500, "new_payee": False})

    expected_score = int(round(p * 100))
    assert data["fraud_score"] == expected_score
    if expected_score >= 50:
        assert data["decision"] == "BLOCK"
    elif expected_score >= 40:
        assert data["decision"] == "REQUIRE_2FA"
    else:
        assert data["decision"] == "ALLOW"
