"""
Preservation tests for the Basic tier PHP 30,000 transfer flag fix (Property 2).

Every expected value in ``BASELINE_ANALYZE`` / ``BASELINE_SCORE`` was observed on the UNFIXED
risk service (no Basic-tier rule) and hardcoded. These inputs are all outside the bug condition
(BASIC, not restricted, amount >= 30000), or are verdicts the Basic-tier rule must never lower, so
the same values must hold after the fix.

The tier comes from a configurable stub account-service answering
``GET /internal/limits/tier``. The stub is only consulted once the fix exists.

Covers: 3.1, 3.2, 3.5, 3.6 and 2.5 (fail-open on a bad tier lookup).
"""

import json
import os
import random
import re
import socket
import sys
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np
import pytest

_SERVICE_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _SERVICE_ROOT not in sys.path:
    sys.path.insert(0, _SERVICE_ROOT)

ACCOUNT = "1000-2000-3001"

_STATE = {"mode": "ok", "tier": "BASIC", "restricted": False}


class _TierStubHandler(BaseHTTPRequestHandler):
    """Configurable tier lookup: ok / http500 / delay."""

    def do_GET(self):
        if self.path.split("?")[0] != "/internal/limits/tier":
            self.send_response(404)
            self.end_headers()
            return
        try:
            if _STATE["mode"] == "delay":
                time.sleep(2.0)  # well past the rule's lookup timeout
            if _STATE["mode"] == "http500":
                self.send_response(500)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
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


@pytest.fixture(scope="module")
def risk_server(stub_url):
    # Must be set before app.server (and thus app.basic_tier_rule) is imported.
    os.environ["ACCOUNT_SERVICE_URL"] = stub_url
    from app import server

    return server


