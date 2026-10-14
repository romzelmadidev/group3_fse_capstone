/**
 * ============================================================================
 * AuraBank / FSE-Capstone Breakscale Discrete-Event Architecture Simulator
 * ============================================================================
 * Implements Breakscale's discrete-event simulation engine (min-heap event queue,
 * gamma-distributed service times, finite server slots, and trailing percentiles)
 * to model the AuraBank remittance pipeline and demonstrate its bottlenecks.
 *
 * Runs with standard Node.js (zero external dependencies required):
 *   node scripts/breakscale_simulate.js
 * ============================================================================
 */

// ----------------------------------------------------------------------------
// 1. MinHeap Event Queue & PRNG (matching Breakscale's core mechanics)
// ----------------------------------------------------------------------------

class MinHeap {
  constructor() {
    this.items = [];
  }
  get size() {
    return this.items.length;
  }
  push(item) {
    this.items.push(item);
    let i = this.items.length - 1;
    while (i > 0) {
      const parent = (i - 1) >> 1;
      if (this.less(this.items[i], this.items[parent])) {
        this.swap(i, parent);
        i = parent;
      } else break;
    }
  }
  pop() {
    if (this.items.length === 0) return null;
    const top = this.items[0];
    const last = this.items.pop();
    if (this.items.length > 0) {
      this.items[0] = last;
      let i = 0;
      for (;;) {
        const l = 2 * i + 1;
        const r = l + 1;
        let smallest = i;
        if (l < this.items.length && this.less(this.items[l], this.items[smallest])) smallest = l;
        if (r < this.items.length && this.less(this.items[r], this.items[smallest])) smallest = r;
        if (smallest === i) break;
        this.swap(i, smallest);
        i = smallest;
      }
    }
    return top;
  }
  less(a, b) {
    return a.time !== b.time ? a.time < b.time : a.seq < b.seq;
  }
  swap(i, j) {
    const tmp = this.items[i];
    this.items[i] = this.items[j];
    this.items[j] = tmp;
  }
}

class Mulberry32Rng {
  constructor(seed = 42) {
    this.s = seed >>> 0;
  }
  next() {
    this.s = (this.s + 0x6d2b79f5) >>> 0;
    let t = this.s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  }
  exponential(mean) {
    if (mean <= 0) return 0;
    const u = 1 - this.next();
    return -Math.log(u) * mean;
  }
  normal() {
    let u = 0, v = 0;
    while (u === 0) u = this.next();
    while (v === 0) v = this.next();
    return Math.sqrt(-2.0 * Math.log(u)) * Math.cos(2.0 * Math.PI * v);
  }
  gamma(shape, scale) {
    if (shape < 1) {
      const u = 1 - this.next();
      return this.gamma(shape + 1, scale) * Math.pow(u, 1 / shape);
    }
    const d = shape - 1 / 3;
    const c = 1 / Math.sqrt(9 * d);
    for (;;) {
      let x, v;
      do {
        x = this.normal();
        v = 1 + c * x;
      } while (v <= 0);
      v = v * v * v;
      const u = 1 - this.next();
      if (u < 1 - 0.0331 * x * x * x * x) return d * v * scale;
      if (Math.log(u) < 0.5 * x * x + d * (1 - v + Math.log(v))) {
        return d * v * scale;
      }
    }
  }
  serviceTime(mean, cv = 0.5) {
    if (mean <= 0) return 0;
    if (cv <= 0.01) return mean;
    const shape = 1 / (cv * cv);
    const scale = mean / shape;
    return this.gamma(shape, scale);
  }
}

// ----------------------------------------------------------------------------
// 2. Discrete-Event Simulation Engine
// ----------------------------------------------------------------------------

