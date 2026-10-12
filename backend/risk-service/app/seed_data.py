"""
Seeded Customer Profile and Historical Geolocation Data Store.
Provides realistic baseline telemetry for Philippine retail banking personas.
"""

from datetime import datetime, timezone, timedelta
from typing import Dict, Optional, Any


# Standard historical anchor profiles
CUSTOMER_PROFILES: Dict[str, Dict[str, Any]] = {
    "USR-1001": {
        "user_id": "USR-1001",
        "account_id": "ACC-100001",
        "full_name": "Juan Dela Cruz",
        "kyc_status": "VERIFIED",
        "account_age_days": 420,
        "average_transfer_amount": 2500.00,
        "daily_transfer_limit": 100000.00,
        "home_coordinates": {
            "latitude": 14.5547,
            "longitude": 121.0244,
            "label": "Bonifacio Global City, Taguig"
        },
        "last_transaction": {
            "transaction_id": "TX-PREV-01",
            "amount": 1800.00,
            "timestamp": (datetime.now(timezone.utc) - timedelta(minutes=15)).isoformat(),
            "coordinates": {
                "latitude": 14.5547,
                "longitude": 121.0180,
                "label": "Makati CBD"
            }
        },
        "typical_counterparties": ["ACC-100002", "ACC-100005"]
    },
    "USR-1002": {
        "user_id": "USR-1002",
        "account_id": "ACC-100002",
        "full_name": "Maria Santos",
        "kyc_status": "VERIFIED",
        "account_age_days": 180,
        "average_transfer_amount": 4000.00,
        "daily_transfer_limit": 150000.00,
        "home_coordinates": {
            "latitude": 14.6760,
            "longitude": 121.0437,
            "label": "Diliman, Quezon City"
        },
        "last_transaction": {
            "transaction_id": "TX-PREV-02",
            "amount": 3500.00,
            "timestamp": (datetime.now(timezone.utc) - timedelta(hours=2)).isoformat(),
            "coordinates": {
                "latitude": 14.6500,
                "longitude": 121.0300,
                "label": "Quezon City"
            }
        },
        "typical_counterparties": ["ACC-100001"]
    },
    "USR-1003": {
        "user_id": "USR-1003",
        "account_id": "ACC-100003",
        "full_name": "Mark Tan",
        "kyc_status": "VERIFIED",
        "account_age_days": 730,
        "average_transfer_amount": 15000.00,
        "daily_transfer_limit": 500000.00,
        "home_coordinates": {
            "latitude": 10.3157,
            "longitude": 123.8854,
            "label": "Cebu Business Park, Cebu City"
        },
        "last_transaction": {
            "transaction_id": "TX-PREV-03",
            "amount": 12000.00,
            "timestamp": (datetime.now(timezone.utc) - timedelta(minutes=20)).isoformat(),
            "coordinates": {
                "latitude": 10.3100,
                "longitude": 123.8900,
                "label": "IT Park, Cebu City"
            }
        },
        "typical_counterparties": ["ACC-100004"]
    },
    "USR-1004": {
        "user_id": "USR-1004",
        "account_id": "ACC-100004",
        "full_name": "Elena Reyes",
        "kyc_status": "VERIFIED",
        "account_age_days": 90,
        "average_transfer_amount": 1200.00,
        "daily_transfer_limit": 50000.00,
        "home_coordinates": {
            "latitude": 14.5869,
            "longitude": 121.0614,
            "label": "Ortigas Center, Pasig"
        },
        "last_transaction": {
            "transaction_id": "TX-PREV-04",
            "amount": 950.00,
            "timestamp": (datetime.now(timezone.utc) - timedelta(hours=1)).isoformat(),
            "coordinates": {
                "latitude": 14.5800,
                "longitude": 121.0600,
                "label": "Capitol Commons, Pasig"
            }
        },
        "typical_counterparties": ["ACC-100003"]
    },
    "1000-2000-3001": {
        "user_id": "usr-1001-cst-001",
        "account_id": "1000-2000-3001",
        "full_name": "Juan Dela Cruz",
        "kyc_status": "VERIFIED",
        "account_age_days": 420,
        "average_transfer_amount": 2500.00,
        "daily_transfer_limit": 500000.00,
        "home_coordinates": {
            "latitude": 14.5547,
            "longitude": 121.0244,
            "label": "Bonifacio Global City, Taguig"
        },
        "last_transaction": {
            "transaction_id": "TX-PREV-01",
            "amount": 1800.00,
            "timestamp": (datetime.now(timezone.utc) - timedelta(minutes=15)).isoformat(),
            "coordinates": {
                "latitude": 14.5547,
                "longitude": 121.0180,
                "label": "Makati CBD"
            }
        },
        "typical_counterparties": ["1000-2000-3002", "1000-2000-3004"]
    },
    "1000-2000-3002": {
        "user_id": "usr-1002-cst-002",
        "account_id": "1000-2000-3002",
        "full_name": "Maria Clara Reyes",
        "kyc_status": "VERIFIED",
        "account_age_days": 180,
        "average_transfer_amount": 4000.00,
        "daily_transfer_limit": 250000.00,
        "home_coordinates": {
            "latitude": 14.6760,
            "longitude": 121.0437,
            "label": "Diliman, Quezon City"
        },
        "last_transaction": {
            "transaction_id": "TX-PREV-02",
            "amount": 3500.00,
            "timestamp": (datetime.now(timezone.utc) - timedelta(hours=2)).isoformat(),
            "coordinates": {
                "latitude": 14.6500,
                "longitude": 121.0300,
                "label": "Quezon City"
            }
        },
        "typical_counterparties": ["1000-2000-3001"]
    },
    "acc-2001-sav-001": {
        "user_id": "usr-1001-cst-001",
        "account_id": "1000-2000-3001",
        "full_name": "Juan Dela Cruz",
        "kyc_status": "VERIFIED",
        "account_age_days": 420,
        "average_transfer_amount": 2500.00,
        "daily_transfer_limit": 500000.00,
        "home_coordinates": {
            "latitude": 14.5547,
            "longitude": 121.0244,
            "label": "Bonifacio Global City, Taguig"
        },
        "last_transaction": {
            "transaction_id": "TX-PREV-01",
            "amount": 1800.00,
            "timestamp": (datetime.now(timezone.utc) - timedelta(minutes=15)).isoformat(),
            "coordinates": {
                "latitude": 14.5547,
                "longitude": 121.0180,
                "label": "Makati CBD"
            }
        },
        "typical_counterparties": ["1000-2000-3002", "1000-2000-3004"]
    },
    "acc-2002-chk-001": {
        "user_id": "usr-1002-cst-002",
        "account_id": "acc-2002-chk-001",
        "full_name": "Maria Santos",
        "kyc_status": "VERIFIED",
        "account_age_days": 180,
        "average_transfer_amount": 4000.00,
        "daily_transfer_limit": 250000.00,
        "home_coordinates": {
            "latitude": 14.6760,
            "longitude": 121.0437,
            "label": "Diliman, Quezon City"
        },
        "last_transaction": {
            "transaction_id": "TX-PREV-02",
            "amount": 3500.00,
            "timestamp": (datetime.now(timezone.utc) - timedelta(hours=2)).isoformat(),
            "coordinates": {
                "latitude": 14.6500,
                "longitude": 121.0300,
                "label": "Quezon City"
            }
        },
        "typical_counterparties": ["acc-2001-sav-001"]
    },
    "usr-1001-cst-001": {
        "user_id": "usr-1001-cst-001",
        "account_id": "acc-2001-sav-001",
        "full_name": "Juan Dela Cruz",
        "kyc_status": "VERIFIED",
        "account_age_days": 420,
        "average_transfer_amount": 2500.00,
        "daily_transfer_limit": 500000.00,
        "home_coordinates": {
            "latitude": 14.5547,
            "longitude": 121.0244,
            "label": "Bonifacio Global City, Taguig"
        },
        "last_transaction": {
            "transaction_id": "TX-PREV-01",
            "amount": 1800.00,
            "timestamp": (datetime.now(timezone.utc) - timedelta(minutes=15)).isoformat(),
            "coordinates": {
                "latitude": 14.5547,
                "longitude": 121.0180,
                "label": "Makati CBD"
            }
        },
        "typical_counterparties": ["acc-2002-chk-001", "acc-2003-sav-002"]
    },
    "acc-2003-sav-002": {
        "user_id": "usr-1003-cst-003",
        "account_id": "acc-2003-sav-002",
        "full_name": "Mark Tan",
        "kyc_status": "VERIFIED",
        "account_age_days": 730,
        "average_transfer_amount": 15000.00,
        "daily_transfer_limit": 500000.00,
        "home_coordinates": {
            "latitude": 10.3157,
            "longitude": 123.8854,
            "label": "Cebu Business Park, Cebu City"
        },
        "last_transaction": {
            "transaction_id": "TX-PREV-03",
            "amount": 12000.00,
            "timestamp": (datetime.now(timezone.utc) - timedelta(minutes=20)).isoformat(),
            "coordinates": {
                "latitude": 10.3100,
                "longitude": 123.8900,
                "label": "IT Park, Cebu City"
            }
        },
        "typical_counterparties": ["acc-2001-sav-001"]
    },
    "usr-1003-cst-003": {
        "user_id": "usr-1003-cst-003",
        "account_id": "acc-2003-sav-002",
        "full_name": "Mark Tan",
        "kyc_status": "VERIFIED",
        "account_age_days": 730,
        "average_transfer_amount": 15000.00,
        "daily_transfer_limit": 500000.00,
        "home_coordinates": {
            "latitude": 10.3157,
            "longitude": 123.8854,
            "label": "Cebu Business Park, Cebu City"
        },
        "last_transaction": {
            "transaction_id": "TX-PREV-03",
            "amount": 12000.00,
            "timestamp": (datetime.now(timezone.utc) - timedelta(minutes=20)).isoformat(),
            "coordinates": {
                "latitude": 10.3100,
                "longitude": 123.8900,
                "label": "IT Park, Cebu City"
            }
        },
        "typical_counterparties": ["acc-2001-sav-001"]
    }
,

    # 2026 Seed Customer Profiles (Paolo, Elena, Marco)
    "U9001": {
    "user_id": "U9001",
    "account_id": "A9001",
    "account_number": "1000-9000-0001",
    "full_name": "Paolo Reyes",
    "kyc_status": "VERIFIED",
    "account_age_days": 423,
    "average_transfer_amount": 2374.42,
    "largest_outgoing_on_or_before_2026_10_11": 4200,
    "spike_ratio_if_next_equals_largest": 1.77,
    "daily_transfer_limit": 100000,
    "current_balance_on_2026_10_11": 198300,
    "home_coordinates": {
        "latitude": 14.5547,
        "longitude": 121.0244,
        "label": "Bonifacio Global City, Taguig"
    },
    "last_transaction": {
        "transaction_id": "TX-2026-0326",
        "amount": 2000.0,
        "timestamp": "2026-10-11T09:05:00+08:00",
        "coordinates": {
            "latitude": 14.5547,
            "longitude": 121.0244,
            "label": "Bonifacio Global City, Taguig"
        }
    },
    "typical_counterparties": [
        "A8004",
        "A9002"
    ]
},
    "A9001": {
    "user_id": "U9001",
    "account_id": "A9001",
    "account_number": "1000-9000-0001",
    "full_name": "Paolo Reyes",
    "kyc_status": "VERIFIED",
    "account_age_days": 423,
    "average_transfer_amount": 2374.42,
    "largest_outgoing_on_or_before_2026_10_11": 4200,
    "spike_ratio_if_next_equals_largest": 1.77,
    "daily_transfer_limit": 100000,
    "current_balance_on_2026_10_11": 198300,
    "home_coordinates": {
        "latitude": 14.5547,
        "longitude": 121.0244,
        "label": "Bonifacio Global City, Taguig"
    },
    "last_transaction": {
        "transaction_id": "TX-2026-0326",
        "amount": 2000.0,
        "timestamp": "2026-10-11T09:05:00+08:00",
        "coordinates": {
            "latitude": 14.5547,
            "longitude": 121.0244,
            "label": "Bonifacio Global City, Taguig"
        }
    },
    "typical_counterparties": [
        "A8004",
        "A9002"
    ]
},
    "1000-9000-0001": {
    "user_id": "U9001",
    "account_id": "A9001",
    "account_number": "1000-9000-0001",
    "full_name": "Paolo Reyes",
    "kyc_status": "VERIFIED",
    "account_age_days": 423,
    "average_transfer_amount": 2374.42,
    "largest_outgoing_on_or_before_2026_10_11": 4200,
    "spike_ratio_if_next_equals_largest": 1.77,
    "daily_transfer_limit": 100000,
    "current_balance_on_2026_10_11": 198300,
    "home_coordinates": {
        "latitude": 14.5547,
        "longitude": 121.0244,
        "label": "Bonifacio Global City, Taguig"
    },
    "last_transaction": {
        "transaction_id": "TX-2026-0326",
        "amount": 2000.0,
        "timestamp": "2026-10-11T09:05:00+08:00",
        "coordinates": {
            "latitude": 14.5547,
            "longitude": 121.0244,
            "label": "Bonifacio Global City, Taguig"
        }
    },
    "typical_counterparties": [
        "A8004",
        "A9002"
    ]
},
    "U9002": {
    "user_id": "U9002",
    "account_id": "A9002",
    "account_number": "1000-9000-0002",
    "full_name": "Elena Cruz",
    "kyc_status": "VERIFIED",
    "account_age_days": 423,
    "average_transfer_amount": 1667.65,
    "largest_outgoing_on_or_before_2026_10_11": 2625,
    "spike_ratio_if_next_equals_largest": 1.57,
    "daily_transfer_limit": 100000,
    "current_balance_on_2026_10_11": 106250,
    "home_coordinates": {
        "latitude": 14.676,
        "longitude": 121.0437,
        "label": "Diliman, Quezon City"
    },
    "last_transaction": {
        "transaction_id": "TX-2026-0330",
        "amount": 1687.0,
        "timestamp": "2026-10-11T19:35:00+08:00",
        "coordinates": {
            "latitude": 14.676,
            "longitude": 121.0437,
            "label": "Diliman, Quezon City"
        }
    },
    "typical_counterparties": [
        "A8004",
        "A9001"
    ]
},
    "A9002": {
    "user_id": "U9002",
    "account_id": "A9002",
    "account_number": "1000-9000-0002",
    "full_name": "Elena Cruz",
    "kyc_status": "VERIFIED",
    "account_age_days": 423,
    "average_transfer_amount": 1667.65,
    "largest_outgoing_on_or_before_2026_10_11": 2625,
    "spike_ratio_if_next_equals_largest": 1.57,
    "daily_transfer_limit": 100000,
    "current_balance_on_2026_10_11": 106250,
    "home_coordinates": {
        "latitude": 14.676,
        "longitude": 121.0437,
        "label": "Diliman, Quezon City"
    },
    "last_transaction": {
        "transaction_id": "TX-2026-0330",
        "amount": 1687.0,
        "timestamp": "2026-10-11T19:35:00+08:00",
        "coordinates": {
            "latitude": 14.676,
            "longitude": 121.0437,
            "label": "Diliman, Quezon City"
        }
    },
    "typical_counterparties": [
        "A8004",
        "A9001"
    ]
},
    "1000-9000-0002": {
    "user_id": "U9002",
    "account_id": "A9002",
    "account_number": "1000-9000-0002",
    "full_name": "Elena Cruz",
    "kyc_status": "VERIFIED",
    "account_age_days": 423,
    "average_transfer_amount": 1667.65,
    "largest_outgoing_on_or_before_2026_10_11": 2625,
    "spike_ratio_if_next_equals_largest": 1.57,
    "daily_transfer_limit": 100000,
    "current_balance_on_2026_10_11": 106250,
    "home_coordinates": {
        "latitude": 14.676,
        "longitude": 121.0437,
        "label": "Diliman, Quezon City"
    },
    "last_transaction": {
        "transaction_id": "TX-2026-0330",
        "amount": 1687.0,
        "timestamp": "2026-10-11T19:35:00+08:00",
        "coordinates": {
            "latitude": 14.676,
            "longitude": 121.0437,
            "label": "Diliman, Quezon City"
        }
    },
    "typical_counterparties": [
        "A8004",
        "A9001"
    ]
},
    "U9003": {
    "user_id": "U9003",
    "account_id": "A9003",
    "account_number": "1000-9000-0003",
    "full_name": "Marco Santos",
    "kyc_status": "VERIFIED",
    "account_age_days": 423,
    "average_transfer_amount": 3158.9,
    "largest_outgoing_on_or_before_2026_10_11": 5000,
    "spike_ratio_if_next_equals_largest": 1.58,
    "daily_transfer_limit": 500000,
    "current_balance_on_2026_10_11": 330200,
    "home_coordinates": {
        "latitude": 10.3157,
        "longitude": 123.8854,
        "label": "Cebu Business Park, Cebu City"
    },
    "last_transaction": {
        "transaction_id": "TX-2026-0328",
        "amount": 1200.0,
        "timestamp": "2026-10-11T16:45:00+08:00",
        "coordinates": {
            "latitude": 10.3157,
            "longitude": 123.8854,
            "label": "Cebu Business Park, Cebu City"
        }
    },
    "typical_counterparties": [
        "A8004",
        "A9001"
    ]
},
    "A9003": {
    "user_id": "U9003",
    "account_id": "A9003",
    "account_number": "1000-9000-0003",
    "full_name": "Marco Santos",
    "kyc_status": "VERIFIED",
    "account_age_days": 423,
    "average_transfer_amount": 3158.9,
    "largest_outgoing_on_or_before_2026_10_11": 5000,
    "spike_ratio_if_next_equals_largest": 1.58,
    "daily_transfer_limit": 500000,
    "current_balance_on_2026_10_11": 330200,
    "home_coordinates": {
        "latitude": 10.3157,
        "longitude": 123.8854,
        "label": "Cebu Business Park, Cebu City"
    },
    "last_transaction": {
        "transaction_id": "TX-2026-0328",
        "amount": 1200.0,
        "timestamp": "2026-10-11T16:45:00+08:00",
        "coordinates": {
            "latitude": 10.3157,
            "longitude": 123.8854,
            "label": "Cebu Business Park, Cebu City"
        }
    },
    "typical_counterparties": [
        "A8004",
        "A9001"
    ]
},
    "1000-9000-0003": {
    "user_id": "U9003",
    "account_id": "A9003",
    "account_number": "1000-9000-0003",
    "full_name": "Marco Santos",
    "kyc_status": "VERIFIED",
    "account_age_days": 423,
    "average_transfer_amount": 3158.9,
    "largest_outgoing_on_or_before_2026_10_11": 5000,
    "spike_ratio_if_next_equals_largest": 1.58,
    "daily_transfer_limit": 500000,
    "current_balance_on_2026_10_11": 330200,
    "home_coordinates": {
        "latitude": 10.3157,
        "longitude": 123.8854,
        "label": "Cebu Business Park, Cebu City"
    },
    "last_transaction": {
        "transaction_id": "TX-2026-0328",
        "amount": 1200.0,
        "timestamp": "2026-10-11T16:45:00+08:00",
        "coordinates": {
            "latitude": 10.3157,
            "longitude": 123.8854,
            "label": "Cebu Business Park, Cebu City"
        }
    },
    "typical_counterparties": [
        "A8004",
        "A9001"
    ]
},
}



def get_customer_profile(identifier: str) -> Dict[str, Any]:
    """
    Looks up profile by user_id (e.g. USR-1001) or account_id (e.g. ACC-100001).
    Falls back to a sensible default if the identifier is unrecognized.
    """
    if identifier in CUSTOMER_PROFILES:
        return CUSTOMER_PROFILES[identifier]

    for profile in CUSTOMER_PROFILES.values():
        if profile.get("account_id") == identifier:
            return profile

    # Default fallback profile for ad-hoc accounts
    return {
        "user_id": identifier,
        "account_id": identifier,
        "full_name": "Standard Customer",
        "kyc_status": "VERIFIED",
        "account_age_days": 60,
        "average_transfer_amount": 2000.00,
        "daily_transfer_limit": 50000.00,
        "home_coordinates": {
            "latitude": 14.5995,
            "longitude": 120.9842,
            "label": "Manila"
        },
        "last_transaction": {
            "transaction_id": "TX-PREV-DEF",
            "amount": 1500.00,
            "timestamp": (datetime.now(timezone.utc) - timedelta(hours=3)).isoformat(),
            "coordinates": {
                "latitude": 14.5995,
                "longitude": 120.9842,
                "label": "Manila"
            }
        },
        "typical_counterparties": []
    }
