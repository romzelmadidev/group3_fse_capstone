import React, { useEffect, useRef, useState, useMemo } from 'react';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { 
  ShieldAlert, 
  MapPin, 
  Navigation, 
  Activity, 
  AlertTriangle, 
  CheckCircle2, 
  Clock, 
  Plane, 
  RefreshCw, 
  Zap, 
  Globe, 
  User, 
  ArrowRight, 
  PlusCircle, 
  RotateCcw, 
  Compass, 
  ChevronRight,
  Eye,
  Filter
} from 'lucide-react';
import apiClient, { mockState } from '../services/api';
import { formatPHP } from '../utils/currency';

// Preset locations database with WGS-84 coordinates
const PRESET_CITIES = {
  MNL: {
    cityId: 'MNL',
    cityName: 'Manila',
    locationName: 'Manila, Philippines',
    country: 'Philippines',
    lat: 14.5995,
    lon: 120.9842,
    ip: '112.198.45.10',
  },
  LON: {
    cityId: 'LON',
    cityName: 'London',
    locationName: 'London, United Kingdom',
    country: 'United Kingdom',
    lat: 51.5074,
    lon: -0.1278,
    ip: '185.86.151.11',
  },
  NYC: {
    cityId: 'NYC',
    cityName: 'New York',
    locationName: 'New York, USA',
    country: 'United States',
    lat: 40.7128,
    lon: -74.0060,
    ip: '198.51.100.42',
  },
  CEB: {
    cityId: 'CEB',
    cityName: 'Cebu City',
    locationName: 'Cebu City, Philippines',
    country: 'Philippines',
    lat: 10.3157,
    lon: 123.8854,
    ip: '112.198.88.22',
  },
  TYO: {
    cityId: 'TYO',
    cityName: 'Tokyo',
    locationName: 'Tokyo, Japan',
    country: 'Japan',
    lat: 35.6762,
    lon: 139.6503,
    ip: '133.242.18.9',
  },
  DVO: {
    cityId: 'DVO',
    cityName: 'Davao City',
    locationName: 'Davao City, Philippines',
    country: 'Philippines',
    lat: 7.1907,
    lon: 125.4553,
    ip: '112.198.99.55',
  },
  BGC: {
    cityId: 'BGC',
    cityName: 'Taguig / BGC',
    locationName: 'BGC Taguig, Philippines',
    country: 'Philippines',
    lat: 14.5480,
    lon: 121.0509,
    ip: '120.28.14.22',
  },
  MKT: {
    cityId: 'MKT',
    cityName: 'Makati CBD',
    locationName: 'Makati CBD, Philippines',
    country: 'Philippines',
    lat: 14.5547,
    lon: 121.0244,
    ip: '120.28.11.90',
  }
};

