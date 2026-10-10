const apiKey = process.env.DATADOG_API_KEY || 'a65a6c84cbe468582ca3cc16c8b8cf81';
const appKey = process.env.DATADOG_APP_KEY || 'ddapp_ebWVMLRQjDpJM0lsv8ehu6R78qv63V8Xuu';
const location = process.env.DATADOG_LOCATION || 'pl:fse-local-banking-worker-0b9cc28d6a0207bc59e9b9b22b68a9db';

const testsToCreate = [
  {
    name: 'Gateway Performance & SLA Latency Probe',
    type: 'api',
    subtype: 'http',
    status: 'live',
    locations: [location],
    config: {
      request: {
        method: 'GET',
        url: 'http://gateway-service:8080/actuator/health',
        timeout: 5
      },
      assertions: [
        { type: 'statusCode', operator: 'is', target: 200 },
        { type: 'responseTime', operator: 'lessThan', target: 100 } // Strict 100ms SLA
      ]
    },
    options: {
      tick_every: 60,
      min_failure_duration: 0,
      min_location_failed: 1
    },
    message: 'CRITICAL: Gateway latency exceeded 100ms SLA or is DOWN',
    tags: ['service:gateway-service', 'tier:p1', 'env:dev']
  },
  {
    name: 'Ledger Mutation Engine - Concurrency & Solvency Probe',
    type: 'api',
    subtype: 'http',
    status: 'live',
    locations: [location],
    config: {
      request: {
        method: 'GET',
        url: 'http://ledger-mutation-engine:8082/actuator/health',
        timeout: 5
      },
      assertions: [
        { type: 'statusCode', operator: 'is', target: 200 },
        { type: 'responseTime', operator: 'lessThan', target: 150 }
      ]
    },
    options: {
      tick_every: 60,
      min_failure_duration: 0,
      min_location_failed: 1
    },
    message: 'CRITICAL: Core Ledger Mutation Engine is UNHEALTHY',
    tags: ['service:ledger-mutation-engine', 'tier:core', 'env:dev']
  },
  {
    name: 'Account & Identity Service - KYC Probe',
    type: 'api',
    subtype: 'http',
    status: 'live',
    locations: [location],
    config: {
      request: {
        method: 'GET',
        url: 'http://account-service:8081/actuator/health',
        timeout: 5
      },
      assertions: [
        { type: 'statusCode', operator: 'is', target: 200 },
        { type: 'responseTime', operator: 'lessThan', target: 150 }
      ]
    },
    options: {
      tick_every: 60,
      min_failure_duration: 0,
      min_location_failed: 1
    },
    message: 'CRITICAL: Account & Identity Service is UNHEALTHY',
    tags: ['service:account-service', 'tier:core', 'env:dev']
  },
  {
    name: 'Notification & Event Streaming Service Probe',
    type: 'api',
    subtype: 'http',
    status: 'live',
    locations: [location],
    config: {
      request: {
        method: 'GET',
        url: 'http://notification-service:8083/actuator/health',
        timeout: 5
      },
      assertions: [
        { type: 'statusCode', operator: 'is', target: 200 },
        { type: 'responseTime', operator: 'lessThan', target: 150 }
      ]
    },
    options: {
      tick_every: 60,
      min_failure_duration: 0,
      min_location_failed: 1
    },
    message: 'WARNING: Notification & Alert Service is UNHEALTHY',
    tags: ['service:notification-service', 'tier:support', 'env:dev']
  },
  {
    name: 'Risk Engine (S2 + Laya AI) Health Probe',
    type: 'api',
    subtype: 'http',
    status: 'live',
    locations: [location],
    config: {
      request: {
        method: 'GET',
        url: 'http://risk-service:8084/health',
        timeout: 5
      },
      assertions: [
        { type: 'statusCode', operator: 'is', target: 200 },
        { type: 'responseTime', operator: 'lessThan', target: 200 }
      ]
    },
    options: {
      tick_every: 60,
      min_failure_duration: 0,
      min_location_failed: 1
    },
    message: 'CRITICAL: Risk Service or Laya AI model inference is UNHEALTHY',
    tags: ['service:risk-service', 'tier:ai', 'env:dev']
  }
];

async function createTests() {
  for (const test of testsToCreate) {
    try {
      const res = await fetch('https://api.datadoghq.com/api/v1/synthetics/tests/api', {
        method: 'POST',
        headers: {
          'DD-API-KEY': apiKey,
          'DD-APPLICATION-KEY': appKey,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(test)
      });
      const data = await res.json();
      if (res.ok) {
        console.log(`[CREATED] ${test.name} -> ID: ${data.public_id}`);
      } else {
        console.error(`[FAILED] ${test.name}:`, data);
      }
    } catch (err) {
      console.error(`[ERROR] ${test.name}:`, err.message);
    }
  }
}

createTests();
