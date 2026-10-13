"""
Travel-speed rule for transfers scored on /api/v1/risk/score.

The customer's current location is read from account-service (never from the request body),
compared with where their previous transfer was scored, and a transfer that would need faster
than airliner speed to get there is blocked. A blocked transfer does not move the baseline, so
retrying from the new place stays blocked until enough time has passed to have travelled.
A lookup problem fails open: ``check`` returns None and the caller keeps today's verdict.
"""
import json
import logging
import os
import threading
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from typing import Dict, Optional, Tuple

from app.geo_math import IMPOSSIBLE_TRAVEL_SPEED_KMH, calculate_haversine_distance

FLAG = "IMPOSSIBLE_TRAVEL_VELOCITY"
REASON = "Location changed faster than physically possible since the previous transfer"
ACCOUNT_SERVICE_URL = os.environ.get("ACCOUNT_SERVICE_URL", "http://account-service:8081")
_LOOKUP_TIMEOUT_SECONDS = 0.5
# Moves shorter than this are treated as GPS/city jitter, however quickly they happen.
MIN_DISTANCE_KM = 100.0

logger = logging.getLogger(__name__)

# account id -> (latitude, longitude, time of the last transfer that was allowed through)
_last_transfer: Dict[str, Tuple[float, float, datetime]] = {}
_lock = threading.Lock()


def _current_location(account_id: str) -> Optional[Tuple[float, float]]:
    url = f"{ACCOUNT_SERVICE_URL}/internal/accounts/location?" + urllib.parse.urlencode(
        {"accountId": account_id}
    )
    try:
        with urllib.request.urlopen(url, timeout=_LOOKUP_TIMEOUT_SECONDS) as res:
            if res.status != 200:
                return None
            body = json.loads(res.read().decode("utf-8"))
        return float(body["latitude"]), float(body["longitude"])
    except Exception as exc:  # connect, timeout, 404, bad JSON: fail open
        logger.warning("geo_velocity_rule: location lookup failed, rule not applied: %r", exc)
        return None


def _speed_kmh(prev: Tuple[float, float, datetime], lat: float, lon: float, now: datetime) -> float:
    distance_km = calculate_haversine_distance(prev[0], prev[1], lat, lon)
    hours = max((now - prev[2]).total_seconds(), 1.0) / 3600.0
    return distance_km / hours


def check(account_id) -> Optional[Dict]:
    """
    Returns a dict describing impossible travel (velocity_kmh, distance_km) or None when the
    transfer is fine, is the account's first one seen, or the location could not be read.
    On a pass, the current location becomes the baseline for the next transfer.
    """
    if not isinstance(account_id, str) or not account_id.strip():
        return None
    here = _current_location(account_id)
    if here is None:
        return None
    lat, lon = here
    now = datetime.now(timezone.utc)

    with _lock:
        prev = _last_transfer.get(account_id)
        if prev is not None:
            distance_km = calculate_haversine_distance(prev[0], prev[1], lat, lon)
            speed = _speed_kmh(prev, lat, lon, now)
            if distance_km >= MIN_DISTANCE_KM and speed > IMPOSSIBLE_TRAVEL_SPEED_KMH:
                return {"velocity_kmh": round(speed, 1), "distance_km": round(distance_km, 1)}
        _last_transfer[account_id] = (lat, lon, now)
    return None


def reset() -> None:
    """Test helper."""
    with _lock:
        _last_transfer.clear()