@pytest.fixture(scope="module")
def base_url(risk_server, stub_url):
    mp = pytest.MonkeyPatch()
    try:
        from app import basic_tier_rule  # noqa: E402  (absent on unfixed code)

        mp.setattr(basic_tier_rule, "ACCOUNT_SERVICE_URL", stub_url)
    except ImportError:
        pass

    httpd = ThreadingHTTPServer(("127.0.0.1", 0), risk_server.RiskRequestHandler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{httpd.server_address[1]}"
    httpd.shutdown()
    httpd.server_close()
    mp.undo()


@pytest.fixture
def stub(base_url, stub_url):
    """Per-test tier control; always restored."""

    class Ctl:
        def set(self, tier="BASIC", restricted=False, mode="ok"):
            _STATE.update(tier=tier, restricted=restricted, mode=mode)

        def unreachable(self, monkeypatch):
            """Point the rule at a port nobody listens on (no-op on unfixed code)."""
            with socket.socket() as s:
                s.bind(("127.0.0.1", 0))
                dead = f"http://127.0.0.1:{s.getsockname()[1]}"
            try:
                from app import basic_tier_rule

                monkeypatch.setattr(basic_tier_rule, "ACCOUNT_SERVICE_URL", dead)
            except ImportError:
                pass

    _STATE.update(tier="BASIC", restricted=False, mode="ok")
    yield Ctl()
    _STATE.update(tier="BASIC", restricted=False, mode="ok")


# --------------------------------------------------------------------------- helpers


def _post(base_url, path, payload):
    req = urllib.request.Request(
        f"{base_url}{path}",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as res:
            assert res.status == 200
            return json.loads(res.read().decode("utf-8"))
    except Exception as exc:
        pytest.fail(f"no HTTP 200 response from {path}: {exc!r}")


class _FixedProbModel:
    def __init__(self, p):
        self.p = p

    def predict_proba(self, _x):
        return np.array([[1.0 - self.p, self.p]])


class _IdentityPipeline:
    def transform(self, df):
        return df


def analyze_stable(base_url, risk_server, mp, payload, s2_p=None):
    if s2_p is not None:
        mp.setattr(risk_server, "s2_model", _FixedProbModel(s2_p))
        mp.setattr(risk_server, "s2_pipeline", _IdentityPipeline())
    d = _post(base_url, "/api/v1/risk/analyze", payload)
    return {
        "decision": d["decision"],
        "fraud_score": d["fraud_score"],
        "primary_flag": d["primary_flag"],
        "all_flags": d["all_flags"],
        # velocity depends on the wall clock (time since the seeded last transaction)
        "cause_of_suspicion": re.sub(r"\([\d,.]+ km/h\)", "(<v> km/h)", d["cause_of_suspicion"]),
    }


def score_stable(base_url, payload):
    d = _post(base_url, "/api/v1/risk/score", payload)
    return {"decision": d["decision"], "score": d["score"], "riskReason": d["riskReason"]}


# --------------------------------------------------------------------------- inputs

_rnd = random.Random(0)
LOOP_AMOUNTS = [1, 29999] + [_rnd.randint(1, 29999) for _ in range(15)]

# name -> (/analyze payload, fixed S2 probability or None for the real model)
ANALYZE_CASES = {
    "plain_30000": ({"amount": 30000, "account_id": ACCOUNT}, None),
    "plain_500000": ({"amount": 500000, "account_id": ACCOUNT}, None),
    "device_anydesk_29999": (
        {
            "amount": 29999,
            "account_id": ACCOUNT,
            "memo": "",
            "device_context": {
                "is_primary_device": True,
                "active_accessibility_services": ["com.anydesk.anydeskandroid"],
                "media_projection": {"is_screen_sharing": True, "virtual_display_count": 1},
            },
        },
        None,
    ),
    "device_call_1500": (
        {
            "amount": 1500,
            "account_id": ACCOUNT,
            "memo": "",
            "device_context": {
                "is_primary_device": True,
                "telephony": {"call_state": "CALL_STATE_OFFHOOK", "call_duration_seconds": 180.0},
            },
        },
        None,
    ),
    "memo_scam_29999": (
        {
            "amount": 29999,
            "account_id": ACCOUNT,
            "memo": "urgent investment crypto broker guaranteed returns bail police",
        },
        None,
    ),
    # Severe verdicts that a BASIC 30000+ input must keep (3.5, 3.6)
    "gate0_emulator_50000": ({"emulator": True, "amount": 50000, "account_id": ACCOUNT}, None),
    "gate0_travel_30000": (
        {
            "user_id": "usr-1003-cst-003",
            "target_account_id": "acc-2001-sav-001",
            "account_id": ACCOUNT,
            "amount": 30000.0,
            "latitude": 1.3521,
            "longitude": 103.8198,
        },
        None,
    ),
    "gate0_2fa_30000": (
        {"rooted": True, "new_payee": True, "amount": 30000, "account_id": ACCOUNT},
        None,
    ),
    "s2_2fa_30000": ({"amount": 30000, "new_payee": False, "account_id": ACCOUNT}, 0.45),
    "s2_block_30000": ({"amount": 30000, "new_payee": False, "account_id": ACCOUNT}, 0.9),
}
for _a in LOOP_AMOUNTS:
    ANALYZE_CASES[f"amt_{_a}"] = ({"amount": _a, "account_id": ACCOUNT}, None)

# The app always sends a target account; without it the device/memo analysis is skipped
# (RiskAnalysisRequest validation), so give every case one.
for _payload, _ in ANALYZE_CASES.values():
    _payload.setdefault("target_account_id", "acc-2002-chk-001")

SCORE_CASES = {
    "score_30000": {"amount": 30000, "sourceAccountId": ACCOUNT},
    "score_500000": {"amount": 500000, "sourceAccountId": ACCOUNT},
    "score_250000": {"amount": 250000, "sourceAccountId": ACCOUNT},
    "score_29999": {"amount": 29999, "sourceAccountId": ACCOUNT},
    "score_memo_29999": {"amount": 29999, "sourceAccountId": ACCOUNT, "description": "crypto broker"},
}
for _a in LOOP_AMOUNTS:
    SCORE_CASES[f"score_amt_{_a}"] = {"amount": _a, "sourceAccountId": ACCOUNT}

# --------------------------------------------------------------------------- observed baseline
# Observed on UNFIXED code (no app.basic_tier_rule module) and pasted verbatim.
BASELINE_ANALYZE = {'plain_30000': {'decision': 'ALLOW',
                 'fraud_score': 10,
                 'primary_flag': 'NONE',
                 'all_flags': [],
                 'cause_of_suspicion': 'Anomalous transaction spike (12.0x baseline) deviating '
                                       'substantially from historical customer behavioral '
                                       'profile.'},
 'plain_500000': {'decision': 'ALLOW',
                  'fraud_score': 10,
                  'primary_flag': 'NONE',
                  'all_flags': [],
                  'cause_of_suspicion': 'Anomalous transaction spike (200.0x baseline) deviating '
                                        'substantially from historical customer behavioral '
                                        'profile.'},
 'device_anydesk_29999': {'decision': 'ADVISORY_WARNING',
                          'fraud_score': 10,
                          'primary_flag': 'DEVICE_THREAT_REMOTE_ACCESS_MALWARE',
                          'all_flags': ['DEVICE_THREAT_REMOTE_ACCESS_MALWARE'],
                          'cause_of_suspicion': 'Remote screen broadcast or control assistance '
                                                'application (e.g. AnyDesk, TeamViewer) actively '
                                                'operating during financial movement.'},
 'device_call_1500': {'decision': 'ADVISORY_WARNING',
                      'fraud_score': 1,
                      'primary_flag': 'DEVICE_THREAT_LIVE_CALL_COERCION',
                      'all_flags': ['DEVICE_THREAT_LIVE_CALL_COERCION'],
                      'cause_of_suspicion': 'Active phone call maintained concurrently with fund '
                                            'transfer to an unverified recipient, exhibiting '
                                            'phone-based social engineering or coercion.'},
 'memo_scam_29999': {'decision': 'ADVISORY_WARNING',
                     'fraud_score': 10,
                     'primary_flag': 'DEVICE_THREAT_LIVE_CALL_COERCION',
                     'all_flags': ['DEVICE_THREAT_LIVE_CALL_COERCION'],
                     'cause_of_suspicion': 'Active phone call maintained concurrently with fund '
                                           'transfer to an unverified recipient, exhibiting '
                                           'phone-based social engineering or coercion.'},
 'gate0_emulator_50000': {'decision': 'BLOCK',
                          'fraud_score': 100,
                          'primary_flag': 'CRITICAL_DEVICE_TAMPERING_ENVIRONMENT',
                          'all_flags': ['CRITICAL_DEVICE_TAMPERING_ENVIRONMENT'],
                          'cause_of_suspicion': 'Anomalous transaction spike (20.0x baseline) '
                                                'deviating substantially from historical '
                                                'customer behavioral profile.'},
 'gate0_travel_30000': {'decision': 'ADVISORY_WARNING',
                        'fraud_score': 75,
                        'primary_flag': 'IMPOSSIBLE_TRAVEL_VELOCITY',
                        'all_flags': ['IMPOSSIBLE_TRAVEL_VELOCITY'],
                        'cause_of_suspicion': 'Physically impossible travel velocity (<v> '
                                              'km/h), indicating remote credential abuse, proxy '
                                              'routing, or session token replay.'},
 # Any rooted device is blocked by Gate 0, so this case (rooted + new payee) is a BLOCK.
 'gate0_2fa_30000': {'decision': 'BLOCK',
                     'fraud_score': 100,
                     'primary_flag': 'CRITICAL_DEVICE_TAMPERING_ENVIRONMENT',
                     'all_flags': ['CRITICAL_DEVICE_TAMPERING_ENVIRONMENT'],
                     'cause_of_suspicion': 'Anomalous transaction spike (12.0x baseline) '
                                           'deviating substantially from historical '
                                           'customer behavioral profile.'},
 's2_2fa_30000': {'decision': 'REQUIRE_2FA',
                  'fraud_score': 45,
                  'primary_flag': 'ELEVATED_S2_SCORE',
                  'all_flags': ['ELEVATED_S2_SCORE'],
                  'cause_of_suspicion': 'Anomalous transaction spike (12.0x baseline) deviating '
                                        'substantially from historical customer behavioral '
                                        'profile.'},
 's2_block_30000': {'decision': 'BLOCK',
                    'fraud_score': 90,
                    'primary_flag': 'CRITICAL_FRAUD_RISK',
                    'all_flags': ['CRITICAL_FRAUD_RISK'],
                    'cause_of_suspicion': 'Anomalous transaction spike (12.0x baseline) deviating '
                                          'substantially from historical customer behavioral '
                                          'profile.'},
 'amt_1': {'decision': 'ALLOW',
           'fraud_score': 1,
           'primary_flag': 'NONE',
           'all_flags': [],
           'cause_of_suspicion': 'Transaction conforms to expected baseline behavior.'},
 'amt_29999': {'decision': 'ALLOW',
               'fraud_score': 10,
               'primary_flag': 'NONE',
               'all_flags': [],
               'cause_of_suspicion': 'Anomalous transaction spike (12.0x baseline) deviating '
                                     'substantially from historical customer behavioral profile.'},
 'amt_27671': {'decision': 'ALLOW',
               'fraud_score': 10,
               'primary_flag': 'NONE',
               'all_flags': [],
               'cause_of_suspicion': 'Anomalous transaction spike (11.1x baseline) deviating '
                                     'substantially from historical customer behavioral profile.'},
 'amt_12624': {'decision': 'ALLOW',
               'fraud_score': 5,
               'primary_flag': 'NONE',
               'all_flags': [],
               'cause_of_suspicion': 'Anomalous transaction spike (5.0x baseline) deviating '
                                     'substantially from historical customer behavioral profile.'},
 'amt_24837': {'decision': 'ALLOW',
               'fraud_score': 10,
               'primary_flag': 'NONE',
               'all_flags': [],
               'cause_of_suspicion': 'Anomalous transaction spike (9.9x baseline) deviating '
                                     'substantially from historical customer behavioral profile.'},
 'amt_29172': {'decision': 'ALLOW',
               'fraud_score': 10,
               'primary_flag': 'NONE',
               'all_flags': [],
               'cause_of_suspicion': 'Anomalous transaction spike (11.7x baseline) deviating '
                                     'substantially from historical customer behavioral profile.'},
 'amt_13782': {'decision': 'ALLOW',
               'fraud_score': 10,
               'primary_flag': 'NONE',
               'all_flags': [],
               'cause_of_suspicion': 'Anomalous transaction spike (5.5x baseline) deviating '
                                     'substantially from historical customer behavioral profile.'},
 'amt_1327': {'decision': 'ALLOW',
              'fraud_score': 1,
              'primary_flag': 'NONE',
              'all_flags': [],
              'cause_of_suspicion': 'Transaction conforms to expected baseline behavior.'},
 'amt_8485': {'decision': 'ALLOW',
              'fraud_score': 6,
              'primary_flag': 'NONE',
              'all_flags': [],
              'cause_of_suspicion': 'Transaction conforms to expected baseline behavior.'},
 'amt_16754': {'decision': 'ALLOW',
               'fraud_score': 10,
               'primary_flag': 'NONE',
               'all_flags': [],
               'cause_of_suspicion': 'Anomalous transaction spike (6.7x baseline) deviating '
                                     'substantially from historical customer behavioral profile.'},
 'amt_15923': {'decision': 'ALLOW',
               'fraud_score': 10,
               'primary_flag': 'NONE',
               'all_flags': [],
               'cause_of_suspicion': 'Anomalous transaction spike (6.4x baseline) deviating '
                                     'substantially from historical customer behavioral profile.'},
 'amt_13269': {'decision': 'ALLOW',
               'fraud_score': 9,
               'primary_flag': 'NONE',
               'all_flags': [],
               'cause_of_suspicion': 'Anomalous transaction spike (5.3x baseline) deviating '
                                     'substantially from historical customer behavioral profile.'},
 'amt_25684': {'decision': 'ALLOW',
               'fraud_score': 10,
               'primary_flag': 'NONE',
               'all_flags': [],
               'cause_of_suspicion': 'Anomalous transaction spike (10.3x baseline) deviating '
                                     'substantially from historical customer behavioral profile.'},
 'amt_27193': {'decision': 'ALLOW',
               'fraud_score': 10,
               'primary_flag': 'NONE',
               'all_flags': [],
               'cause_of_suspicion': 'Anomalous transaction spike (10.9x baseline) deviating '
                                     'substantially from historical customer behavioral profile.'},
 'amt_9939': {'decision': 'ALLOW',
              'fraud_score': 5,
              'primary_flag': 'NONE',
              'all_flags': [],
              'cause_of_suspicion': 'Transaction conforms to expected baseline behavior.'},
 'amt_15618': {'decision': 'ALLOW',
               'fraud_score': 10,
               'primary_flag': 'NONE',
               'all_flags': [],
               'cause_of_suspicion': 'Anomalous transaction spike (6.2x baseline) deviating '
                                     'substantially from historical customer behavioral profile.'},
 'amt_11733': {'decision': 'ALLOW',
               'fraud_score': 5,
               'primary_flag': 'NONE',
               'all_flags': [],
               'cause_of_suspicion': 'Anomalous transaction spike (4.7x baseline) deviating '
                                     'substantially from historical customer behavioral profile.'}}

BASELINE_SCORE = {'score_30000': {'decision': 'ALLOW', 'score': 15, 'riskReason': 'Standard low risk transaction'},
 'score_500000': {'decision': 'ADVISORY_WARNING',
                  'score': 75,
                  'riskReason': 'Potential high-risk payee or scam memo typology detected'},
 'score_250000': {'decision': 'REQUIRE_2FA',
                  'score': 65,
                  'riskReason': 'High-value threshold step-up required (AMLA elevated monitoring)'},
 'score_29999': {'decision': 'ALLOW', 'score': 15, 'riskReason': 'Standard low risk transaction'},
 'score_memo_29999': {'decision': 'ADVISORY_WARNING',
                      'score': 75,
                      'riskReason': 'Potential high-risk payee or scam memo typology detected'},
 'score_amt_1': {'decision': 'ALLOW', 'score': 15, 'riskReason': 'Standard low risk transaction'},
 'score_amt_29999': {'decision': 'ALLOW',
                     'score': 15,
                     'riskReason': 'Standard low risk transaction'},
 'score_amt_27671': {'decision': 'ALLOW',
                     'score': 15,
                     'riskReason': 'Standard low risk transaction'},
 'score_amt_12624': {'decision': 'ALLOW',
                     'score': 15,
                     'riskReason': 'Standard low risk transaction'},
 'score_amt_24837': {'decision': 'ALLOW',
                     'score': 15,
                     'riskReason': 'Standard low risk transaction'},
 'score_amt_29172': {'decision': 'ALLOW',
                     'score': 15,
                     'riskReason': 'Standard low risk transaction'},
 'score_amt_13782': {'decision': 'ALLOW',
                     'score': 15,
                     'riskReason': 'Standard low risk transaction'},
 'score_amt_1327': {'decision': 'ALLOW',
                    'score': 15,
                    'riskReason': 'Standard low risk transaction'},
 'score_amt_8485': {'decision': 'ALLOW',
                    'score': 15,
                    'riskReason': 'Standard low risk transaction'},
 'score_amt_16754': {'decision': 'ALLOW',
                     'score': 15,
                     'riskReason': 'Standard low risk transaction'},
 'score_amt_15923': {'decision': 'ALLOW',
                     'score': 15,
                     'riskReason': 'Standard low risk transaction'},
 'score_amt_13269': {'decision': 'ALLOW',
                     'score': 15,
                     'riskReason': 'Standard low risk transaction'},
 'score_amt_25684': {'decision': 'ALLOW',
                     'score': 15,
                     'riskReason': 'Standard low risk transaction'},
 'score_amt_27193': {'decision': 'ALLOW',
                     'score': 15,
                     'riskReason': 'Standard low risk transaction'},
 'score_amt_9939': {'decision': 'ALLOW',
                    'score': 15,
                    'riskReason': 'Standard low risk transaction'},
 'score_amt_15618': {'decision': 'ALLOW',
                     'score': 15,
                     'riskReason': 'Standard low risk transaction'},
 'score_amt_11733': {'decision': 'ALLOW',
                     'score': 15,
                     'riskReason': 'Standard low risk transaction'}}


def _check_analyze(base_url, risk_server, mp, name):
    payload, s2_p = ANALYZE_CASES[name]
    assert analyze_stable(base_url, risk_server, mp, dict(payload), s2_p) == BASELINE_ANALYZE[name], name


def _check_score(base_url, name):
    assert score_stable(base_url, dict(SCORE_CASES[name])) == BASELINE_SCORE[name], name


# --------------------------------------------------------------------------- tests


@pytest.mark.parametrize("name", ["plain_30000", "plain_500000"])
def test_fully_verified_analyze_unchanged(base_url, risk_server, stub, monkeypatch, name):
    """3.1: FULLY_VERIFIED /analyze at 30000 and 500000 keeps today's verdict."""
    stub.set(tier="FULLY_VERIFIED")
    _check_analyze(base_url, risk_server, monkeypatch, name)


@pytest.mark.parametrize("name", ["score_30000", "score_500000"])
def test_fully_verified_score_unchanged(base_url, stub, name):
    """3.1: FULLY_VERIFIED /score at 30000 and 500000 keeps today's verdict."""
    stub.set(tier="FULLY_VERIFIED")
    _check_score(base_url, name)


def test_basic_below_threshold_analyze_unchanged(base_url, risk_server, stub, monkeypatch):
    """3.2: BASIC under 30000 on /analyze, incl. 29999, seeded loop, device and memo signals."""
    stub.set(tier="BASIC")
    names = [f"amt_{a}" for a in LOOP_AMOUNTS] + [
        "device_anydesk_29999",
        "device_call_1500",
        "memo_scam_29999",
    ]
    for name in names:
        _check_analyze(base_url, risk_server, monkeypatch, name)


def test_basic_below_threshold_score_unchanged(base_url, stub):
    """3.2: BASIC under 30000 on /score, incl. 29999, seeded loop and scam memo."""
    stub.set(tier="BASIC")
    names = [f"score_amt_{a}" for a in LOOP_AMOUNTS] + ["score_29999", "score_memo_29999"]
    for name in names:
        _check_score(base_url, name)


@pytest.mark.parametrize(
    "name",
    [
        "gate0_emulator_50000",
        "gate0_travel_30000",
        "gate0_2fa_30000",
        "s2_2fa_30000",
        "s2_block_30000",
    ],
)
def test_basic_severe_analyze_verdicts_kept(base_url, risk_server, stub, monkeypatch, name):
    """3.5, 3.6: Gate 0 BLOCK / REQUIRE_2FA and S2 verdicts are never lowered for BASIC 30000+."""
    stub.set(tier="BASIC")
    _check_analyze(base_url, risk_server, monkeypatch, name)


def test_basic_250000_score_stays_require_2fa(base_url, stub):
    """3.5: /score at 250000 stays REQUIRE_2FA for BASIC."""
    stub.set(tier="BASIC")
    _check_score(base_url, "score_250000")
    assert BASELINE_SCORE["score_250000"]["decision"] == "REQUIRE_2FA"
    got = score_stable(base_url, dict(SCORE_CASES["score_250000"]))
    assert got["score"] == 65
    assert got["riskReason"] == "High-value threshold step-up required (AMLA elevated monitoring)"


def test_basic_score_existing_advisories_keep_their_reason(base_url, stub):
    """3.5: /score scam memo and 500000+ ADVISORY_WARNING keep their own reason for BASIC."""
    stub.set(tier="BASIC")
    _check_score(base_url, "score_500000")
    _check_score(base_url, "score_memo_29999")


@pytest.mark.parametrize("mode", ["restricted", "http500", "delay", "closed_port"])
def test_tier_lookup_trouble_keeps_todays_verdict(
    base_url, risk_server, stub, monkeypatch, mode
):
    """2.5, 3.2: restricted account or a failing lookup -> unflagged baseline, HTTP 200, no exception."""
    if mode == "restricted":
        stub.set(tier="BASIC", restricted=True)
    elif mode == "http500":
        stub.set(tier="BASIC", mode="http500")
    elif mode == "delay":
        stub.set(tier="BASIC", mode="delay")
    else:
        stub.set(tier="BASIC")
        stub.unreachable(monkeypatch)

    _check_analyze(base_url, risk_server, monkeypatch, "plain_30000")
    _check_score(base_url, "score_30000")
    _check_analyze(base_url, risk_server, monkeypatch, "gate0_2fa_30000")
    _check_score(base_url, "score_250000")


def test_client_supplied_tier_is_ignored(base_url, risk_server, stub, monkeypatch):
    """Body claiming BASIC/PENDING while the server says FULLY_VERIFIED -> unflagged baseline."""
    stub.set(tier="FULLY_VERIFIED")
    claims = {"tier": "BASIC", "kyc_status": "PENDING"}

    payload, _ = ANALYZE_CASES["plain_30000"]
    got = analyze_stable(base_url, risk_server, monkeypatch, {**payload, **claims})
    assert got == BASELINE_ANALYZE["plain_30000"]

    got = score_stable(base_url, {**SCORE_CASES["score_30000"], **claims})
    assert got == BASELINE_SCORE["score_30000"]


# --------------------------------------------------------------------------- /analyze merge cases (task 3.5)

FLAG = "BASIC_TIER_HIGH_AMOUNT"


def test_device_advisory_plus_basic_30000_keeps_device_signal(base_url, stub):
    """2.2, 3.6: an existing device advisory keeps its flag, dialog and cause; score floored, flag appended."""
    stub.set(tier="BASIC")
    payload = dict(ANALYZE_CASES["device_anydesk_29999"][0], amount=30000)
    got = _post(base_url, "/api/v1/risk/analyze", payload)
    baseline = BASELINE_ANALYZE["device_anydesk_29999"]

    assert got["decision"] == "ADVISORY_WARNING"
    assert got["primary_flag"] == baseline["primary_flag"]
    assert got["cause_of_suspicion"] == baseline["cause_of_suspicion"]
    assert got["fraud_score"] >= 75
    assert FLAG in got["all_flags"]
    assert baseline["primary_flag"] in got["all_flags"]
    assert got["warning_dialog"]["threat_category"] == "REMOTE_ACCESS_MALWARE"

    # The same device input from a FULLY_VERIFIED account gets no Basic-tier flag.
    stub.set(tier="FULLY_VERIFIED")
    other = _post(base_url, "/api/v1/risk/analyze", payload)
    assert FLAG not in other["all_flags"]
    assert other["fraud_score"] < 75


def test_basic_30000_allow_becomes_full_advisory(base_url, stub):
    """2.2: ALLOW -> ADVISORY_WARNING with flag, threat category, dialog and cause set; works without target_account_id."""
    stub.set(tier="BASIC")
    for extra in ({}, {"target_account_id": "acc-2002-chk-001"}):
        got = _post(
            base_url, "/api/v1/risk/analyze", {"amount": 30000, "account_id": ACCOUNT, **extra}
        )
        assert got["decision"] == "ADVISORY_WARNING"
        assert got["status"] == "ADVISORY_PENDING"
        assert got["advisory_tier"] == "ADVISORY_WARNING"
        assert got["primary_flag"] == FLAG
        assert got["threat_category"] == FLAG
        assert got["all_flags"] == [FLAG]
        assert got["warning_dialog"] is not None
        assert got["cause_of_suspicion"] == "Suspicious high amount for Basic tier (PHP 30,000.00)"


def test_basic_30000_creates_no_sar_draft(base_url, stub):
    """Score 75 stays under the SAR trigger (fraud_score >= 80)."""
    stub.set(tier="BASIC")
    got = _post(
        base_url,
        "/api/v1/risk/analyze",
        {"amount": 30000, "account_id": ACCOUNT, "target_account_id": "acc-2002-chk-001"},
    )
    assert got["fraud_score"] == 75
    assert got["sar_draft_created"] is False
    assert got["sar_report_id"] is None


@pytest.mark.parametrize("name", ["gate0_2fa_30000", "gate0_emulator_50000", "gate0_travel_30000"])
def test_gate0_verdicts_get_no_basic_flag(base_url, stub, name):
    """3.5: Gate 0 REQUIRE_2FA / BLOCK are untouched: no flag, same decision and score."""
    stub.set(tier="BASIC")
    payload, _ = ANALYZE_CASES[name]
    got = _post(base_url, "/api/v1/risk/analyze", dict(payload))
    assert FLAG not in got["all_flags"]
    assert got["decision"] == BASELINE_ANALYZE[name]["decision"]
    assert got["fraud_score"] == BASELINE_ANALYZE[name]["fraud_score"]
