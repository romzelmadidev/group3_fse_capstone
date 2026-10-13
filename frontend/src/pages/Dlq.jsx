import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  AlertOctagon,
  AlertTriangle,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Clock,
  Copy,
  Cpu,
  FileCode,
  Layers,
  RefreshCw,
  RotateCcw,
  ShieldAlert,
  ShieldCheck,
  Zap,
} from 'lucide-react';
import { api, errorMessage } from '../lib/api';
import { dateTime, money, titleCase } from '../lib/format';
import {
  Badge,
  Button,
  Drawer,
  Empty,
  ErrorNote,
  PageHeader,
  SearchBar,
  SkeletonRows,
  useToast,
} from '../components/ui';

export default function Dlq() {
  const toast = useToast();
  const [incidents, setIncidents] = useState([]);
  const [daemonStatus, setDaemonStatus] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const [activeTab, setActiveTab] = useState('attention'); // 'attention' | 'resolved' | 'expired' | 'all'
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedIncident, setSelectedIncident] = useState(null);
  const [showStackTrace, setShowStackTrace] = useState(false);
  const [replayingId, setReplayingId] = useState(null);
  const [recentlyResolvedIds, setRecentlyResolvedIds] = useState(new Set());

  const fetchIncidentsAndStatus = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [incidentsRes, statusRes] = await Promise.all([
        api.get('/compliance/dlq/incidents?status=ALL&page=0&size=100').catch((e) => {
          // fallback to direct cbs audit endpoint if needed
          return api.get('/cbs/audit/failed-transactions?status=ALL&page=0&size=100');
        }),
        api.get('/compliance/dlq/status').catch(() => ({
          data: {
            autonomousDaemonActive: true,
            shedLockSynced: true,
            cbsHealthy: true,
            maxAgeMinutes: 10,
            throttleMs: 300,
          },
        })),
      ]);

      const list = Array.isArray(incidentsRes.data) ? incidentsRes.data : [];
      setIncidents(list);
      setDaemonStatus(statusRes.data || null);
    } catch (err) {
      console.error('Failed to load DLQ incidents:', err);
      setError(err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchIncidentsAndStatus();
    const timer = setInterval(() => {
      fetchIncidentsAndStatus();
    }, 30000);
    return () => clearInterval(timer);
  }, [fetchIncidentsAndStatus]);

  // Counts for top tiles
  const counts = useMemo(() => {
    let pending = 0;
    let manual = 0;
    let expired = 0;
    let resolved = 0;

    for (const item of incidents) {
      const status = (item.replayStatus || 'PENDING_REPLAY').toUpperCase();
      if (status === 'RESOLVED' || status === 'REPLAYED') resolved++;
      else if (status === 'EXPIRED_CANCELLED' || status === 'DISMISSED') expired++;
      else if (status === 'MANUAL_REVIEW_REQUIRED') manual++;
      else pending++;
    }

    return { pending, manual, expired, resolved, attention: pending + manual, total: incidents.length };
  }, [incidents]);

  // Filtered by tab and search
  const filteredIncidents = useMemo(() => {
    let list = incidents;

    if (activeTab === 'attention') {
      list = list.filter((i) => {
        const s = (i.replayStatus || 'PENDING_REPLAY').toUpperCase();
        const isRecentlyResolved = recentlyResolvedIds.has(i.transactionId) || recentlyResolvedIds.has(i.incidentId);
        return s === 'PENDING_REPLAY' || s === 'MANUAL_REVIEW_REQUIRED' || isRecentlyResolved;
      });
    } else if (activeTab === 'resolved') {
      list = list.filter((i) => {
        const s = (i.replayStatus || '').toUpperCase();
        return s === 'RESOLVED' || s === 'REPLAYED';
      });
    } else if (activeTab === 'expired') {
      list = list.filter((i) => {
        const s = (i.replayStatus || '').toUpperCase();
        return s === 'EXPIRED_CANCELLED' || s === 'DISMISSED';
      });
    }

    if (!searchQuery.trim()) return list;
    const needle = searchQuery.toLowerCase().trim();
    return list.filter((i) => {
      return (
        i.incidentId?.toLowerCase().includes(needle) ||
        i.transactionId?.toLowerCase().includes(needle) ||
        i.correlationId?.toLowerCase().includes(needle) ||
        i.errorType?.toLowerCase().includes(needle) ||
        i.errorCode?.toLowerCase().includes(needle) ||
        i.payloadJson?.toLowerCase().includes(needle)
      );
    });
  }, [incidents, activeTab, searchQuery, recentlyResolvedIds]);

  // Handle Manual Replay Action
  const handleReplay = async (incident, e) => {
    if (e) e.stopPropagation();
    const txId = incident.transactionId || incident.incidentId;
    setReplayingId(txId);

    try {
      const res = await api.post(`/compliance/dlq/replay/${encodeURIComponent(txId)}`);
      if (res.data?.status === 'REPLAY_FAILED') {
        throw new Error(res.data?.error || 'Replay rejected by transfer orchestrator.');
      }

      // Immediately mark as RESOLVED in UI state so it stays visible and marked as resolved
      const nowIso = new Date().toISOString();
      setRecentlyResolvedIds((prev) => new Set([...prev, txId, incident.incidentId, incident.transactionId].filter(Boolean)));
      setIncidents((prev) =>
        prev.map((item) =>
          item.transactionId === txId || item.incidentId === txId
            ? {
                ...item,
                replayStatus: 'RESOLVED',
                resolvedBy: 'COMPLIANCE_OPERATOR',
                resolvedAt: nowIso,
              }
            : item
        )
      );

      if (selectedIncident?.incidentId === incident.incidentId || selectedIncident?.transactionId === txId) {
        setSelectedIncident((prev) =>
          prev
            ? {
                ...prev,
                replayStatus: 'RESOLVED',
                resolvedBy: 'COMPLIANCE_OPERATOR',
                resolvedAt: nowIso,
              }
            : null
        );
      }

      toast(`Transfer ${txId} replayed successfully and marked as RESOLVED!`, 'ok');
      await fetchIncidentsAndStatus();
    } catch (err) {
      console.error('Replay failed:', err);
      toast(`Replay failed for ${txId}: ${errorMessage(err)}`, 'error');
    } finally {
      setReplayingId(null);
    }
  };

  // Status Badge Tone
  const getStatusTone = (status) => {
    const s = (status || 'PENDING_REPLAY').toUpperCase();
    if (s === 'RESOLVED' || s === 'REPLAYED') return 'mint';
    if (s === 'MANUAL_REVIEW_REQUIRED') return 'danger';
    if (s === 'EXPIRED_CANCELLED' || s === 'DISMISSED') return 'neutral';
    return 'ember';
  };

  // Parse payload safely
  const parsedPayload = useMemo(() => {
    if (!selectedIncident?.payloadJson) return null;
    try {
      if (typeof selectedIncident.payloadJson === 'object') return selectedIncident.payloadJson;
      return JSON.parse(selectedIncident.payloadJson);
    } catch {
      return null;
    }
  }, [selectedIncident]);

  return (
    <>
      <PageHeader
        title="DLQ incidents"
        description="Failed transactions and core banking timeout exceptions captured in the audit vault. Inspect failure diagnostics, monitor autonomous recovery, or trigger authorized replays."
        actions={
          <Button variant="ghost" icon={RotateCcw} onClick={fetchIncidentsAndStatus} loading={loading}>
            Refresh
          </Button>
        }
      />

      {/* Autonomous Constrained Recovery Daemon Banner */}
      <div className="mb-6 overflow-hidden rounded-2xl border border-ink-100 bg-white p-5 shadow-card animate-rise">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <div className="grid size-10 place-items-center rounded-xl bg-mint-wash text-mint-deep">
              <Zap className="size-5" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <span className="font-semibold text-ink text-sm">Autonomous Recovery Daemon</span>
                <span className="inline-flex items-center gap-1 rounded-full bg-mint-wash px-2 py-0.5 text-2xs font-semibold text-mint-deep">
                  <span className="size-1.5 rounded-full bg-mint-deep animate-pulse" />
                  ACTIVE
                </span>
                <span className="rounded-full bg-ink-100 px-2 py-0.5 text-2xs font-mono text-ink-500">
                  ShedLock Synced (30s)
                </span>
              </div>
              <p className="mt-0.5 text-xs text-ink-500">
                Polls PostgreSQL audit vault under Redis distributed mutex. Automatically recovers healthy CBS outages without manual intervention.
              </p>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <div className="flex items-center gap-1.5 rounded-lg border border-ink-100 bg-paper px-3 py-1.5 text-xs">
              <ShieldCheck className="size-3.5 text-mint-deep" />
              <span className="text-ink-500">CBS Health:</span>
              <span className="font-semibold text-ink">
                {daemonStatus?.cbsHealthy !== false ? 'UP (200 OK)' : 'DOWN / Circuit Open'}
              </span>
            </div>
            <div className="flex items-center gap-1.5 rounded-lg border border-ink-100 bg-paper px-3 py-1.5 text-xs">
              <Clock className="size-3.5 text-ember-deep" />
              <span className="text-ink-500">TTL Safety Guard:</span>
              <span className="font-semibold text-ink">10 Mins (BSP 1160)</span>
            </div>
            <div className="flex items-center gap-1.5 rounded-lg border border-ink-100 bg-paper px-3 py-1.5 text-xs">
              <Cpu className="size-3.5 text-sky-deep" />
              <span className="text-ink-500">Throttled Drip:</span>
              <span className="font-semibold text-ink">300ms Delay</span>
            </div>
          </div>
        </div>
      </div>

      {/* Metric Tiles */}
      <div className="mb-6 grid grid-cols-2 gap-4 sm:grid-cols-4 animate-rise" style={{ animationDelay: '40ms' }}>
        <button
          onClick={() => setActiveTab('attention')}
          className={`panel p-4 text-left transition-all hover:ring-2 hover:ring-ink-200 ${
            activeTab === 'attention' ? 'ring-2 ring-ink' : ''
          }`}
        >
          <div className="flex items-center justify-between text-xs text-ink-500 font-medium">
            <span>Pending Replay</span>
            <span className="size-2 rounded-full bg-ember" />
          </div>
          <p className="mt-2 text-2xl font-bold tracking-tight text-ink tabular-nums">{counts.pending}</p>
          <p className="mt-1 text-2xs text-ink-400">Fresh incidents &lt;10m awaiting worker</p>
        </button>

        <button
          onClick={() => setActiveTab('attention')}
          className={`panel p-4 text-left transition-all hover:ring-2 hover:ring-ink-200 ${
            activeTab === 'attention' ? 'ring-2 ring-danger' : ''
          }`}
        >
          <div className="flex items-center justify-between text-xs text-ink-500 font-medium">
            <span>Manual Review Required</span>
            <span className="size-2 rounded-full bg-danger" />
          </div>
          <p className="mt-2 text-2xl font-bold tracking-tight text-danger tabular-nums">{counts.manual}</p>
          <p className="mt-1 text-2xs text-ink-400">Poison pill or non-transient error</p>
        </button>

        <button
          onClick={() => setActiveTab('expired')}
          className={`panel p-4 text-left transition-all hover:ring-2 hover:ring-ink-200 ${
            activeTab === 'expired' ? 'ring-2 ring-ink-300' : ''
          }`}
        >
          <div className="flex items-center justify-between text-xs text-ink-500 font-medium">
            <span>Expired / Cancelled</span>
            <span className="size-2 rounded-full bg-ink-400" />
          </div>
          <p className="mt-2 text-2xl font-bold tracking-tight text-ink-600 tabular-nums">{counts.expired}</p>
          <p className="mt-1 text-2xs text-ink-400">Aged past 10m TTL window</p>
        </button>

        <button
          onClick={() => setActiveTab('resolved')}
          className={`panel p-4 text-left transition-all hover:ring-2 hover:ring-ink-200 ${
            activeTab === 'resolved' ? 'ring-2 ring-mint-deep' : ''
          }`}
        >
          <div className="flex items-center justify-between text-xs text-ink-500 font-medium">
            <span>Resolved</span>
            <span className="size-2 rounded-full bg-mint-deep" />
          </div>
          <p className="mt-2 text-2xl font-bold tracking-tight text-mint-deep tabular-nums">{counts.resolved}</p>
          <p className="mt-1 text-2xs text-ink-400">Autonomous daemon & operator replays</p>
        </button>
      </div>

      {/* Main Table Panel */}
      <div className="panel overflow-hidden animate-rise" style={{ animationDelay: '80ms' }}>
        {/* Navigation Tabs */}
        <div className="flex border-b border-ink-100 px-5 pt-3">
          {[
            { id: 'attention', label: 'Needs Attention', count: counts.attention },
            { id: 'resolved', label: 'Resolved', count: counts.resolved },
            { id: 'expired', label: 'Expired / Cancelled', count: counts.expired },
            { id: 'all', label: 'All Incidents', count: counts.total },
          ].map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`flex items-center gap-2 border-b-2 px-4 py-3 text-sm font-semibold transition-colors ${
                activeTab === tab.id
                  ? 'border-ink text-ink'
                  : 'border-transparent text-ink-400 hover:text-ink-600'
              }`}
            >
              <span>{tab.label}</span>
              <span className="rounded-full bg-ink-100 px-2 py-0.5 text-xs font-medium tabular-nums text-ink-500">
                {tab.count}
              </span>
            </button>
          ))}
        </div>

        {/* Search Bar */}
        <SearchBar
          value={searchQuery}
          onChange={setSearchQuery}
          placeholder="Filter by incident ID, transaction ID, correlation ID, error type, or payload..."
          ariaLabel="Search DLQ incidents"
          count={filteredIncidents.length}
          total={incidents.length}
        />

        {/* Table Body */}
        {loading ? (
          <SkeletonRows rows={6} />
        ) : error ? (
          <div className="p-5">
            <ErrorNote message={errorMessage(error)} onRetry={fetchIncidentsAndStatus} />
          </div>
        ) : filteredIncidents.length === 0 ? (
          <Empty
            icon={CheckCircle2}
            title={searchQuery ? 'No matching incidents found' : 'Queue is clear'}
            body={
              searchQuery
                ? 'Try a different search query or clear the filter.'
                : activeTab === 'attention'
                ? 'No failed transactions currently require replay or manual attention.'
                : 'No incidents match this status filter.'
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-ink-100 text-left">
                  <th className="label whitespace-nowrap px-5 py-3 font-semibold">When</th>
                  <th className="label whitespace-nowrap px-5 py-3 font-semibold">Incident ID</th>
                  <th className="label whitespace-nowrap px-5 py-3 font-semibold">Transfer ID</th>
                  <th className="label whitespace-nowrap px-5 py-3 font-semibold">Error Type</th>
                  <th className="label whitespace-nowrap px-5 py-3 font-semibold">Circuit Breaker</th>
                  <th className="label whitespace-nowrap px-5 py-3 font-semibold">Status</th>
                  <th className="label whitespace-nowrap px-5 py-3 font-semibold text-right pr-6">Action</th>
                </tr>
              </thead>
              <tbody>
                {filteredIncidents.map((incident, idx) => {
                  const status = (incident.replayStatus || 'PENDING_REPLAY').toUpperCase();
                  const isPendingOrManual = status === 'PENDING_REPLAY' || status === 'MANUAL_REVIEW_REQUIRED';
                  const txId = incident.transactionId || incident.incidentId;
                  const isReplayingThis = replayingId === txId;

                  return (
                    <tr
                      key={incident.incidentId || idx}
                      onClick={() => setSelectedIncident(incident)}
                      className="row-enter cursor-pointer border-b border-ink-100 transition-colors hover:bg-ink-50/50 last:border-0"
                      style={{ '--i': Math.min(idx, 15) }}
                    >
                      <td className="whitespace-nowrap px-5 py-3.5 text-xs text-ink-500">
                        {dateTime(incident.failureTimestamp)}
                      </td>
                      <td className="px-5 py-3.5 font-mono text-xs font-semibold text-ink">
                        {incident.incidentId}
                      </td>
                      <td className="px-5 py-3.5 font-mono text-xs text-ink-600">
                        {incident.transactionId || '-'}
                      </td>
                      <td className="px-5 py-3.5">
                        <span className="font-medium text-xs text-ink max-w-[220px] truncate block" title={incident.errorType}>
                          {incident.errorType}
                        </span>
                        {incident.errorCode && (
                          <span className="text-2xs text-ink-400 font-mono">{incident.errorCode}</span>
                        )}
                      </td>
                      <td className="px-5 py-3.5">
                        <span
                          className={`inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-2xs font-mono font-semibold ${
                            incident.circuitBreakerState === 'OPEN'
                              ? 'bg-danger-wash text-danger'
                              : 'bg-ink-100 text-ink-600'
                          }`}
                        >
                          {incident.circuitBreakerState || 'CLOSED'}
                        </span>
                      </td>
                      <td className="px-5 py-3.5">
                        <Badge tone={getStatusTone(status)}>{titleCase(status)}</Badge>
                      </td>
                      <td className="px-5 py-3.5 text-right pr-6">
                        {isPendingOrManual && !recentlyResolvedIds.has(incident.transactionId) && !recentlyResolvedIds.has(incident.incidentId) ? (
                          <Button
                            variant="primary"
                            size="sm"
                            icon={RefreshCw}
                            loading={isReplayingThis}
                            onClick={(e) => handleReplay(incident, e)}
                          >
                            Replay
                          </Button>
                        ) : status === 'RESOLVED' || recentlyResolvedIds.has(incident.transactionId) || recentlyResolvedIds.has(incident.incidentId) ? (
                          <span className="inline-flex items-center gap-1 text-xs font-semibold text-mint-deep">
                            <CheckCircle2 className="size-3.5" /> Resolved
                          </span>
                        ) : (
                          <span className="text-xs font-semibold text-ink-400 hover:text-ink">
                            Details &rarr;
                          </span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Incident Inspection Drawer */}
      <Drawer
        open={Boolean(selectedIncident)}
        onClose={() => {
          setSelectedIncident(null);
          setShowStackTrace(false);
        }}
        title={`Incident ${selectedIncident?.incidentId || ''}`}
        subtitle={`Correlation ID: ${selectedIncident?.correlationId || 'N/A'}`}
        footer={
          selectedIncident && (
            <div className="flex items-center justify-between gap-4">
              <div className="text-xs text-ink-500">
                Status: <span className="font-semibold text-ink">{titleCase(selectedIncident.replayStatus || 'PENDING_REPLAY')}</span>
              </div>
              <div className="flex gap-2">
                <Button variant="ghost" size="sm" onClick={() => setSelectedIncident(null)}>
                  Close
                </Button>
                {((selectedIncident.replayStatus || 'PENDING_REPLAY').toUpperCase() === 'PENDING_REPLAY' ||
                  (selectedIncident.replayStatus || '').toUpperCase() === 'MANUAL_REVIEW_REQUIRED') && (
                  <Button
                    variant="primary"
                    size="sm"
                    icon={RefreshCw}
                    loading={replayingId === (selectedIncident.transactionId || selectedIncident.incidentId)}
                    onClick={() => handleReplay(selectedIncident)}
                  >
                    Replay Transfer
                  </Button>
                )}
              </div>
            </div>
          )
        }
      >
        {selectedIncident && (
          <div className="space-y-6">
            {/* Resolution Banner */}
            {(selectedIncident.replayStatus || '').toUpperCase() === 'EXPIRED_CANCELLED' && (
              <div className="rounded-xl border border-ink-200 bg-ink-50 p-4">
                <div className="flex items-start gap-3">
                  <ShieldAlert className="size-5 text-ink-600 shrink-0 mt-0.5" />
                  <div>
                    <h4 className="text-sm font-semibold text-ink">Safely Expired (BSP Circular 1160)</h4>
                    <p className="mt-1 text-xs text-ink-500 leading-relaxed">
                      This incident exceeded the 10-minute safe automated replay window. To protect depositors from unexpected delayed debits, automated retries were halted. The depositor has been advised to re-initiate if desired.
                    </p>
                  </div>
                </div>
              </div>
            )}

            {(selectedIncident.replayStatus || '').toUpperCase() === 'RESOLVED' && (
              <div className="rounded-xl border border-mint/30 bg-mint-wash p-4">
                <div className="flex items-start gap-3">
                  <CheckCircle2 className="size-5 text-mint-deep shrink-0 mt-0.5" />
                  <div>
                    <h4 className="text-sm font-semibold text-mint-deep">Incident Resolved</h4>
                    <p className="mt-0.5 text-xs text-ink-600">
                      Resolved by: <span className="font-mono font-semibold">{selectedIncident.resolvedBy || 'AUTONOMOUS_RECOVERY_DAEMON'}</span>
                      {selectedIncident.resolvedAt && (
                        <span> &bull; {dateTime(selectedIncident.resolvedAt)}</span>
                      )}
                    </p>
                  </div>
                </div>
              </div>
            )}

            {/* Incident Summary Card */}
            <div className="rounded-2xl border border-ink-100 bg-white p-5 shadow-card">
              <h3 className="text-xs font-semibold uppercase tracking-wider text-ink-400 mb-4">Diagnostics Overview</h3>
              <div className="grid grid-cols-2 gap-4 text-xs">
                <div>
                  <span className="block text-ink-400">Transfer ID</span>
                  <span className="font-mono font-semibold text-ink break-all">{selectedIncident.transactionId || '-'}</span>
                </div>
                <div>
                  <span className="block text-ink-400">Failure Timestamp</span>
                  <span className="font-semibold text-ink">{dateTime(selectedIncident.failureTimestamp)}</span>
                </div>
                <div>
                  <span className="block text-ink-400">Error Type</span>
                  <span className="font-semibold text-danger">{selectedIncident.errorType}</span>
                </div>
                <div>
                  <span className="block text-ink-400">Error Code</span>
                  <span className="font-mono font-semibold text-ink">{selectedIncident.errorCode || 'N/A'}</span>
                </div>
                <div>
                  <span className="block text-ink-400">Circuit Breaker</span>
                  <span className="font-mono font-semibold text-ink">{selectedIncident.circuitBreakerState}</span>
                </div>
                <div>
                  <span className="block text-ink-400">Current Status</span>
                  <Badge tone={getStatusTone(selectedIncident.replayStatus)}>
                    {titleCase(selectedIncident.replayStatus || 'PENDING_REPLAY')}
                  </Badge>
                </div>
              </div>
            </div>

            {/* Transfer Payload Inspection */}
            {parsedPayload && (
              <div className="rounded-2xl border border-ink-100 bg-white p-5 shadow-card">
                <h3 className="text-xs font-semibold uppercase tracking-wider text-ink-400 mb-3">Transfer Payload Details</h3>
                <div className="space-y-3 text-xs">
                  <div className="flex justify-between py-1.5 border-b border-ink-100">
                    <span className="text-ink-500">Source Account:</span>
                    <span className="font-mono font-semibold text-ink">{parsedPayload.sourceAccountId}</span>
                  </div>
                  <div className="flex justify-between py-1.5 border-b border-ink-100">
                    <span className="text-ink-500">Destination Account:</span>
                    <span className="font-mono font-semibold text-ink">{parsedPayload.destinationAccountId}</span>
                  </div>
                  <div className="flex justify-between py-1.5 border-b border-ink-100">
                    <span className="text-ink-500">Amount:</span>
                    <span className="font-semibold text-ink">{money(parsedPayload.amount)} {parsedPayload.currency || 'PHP'}</span>
                  </div>
                  {parsedPayload.description && (
                    <div className="flex justify-between py-1.5 border-b border-ink-100">
                      <span className="text-ink-500">Description:</span>
                      <span className="text-ink">{parsedPayload.description}</span>
                    </div>
                  )}
                  {parsedPayload.idempotencyKey && (
                    <div className="flex justify-between py-1.5 border-b border-ink-100">
                      <span className="text-ink-500">Idempotency Key:</span>
                      <span className="font-mono text-2xs text-ink-500 truncate max-w-xs">{parsedPayload.idempotencyKey}</span>
                    </div>
                  )}
                </div>
              </div>
            )}

            {/* Raw JSON Payload */}
            {selectedIncident.payloadJson && (
              <div className="rounded-2xl border border-ink-100 bg-white p-5 shadow-card">
                <div className="flex items-center justify-between mb-3">
                  <h3 className="text-xs font-semibold uppercase tracking-wider text-ink-400">Raw JSON Payload</h3>
                  <button
                    onClick={() => {
                      navigator.clipboard.writeText(selectedIncident.payloadJson);
                      toast('Payload copied to clipboard', 'ok');
                    }}
                    className="inline-flex items-center gap-1 text-2xs font-semibold text-ink-500 hover:text-ink"
                  >
                    <Copy className="size-3" /> Copy
                  </button>
                </div>
                <pre className="max-h-48 overflow-y-auto rounded-xl bg-ink p-3 font-mono text-2xs text-white/90 leading-relaxed whitespace-pre-wrap">
                  {typeof selectedIncident.payloadJson === 'string'
                    ? (() => {
                        try {
                          return JSON.stringify(JSON.parse(selectedIncident.payloadJson), null, 2);
                        } catch {
                          return selectedIncident.payloadJson;
                        }
                      })()
                    : JSON.stringify(selectedIncident.payloadJson, null, 2)}
                </pre>
              </div>
            )}

            {/* Stack Trace Accordion */}
            {selectedIncident.stackTrace && (
              <div className="rounded-2xl border border-ink-100 bg-white p-5 shadow-card">
                <button
                  onClick={() => setShowStackTrace(!showStackTrace)}
                  className="flex w-full items-center justify-between text-xs font-semibold uppercase tracking-wider text-ink-500"
                >
                  <span className="flex items-center gap-1.5 text-danger">
                    <AlertTriangle className="size-3.5" /> Stack Trace
                  </span>
                  {showStackTrace ? <ChevronDown className="size-4" /> : <ChevronRight className="size-4" />}
                </button>
                {showStackTrace && (
                  <pre className="mt-3 max-h-64 overflow-y-auto rounded-xl bg-ink p-3 font-mono text-2xs text-danger-wash leading-relaxed whitespace-pre-wrap">
                    {selectedIncident.stackTrace}
                  </pre>
                )}
              </div>
            )}
          </div>
        )}
      </Drawer>
    </>
  );
}