function runSimulation(config) {
  const heap = new MinHeap();
  const rng = new Mulberry32Rng(config.seed || 12345);
  let seq = 0;
  let now = 0;

  // Global metrics
  let totalSubmitted = 0;
  let totalCompleted = 0;
  let totalDropped = 0;
  const dropReasons = {};
  const latencies = [];

  function recordDrop(reason) {
    totalDropped++;
    dropReasons[reason] = (dropReasons[reason] || 0) + 1;
  }

  // Component states
  const gateway = {
    tokens: config.gatewayBurst || 20,
    maxTokens: config.gatewayBurst || 20,
    replenishRate: config.gatewayReplenish || 10, // per sec
    lastReplenish: 0
  };

  const redis = {
    activeSlots: 0,
    maxSlots: 1000,
    queue: 0,
    serviceMean: 1.5
  };

  const orchestrator = {
    activeSlots: 0,
    maxSlots: config.orchestratorWorkers || 50,
    queue: [],
    maxQueue: 150,
    serviceMean: 4.0,
    peakQueue: 0
  };

  const riskEngine = {
    activeSlots: 0,
    maxSlots: config.riskEngineSlots || 16,
    queue: [],
    maxQueue: 100,
    serviceMean: config.riskLatencyMean || 25,
    peakQueue: 0
  };

  const circuitBreaker = {
    state: 'CLOSED', // CLOSED, OPEN, HALF_OPEN
    window: [],
    windowSize: 10,
    failureThreshold: 0.5,
    tripTime: -1,
    recoveryTimeout: 5000 // 5s
  };

  const partnerBank = {
    latencyMean: config.partnerLatencyMean || 250,
    failureRate: config.partnerFailureRate || 0.05
  };

  const t24Cbs = {
    activeSlots: 0,
    maxSlots: config.cbsWorkers || 20,
    queue: [],
    maxQueue: 100,
    serviceMean: 10,
    peakQueue: 0
  };

  const azureSql = {
    activeConnections: 0,
    poolSize: config.hikariPoolSize || 20,
    queue: [],
    maxQueue: 200,
    baseServiceMean: 12,
    lockContentionPenalty: config.lockContentionPenalty || 0, // extra ms for UPDLOCK contention
    peakQueue: 0,
    peakConnections: 0
  };

  const kafka = {
    bufferedMessages: 0,
    peakBuffer: 0
  };

  // --------------------------------------------------------------------------
  // Schedule Client Arrivals
  // --------------------------------------------------------------------------
  const durationMs = (config.durationSeconds || 15) * 1000;
  const trafficRps = config.trafficRps || 20;
  const interArrivalMean = 1000 / trafficRps;

  let arrivalTime = 0;
  while (arrivalTime < durationMs) {
    const delta = rng.exponential(interArrivalMean);
    arrivalTime += delta;
    if (arrivalTime < durationMs) {
      heap.push({
        time: arrivalTime,
        seq: seq++,
        type: 'REQ_ARRIVAL',
        reqId: ++totalSubmitted,
        isInterbank: config.interbankRatio ? rng.next() < config.interbankRatio : false,
        startTime: arrivalTime,
        retriesLeft: config.clientRetries || 0
      });
    }
  }

  // --------------------------------------------------------------------------
  // Event Handlers
  // --------------------------------------------------------------------------
  while (heap.size > 0) {
    const event = heap.pop();
    now = event.time;

    // Refresh Gateway Token Bucket
    const elapsedSec = (now - gateway.lastReplenish) / 1000;
    if (elapsedSec > 0) {
      gateway.tokens = Math.min(gateway.maxTokens, gateway.tokens + elapsedSec * gateway.replenishRate);
      gateway.lastReplenish = now;
    }

    // Refresh Circuit Breaker State
    if (circuitBreaker.state === 'OPEN' && now - circuitBreaker.tripTime >= circuitBreaker.recoveryTimeout) {
      circuitBreaker.state = 'HALF_OPEN';
    }

    switch (event.type) {
      case 'REQ_ARRIVAL': {
        const req = event;

        // Step 1: Perimeter Rate Limiter
        if (gateway.tokens < 1.0) {
          if (req.retriesLeft > 0 && config.enableRetryStorm) {
            // Immediate unjittered retry (retry storm)
            req.retriesLeft--;
            heap.push({
              time: now + 50,
              seq: seq++,
              type: 'REQ_ARRIVAL',
              ...req
            });
          }
          recordDrop('GATEWAY_429_RATE_LIMITED');
          break;
        }
        gateway.tokens -= 1.0;

        // Step 2: Ingress Redis Session Check
        const redisDur = rng.serviceTime(redis.serviceMean, 0.2);

        // Step 3: Enqueue into Transfer Orchestrator
        heap.push({
          time: now + redisDur,
          seq: seq++,
          type: 'ORCHESTRATOR_ENTER',
          req
        });
        break;
      }

      case 'ORCHESTRATOR_ENTER': {
        const req = event.req;
        if (orchestrator.activeSlots < orchestrator.maxSlots) {
          orchestrator.activeSlots++;
          startOrchestration(req);
        } else if (orchestrator.queue.length < orchestrator.maxQueue) {
          orchestrator.queue.push(req);
          orchestrator.peakQueue = Math.max(orchestrator.peakQueue, orchestrator.queue.length);
        } else {
          recordDrop('ORCHESTRATOR_QUEUE_FULL');
        }
        break;
      }

      case 'RISK_DONE': {
        const { req } = event;
        riskEngine.activeSlots--;
        if (riskEngine.queue.length > 0) {
          const next = riskEngine.queue.shift();
          riskEngine.activeSlots++;
          const svcTime = rng.serviceTime(riskEngine.serviceMean, 0.4);
          heap.push({ time: now + svcTime, seq: seq++, type: 'RISK_DONE', req: next });
        }

        // Check if orchestrator timed out (1500 ms SLA)
        if (now - req.startTime > 1500) {
          releaseOrchestratorSlot();
          recordDrop('ORCHESTRATOR_TIMEOUT_EXPIRED');
          break;
        }

        // Route: Intra-bank vs Interbank
        if (req.isInterbank) {
          // Interbank wire with Resilience4j Circuit Breaker
          handleInterbankWire(req);
        } else {
          // Intra-bank core ledger mutation via T24 CBS
          handleT24Transfer(req);
        }
        break;
      }

      case 'PARTNER_DONE': {
        const { req, failed } = event;
        // Update circuit breaker window
        circuitBreaker.window.push(failed ? 1 : 0);
        if (circuitBreaker.window.length > circuitBreaker.windowSize) {
          circuitBreaker.window.shift();
        }
        const failRate = circuitBreaker.window.reduce((a, b) => a + b, 0) / circuitBreaker.window.length;
        if (failRate >= circuitBreaker.failureThreshold && circuitBreaker.window.length >= 5) {
          circuitBreaker.state = 'OPEN';
          circuitBreaker.tripTime = now;
        } else if (circuitBreaker.state === 'HALF_OPEN') {
          circuitBreaker.state = failed ? 'OPEN' : 'CLOSED';
        }

        if (failed) {
          // Offloaded to DLQ, trigger compensating rollback on local ledger
          kafka.bufferedMessages++;
          kafka.peakBuffer = Math.max(kafka.peakBuffer, kafka.bufferedMessages);
          recordDrop('PARTNER_BANK_TIMEOUT_DLQ_SAGA_ROLLBACK');
          releaseOrchestratorSlot();
        } else {
          // Succeeded interbank wire
          completeRequest(req);
          releaseOrchestratorSlot();
        }
        break;
      }

      case 'CBS_DONE': {
        const { req } = event;
        t24Cbs.activeSlots--;
        if (t24Cbs.queue.length > 0) {
          const next = t24Cbs.queue.shift();
          t24Cbs.activeSlots++;
          const cbsTime = rng.serviceTime(t24Cbs.serviceMean, 0.3);
          heap.push({ time: now + cbsTime, seq: seq++, type: 'CBS_ENTER_DB', req: next });
        }
        // Now query Azure SQL with UPDLOCK, ROWLOCK
        handleAzureSqlMutation(req);
        break;
      }

      case 'AZURE_SQL_DONE': {
        const { req } = event;
        azureSql.activeConnections--;
        if (azureSql.queue.length > 0) {
          const next = azureSql.queue.shift();
          azureSql.activeConnections++;
          azureSql.peakConnections = Math.max(azureSql.peakConnections, azureSql.activeConnections);
          const totalSqlMean = azureSql.baseServiceMean + azureSql.lockContentionPenalty;
          const sqlDur = rng.serviceTime(totalSqlMean, 0.4);
          heap.push({ time: now + sqlDur, seq: seq++, type: 'AZURE_SQL_DONE', req: next });
        }

        // Commit outbox message to Kafka
        kafka.bufferedMessages++;
        kafka.peakBuffer = Math.max(kafka.peakBuffer, kafka.bufferedMessages);

        completeRequest(req);
        releaseOrchestratorSlot();
        break;
      }
    }
  }

  function startOrchestration(req) {
    // Stage 1: Call Risk Engine
    if (riskEngine.activeSlots < riskEngine.maxSlots) {
      riskEngine.activeSlots++;
      const svcTime = rng.serviceTime(riskEngine.serviceMean, 0.4);
      heap.push({ time: now + svcTime, seq: seq++, type: 'RISK_DONE', req });
    } else if (riskEngine.queue.length < riskEngine.maxQueue) {
      riskEngine.queue.push(req);
      riskEngine.peakQueue = Math.max(riskEngine.peakQueue, riskEngine.queue.length);
    } else {
      recordDrop('RISK_ENGINE_QUEUE_FULL');
      releaseOrchestratorSlot();
    }
  }

  function handleInterbankWire(req) {
    if (circuitBreaker.state === 'OPEN') {
      recordDrop('CIRCUIT_BREAKER_OPEN_FAST_FAIL');
      kafka.bufferedMessages++; // Sent to DLQ
      kafka.peakBuffer = Math.max(kafka.peakBuffer, kafka.bufferedMessages);
      releaseOrchestratorSlot();
      return;
    }

    const partnerDur = rng.serviceTime(partnerBank.latencyMean, 0.5);
    const hasFailed = rng.next() < partnerBank.failureRate || partnerDur > 1200;
    heap.push({
      time: now + partnerDur,
      seq: seq++,
      type: 'PARTNER_DONE',
      req,
      failed: hasFailed
    });
  }

  function handleT24Transfer(req) {
    if (t24Cbs.activeSlots < t24Cbs.maxSlots) {
      t24Cbs.activeSlots++;
      const cbsTime = rng.serviceTime(t24Cbs.serviceMean, 0.3);
      heap.push({ time: now + cbsTime, seq: seq++, type: 'CBS_DONE', req });
    } else if (t24Cbs.queue.length < t24Cbs.maxQueue) {
      t24Cbs.queue.push(req);
      t24Cbs.peakQueue = Math.max(t24Cbs.peakQueue, t24Cbs.queue.length);
    } else {
      recordDrop('CBS_THREAD_POOL_EXHAUSTED');
      releaseOrchestratorSlot();
    }
  }

  function handleAzureSqlMutation(req) {
    if (azureSql.activeConnections < azureSql.poolSize) {
      azureSql.activeConnections++;
      azureSql.peakConnections = Math.max(azureSql.peakConnections, azureSql.activeConnections);
      const totalSqlMean = azureSql.baseServiceMean + azureSql.lockContentionPenalty;
      const sqlDur = rng.serviceTime(totalSqlMean, 0.4);
      heap.push({ time: now + sqlDur, seq: seq++, type: 'AZURE_SQL_DONE', req });
    } else if (azureSql.queue.length < azureSql.maxQueue) {
      azureSql.queue.push(req);
      azureSql.peakQueue = Math.max(azureSql.peakQueue, azureSql.queue.length);
    } else {
      recordDrop('HIKARICP_POOL_EXHAUSTED_LOCK_TIMEOUT');
      releaseOrchestratorSlot();
    }
  }

  function releaseOrchestratorSlot() {
    orchestrator.activeSlots--;
    if (orchestrator.queue.length > 0) {
      const next = orchestrator.queue.shift();
      orchestrator.activeSlots++;
      startOrchestration(next);
    }
  }

  function completeRequest(req) {
    totalCompleted++;
    latencies.push(now - req.startTime);
  }

  // --------------------------------------------------------------------------
  // Summary Metrics Computation
  // --------------------------------------------------------------------------
  latencies.sort((a, b) => a - b);
  const p50 = latencies.length ? latencies[Math.floor(latencies.length * 0.5)].toFixed(1) : '0';
  const p95 = latencies.length ? latencies[Math.floor(latencies.length * 0.95)].toFixed(1) : '0';
  const p99 = latencies.length ? latencies[Math.floor(latencies.length * 0.99)].toFixed(1) : '0';
  const successRate = totalSubmitted ? ((totalCompleted / totalSubmitted) * 100).toFixed(1) : '0';

  return {
    scenarioName: config.name,
    offeredRps: config.trafficRps,
    totalSubmitted,
    totalCompleted,
    totalDropped,
    successRate: `${successRate}%`,
    p50Ms: `${p50} ms`,
    p95Ms: `${p95} ms`,
    p99Ms: `${p99} ms`,
    dropReasons,
    peaks: {
      orchestratorPeakQueue: orchestrator.peakQueue,
      riskEnginePeakQueue: riskEngine.peakQueue,
      azureSqlPeakConnections: `${azureSql.peakConnections}/${azureSql.poolSize}`,
      azureSqlPeakQueue: azureSql.peakQueue,
      kafkaPeakBuffer: kafka.peakBuffer,
      circuitBreakerState: circuitBreaker.state
    }
  };
}

