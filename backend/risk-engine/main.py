"""
Core Retail Banking - Asynchronous Fraud Risk Screening Engine
FastAPI Service evaluating geovelocity and impossible travel heuristics under <= 200ms SLA.
"""

import math
import time
import logging
from typing import Optional
from fastapi import FastAPI, Request, status
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] [RISK-ENGINE] %(message)s"
)
logger = logging.getLogger("risk-engine")

app = FastAPI(
    title="Real-Time Fraud Risk Screening Engine",
    description="Asynchronous Geovelocity & Impossible Travel Heuristics Engine",
    version="2.0.0"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """
    Calculate the great circle distance between two points 
    on the earth (specified in decimal degrees) using Haversine formula.
    """
    R = 6371.0  # Earth's radius in kilometers
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = (math.sin(dlat / 2.0) ** 2 +
         math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) *
         math.sin(dlon / 2.0) ** 2)
    c = 2.0 * math.atan2(math.sqrt(a), math.sqrt(1.0 - a))
    return R * c

class RiskEvaluationRequest(BaseModel):
    user_id: Optional[str] = "U1001"
    account_id: str
    amount: float = Field(gt=0, description="Transaction amount in PHP")
    current_lat: Optional[float] = None
    current_lon: Optional[float] = None
    current_city: Optional[str] = "Unknown"
    previous_lat: Optional[float] = None
    previous_lon: Optional[float] = None
    previous_city: Optional[str] = "Unknown"
    time_diff_seconds: Optional[float] = None

class RiskEvaluationResponse(BaseModel):
    risk_score: float
    decision: str  # ALLOW or DENY
    reason: str
    velocity_kmh: float
    distance_km: float
    evaluation_time_ms: float

@app.get("/health")
@app.get("/actuator/health")
def health_check():
    return {"status": "UP", "service": "risk-engine", "version": "2.0.0"}

@app.post("/api/v1/risk/evaluate", response_model=RiskEvaluationResponse)
def evaluate_transaction_risk(req: RiskEvaluationRequest):
    start_time = time.time()
    
    # 1. Base Score calculation
    base_risk = 0.05
    
    # Check for missing coordinates (Default to safe low-risk baseline)
    if req.current_lat is None or req.current_lon is None:
        elapsed_ms = (time.time() - start_time) * 1000.0
        return RiskEvaluationResponse(
            risk_score=0.10,
            decision="ALLOW",
            reason="NO_GEOLOCATION_PROVIDED_BASELINE_ALLOW",
            velocity_kmh=0.0,
            distance_km=0.0,
            evaluation_time_ms=round(elapsed_ms, 2)
        )

    # First transaction baseline (no previous coordinates to compare)
    if req.previous_lat is None or req.previous_lon is None:
        elapsed_ms = (time.time() - start_time) * 1000.0
        logger.info(f"First transaction recorded for account {req.account_id} at {req.current_city} ({req.current_lat}, {req.current_lon})")
        return RiskEvaluationResponse(
            risk_score=0.05,
            decision="ALLOW",
            reason="FIRST_TRANSACTION_ESTABLISHED_BASELINE",
            velocity_kmh=0.0,
            distance_km=0.0,
            evaluation_time_ms=round(elapsed_ms, 2)
        )

    # 2. Compute Haversine Great Circle Distance
    distance_km = haversine_km(req.previous_lat, req.previous_lon, req.current_lat, req.current_lon)
    
    # Calculate Time Delta
    time_sec = req.time_diff_seconds if req.time_diff_seconds and req.time_diff_seconds > 0 else 60.0
    time_hours = time_sec / 3600.0
    
    # Velocity in km/h
    velocity_kmh = distance_km / time_hours if time_hours > 0 else 0.0

    logger.info(
        f"[GEORISK EVAL] Account: {req.account_id} | From: {req.previous_city} to {req.current_city} | "
        f"Distance: {distance_km:.2f} km | Time: {time_sec:.0f}s | Speed: {velocity_kmh:.2f} km/h"
    )

    # 3. Impossible Travel Heuristic Evaluation
    # Commercial aircraft speed threshold: 800 km/h (minimum distance 50 km to ignore GPS jitter)
    if velocity_kmh > 800.0 and distance_km > 50.0:
        risk_score = 0.98  # Critical threshold (> 0.85)
        decision = "DENY"
        reason = (
            f"IMPOSSIBLE_TRAVEL_DETECTED: Velocity {velocity_kmh:,.1f} km/h between "
            f"{req.previous_city} and {req.current_city} ({distance_km:,.1f} km in {time_sec:.0f}s) "
            f"exceeds maximum physical aircraft velocity of 800 km/h."
        )
        logger.warning(f"🚨 [FRAUD DETECTED] {reason}")
    elif velocity_kmh > 300.0 and distance_km > 30.0:
        # High speed (e.g. high-speed rail / bullet train or localized flight)
        risk_score = 0.55
        decision = "ALLOW"
        reason = f"HIGH_VELOCITY_WARNING: {velocity_kmh:.1f} km/h detected between {req.previous_city} and {req.current_city}"
    else:
        # Normal domestic or stationary velocity
        risk_score = 0.05
        decision = "ALLOW"
        reason = f"NORMAL_GEOVELOCITY: {velocity_kmh:.1f} km/h within legitimate travel limits"

    elapsed_ms = (time.time() - start_time) * 1000.0

    return RiskEvaluationResponse(
        risk_score=round(risk_score, 2),
        decision=decision,
        reason=reason,
        velocity_kmh=round(velocity_kmh, 2),
        distance_km=round(distance_km, 2),
        evaluation_time_ms=round(elapsed_ms, 2)
    )
