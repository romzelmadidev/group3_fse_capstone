"""
Basic-tier high-amount rule.

A transfer of PHP 30,000 or more from a non-restricted BASIC KYC tier account is raised to an
ADVISORY_WARNING. The tier is read only from account-service (never from the request body).
Any lookup problem fails open: ``applies`` returns False and the caller keeps today's verdict.
"""

import json
import logging
import math
import os
import urllib.parse
import urllib.request

THRESHOLD = 30000.0
SCORE = 75
FLAG = "BASIC_TIER_HIGH_AMOUNT"
REASON = "Suspicious high amount for Basic tier"

ACCOUNT_SERVICE_URL = os.environ.get("ACCOUNT_SERVICE_URL", "http://account-service:8081")
_LOOKUP_TIMEOUT_SECONDS = 0.5

logger = logging.getLogger(__name__)


def applies(account_id, amount) -> bool:
    """True only for a non-restricted BASIC account sending at least THRESHOLD."""
    try:
        value = float(amount)
    except (TypeError, ValueError):
        return False
    if not math.isfinite(value) or value < THRESHOLD:
        return False
    if not isinstance(account_id, str) or not account_id.strip():
        return False

    url = f"{ACCOUNT_SERVICE_URL}/internal/limits/tier?" + urllib.parse.urlencode(
        {"accountId": account_id}
    )
    try:
        with urllib.request.urlopen(url, timeout=_LOOKUP_TIMEOUT_SECONDS) as res:
            if res.status != 200:
                return False
            body = json.loads(res.read().decode("utf-8"))
        return body.get("tier") == "BASIC" and body.get("restricted") is False
    except Exception as exc:  # connect, timeout, non-200, bad JSON: fail open
        logger.warning("basic_tier_rule: tier lookup failed, rule not applied: %r", exc)
        return False