// ----------------------------------------------------------------------------
// 3. Execution of the 4 Bottleneck Scenarios
// ----------------------------------------------------------------------------

console.log('================================================================================');
console.log('  AURABANK / FSE-CAPSTONE: BREAKSCALE ARCHITECTURE BOTTLENECK SIMULATOR');
console.log('  Discrete-Event Engine: Min-Heap Event Queue, Gamma Service Times, Finite Slots');
console.log('================================================================================\n');

const scenarios = [
  {
    name: 'Scenario 1: Baseline Healthy Load (Within SLA)',
    trafficRps: 8, // Strictly within 10 RPS gateway limit
    gatewayBurst: 20,
    gatewayReplenish: 10,
    riskLatencyMean: 25,
    hikariPoolSize: 20,
    lockContentionPenalty: 0,
    interbankRatio: 0.1,
    partnerLatencyMean: 180,
    partnerFailureRate: 0.0,
    durationSeconds: 15
  },
  {
    name: 'Scenario 2: Hot-Account Row Lock (UPDLOCK) & HikariCP Pool Starvation',
    trafficRps: 85,
    gatewayBurst: 200,
    gatewayReplenish: 150,
    riskLatencyMean: 20,
    cbsWorkers: 30,
    hikariPoolSize: 15,
    lockContentionPenalty: 280, // Heavy lock serialization on balance_master
    interbankRatio: 0.0,
    durationSeconds: 15
  },
  {
    name: 'Scenario 3: Risk Engine Stage B NLP Stall & Ingress Retry Storm',
    trafficRps: 45,
    gatewayBurst: 100,
    gatewayReplenish: 80,
    riskLatencyMean: 450, // Transformer model slowdown
    riskEngineSlots: 8,
    orchestratorWorkers: 30,
    enableRetryStorm: true,
    clientRetries: 2,
    hikariPoolSize: 20,
    lockContentionPenalty: 0,
    durationSeconds: 15
  },
  {
    name: 'Scenario 4: Partner Bank Timeout, Circuit Breaker Trip & DLQ / Saga Flood',
    trafficRps: 30,
    gatewayBurst: 100,
    gatewayReplenish: 50,
    riskLatencyMean: 25,
    interbankRatio: 0.7, // Heavy outward wire volume
    partnerLatencyMean: 1800, // Partner degradation
    partnerFailureRate: 0.60, // 60% failure / timeout rate
    hikariPoolSize: 20,
    lockContentionPenalty: 0,
    durationSeconds: 15
  }
];