// Calculate Haversine great-circle distance & velocity
function calculateHaversine(lat1, lon1, lat2, lon2, timeDeltaSec) {
  const R = 6371.0; // Earth radius in km
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLon = ((lon2 - lon1) * Math.PI) / 180;
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos((lat1 * Math.PI) / 180) *
      Math.cos((lat2 * Math.PI) / 180) *
      Math.sin(dLon / 2) *
      Math.sin(dLon / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  const distKm = R * c;
  const effectiveSeconds = Math.max(1, timeDeltaSec || 1);
  const hours = effectiveSeconds / 3600;
  const velocityKmh = distKm / hours;
  const isFraud = velocityKmh > 800.0;
  return {
    distKm,
    velocityKmh,
    isFraud,
    riskScore: isFraud ? 0.98 : 0.05
  };
}

// Initial realistic baseline transaction journeys for bank accounts
const INITIAL_PROFILES = [
  {
    userId: 'U1001',
    accountId: '1000-2000-3001',
    userName: 'Juan Dela Cruz',
    accountType: 'SAVINGS (Primary)',
    hasFraudFlag: true,
    flagReason: 'Impossible Travel detected: 2.5M km/h jump between Manila & London',
    transactions: [
      {
        id: 'TX-984210',
        step: 1,
        timestamp: new Date(Date.now() - 7200000).toISOString(),
        locationName: PRESET_CITIES.MNL.locationName,
        cityName: PRESET_CITIES.MNL.cityName,
        lat: PRESET_CITIES.MNL.lat,
        lon: PRESET_CITIES.MNL.lon,
        ip: PRESET_CITIES.MNL.ip,
        amount: 15000.0,
        status: 'COMMITTED',
        riskScore: 0.05,
        riskReason: 'Authorized baseline transaction from registered home city.',
        timeDeltaLabel: 'Baseline Origin',
        timeDeltaSeconds: 0,
        distanceKm: 0,
        velocityKmh: 0,
        isFraud: false
      },
      {
        id: 'TX-FRAUD-7721',
        step: 2,
        timestamp: new Date(Date.now() - 7185000).toISOString(),
        locationName: PRESET_CITIES.LON.locationName,
        cityName: PRESET_CITIES.LON.cityName,
        lat: PRESET_CITIES.LON.lat,
        lon: PRESET_CITIES.LON.lon,
        ip: PRESET_CITIES.LON.ip,
        amount: 50000.0,
        status: 'REJECTED_FRAUD',
        riskScore: 0.98,
        riskReason: 'IMPOSSIBLE_TRAVEL_DETECTED: Velocity 2,576,400 km/h exceeds physical commercial aircraft ceiling (800 km/h).',
        timeDeltaLabel: '15 seconds after Step #1',
        timeDeltaSeconds: 15,
        distanceKm: 10735,
        velocityKmh: 2576400,
        isFraud: true
      },
      {
        id: 'TX-984332',
        step: 3,
        timestamp: new Date(Date.now() - 3600000).toISOString(),
        locationName: PRESET_CITIES.CEB.locationName,
        cityName: PRESET_CITIES.CEB.cityName,
        lat: PRESET_CITIES.CEB.lat,
        lon: PRESET_CITIES.CEB.lon,
        ip: PRESET_CITIES.CEB.ip,
        amount: 8500.0,
        status: 'COMMITTED',
        riskScore: 0.06,
        riskReason: 'NORMAL_GEOVELOCITY: Velocity 285 km/h within legitimate domestic airline flight limits.',
        timeDeltaLabel: '2 hours after Step #1',
        timeDeltaSeconds: 7200,
        distanceKm: 570,
        velocityKmh: 285,
        isFraud: false
      }
    ]
  },
  {
    userId: 'U1002',
    accountId: '1000-2000-3002',
    userName: 'Maria Clara Santos',
    accountType: 'SAVINGS',
    hasFraudFlag: false,
    flagReason: 'All vectors within physical transit boundaries',
    transactions: [
      {
        id: 'TX-882101',
        step: 1,
        timestamp: new Date(Date.now() - 18000000).toISOString(),
        locationName: PRESET_CITIES.CEB.locationName,
        cityName: PRESET_CITIES.CEB.cityName,
        lat: PRESET_CITIES.CEB.lat,
        lon: PRESET_CITIES.CEB.lon,
        ip: PRESET_CITIES.CEB.ip,
        amount: 12000.0,
        status: 'COMMITTED',
        riskScore: 0.04,
        riskReason: 'Authorized baseline transaction from registered home city.',
        timeDeltaLabel: 'Baseline Origin',
        timeDeltaSeconds: 0,
        distanceKm: 0,
        velocityKmh: 0,
        isFraud: false
      },
      {
        id: 'TX-882102',
        step: 2,
        timestamp: new Date(Date.now() - 3600000).toISOString(),
        locationName: PRESET_CITIES.DVO.locationName,
        cityName: PRESET_CITIES.DVO.cityName,
        lat: PRESET_CITIES.DVO.lat,
        lon: PRESET_CITIES.DVO.lon,
        ip: PRESET_CITIES.DVO.ip,
        amount: 8500.0,
        status: 'COMMITTED',
        riskScore: 0.05,
        riskReason: 'NORMAL_GEOVELOCITY: Velocity 101 km/h within inter-island regional transit limits.',
        timeDeltaLabel: '4 hours after Step #1',
        timeDeltaSeconds: 14400,
        distanceKm: 405,
        velocityKmh: 101.25,
        isFraud: false
      }
    ]
  },
  {
    userId: 'U3003',
    accountId: '1000-2000-3003',
    userName: 'Carlos Mendoza',
    accountType: 'CURRENT (Executive)',
    hasFraudFlag: false,
    flagReason: 'Intra-metro traversal verified',
    transactions: [
      {
        id: 'TX-770101',
        step: 1,
        timestamp: new Date(Date.now() - 7200000).toISOString(),
        locationName: PRESET_CITIES.MKT.locationName,
        cityName: PRESET_CITIES.MKT.cityName,
        lat: PRESET_CITIES.MKT.lat,
        lon: PRESET_CITIES.MKT.lon,
        ip: PRESET_CITIES.MKT.ip,
        amount: 35000.0,
        status: 'COMMITTED',
        riskScore: 0.02,
        riskReason: 'Authorized baseline transaction from business headquarters.',
        timeDeltaLabel: 'Baseline Origin',
        timeDeltaSeconds: 0,
        distanceKm: 0,
        velocityKmh: 0,
        isFraud: false
      },
      {
        id: 'TX-770102',
        step: 2,
        timestamp: new Date(Date.now() - 5100000).toISOString(),
        locationName: PRESET_CITIES.BGC.locationName,
        cityName: PRESET_CITIES.BGC.cityName,
        lat: PRESET_CITIES.BGC.lat,
        lon: PRESET_CITIES.BGC.lon,
        ip: PRESET_CITIES.BGC.ip,
        amount: 18000.0,
        status: 'COMMITTED',
        riskScore: 0.03,
        riskReason: 'NORMAL_GEOVELOCITY: Intra-city vehicular commute (3.8 km in 35 mins).',
        timeDeltaLabel: '35 mins after Step #1',
        timeDeltaSeconds: 2100,
        distanceKm: 3.8,
        velocityKmh: 6.5,
        isFraud: false
      }
    ]
  }
];

export default function FraudMap() {
  const mapContainerRef = useRef(null);
  const mapInstanceRef = useRef(null);
  const markersLayerGroupRef = useRef(null);
  const polylinesLayerGroupRef = useRef(null);
  const markerObjectsRef = useRef({});

  // Active User Profile State
  const [profiles, setProfiles] = useState(INITIAL_PROFILES);
  const [selectedAccountId, setSelectedAccountId] = useState('1000-2000-3001'); // Default to Juan Dela Cruz (Flagged)
  const [focusedTxId, setFocusedTxId] = useState(null);
  const [isSimulating, setIsSimulating] = useState(false);

  // Active Selected User Profile
  const activeProfile = useMemo(() => {
    return profiles.find((p) => p.accountId === selectedAccountId) || profiles[0];
  }, [profiles, selectedAccountId]);

  // Chronological transactions for the selected user
  const chronologicalTxList = useMemo(() => {
    const list = [...(activeProfile?.transactions || [])];
    list.sort((a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime());
    return list.map((tx, idx) => ({ ...tx, stepNumber: idx + 1 }));
  }, [activeProfile]);

  // Initialize Leaflet Map Once
  useEffect(() => {
    if (!mapContainerRef.current) return;

    if (!mapInstanceRef.current) {
      const map = L.map(mapContainerRef.current, {
        center: [14.5995, 120.9842],
        zoom: 3,
        minZoom: 1,
        maxZoom: 18,
        worldCopyJump: true
      });

      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
        maxZoom: 19
      }).addTo(map);

      // Create LayerGroups for clean marker & vector teardown
      markersLayerGroupRef.current = L.layerGroup().addTo(map);
      polylinesLayerGroupRef.current = L.layerGroup().addTo(map);

      mapInstanceRef.current = map;
    }

    // Force map size recalculation
    setTimeout(() => {
      mapInstanceRef.current?.invalidateSize();
    }, 200);

    return () => {
      // Map cleanup if container is destroyed
    };
  }, []);

  // Render Sequential Numbered Markers (1, 2, 3...) & Flight Polylines
  useEffect(() => {
    const map = mapInstanceRef.current;
    if (!map || !markersLayerGroupRef.current || !polylinesLayerGroupRef.current) return;

    const markersGroup = markersLayerGroupRef.current;
    const polylineGroup = polylinesLayerGroupRef.current;

    // Clear previous elements
    markersGroup.clearLayers();
    polylineGroup.clearLayers();
    markerObjectsRef.current = {};

    if (chronologicalTxList.length === 0) return;

    const latLngPoints = [];

    chronologicalTxList.forEach((tx, idx) => {
      const stepNumber = idx + 1;
      const isFraud = tx.isFraud || tx.status === 'REJECTED_FRAUD';
      const latLng = [tx.lat, tx.lon];
      latLngPoints.push(latLng);

      // 1. Create Custom Leaflet DivIcon with sequential number: 1, 2, 3...
      const numberedIcon = L.divIcon({
        className: 'custom-numbered-pin',
        html: `
          <div style="position: relative; width: 34px; height: 34px; display: flex; align-items: center; justify-content: center;">
            ${isFraud ? `
              <div style="
                position: absolute; 
                width: 44px; 
                height: 44px; 
                border-radius: 50%; 
                border: 2px solid #ef4444; 
                opacity: 0.8; 
                animation: ping 1.4s cubic-bezier(0, 0, 0.2, 1) infinite;
              "></div>
            ` : ''}
            <div style="
              width: 30px; 
              height: 30px; 
              border-radius: 50%; 
              background-color: ${isFraud ? '#ef4444' : '#10b981'}; 
              border: 3px solid #ffffff; 
              box-shadow: 0 4px 12px ${isFraud ? 'rgba(239, 68, 68, 0.6)' : 'rgba(16, 185, 129, 0.5)'}; 
              display: flex; 
              align-items: center; 
              justify-content: center; 
              color: #ffffff; 
              font-weight: 800; 
              font-family: ui-monospace, monospace; 
              font-size: 13px;
              user-select: none;
            ">
              ${stepNumber}
            </div>
          </div>
        `,
        iconSize: [34, 34],
        iconAnchor: [17, 17],
        popupAnchor: [0, -20],
        tooltipAnchor: [18, 0]
      });

      // 2. Instantiate Marker
      const marker = L.marker(latLng, { icon: numberedIcon });

      // 3. Hover Tooltip
      const formattedDate = new Date(tx.timestamp).toLocaleString('en-US', {
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit'
      });

      const tooltipContent = `
        <div style="font-family: ui-monospace, monospace; font-size: 11px; line-height: 1.5; padding: 6px 8px; color: #0f172a; min-width: 220px;">
          <div style="display: flex; align-items: center; justify-content: space-between; margin-bottom: 4px; border-bottom: 1px solid #e2e8f0; padding-bottom: 3px;">
            <span style="font-weight: 800; font-size: 12px; color: ${isFraud ? '#dc2626' : '#059669'};">
              ● Step #${stepNumber}: ${tx.cityName || tx.locationName}
            </span>
            <span style="font-size: 10px; font-weight: 700; padding: 1px 4px; border-radius: 2px; background: ${isFraud ? '#fee2e2' : '#d1fae5'}; color: ${isFraud ? '#dc2626' : '#059669'};">
              ${tx.status}
            </span>
          </div>
          <div><b>Tx Ref:</b> ${tx.id}</div>
          <div><b>User:</b> ${activeProfile.userName}</div>
          <div><b>Amount:</b> ₱${Number(tx.amount).toLocaleString('en-US', { minimumFractionDigits: 2 })}</div>
          <div><b>Coordinates:</b> ${tx.lat.toFixed(4)}°, ${tx.lon.toFixed(4)}°</div>
          <div><b>Timestamp:</b> ${formattedDate}</div>
          ${stepNumber > 1 ? `
            <div style="margin-top: 4px; padding-top: 3px; border-top: 1px dashed #cbd5e1; color: #475569;">
              <div><b>Travel Delta:</b> ${tx.timeDeltaLabel || 'Consecutive'}</div>
              <div><b>Distance:</b> ${tx.distanceKm ? tx.distanceKm.toLocaleString(undefined, {maximumFractionDigits: 1}) + ' km' : '--'}</div>
              <div><b>Velocity:</b> <span style="font-weight: 700; color: ${isFraud ? '#dc2626' : '#0f172a'};">${tx.velocityKmh ? tx.velocityKmh.toLocaleString(undefined, {maximumFractionDigits: 1}) + ' km/h' : '--'}</span></div>
            </div>
          ` : '<div style="color: #059669; font-weight: 600; margin-top: 2px;">★ Initial Baseline Origin</div>'}
        </div>
      `;

      marker.bindTooltip(tooltipContent, {
        direction: 'top',
        opacity: 0.98,
        offset: [0, -10]
      });

      // 4. Click Popup Dossier
      const popupContent = `
        <div style="font-family: ui-monospace, monospace; font-size: 12px; line-height: 1.5; padding: 8px; color: #0f172a; min-width: 260px;">
          <div style="display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; border-bottom: 1.5px solid ${isFraud ? '#ef4444' : '#10b981'}; padding-bottom: 4px;">
            <b style="font-size: 13px; color: ${isFraud ? '#dc2626' : '#059669'};">
              Sequence Step #${stepNumber} Dossier
            </b>
            <span style="font-size: 10px; font-weight: 800; padding: 2px 6px; background: ${isFraud ? '#ef4444' : '#10b981'}; color: #fff; border-radius: 3px;">
              ${isFraud ? '🚨 FLAGGED' : '✓ CLEARED'}
            </span>
          </div>
          <div><b>Transaction Ref:</b> ${tx.id}</div>
          <div><b>Account:</b> ${activeProfile.accountId}</div>
          <div><b>Location:</b> ${tx.locationName}</div>
          <div><b>IP Address:</b> ${tx.ip || '112.198.45.10'}</div>
          <div><b>Amount:</b> ₱${Number(tx.amount).toLocaleString('en-US', { minimumFractionDigits: 2 })}</div>
          <div><b>Risk Score:</b> ${tx.riskScore} (Threshold > 0.85)</div>
          <div style="margin-top: 4px; padding: 4px; background: #f8fafc; border-left: 3px solid ${isFraud ? '#ef4444' : '#10b981'}; font-size: 11px;">
            ${tx.riskReason}
          </div>
        </div>
      `;

      marker.bindPopup(popupContent);
      marker.on('click', () => {
        setFocusedTxId(tx.id);
      });

      marker.addTo(markersGroup);
      markerObjectsRef.current[tx.id] = marker;
    });

    // 5. Draw Flight Vector Polylines Connecting 1 -> 2 -> 3...
    if (latLngPoints.length > 1) {
      for (let i = 0; i < latLngPoints.length - 1; i++) {
        const fromPoint = latLngPoints[i];
        const toPoint = latLngPoints[i + 1];
        const nextTx = chronologicalTxList[i + 1];
        const isSegmentFraud = nextTx?.isFraud || nextTx?.status === 'REJECTED_FRAUD';

        // Flight Polyline Segment
        L.polyline([fromPoint, toPoint], {
          color: isSegmentFraud ? '#ef4444' : '#10b981',
          weight: isSegmentFraud ? 3.5 : 2.5,
          dashArray: isSegmentFraud ? '8, 8' : '4, 4',
          opacity: 0.85
        }).addTo(polylineGroup);
      }

      // Auto-fit map bounds with padding
      const polylineBounds = L.polyline(latLngPoints).getBounds();
      map.fitBounds(polylineBounds, { padding: [50, 50], maxZoom: 8 });
    } else if (latLngPoints.length === 1) {
      map.setView(latLngPoints[0], 5);
    }
  }, [chronologicalTxList, selectedAccountId]);

  // Handle Focus On Map when clicking a transaction in the table
  const handleFocusTransactionOnMap = (tx) => {
    setFocusedTxId(tx.id);
    const map = mapInstanceRef.current;
    const marker = markerObjectsRef.current[tx.id];
    if (map && marker) {
      map.setView([tx.lat, tx.lon], 6, { animate: true });
      marker.openPopup();
    }
  };

  // Simulate an additional transaction hop for the active user
  const handleAddSimulatedHop = (cityKey, elapsedSeconds, amount = 25000) => {
    setIsSimulating(true);
    const targetCity = PRESET_CITIES[cityKey];
    if (!targetCity) return;

    const currentTxs = activeProfile.transactions;
    const lastTx = currentTxs[currentTxs.length - 1];

    const { distKm, velocityKmh, isFraud, riskScore } = calculateHaversine(
      lastTx.lat,
      lastTx.lon,
      targetCity.lat,
      targetCity.lon,
      elapsedSeconds
    );

    const newStepNum = currentTxs.length + 1;
    const newTxId = isFraud 
      ? `TX-FRAUD-${Math.floor(1000 + Math.random() * 9000)}`
      : `TX-${Math.floor(900000 + Math.random() * 99999)}`;

    const formatElapsedText = () => {
      if (elapsedSeconds < 60) return `${elapsedSeconds} seconds after Step #${newStepNum - 1}`;
      if (elapsedSeconds < 3600) return `${Math.round(elapsedSeconds / 60)} minutes after Step #${newStepNum - 1}`;
      return `${(elapsedSeconds / 3600).toFixed(1)} hours after Step #${newStepNum - 1}`;
    };

    const newTxRecord = {
      id: newTxId,
      step: newStepNum,
      timestamp: new Date().toISOString(),
      locationName: targetCity.locationName,
      cityName: targetCity.cityName,
      lat: targetCity.lat,
      lon: targetCity.lon,
      ip: targetCity.ip,
      amount: amount,
      status: isFraud ? 'REJECTED_FRAUD' : 'COMMITTED',
      riskScore: riskScore,
      riskReason: isFraud 
        ? `IMPOSSIBLE_TRAVEL_DETECTED: Velocity ${velocityKmh.toLocaleString(undefined, {maximumFractionDigits: 1})} km/h exceeds 800 km/h commercial aircraft limit.`
        : `NORMAL_GEOVELOCITY: Velocity ${velocityKmh.toLocaleString(undefined, {maximumFractionDigits: 1})} km/h within legitimate travel limits.`,
      timeDeltaLabel: formatElapsedText(),
      timeDeltaSeconds: elapsedSeconds,
      distanceKm: distKm,
      velocityKmh: velocityKmh,
      isFraud: isFraud
    };

    setProfiles((prev) =>
      prev.map((prof) => {
        if (prof.accountId === selectedAccountId) {
          const updatedTxs = [...prof.transactions, newTxRecord];
          const hasAnyFraud = updatedTxs.some((t) => t.isFraud || t.status === 'REJECTED_FRAUD');
          return {
            ...prof,
            hasFraudFlag: hasAnyFraud,
            flagReason: hasAnyFraud
              ? 'Impossible Travel detected along journey timeline'
              : 'All vectors verified normal',
            transactions: updatedTxs
          };
        }
        return prof;
      })
    );

    setTimeout(() => {
      setIsSimulating(false);
      handleFocusTransactionOnMap(newTxRecord);
    }, 150);
  };

  // Reset selected user profile back to initial baseline
  const handleResetProfile = () => {
    const original = INITIAL_PROFILES.find((p) => p.accountId === selectedAccountId);
    if (!original) return;
    setProfiles((prev) =>
      prev.map((prof) => (prof.accountId === selectedAccountId ? { ...original } : prof))
    );
    setFocusedTxId(null);
  };

  return (
    <div className="space-y-6">
      {/* ========================================================
          1. HEADER BANNER: GEOVELOCITY SOC MONITOR & USER SELECTOR
         ======================================================== */}
      <div className="bg-surface border border-line p-5 flex flex-col lg:flex-row items-start lg:items-center justify-between gap-5">
        <div>
          <div className="flex flex-wrap items-center gap-2.5">
            <ShieldAlert className="w-5 h-5 text-red-500 animate-pulse" />
            <h2 className="text-base font-semibold text-fg">
              Geovelocity &amp; Impossible Travel Forensic Map (ADR-04)
            </h2>
            <span className="px-2 py-0.5 text-2xs font-mono font-bold bg-red-500/10 text-red-600 dark:text-red-400 border border-red-500/30">
              AIRCRAFT CEILING: 800 KM/H
            </span>
          </div>
          <p className="text-xs text-fg-muted mt-1">
            Real-time geospatial vector tracking displaying sequential transaction locations (<span className="font-mono font-bold text-emerald-600">1</span>, <span className="font-mono font-bold text-red-500">2</span>, <span className="font-mono font-bold text-emerald-600">3</span>...) per customer account.
          </p>
        </div>

        {/* User Account Selector */}
        <div className="flex flex-wrap items-center gap-3 w-full lg:w-auto">
          <div className="flex items-center gap-2 bg-sunken border border-line p-1 px-3 w-full sm:w-auto">
            <User className="w-4 h-4 text-accent shrink-0" />
            <span className="text-xs font-mono text-fg-subtle shrink-0">Account:</span>
            <select
              value={selectedAccountId}
              onChange={(e) => {
                setSelectedAccountId(e.target.value);
                setFocusedTxId(null);
              }}
              className="bg-transparent text-xs font-semibold font-mono text-fg focus:outline-none cursor-pointer pr-4"
            >
              {profiles.map((p) => (
                <option key={p.accountId} value={p.accountId} className="bg-surface text-fg">
                  {p.userName} ({p.accountId}) {p.hasFraudFlag ? '🚨 [FRAUD FLAGGED]' : '✓ [CLEARED]'}
                </option>
              ))}
            </select>
          </div>

          <button
            type="button"
            onClick={handleResetProfile}
            title="Reset journey to baseline"
            className="px-3 py-1.5 text-xs font-medium border border-line bg-sunken hover:bg-surface text-fg-muted hover:text-fg transition-colors flex items-center gap-1.5 cursor-pointer shrink-0"
          >
            <RotateCcw className="w-3.5 h-3.5" />
            <span>Reset User</span>
          </button>
        </div>
      </div>

      {/* ========================================================
          2. ACTIVE USER STATUS CARD & QUICK STATS
         ======================================================== */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        {/* Account Info */}
        <div className="p-4 bg-surface border border-line space-y-1">
          <span className="text-2xs font-mono uppercase tracking-wider text-fg-subtle">Inspected Account</span>
          <p className="text-sm font-bold text-fg flex items-center gap-1.5">
            <span>{activeProfile.userName}</span>
            {activeProfile.hasFraudFlag && (
              <span className="text-2xs font-mono text-red-500 font-bold bg-red-500/10 px-1.5 py-0.5 border border-red-500/30">
                HOLD
              </span>
            )}
          </p>
          <span className="text-2xs font-mono text-fg-subtle block">{activeProfile.accountId} &bull; {activeProfile.accountType}</span>
        </div>

        {/* Total Traversed Hops */}
        <div className="p-4 bg-surface border border-line space-y-1">
          <span className="text-2xs font-mono uppercase tracking-wider text-fg-subtle">Recorded Location Hops</span>
          <p className="text-sm font-bold font-mono text-fg flex items-center gap-2">
            <span>{chronologicalTxList.length} Sequenced Points</span>
            <span className="text-2xs font-normal text-fg-subtle">
              ({chronologicalTxList.map((_, i) => i + 1).join(' ➔ ')})
            </span>
          </p>
          <span className="text-2xs font-mono text-fg-subtle block">Chronologically numbered 1, 2, 3...</span>
        </div>

        {/* Security Hold Status */}
        <div className={`p-4 border space-y-1 ${activeProfile.hasFraudFlag ? 'bg-red-500/10 border-red-500/30 text-red-700 dark:text-red-400' : 'bg-emerald-500/10 border-emerald-500/30 text-emerald-700 dark:text-emerald-400'}`}>
          <span className="text-2xs font-mono uppercase tracking-wider block font-bold">
            {activeProfile.hasFraudFlag ? 'SECURITY HOLD ACTIVE' : 'NO ANOMALIES DETECTED'}
          </span>
          <p className="text-xs font-semibold line-clamp-1">
            {activeProfile.hasFraudFlag ? '🚨 Geovelocity Impossible Travel' : '✓ Normal Commute Bounds'}
          </p>
          <span className="text-2xs opacity-85 block">{activeProfile.flagReason}</span>
        </div>

        {/* Engine SLA Latency */}
        <div className="p-4 bg-surface border border-line space-y-1">
          <span className="text-2xs font-mono uppercase tracking-wider text-fg-subtle">Engine SLA Latency</span>
          <p className="text-sm font-bold font-mono text-emerald-600 flex items-center gap-1.5">
            <Activity className="w-3.5 h-3.5" />
            <span>1.85 ms</span>
            <span className="text-2xs font-normal text-fg-subtle">(SLA &le; 200 ms)</span>
          </p>
          <span className="text-2xs font-mono text-fg-subtle block">Asynchronous non-blocking screening</span>
        </div>
      </div>

      {/* ========================================================
          3. MAIN MAP WORKSPACE + INTERACTIVE HOP SIMULATOR
         ======================================================== */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        {/* OpenStreetMap Visual Canvas */}
        <div className="lg:col-span-8 bg-surface border border-line p-4 space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-3 pb-1">
            <div className="flex items-center gap-2">
              <Globe className="w-4 h-4 text-accent" />
              <span className="text-xs font-semibold uppercase tracking-wider text-fg font-mono">
                OpenStreetMap Geodesic Travel Trajectory &bull; {activeProfile.userName}
              </span>
            </div>
            <div className="flex items-center gap-3 text-2xs font-mono">
              <span className="flex items-center gap-1 text-emerald-600">
                <span className="w-3 h-3 rounded-full bg-emerald-500 text-white font-bold inline-flex items-center justify-center text-[9px]">1</span>
                <span>Authorized / Cleared</span>
              </span>
              <span className="flex items-center gap-1 text-red-500">
                <span className="w-3 h-3 rounded-full bg-red-500 text-white font-bold inline-flex items-center justify-center text-[9px]">2</span>
                <span>🚨 Impossible Travel</span>
              </span>
            </div>
          </div>

          {/* Leaflet DOM Mounting Container */}
          <div
            ref={mapContainerRef}
            className="w-full h-[480px] border border-line bg-sunken z-0 relative shadow-inner"
          />

          {/* Footer Guide */}
          <div className="flex flex-wrap items-center justify-between gap-2 pt-2 text-2xs font-mono text-fg-subtle border-t border-line">
            <span>Projection: WGS84 &bull; Great-Circle Haversine Formula</span>
            <span>Hover on numbered markers (1, 2, 3...) for metrics; click to open dossier.</span>
          </div>
        </div>

        {/* Right Side: Simulate Location Hop for this Particular User */}
        <div className="lg:col-span-4 space-y-4">
          <div className="bg-surface border border-line p-4 space-y-3">
            <div className="flex items-center justify-between">
              <h3 className="text-xs font-semibold uppercase tracking-wider text-fg font-mono flex items-center gap-1.5">
                <Zap className="w-4 h-4 text-accent" />
                Simulate Location for {activeProfile.userName.split(' ')[0]}
              </h3>
              {isSimulating && <RefreshCw className="w-3.5 h-3.5 text-accent animate-spin" />}
            </div>
            <p className="text-2xs text-fg-muted">
              Add consecutive transaction hops to simulate how the fraud engine numbers and marks subsequent locations:
            </p>

            <div className="space-y-2.5 pt-1">
              {/* Option 1: London Attacker Hop (Impossible Travel) */}
              <button
                type="button"
                onClick={() => handleAddSimulatedHop('LON', 15, 60000)}
                className="w-full text-left p-3 text-xs font-mono border border-line bg-sunken hover:bg-surface hover:border-red-500/50 transition-all cursor-pointer group"
              >
                <div className="flex items-center justify-between">
                  <span className="font-semibold text-fg group-hover:text-red-500 flex items-center gap-1.5">
                    <Plane className="w-3.5 h-3.5 text-red-500 rotate-45" />
                    <span>➔ 🇬🇧 London, UK</span>
                  </span>
                  <span className="text-2xs px-1.5 py-0.5 bg-red-500/10 text-red-500 font-bold border border-red-500/20">
                    15 SECONDS
                  </span>
                </div>
                <div className="text-2xs text-fg-subtle mt-1">
                  10,735 km &bull; Velocity ~2.5M km/h &bull; <span className="text-red-500 font-bold">Marks REJECTED_FRAUD</span>
                </div>
              </button>

              {/* Option 2: New York Attacker Hop (Impossible Travel) */}
              <button
                type="button"
                onClick={() => handleAddSimulatedHop('NYC', 45, 75000)}
                className="w-full text-left p-3 text-xs font-mono border border-line bg-sunken hover:bg-surface hover:border-red-500/50 transition-all cursor-pointer group"
              >
                <div className="flex items-center justify-between">
                  <span className="font-semibold text-fg group-hover:text-red-500 flex items-center gap-1.5">
                    <Plane className="w-3.5 h-3.5 text-red-500 rotate-45" />
                    <span>➔ 🇺🇸 New York, USA</span>
                  </span>
                  <span className="text-2xs px-1.5 py-0.5 bg-red-500/10 text-red-500 font-bold border border-red-500/20">
                    45 SECONDS
                  </span>
                </div>
                <div className="text-2xs text-fg-subtle mt-1">
                  13,670 km &bull; Velocity ~1.1M km/h &bull; <span className="text-red-500 font-bold">Marks REJECTED_FRAUD</span>
                </div>
              </button>

              {/* Option 3: Legitimate Domestic Flight to Cebu (+2 Hours) */}
              <button
                type="button"
                onClick={() => handleAddSimulatedHop('CEB', 7200, 15000)}
                className="w-full text-left p-3 text-xs font-mono border border-line bg-sunken hover:bg-surface hover:border-emerald-500/50 transition-all cursor-pointer group"
              >
                <div className="flex items-center justify-between">
                  <span className="font-semibold text-fg group-hover:text-emerald-500 flex items-center gap-1.5">
                    <CheckCircle2 className="w-3.5 h-3.5 text-emerald-500" />
                    <span>➔ 🇵🇭 Cebu City, PH</span>
                  </span>
                  <span className="text-2xs px-1.5 py-0.5 bg-emerald-500/10 text-emerald-600 font-bold border border-emerald-500/20">
                    +2 HOURS
                  </span>
                </div>
                <div className="text-2xs text-fg-subtle mt-1">
                  570 km &bull; Velocity 285 km/h &bull; <span className="text-emerald-600 font-bold">Clears COMMITTED</span>
                </div>
              </button>

              {/* Option 4: Legitimate International Flight to Tokyo (+4.5 Hours) */}
              <button
                type="button"
                onClick={() => handleAddSimulatedHop('TYO', 16200, 20000)}
                className="w-full text-left p-3 text-xs font-mono border border-line bg-sunken hover:bg-surface hover:border-emerald-500/50 transition-all cursor-pointer group"
              >
                <div className="flex items-center justify-between">
                  <span className="font-semibold text-fg group-hover:text-emerald-500 flex items-center gap-1.5">
                    <CheckCircle2 className="w-3.5 h-3.5 text-emerald-500" />
                    <span>➔ 🇯🇵 Tokyo, Japan</span>
                  </span>
                  <span className="text-2xs px-1.5 py-0.5 bg-emerald-500/10 text-emerald-600 font-bold border border-emerald-500/20">
                    +4.5 HOURS
                  </span>
                </div>
                <div className="text-2xs text-fg-subtle mt-1">
                  2,990 km &bull; Velocity 664 km/h &bull; <span className="text-emerald-600 font-bold">Clears COMMITTED</span>
                </div>
              </button>
            </div>
          </div>

          {/* Heuristic Parameter Reference Box */}
          <div className="p-4 bg-sunken border border-line text-xs font-mono space-y-2">
            <span className="text-2xs font-semibold text-fg uppercase tracking-wider block">
              ADR-04 Geovelocity Ruleset
            </span>
            <div className="space-y-1.5 text-2xs text-fg-muted">
              <div className="flex justify-between">
                <span>Maximum Physical Velocity:</span>
                <span className="font-bold text-fg">800 km/h (Commercial Air)</span>
              </div>
              <div className="flex justify-between">
                <span>Instant Drop HTTP Status:</span>
                <span className="font-bold text-red-500">422 Unprocessable Entity</span>
              </div>
              <div className="flex justify-between">
                <span>Customer Ledger Impact:</span>
                <span className="font-bold text-emerald-600">Zero Mutation (₱0.00 lock)</span>
              </div>
              <div className="flex justify-between">
                <span>Customer Presentation:</span>
                <span className="font-bold text-amber-500">Generic Security Notice</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* ========================================================
          4. TRANSACTION RECORDS & CHRONOLOGICAL HOPS TABLE
         ======================================================== */}
      <div className="bg-surface border border-line p-5 space-y-4">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <div>
            <h3 className="text-sm font-semibold text-fg flex items-center gap-2">
              <MapPin className="w-4 h-4 text-accent" />
              <span>Chronological Transaction Locations for {activeProfile.userName}</span>
            </h3>
            <p className="text-xs text-fg-muted mt-0.5">
              Click any record to inspect and highlight its numbered point (<span className="font-mono font-bold text-emerald-600">1</span>, <span className="font-mono font-bold text-red-500">2</span>, <span className="font-mono font-bold text-emerald-600">3</span>...) on the map.
            </p>
          </div>

          <span className="text-2xs font-mono text-fg-subtle">
            Showing {chronologicalTxList.length} sequential record(s)
          </span>
        </div>

        <div className="overflow-x-auto border border-line">
          <table className="w-full text-xs text-left border-collapse font-mono">
            <thead>
              <tr className="bg-sunken border-b border-line text-fg-subtle text-2xs uppercase tracking-wider">
                <th className="py-2.5 px-3 font-semibold text-center w-12">#</th>
                <th className="py-2.5 px-3 font-semibold">Transaction ID</th>
                <th className="py-2.5 px-3 font-semibold">Timestamp</th>
                <th className="py-2.5 px-3 font-semibold">Location &amp; Coordinates</th>
                <th className="py-2.5 px-3 font-semibold text-right">Amount (PHP)</th>
                <th className="py-2.5 px-3 font-semibold">Travel Distance &amp; Speed</th>
                <th className="py-2.5 px-3 font-semibold text-center">Status</th>
                <th className="py-2.5 px-3 font-semibold text-center">Action</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {chronologicalTxList.map((tx, index) => {
                const isFraud = tx.isFraud || tx.status === 'REJECTED_FRAUD';
                const isFocused = focusedTxId === tx.id;

                return (
                  <tr
                    key={tx.id}
                    onClick={() => handleFocusTransactionOnMap(tx)}
                    className={`transition-colors cursor-pointer ${
                      isFocused 
                        ? 'bg-accent/10 border-l-4 border-accent' 
                        : isFraud 
                          ? 'bg-red-500/5 hover:bg-red-500/10' 
                          : 'hover:bg-sunken'
                    }`}
                  >
                    {/* Numbered Step Circle */}
                    <td className="py-3 px-3 text-center">
                      <span className={`w-6 h-6 rounded-full inline-flex items-center justify-center text-xs font-extrabold text-white shadow-xs ${
                        isFraud ? 'bg-red-500' : 'bg-emerald-500'
                      }`}>
                        {index + 1}
                      </span>
                    </td>

                    {/* Tx ID */}
                    <td className="py-3 px-3 font-bold text-fg">
                      {tx.id}
                    </td>

                    {/* Timestamp */}
                    <td className="py-3 px-3 text-fg-muted whitespace-nowrap">
                      {new Date(tx.timestamp).toLocaleString('en-US', {
                        month: 'short',
                        day: 'numeric',
                        hour: '2-digit',
                        minute: '2-digit',
                        second: '2-digit'
                      })}
                    </td>

                    {/* Location */}
                    <td className="py-3 px-3">
                      <div className="font-semibold text-fg">{tx.locationName}</div>
                      <div className="text-2xs text-fg-subtle">
                        {tx.lat.toFixed(4)}°, {tx.lon.toFixed(4)}° &bull; IP: {tx.ip || '112.198.45.10'}
                      </div>
                    </td>

                    {/* Amount */}
                    <td className="py-3 px-3 text-right font-bold text-fg whitespace-nowrap">
                      {formatPHP(tx.amount)}
                    </td>

                    {/* Distance & Velocity */}
                    <td className="py-3 px-3">
                      {index === 0 ? (
                        <span className="text-emerald-600 font-semibold text-2xs">
                          ★ Initial Baseline Origin
                        </span>
                      ) : (
                        <div className="space-y-0.5">
                          <div className={`font-bold ${isFraud ? 'text-red-500' : 'text-fg'}`}>
                            {tx.velocityKmh ? `${tx.velocityKmh.toLocaleString(undefined, {maximumFractionDigits: 1})} km/h` : '--'}
                          </div>
                          <div className="text-2xs text-fg-subtle">
                            {tx.distanceKm ? `${tx.distanceKm.toLocaleString(undefined, {maximumFractionDigits: 1})} km` : '0 km'} in {tx.timeDeltaLabel}
                          </div>
                        </div>
                      )}
                    </td>

                    {/* Status Badge */}
                    <td className="py-3 px-3 text-center">
                      <span className={`inline-flex items-center gap-1 px-2 py-0.5 text-2xs font-bold ${
                        isFraud 
                          ? 'bg-red-500/10 text-red-600 dark:text-red-400 border border-red-500/30' 
                          : 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border border-emerald-500/30'
                      }`}>
                        {isFraud ? (
                          <>
                            <ShieldAlert className="w-3 h-3 text-red-500" />
                            <span>REJECTED_FRAUD</span>
                          </>
                        ) : (
                          <>
                            <CheckCircle2 className="w-3 h-3 text-emerald-500" />
                            <span>COMMITTED</span>
                          </>
                        )}
                      </span>
                    </td>

                    {/* Action Button */}
                    <td className="py-3 px-3 text-center">
                      <button
                        type="button"
                        onClick={(e) => {
                          e.stopPropagation();
                          handleFocusTransactionOnMap(tx);
                        }}
                        className="px-2.5 py-1 text-2xs font-medium border border-line bg-sunken hover:bg-surface text-fg flex items-center gap-1 mx-auto cursor-pointer transition-colors"
                      >
                        <Eye className="w-3 h-3 text-accent" />
                        <span>Focus</span>
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
