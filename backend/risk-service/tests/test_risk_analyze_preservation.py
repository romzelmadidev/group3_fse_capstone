"""
Preservation tests for the risk-engine high-risk flag fix (Property 2).

Observed on UNFIXED ``app/server.py`` and expected to keep passing after the fix.
Covers 3.1 (Gate 0 BLOCK) and 3.2 (S2 path away from the rounding boundaries).
"""

import json
import os
import random
import sys
import threading
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
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
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
    with urllib.request.urlopen(req, timeout=10) as res:
        return res.status, json.loads(res.read().decode("utf-8"))


def test_gate0_impossible_travel_block(base_url):
    """3.1: impossible travel stays BLOCK / 100 with the same flags."""
    status, data = _post_analyze(
        base_url,
        {
            "user_id": "usr-1003-cst-003",
            "target_account_id": "acc-2001-sav-001",
            "amount": 15000.0,
            "latitude": 1.3521,
            "longitude": 103.8198,
        },
    )
    assert status == 200
    assert data["decision"] == "BLOCK"
    assert data["fraud_score"] == 100
    assert data["status"] == "BLOCKED"
    assert data["primary_flag"] == "IMPOSSIBLE_TRAVEL_VELOCITY"
    assert "IMPOSSIBLE_TRAVEL_VELOCITY" in data["all_flags"]
    assert data["review_enqueued"] is False


def test_gate0_emulator_high_value_block(base_url):
    """3.1: emulator with amount >= 50000 stays BLOCK / 100 with the same flags."""
    status, data = _post_analyze(base_url, {"emulator": True, "amount": 50000})
    assert status == 200
    assert data["decision"] == "BLOCK"
    assert data["fraud_score"] == 100
    assert data["status"] == "BLOCKED"
    assert data["primary_flag"] == "CRITICAL_DEVICE_TAMPERING_HIGH_VALUE"
    assert data["all_flags"] == ["CRITICAL_DEVICE_TAMPERING_HIGH_VALUE"]


class _FixedProbModel:
    def __init__(self, p):
        self.p = p

    def predict_proba(self, _x):
        return np.array([[1.0 - self.p, self.p]])


class _IdentityPipeline:
    def transform(self, df):
        return df


def _sample_ps():
    rng = random.Random(0)
    ps = [0.0, 0.05, 0.1, 0.2, 0.3, 0.4, 0.45, 0.5, 0.6, 0.75, 0.9, 1.0]
    ps += [rng.random() for _ in range(25)]
    return [
        p for p in ps
        if not (0.395 <= p < 0.40) and not (0.495 <= p < 0.50)
    ]


def test_s2_path_unchanged_away_from_boundaries(base_url, monkeypatch):
    """3.2: decision follows TAU_2FA/TAU_BLOCK on p; fraud_score is int(p*100)."""
    monkeypatch.setattr(risk_server, "s2_pipeline", _IdentityPipeline())
    for p in _sample_ps():
        monkeypatch.setattr(risk_server, "s2_model", _FixedProbModel(p))
        status, data = _post_analyze(base_url, {"amount": 500, "new_payee": False})
        assert status == 200

        expected = (
            "BLOCK" if p >= risk_server.TAU_BLOCK
            else "REQUIRE_2FA" if p >= risk_server.TAU_2FA
            else "ALLOW"
        )
        assert data["decision"] == expected, f"p={p} -> {data['decision']}"

        # Only compare fraud_score where round() and int() agree, i.e. where the
        # post-fix rounding rule cannot legitimately move the value (see report).
        if int(round(p * 100)) == int(p * 100):
            assert data["fraud_score"] == int(p * 100), f"p={p} -> {data['fraud_score']}"