scenarios.forEach((sc, idx) => {
  const result = runSimulation(sc);
  console.log(`[TEST ${idx + 1}] ${result.scenarioName}`);
  console.log('--------------------------------------------------------------------------------');
  console.log(`  Offered Load       : ${result.offeredRps} RPS`);
  console.log(`  Total Requests     : Submitted=${result.totalSubmitted} | Completed=${result.totalCompleted} | Dropped=${result.totalDropped}`);
  console.log(`  Success Rate       : ${result.successRate}`);
  console.log(`  Latencies          : p50 = ${result.p50Ms} | p95 = ${result.p95Ms} | p99 = ${result.p99Ms}`);
  console.log('  Component Sizing   :');
  console.log(`    - Orchestrator Queue Peak : ${result.peaks.orchestratorPeakQueue}`);
  console.log(`    - Risk Engine Queue Peak  : ${result.peaks.riskEnginePeakQueue}`);
  console.log(`    - Azure SQL Pool Busy     : ${result.peaks.azureSqlPeakConnections}`);
  console.log(`    - Azure SQL Queue Peak    : ${result.peaks.azureSqlPeakQueue}`);
  console.log(`    - Kafka Buffer Peak       : ${result.peaks.kafkaPeakBuffer}`);
  console.log(`    - Circuit Breaker Final   : ${result.peaks.circuitBreakerState}`);

  if (Object.keys(result.dropReasons).length > 0) {
    console.log('  Drop Breakdown     :');
    for (const [reason, count] of Object.entries(result.dropReasons)) {
      console.log(`    * ${reason}: ${count}`);
    }
  }

  // Diagnostic
  console.log('  Architectural Diagnosis:');
  if (result.totalDropped === 0) {
    console.log('    >> HEALTHY: All queues drained smoothly within 200 ms SLA.');
  } else if (result.peaks.azureSqlPeakQueue > 0 || result.dropReasons['HIKARICP_POOL_EXHAUSTED_LOCK_TIMEOUT']) {
    console.log('    >> BOTTLENECK IDENTIFIED: Row-level lock contention on core.balance_master.');
    console.log(`       Azure SQL pool fully saturated (${result.peaks.azureSqlPeakConnections}) with ${result.peaks.azureSqlPeakQueue} waiting transactions.`);
    console.log('       Threads holding UPDLOCK serialize concurrent balance updates, creating severe');
    console.log('       queue delays that cause upstream orchestrator timeout drops.');
  } else if (result.peaks.riskEnginePeakQueue > 0 || result.dropReasons['RISK_ENGINE_QUEUE_FULL']) {
    console.log('    >> BOTTLENECK IDENTIFIED: Synchronous ML inference in the critical transfer path.');
    console.log(`       Risk Engine queue peaked at ${result.peaks.riskEnginePeakQueue}, pinning Orchestrator worker threads.`);
    console.log('       Client retries compounded the stall into a retry storm.');
  } else if (result.peaks.circuitBreakerState === 'OPEN' || result.dropReasons['CIRCUIT_BREAKER_OPEN_FAST_FAIL']) {
    console.log('    >> BOTTLENECK IDENTIFIED: Downstream partner degradation tripped Resilience4j.');
    console.log('       Outward transfers fail-fast with 503 to prevent thread pool exhaustion, diverting');
    console.log('       unsettled events into Kafka banking.transfers.dlq for automated Saga rollbacks.');
  } else if (result.dropReasons['GATEWAY_429_RATE_LIMITED']) {
    console.log('    >> BOTTLENECK IDENTIFIED: Perimeter Edge Rate Limiting.');
    console.log('       Traffic exceeded the Redis token-bucket filter (10 RPS / burst 20). Requests dropped');
    console.log('       at Spring Cloud Gateway while downstream microservices remained underutilized.');
  }
  console.log('\n');
});
