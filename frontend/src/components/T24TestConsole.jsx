import React, { useState, useEffect } from 'react';
import {
  Send,
  Lock,
  Unlock,
  RotateCcw,
  Terminal,
  Moon,
  AlertTriangle,
  CheckCircle2,
  XCircle,
  RefreshCw,
  Copy,
  Check,
  ShieldAlert,
  ArrowRight,
  Database,
  Layers,
  FileCode,
  DollarSign,
  Activity,
  History,
  Clock,
  Play,
  FileText
} from 'lucide-react';
import axios from 'axios';

// API Clients
const API_BASE = '/api/v1';

export default function T24TestConsole() {
  const [activeTab, setActiveTab] = useState('transfer'); // transfer | hold | reversal | ofs | cob | dlq
  const [activeAccount, setActiveAccount] = useState('ACC-1001');
  const [balanceData, setBalanceData] = useState({
    accountId: 'ACC-1001',
    balanceAmount: 150000.0,
    holdAmount: 0.0,
    availableBalance: 150000.0
  });
  const [systemDate, setSystemDate] = useState({
    businessDate: '2026-10-08',
    status: 'ONLINE',
    postingWindowOpen: true
  });
  const [isLoadingBalance, setIsLoadingBalance] = useState(false);
  const [toastMessage, setToastMessage] = useState(null);
  const [copiedText, setCopiedText] = useState(false);

  // Transfer State
  const [transferForm, setTransferForm] = useState({
    sourceAccountId: 'ACC-1001',
    destinationAccountId: 'ACC-1002',
    amount: '5000.00',
    currency: 'PHP',
    description: 'Test Transfer via CBS Console'
  });
  const [transferResult, setTransferResult] = useState(null);
  const [isTransferring, setIsTransferring] = useState(false);

  // Amount Hold & Reservation State
  const [holdForm, setHoldForm] = useState({
    accountId: 'ACC-1001',
    targetAccountId: 'ACC-1002',
    transactionId: 'TX-RES-' + Math.floor(Math.random() * 90000 + 10000),
    holdAmount: '10000.00',
    reason: 'PRE_AUTHORIZATION',
    expiryHours: 24,
    externalReference: 'TRANSFER_RESERVATION'
  });
  const [activeHolds, setActiveHolds] = useState([]);
  const [isHolding, setIsHolding] = useState(false);
  const [holdResult, setHoldResult] = useState(null);
  const [isCapturing, setIsCapturing] = useState(false);
  const [captureResult, setCaptureResult] = useState(null);
  const [isSimulatingFailure, setIsSimulatingFailure] = useState(false);

  // Reversal State
  const [reversalForm, setReversalForm] = useState({
    originalTransactionId: '',
    makerId: 'TELLER_ALICE',
    reason: 'CUSTOMER_DISPUTE',
    notes: 'Customer disputed charge'
  });
  const [checkerForm, setCheckerForm] = useState({
    ticketId: '',
    checkerId: 'MGR_BOB',
    checkerNotes: 'Validated and approved by operations manager'
  });
  const [reversalResult, setReversalResult] = useState(null);
  const [isReversing, setIsReversing] = useState(false);

  // OFS Terminal State
  const [ofsInput, setOfsInput] = useState(
    'AC.LOCKED.EVENTS,INPUT/I/PROCESS/0/1,USER01/123456,,ACCOUNT.NUMBER=ACC-1001,FROM.DATE=20261008,TO.DATE=20261009,LOCKED.AMOUNT=5000.00,HOLD.REASON=MAKER_CHECKER_HOLD,EXT.REF=OFS-DEMO-01'
  );
  const [ofsResponse, setOfsResponse] = useState('');
  const [isExecutingOfs, setIsExecutingOfs] = useState(false);

  // COB Batch State
  const [cobResult, setCobResult] = useState(null);
  const [isExecutingCob, setIsExecutingCob] = useState(false);

  // DLQ State
  const [dlqIncidents, setDlqIncidents] = useState([]);
  const [isLoadingDlq, setIsLoadingDlq] = useState(false);
  const [replayResult, setReplayResult] = useState(null);

  const showToast = (text, type = 'info') => {
    setToastMessage({ text, type });
    setTimeout(() => setToastMessage(null), 5000);
  };

  const copyToClipboard = (text) => {
    navigator.clipboard.writeText(text);
    setCopiedText(true);
    setTimeout(() => setCopiedText(false), 2000);
  };

  // Fetch live balance
  const fetchBalance = async (accId = activeAccount) => {
    setIsLoadingBalance(true);
    try {
      const res = await axios.get(`${API_BASE}/cbs/accounts/${accId}/balance`);
      if (res.data) {
        setBalanceData({
          accountId: accId,
          balanceAmount: parseFloat(res.data.balanceAmount || res.data.currentBalance || 0),
          holdAmount: parseFloat(res.data.holdAmount || 0),
          availableBalance: parseFloat(res.data.availableBalance || 0)
        });
      }
    } catch (e) {
      // Fallback enquiry via OFS or simulated state
      try {
        const ofsRes = await axios.post(`${API_BASE}/cbs/ofs`, `ENQUIRY.SELECT,,USER01/123456,ACCOUNT.NUMBER:EQ=${accId}`, {
          headers: { 'Content-Type': 'text/plain' }
        });
        const text = ofsRes.data || '';
        const balMatch = text.match(/WORKING\.BALANCE:1:1=([\d\.]+)/);
        const holdMatch = text.match(/LOCKED\.AMOUNT:1:1=([\d\.]+)/);
        const availMatch = text.match(/AVAILABLE\.BALANCE:1:1=([\d\.]+)/);
        if (balMatch) {
          setBalanceData({
            accountId: accId,
            balanceAmount: parseFloat(balMatch[1]),
            holdAmount: holdMatch ? parseFloat(holdMatch[1]) : 0,
            availableBalance: availMatch ? parseFloat(availMatch[1]) : parseFloat(balMatch[1])
          });
        }
      } catch (err) {
        console.warn('Balance fetch error:', err);
      }
    } finally {
      setIsLoadingBalance(false);
    }
  };

  // Fetch active holds
  const fetchHolds = async (accId = activeAccount) => {
    try {
      const res = await axios.get(`${API_BASE}/cbs/holds/account/${accId}`);
      if (Array.isArray(res.data)) {
        setActiveHolds(res.data);
      }
    } catch {
      setActiveHolds([]);
    }
  };

  // Fetch system date & COB status
  const fetchSystemDate = async () => {
    try {
      const res = await axios.get(`${API_BASE}/cbs/system-date`);
      if (res.data) setSystemDate(res.data);
    } catch (e) {
      console.warn('System date fetch error:', e);
    }
  };

  // Fetch DLQ incidents
  const fetchDlqIncidents = async () => {
    setIsLoadingDlq(true);
    try {
      const res = await axios.get(`${API_BASE}/compliance/dlq/incidents`);
      if (Array.isArray(res.data)) {
        setDlqIncidents(res.data);
      }
    } catch {
      setDlqIncidents([]);
    } finally {
      setIsLoadingDlq(false);
    }
  };

  useEffect(() => {
    fetchBalance(activeAccount);
    fetchHolds(activeAccount);
    fetchSystemDate();
  }, [activeAccount]);

  // Execute Transfer (Dual-Endpoint 1: POST /t24/funds-transfer)
  const handleExecuteTransfer = async (e) => {
    e.preventDefault();
    setIsTransferring(true);
    setTransferResult(null);
    try {
      const txRef = 'FT' + Math.floor(Math.random() * 900000 + 100000);
      const payload = {
        transaction_id: txRef,
        source_account_id: transferForm.sourceAccountId,
        destination_account_id: transferForm.destinationAccountId,
        amount: parseFloat(transferForm.amount),
        currency: transferForm.currency,
        memo: transferForm.description,
        transaction_type: 'INTRA_BANK',
        requires_maker_checker: 0
      };
      const res = await axios.post(`${API_BASE}/cbs/t24/funds-transfer`, payload);
      setTransferResult({ success: true, data: res.data });
      showToast('T24 Endpoint 1 (/funds-transfer) executed and posted!', 'success');
      // Auto-fill reversal original Tx ID
      const ref = res.data?.t24_reference || res.data?.transactionId || txRef;
      setReversalForm((prev) => ({ ...prev, originalTransactionId: ref }));
      fetchBalance(activeAccount);
    } catch (err) {
      setTransferResult({
        success: false,
        error: err.response?.data?.message || err.response?.data || err.message
      });
      showToast('Transfer failed: ' + (err.response?.data?.message || err.message), 'error');
    } finally {
      setIsTransferring(false);
    }
  };

  // Execute Compensating Reversal (Dual-Endpoint 2: POST /t24/reversal)
  const handleCompensatingReversal = async (e) => {
    if (e && e.preventDefault) e.preventDefault();
    if (!reversalForm.originalTransactionId) {
      showToast('Please specify the original transaction ID to reverse.', 'error');
      return;
    }
    setIsReversing(true);
    setReversalResult(null);
    try {
      const payload = {
        original_transaction_id: reversalForm.originalTransactionId,
        reversal_reason: reversalForm.reason || 'SAGA_COMPENSATION_ROLLBACK',
        actor_id: 'SAGA_COORDINATOR'
      };
      const res = await axios.post(`${API_BASE}/cbs/t24/reversal`, payload);
      setReversalResult({ success: true, step: 'COMPENSATED_EP2', data: res.data });
      showToast('T24 Endpoint 2 (/reversal) compensating saga reversal executed!', 'success');
      fetchBalance(activeAccount);
    } catch (err) {
      setReversalResult({
        success: false,
        error: err.response?.data?.message || err.response?.data || err.message
      });
      showToast('Compensating reversal failed: ' + (err.response?.data?.message || err.message), 'error');
    } finally {
      setIsReversing(false);
    }
  };

  // Place Amount Hold (Reserve Funds for Transfer)
  const handlePlaceHold = async (e) => {
    e.preventDefault();
    setIsHolding(true);
    setHoldResult(null);
    try {
      const payload = {
        account_id: holdForm.accountId,
        target_account_id: holdForm.targetAccountId,
        transaction_id: holdForm.transactionId,
        hold_amount: parseFloat(holdForm.holdAmount),
        reason: holdForm.reason,
        expiry_hours: parseInt(holdForm.expiryHours, 10),
        external_reference: holdForm.externalReference
      };
      const res = await axios.post(`${API_BASE}/cbs/holds`, payload);
      setHoldResult({ success: true, data: res.data });
      showToast(`Funds reserved under Hold ${res.data.hold_id} for Tx ${holdForm.transactionId}!`, 'success');
      // Regenerate next transaction ID for convenience
      setHoldForm((prev) => ({
        ...prev,
        transactionId: 'TX-RES-' + Math.floor(Math.random() * 90000 + 10000)
      }));
      fetchBalance(activeAccount);
      fetchHolds(activeAccount);
    } catch (err) {
      setHoldResult({
        success: false,
        error: err.response?.data?.message || err.response?.data || err.message
      });
      showToast('Hold reservation failed: ' + (err.response?.data?.message || err.message), 'error');
    } finally {
      setIsHolding(false);
    }
  };

  // Capture Amount Hold (Settle Transfer into Destination Account)
  const handleCaptureHold = async (holdId, targetAcc, amount) => {
    setIsCapturing(true);
    setCaptureResult(null);
    try {
      const payload = {
        hold_id: holdId,
        target_account_id: targetAcc || 'ACC-1002',
        capture_amount: amount ? parseFloat(amount) : undefined,
        narrative: 'Settlement capture of reserved hold ' + holdId
      };
      const res = await axios.post(`${API_BASE}/cbs/holds/${holdId}/capture`, payload);
      setCaptureResult({ success: true, data: res.data });
      showToast(`Hold ${holdId} captured and settled into completed transfer!`, 'success');
      fetchBalance(activeAccount);
      fetchHolds(activeAccount);
    } catch (err) {
      setCaptureResult({
        success: false,
        error: err.response?.data?.message || err.response?.data || err.message
      });
      showToast('Capture failed: ' + (err.response?.data?.message || err.message), 'error');
    } finally {
      setIsCapturing(false);
    }
  };

  // Release / Cancel Amount Hold
  const handleReleaseHold = async (holdId) => {
    try {
      const res = await axios.delete(`${API_BASE}/cbs/holds/${holdId}`);
      showToast(`Hold ${holdId} cancelled! Funds restored to available balance.`, 'success');
      fetchBalance(activeAccount);
      fetchHolds(activeAccount);
    } catch (err) {
      showToast('Release failed: ' + (err.response?.data?.message || err.message), 'error');
    }
  };

  // Simulate Failed Transaction (DLQ Injection)
  const handleSimulateFailure = async (errorType, errorCode, cbState = 'OPEN') => {
    setIsSimulatingFailure(true);
    try {
      const txId = 'FAIL-TX-' + Math.floor(Math.random() * 90000 + 10000);
      const payload = {
        transactionId: txId,
        errorType: errorType,
        errorCode: errorCode,
        circuitBreakerState: cbState,
        payload: JSON.stringify({
          sourceAccountId: activeAccount,
          destinationAccountId: 'ACC-1002',
          amount: 5000.0,
          currency: 'PHP',
          reason: 'Failed transfer simulation'
        })
      };
      await axios.post(`${API_BASE}/cbs/audit/failed-transactions/simulate`, payload);
      showToast(`Simulated failure logged to DLQ: ${errorType} (${errorCode})`, 'info');
      fetchDlqIncidents();
    } catch (err) {
      showToast('Simulation failed: ' + (err.response?.data?.message || err.message), 'error');
    } finally {
      setIsSimulatingFailure(false);
    }
  };

  // Maker Reversal Request
  const handleRequestReversal = async (e) => {
    e.preventDefault();
    setIsReversing(true);
    setReversalResult(null);
    try {
      const payload = {
        originalTransactionId: reversalForm.originalTransactionId,
        makerId: reversalForm.makerId,
        reason: reversalForm.reason,
        notes: reversalForm.notes
      };
      const res = await axios.post(`${API_BASE}/cbs/reversals/request`, payload);
      setReversalResult({ success: true, step: 'REQUESTED', data: res.data });
      if (res.data?.ticketId) {
        setCheckerForm((prev) => ({ ...prev, ticketId: res.data.ticketId }));
      }
      showToast('Reversal ticket created! Waiting for Checker approval.', 'info');
    } catch (err) {
      setReversalResult({
        success: false,
        error: err.response?.data?.message || err.response?.data || err.message
      });
      showToast('Reversal request failed: ' + (err.response?.data?.message || err.message), 'error');
    } finally {
      setIsReversing(false);
    }
  };

  // Checker Approve Reversal
  const handleApproveReversal = async () => {
    setIsReversing(true);
    try {
      const payload = {
        reversalRequestId: checkerForm.ticketId,
        checkerId: checkerForm.checkerId,
        checkerNotes: checkerForm.checkerNotes
      };
      const res = await axios.post(`${API_BASE}/cbs/reversals/approve`, payload);
      setReversalResult({ success: true, step: 'APPROVED', data: res.data });
      showToast('Reversal APPROVED! Compensating GL entries posted and balances reversed.', 'success');
      fetchBalance(activeAccount);
    } catch (err) {
      setReversalResult({
        success: false,
        error: err.response?.data?.message || err.response?.data || err.message
      });
      showToast('Reversal approval failed: ' + (err.response?.data?.message || err.message), 'error');
    } finally {
      setIsReversing(false);
    }
  };

  // Checker Reject Reversal
  const handleRejectReversal = async () => {
    setIsReversing(true);
    try {
      const payload = {
        reversalRequestId: checkerForm.ticketId,
        checkerId: checkerForm.checkerId,
        checkerNotes: 'Rejected: ' + checkerForm.checkerNotes
      };
      const res = await axios.post(`${API_BASE}/cbs/reversals/reject`, payload);
      setReversalResult({ success: true, step: 'REJECTED', data: res.data });
      showToast('Reversal REJECTED! Transaction status restored to Posted.', 'info');
    } catch (err) {
      setReversalResult({
        success: false,
        error: err.response?.data?.message || err.response?.data || err.message
      });
      showToast('Reversal rejection failed: ' + (err.response?.data?.message || err.message), 'error');
    } finally {
      setIsReversing(false);
    }
  };

  // Execute Raw OFS Message
  const handleExecuteOfs = async () => {
    setIsExecutingOfs(true);
    setOfsResponse('');
    try {
      const res = await axios.post(`${API_BASE}/cbs/ofs`, ofsInput.trim(), {
        headers: { 'Content-Type': 'text/plain' }
      });
      setOfsResponse(res.data);
      showToast('OFS message processed by CBS wire parser!', 'success');
      fetchBalance(activeAccount);
      fetchHolds(activeAccount);
    } catch (err) {
      setOfsResponse(err.response?.data || err.message);
      showToast('OFS error returned by CBS', 'error');
    } finally {
      setIsExecutingOfs(false);
    }
  };

  // Execute COB Batch
  const handleRunCob = async () => {
    setIsExecutingCob(true);
    setCobResult(null);
    try {
      const res = await axios.post(`${API_BASE}/cbs/cob/run`);
      setCobResult({ success: true, data: res.data });
      showToast(`COB Batch finished! Rolled over to ${res.data.businessDate}`, 'success');
      fetchSystemDate();
      fetchBalance(activeAccount);
    } catch (err) {
      setCobResult({
        success: false,
        error: err.response?.data?.message || err.response?.data || err.message
      });
      showToast('COB Batch halted or failed', 'error');
    } finally {
      setIsExecutingCob(false);
    }
  };

  // Replay DLQ Incident
  const handleReplayDlq = async (transferId) => {
    try {
      const res = await axios.post(`${API_BASE}/compliance/dlq/replay/${transferId}`);
      setReplayResult(res.data);
      showToast(`DLQ transfer ${transferId} replayed successfully!`, 'success');
      fetchDlqIncidents();
    } catch (err) {
      showToast('DLQ replay failed: ' + (err.response?.data?.message || err.message), 'error');
    }
  };

  return (
    <div className="space-y-6">
      {/* Toast alert */}
      {toastMessage && (
        <div
          className={`fixed bottom-6 right-6 z-50 flex items-center gap-3 border px-4 py-3 shadow-xl backdrop-blur-md transition-all ${
            toastMessage.type === 'success'
              ? 'border-emerald-500/50 bg-emerald-950/80 text-emerald-200'
              : toastMessage.type === 'error'
              ? 'border-rose-500/50 bg-rose-950/80 text-rose-200'
              : 'border-cyan-500/50 bg-cyan-950/80 text-cyan-200'
          }`}
        >
          {toastMessage.type === 'success' ? (
            <CheckCircle2 className="h-5 w-5 text-emerald-400" />
          ) : toastMessage.type === 'error' ? (
            <XCircle className="h-5 w-5 text-rose-400" />
          ) : (
            <Activity className="h-5 w-5 text-cyan-400" />
          )}
          <span className="text-sm font-medium">{toastMessage.text}</span>
        </div>
      )}

      {/* Hero Header */}
      <div className="flex flex-col justify-between gap-4 border border-line bg-surface p-6 sm:flex-row sm:items-center">
        <div>
          <div className="flex items-center gap-2">
            <span className="inline-flex items-center gap-1.5 border border-cyan-500/30 bg-cyan-500/10 px-2 py-0.5 font-mono text-2xs uppercase tracking-wider text-cyan-400">
              <Database className="h-3 w-3" /> T24 CBS Core
            </span>
            <span className="inline-flex items-center gap-1 border border-emerald-500/30 bg-emerald-500/10 px-2 py-0.5 font-mono text-2xs uppercase tracking-wider text-emerald-400">
              <Activity className="h-3 w-3" /> Mock Runtime :8085
            </span>
          </div>
          <h1 className="mt-2 text-2xl font-bold tracking-tight text-fg">
            T24 Mock CBS Test Laboratory
          </h1>
          <p className="mt-1 text-sm text-fg-muted">
            Interactive control cockpit for validating Funds Transfers, Amount Holds (`AC.LOCKED.EVENTS`),
            Maker-Checker Reversals, Raw OFS wire strings, COB lifecycle, and DLQ replays.
          </p>
        </div>

        {/* Live CBS System State */}
        <div className="flex items-center gap-4 rounded border border-line bg-sunken p-3">
          <div>
            <div className="text-2xs uppercase tracking-wider text-fg-subtle">Business Date (T)</div>
            <div className="font-mono text-sm font-semibold text-fg">{systemDate.businessDate}</div>
          </div>
          <div className="h-8 w-px bg-line" />
          <div>
            <div className="text-2xs uppercase tracking-wider text-fg-subtle">Posting Window</div>
            <div className="flex items-center gap-1.5 font-mono text-sm font-semibold">
              <span
                className={`h-2 w-2 rounded-full ${
                  systemDate.postingWindowOpen ? 'bg-emerald-400 animate-pulse' : 'bg-rose-400'
                }`}
              />
              <span className={systemDate.postingWindowOpen ? 'text-emerald-400' : 'text-rose-400'}>
                {systemDate.status}
              </span>
            </div>
          </div>
          <button
            onClick={() => {
              fetchSystemDate();
              fetchBalance();
            }}
            className="rounded border border-line bg-surface p-2 text-fg-subtle hover:bg-surface-raised hover:text-fg"
            title="Refresh System Status"
          >
            <RefreshCw className={`h-4 w-4 ${isLoadingBalance ? 'animate-spin' : ''}`} />
          </button>
        </div>
      </div>

      {/* Account Balances Card with Live Inspector */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-4">
        {/* Account Selector */}
        <div className="border border-line bg-surface p-4">
          <label className="block text-2xs font-semibold uppercase tracking-wider text-fg-subtle">
            Select Test Account
          </label>
          <div className="mt-2 flex flex-col gap-1.5">
            {['ACC-1001', 'ACC-1002', '1000-2000-3001', 'ACC-LOW'].map((acc) => (
              <button
                key={acc}
                onClick={() => {
                  setActiveAccount(acc);
                  setTransferForm((prev) => ({ ...prev, sourceAccountId: acc }));
                  setHoldForm((prev) => ({ ...prev, accountId: acc }));
                }}
                className={`flex items-center justify-between px-3 py-2 text-left font-mono text-xs transition-colors ${
                  activeAccount === acc
                    ? 'border-l-2 border-accent bg-accent/10 font-medium text-accent-text'
                    : 'border-l-2 border-transparent bg-sunken text-fg-muted hover:bg-surface-raised'
                }`}
              >
                <span>{acc}</span>
                <span className="text-2xs text-fg-subtle">
                  {acc === 'ACC-1001' ? 'Primary' : acc === 'ACC-1002' ? 'Beneficiary' : 'Customer'}
                </span>
              </button>
            ))}
          </div>
        </div>

        {/* Working Balance (Ledger Master) */}
        <div className="border border-line bg-surface p-4">
          <div className="flex items-center justify-between text-2xs uppercase tracking-wider text-fg-subtle">
            <span>Working Balance</span>
            <Database className="h-3.5 w-3.5 text-blue-400" />
          </div>
          <div className="mt-2 font-mono text-xl font-bold text-fg">
            PHP {balanceData.balanceAmount.toLocaleString('en-US', { minimumFractionDigits: 2 })}
          </div>
          <p className="mt-1 text-2xs text-fg-subtle">Total authoritative ledger balance in `balance_master`</p>
        </div>

        {/* Amount Hold (Locked Events) */}
        <div className="border border-line bg-surface p-4">
          <div className="flex items-center justify-between text-2xs uppercase tracking-wider text-fg-subtle">
            <span>Locked / Held Funds</span>
            <Lock className="h-3.5 w-3.5 text-amber-400" />
          </div>
          <div className="mt-2 font-mono text-xl font-bold text-amber-400">
            PHP {balanceData.holdAmount.toLocaleString('en-US', { minimumFractionDigits: 2 })}
          </div>
          <p className="mt-1 text-2xs text-fg-subtle">
            {activeHolds.length} active hold(s) via `AC.LOCKED.EVENTS`
          </p>
        </div>

        {/* Spendable Available Balance */}
        <div className="border border-line bg-surface p-4">
          <div className="flex items-center justify-between text-2xs uppercase tracking-wider text-fg-subtle">
            <span>Available to Spend</span>
            <DollarSign className="h-3.5 w-3.5 text-emerald-400" />
          </div>
          <div className="mt-2 font-mono text-xl font-bold text-emerald-400">
            PHP {balanceData.availableBalance.toLocaleString('en-US', { minimumFractionDigits: 2 })}
          </div>
          <p className="mt-1 text-2xs text-fg-subtle">Solvency check: (Working - Hold). Protected from overdraft.</p>
        </div>
      </div>

      {/* Navigation Tabs */}
      <div className="flex flex-wrap gap-1 border-b border-line">
        {[
          { id: 'transfer', label: '1. Funds Transfer', icon: Send },
          { id: 'hold', label: '2. Amount Hold (AC.LOCKED.EVENTS)', icon: Lock },
          { id: 'reversal', label: '3. Reversal (Dual Control)', icon: RotateCcw },
          { id: 'ofs', label: '4. Raw Temenos OFS Terminal', icon: Terminal },
          { id: 'cob', label: '5. COB & EOD Lifecycle', icon: Moon },
          { id: 'dlq', label: '6. DLQ Incident Replays', icon: AlertTriangle }
        ].map((tab) => {
          const Icon = tab.icon;
          const active = activeTab === tab.id;
          return (
            <button
              key={tab.id}
              onClick={() => {
                setActiveTab(tab.id);
                if (tab.id === 'dlq') fetchDlqIncidents();
              }}
              className={`flex items-center gap-2 px-4 py-3 text-xs font-semibold uppercase tracking-wider transition-colors ${
                active
                  ? 'border-b-2 border-accent bg-surface text-accent-text'
                  : 'text-fg-subtle hover:bg-surface-raised hover:text-fg'
              }`}
            >
              <Icon className="h-4 w-4" />
              <span>{tab.label}</span>
            </button>
          );
        })}
      </div>

      {/* TAB 1: FUNDS TRANSFER */}
      {activeTab === 'transfer' && (
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
          <div className="border border-line bg-surface p-6">
            <h2 className="flex items-center gap-2 text-base font-semibold text-fg">
              <Send className="h-4 w-4 text-accent" /> Execute Funds Transfer (`FUNDS.TRANSFER`)
            </h2>
            <p className="mt-1 text-xs text-fg-muted">
              Sends an intra-bank transfer payload directly to CBS Core (`POST /api/v1/cbs/postings/transfer`).
              Enforces posting window cutoff, row lock ordering, and solvency check against available balance.
            </p>

            <form onSubmit={handleExecuteTransfer} className="mt-4 space-y-4">
              <div>
                <label className="block text-2xs font-semibold uppercase tracking-wider text-fg-subtle">
                  Debit / Source Account
                </label>
                <input
                  type="text"
                  value={transferForm.sourceAccountId}
                  onChange={(e) => setTransferForm({ ...transferForm, sourceAccountId: e.target.value })}
                  className="mt-1 w-full border border-line bg-sunken px-3 py-2 font-mono text-xs text-fg focus:border-accent focus:outline-none"
                  required
                />
              </div>

              <div>
                <label className="block text-2xs font-semibold uppercase tracking-wider text-fg-subtle">
                  Credit / Destination Account
                </label>
                <input
                  type="text"
                  value={transferForm.destinationAccountId}
                  onChange={(e) => setTransferForm({ ...transferForm, destinationAccountId: e.target.value })}
                  className="mt-1 w-full border border-line bg-sunken px-3 py-2 font-mono text-xs text-fg focus:border-accent focus:outline-none"
                  required
                />
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-2xs font-semibold uppercase tracking-wider text-fg-subtle">
                    Amount (PHP)
                  </label>
                  <input
                    type="number"
                    step="0.01"
                    value={transferForm.amount}
                    onChange={(e) => setTransferForm({ ...transferForm, amount: e.target.value })}
                    className="mt-1 w-full border border-line bg-sunken px-3 py-2 font-mono text-xs text-fg focus:border-accent focus:outline-none"
                    required
                  />
                </div>
                <div>
                  <label className="block text-2xs font-semibold uppercase tracking-wider text-fg-subtle">
                    Currency
                  </label>
                  <input
                    type="text"
                    value={transferForm.currency}
                    readOnly
                    className="mt-1 w-full border border-line bg-sunken px-3 py-2 font-mono text-xs text-fg-subtle"
                  />
                </div>
              </div>

              <div>
                <label className="block text-2xs font-semibold uppercase tracking-wider text-fg-subtle">
                  Description / Remarks
                </label>
                <input
                  type="text"
                  value={transferForm.description}
                  onChange={(e) => setTransferForm({ ...transferForm, description: e.target.value })}
                  className="mt-1 w-full border border-line bg-sunken px-3 py-2 text-xs text-fg focus:border-accent focus:outline-none"
                />
              </div>

              <button
                type="submit"
                disabled={isTransferring}
                className="flex w-full items-center justify-center gap-2 border border-accent bg-accent px-4 py-2.5 text-xs font-semibold uppercase tracking-wider text-fg-inverse hover:opacity-90 disabled:opacity-50"
              >
                {isTransferring ? <RefreshCw className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}
                Post to CBS Core
              </button>
            </form>
          </div>

          {/* Response Inspector */}
          <div className="border border-line bg-surface p-6">
            <h3 className="text-xs font-semibold uppercase tracking-wider text-fg-subtle">
              CBS Core Execution Trace
            </h3>
            {transferResult ? (
              <div className="mt-4 space-y-3">
                <div
                  className={`flex items-center gap-2 border p-3 ${
                    transferResult.success
                      ? 'border-emerald-500/30 bg-emerald-950/20 text-emerald-400'
                      : 'border-rose-500/30 bg-rose-950/20 text-rose-400'
                  }`}
                >
                  {transferResult.success ? <CheckCircle2 className="h-4 w-4" /> : <XCircle className="h-4 w-4" />}
                  <span className="font-mono text-xs font-semibold">
                    {transferResult.success ? 'HTTP 200 OK — POSTED' : 'TRANSFER REJECTED'}
                  </span>
                </div>

                <pre className="overflow-x-auto rounded border border-line bg-sunken p-4 font-mono text-2xs text-fg">
                  {JSON.stringify(transferResult, null, 2)}
                </pre>
              </div>
            ) : (
              <div className="mt-8 flex flex-col items-center justify-center text-center text-fg-subtle">
                <Send className="h-8 w-8 text-line" />
                <p className="mt-2 text-xs">Execute a transfer to inspect response payload and OFS return string.</p>
              </div>
            )}
          </div>
        </div>
      )}

      {/* TAB 2: AMOUNT HOLD & FUNDS RESERVATION (AC.LOCKED.EVENTS) */}
      {activeTab === 'hold' && (
        <div className="space-y-6">
          {/* Architecture Explanatory Banner */}
          <div className="border border-amber-500/30 bg-amber-950/20 p-4 text-xs text-amber-200">
            <div className="font-semibold uppercase tracking-wider text-amber-300">
              Two-Phase Transfer Lifecycle: Reserve &rarr; Capture / Release
            </div>
            <p className="mt-1 text-fg-subtle">
              In transaction orchestration, an Amount Hold is not just an isolated freeze&mdash;it is{' '}
              <strong className="text-amber-300">Phase 1 (Provisional Reservation)</strong> of a pending transfer.
              Available balance drops to prevent double-spending while fraud/maker-checker checks execute. Once verified,{' '}
              <strong className="text-emerald-400">Phase 2 (Capture & Settle)</strong> debits the source, credits the beneficiary, and clears the hold into a posted transfer.
            </p>
          </div>

          <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
            {/* Phase 1: Reserve Funds Form */}
            <div className="border border-line bg-surface p-6">
              <div className="flex items-center gap-2 border-b border-line pb-3">
                <span className="border border-amber-500/30 bg-amber-500/10 px-2 py-0.5 font-mono text-2xs uppercase text-amber-400">
                  Phase 1: Reserve
                </span>
                <h2 className="text-sm font-semibold text-fg">
                  Reserve Funds for Transfer (`RESERVED`)
                </h2>
              </div>

              <form onSubmit={handlePlaceHold} className="mt-4 space-y-4">
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="block text-2xs font-semibold uppercase tracking-wider text-fg-subtle">
                      Source Account
                    </label>
                    <input
                      type="text"
                      value={holdForm.accountId}
                      onChange={(e) => setHoldForm({ ...holdForm, accountId: e.target.value })}
                      className="mt-1 w-full border border-line bg-sunken px-3 py-2 font-mono text-xs text-fg focus:border-accent focus:outline-none"
                      required
                    />
                  </div>
                  <div>
                    <label className="block text-2xs font-semibold uppercase tracking-wider text-fg-subtle">
                      Beneficiary Account
                    </label>
                    <input
                      type="text"
                      value={holdForm.targetAccountId}
                      onChange={(e) => setHoldForm({ ...holdForm, targetAccountId: e.target.value })}
                      className="mt-1 w-full border border-line bg-sunken px-3 py-2 font-mono text-xs text-fg focus:border-accent focus:outline-none"
                      required
                    />
                  </div>
                </div>

                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="block text-2xs font-semibold uppercase tracking-wider text-fg-subtle">
                      Transaction Reference ID
                    </label>
                    <input
                      type="text"
                      value={holdForm.transactionId}
                      onChange={(e) => setHoldForm({ ...holdForm, transactionId: e.target.value })}
                      className="mt-1 w-full border border-line bg-sunken px-3 py-2 font-mono text-xs text-accent-text focus:border-accent focus:outline-none"
                      required
                    />
                  </div>
                  <div>
                    <label className="block text-2xs font-semibold uppercase tracking-wider text-fg-subtle">
                      Hold / Transfer Amount (PHP)
                    </label>
                    <input
                      type="number"
                      step="0.01"
                      value={holdForm.holdAmount}
                      onChange={(e) => setHoldForm({ ...holdForm, holdAmount: e.target.value })}
                      className="mt-1 w-full border border-line bg-sunken px-3 py-2 font-mono text-xs text-fg focus:border-accent focus:outline-none"
                      required
                    />
                  </div>
                </div>

                <div>
                  <label className="block text-2xs font-semibold uppercase tracking-wider text-fg-subtle">
                    Reservation Purpose / Reason
                  </label>
                  <select
                    value={holdForm.reason}
                    onChange={(e) => setHoldForm({ ...holdForm, reason: e.target.value })}
                    className="mt-1 w-full border border-line bg-sunken px-3 py-2 text-xs text-fg focus:border-accent focus:outline-none"
                  >
                    <option value="PRE_AUTHORIZATION">PRE_AUTHORIZATION (Payment / Wire Reservation)</option>
                    <option value="MAKER_CHECKER_HOLD">MAKER_CHECKER_HOLD (Pending Dual Authorization)</option>
                    <option value="CARD_PREAUTH">CARD_PREAUTH (Merchant Terminal Reservation)</option>
                    <option value="COURT_ORDER_FREEZE">COURT_ORDER_FREEZE (Legal Restraint)</option>
                    <option value="AML_INVESTIGATION">AML_INVESTIGATION (Suspicious Transaction Review)</option>
                  </select>
                </div>

                <button
                  type="submit"
                  disabled={isHolding}
                  className="flex w-full items-center justify-center gap-2 border border-amber-500/50 bg-amber-500/20 px-4 py-2.5 text-xs font-semibold uppercase tracking-wider text-amber-300 hover:bg-amber-500/30 disabled:opacity-50"
                >
                  {isHolding ? <RefreshCw className="h-4 w-4 animate-spin" /> : <Lock className="h-4 w-4" />}
                  Step 1: Reserve Funds via AC.LOCKED.EVENTS
                </button>
              </form>
            </div>

            {/* Phase 2: Active Reservations & Settlement Actions */}
            <div className="border border-line bg-surface p-6">
              <div className="flex items-center justify-between border-b border-line pb-3">
                <div className="flex items-center gap-2">
                  <span className="border border-emerald-500/30 bg-emerald-500/10 px-2 py-0.5 font-mono text-2xs uppercase text-emerald-400">
                    Phase 2: Capture / Cancel
                  </span>
                  <h3 className="text-sm font-semibold text-fg">Active Holds on `{activeAccount}`</h3>
                </div>
                <button
                  onClick={() => fetchHolds(activeAccount)}
                  className="text-2xs text-accent-text hover:underline"
                >
                  Refresh Holds
                </button>
              </div>

              {activeHolds.length > 0 ? (
                <div className="mt-4 space-y-3">
                  {activeHolds.map((h) => {
                    const ext = h.externalReference || '';
                    const linkedTx = ext.includes('|') ? ext.split('|')[0] : ext;
                    const beneficiaryAcc = ext.includes('|') ? ext.split('|')[1] : 'ACC-1002';

                    return (
                      <div key={h.holdId} className="border border-line bg-sunken p-3">
                        <div className="flex items-start justify-between">
                          <div>
                            <div className="flex items-center gap-2 font-mono text-xs font-semibold text-fg">
                              <span className="text-amber-400">{h.holdId}</span>
                              <span className="border border-line bg-surface px-1.5 py-0.5 text-2xs text-fg-subtle">
                                {h.t24LockReference}
                              </span>
                              <span className={`px-1.5 py-0.5 text-2xs font-mono uppercase ${
                                h.status === 'ACTIVE'
                                  ? 'bg-amber-500/20 text-amber-300 border border-amber-500/30'
                                  : h.status === 'CAPTURED'
                                  ? 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/30'
                                  : 'bg-zinc-800 text-fg-subtle'
                              }`}>
                                {h.status}
                              </span>
                            </div>

                            <div className="mt-1 font-mono text-2xs text-fg-muted">
                              Reserved: <strong>PHP {parseFloat(h.holdAmount).toLocaleString('en-US', { minimumFractionDigits: 2 })}</strong>
                              {' '}&bull; Linked Tx: <span className="text-accent-text">{linkedTx || 'N/A'}</span>
                              {' '}&bull; Beneficiary: <span className="text-purple-300">{beneficiaryAcc}</span>
                            </div>
                            <div className="text-2xs text-fg-subtle mt-0.5">Reason: {h.reason}</div>
                          </div>
                        </div>

                        {h.status === 'ACTIVE' && (
                          <div className="mt-3 flex items-center justify-end gap-2 border-t border-line/50 pt-2">
                            <button
                              onClick={() => handleReleaseHold(h.holdId)}
                              className="flex items-center gap-1 border border-rose-500/30 bg-rose-950/20 px-2.5 py-1 text-2xs font-semibold text-rose-300 hover:bg-rose-900/40"
                              title="Cancel hold and restore customer available balance"
                            >
                              <Unlock className="h-3 w-3" /> Cancel & Void
                            </button>
                            <button
                              onClick={() => handleCaptureHold(h.holdId, beneficiaryAcc, h.holdAmount)}
                              disabled={isCapturing}
                              className="flex items-center gap-1 border border-emerald-500/40 bg-emerald-950/30 px-3 py-1 text-2xs font-semibold text-emerald-300 hover:bg-emerald-900/50"
                              title="Debit source, credit beneficiary, clear hold, and mark transfer POSTED"
                            >
                              {isCapturing ? <RefreshCw className="h-3 w-3 animate-spin" /> : <CheckCircle2 className="h-3 w-3" />}
                              Capture & Settle Transfer
                            </button>
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              ) : (
                <div className="mt-8 flex flex-col items-center justify-center text-center text-fg-subtle">
                  <Unlock className="h-8 w-8 text-line" />
                  <p className="mt-2 text-xs">No active holds on this account. Funds are 100% available.</p>
                </div>
              )}

              {/* Settlement / Capture Trace Inspector */}
              {captureResult && (
                <div className="mt-4 rounded border border-emerald-500/30 bg-emerald-950/20 p-3">
                  <div className="flex items-center justify-between text-2xs uppercase tracking-wider text-emerald-400">
                    <span>Capture Execution Result</span>
                    <span className="font-mono">HTTP 200 OK &mdash; POSTED</span>
                  </div>
                  <pre className="mt-2 overflow-x-auto font-mono text-2xs text-emerald-300">
                    {JSON.stringify(captureResult.data, null, 2)}
                  </pre>
                </div>
              )}

              {holdResult && !captureResult && (
                <div className="mt-4 rounded border border-amber-500/30 bg-amber-950/20 p-3">
                  <div className="text-2xs uppercase tracking-wider text-amber-400">
                    Provisional Reservation Confirmed
                  </div>
                  <pre className="mt-2 overflow-x-auto font-mono text-2xs text-amber-300">
                    {JSON.stringify(holdResult.data, null, 2)}
                  </pre>
                </div>
              )}
            </div>
          </div>
        </div>
      )}


      {/* TAB 3: TRANSACTION REVERSAL (DUAL ENDPOINT 2 & MAKER-CHECKER) */}
      {activeTab === 'reversal' && (
        <div className="space-y-6">
          {/* Dual-Endpoint 2 Saga Compensation Quick Trigger */}
          <div className="border border-purple-500/30 bg-purple-950/20 p-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="border border-purple-400/40 bg-purple-500/20 px-2 py-0.5 font-mono text-2xs uppercase text-purple-300">
                  Dual Architecture Endpoint 2
                </span>
                <h3 className="text-sm font-semibold text-fg">Automated Saga Compensating Reversal (`POST /t24/reversal`)</h3>
              </div>
              <span className="font-mono text-2xs text-fg-subtle">ACID Rollback</span>
            </div>
            <p className="mt-1 text-xs text-fg-muted">
              Instantly reverses transaction balances in the Oracle Master DB and commits audit records to the PostgreSQL Audit Vault without requiring manual teller maker-checker approval. Invoked by Saga Coordinator on downstream pipeline failure.
            </p>
            <div className="mt-4 flex flex-wrap items-center gap-3">
              <input
                type="text"
                placeholder="Transaction ID to reverse (e.g. FT123456 or TXN-...)"
                value={reversalForm.originalTransactionId}
                onChange={(e) => setReversalForm({ ...reversalForm, originalTransactionId: e.target.value })}
                className="flex-1 min-w-[240px] border border-line bg-sunken px-3 py-2 font-mono text-xs text-fg focus:border-accent focus:outline-none"
              />
              <button
                type="button"
                onClick={handleCompensatingReversal}
                disabled={isReversing}
                className="flex items-center gap-2 border border-purple-500/60 bg-purple-600/30 px-4 py-2 font-mono text-xs font-semibold uppercase text-purple-200 hover:bg-purple-600/40 disabled:opacity-50"
              >
                {isReversing ? <RefreshCw className="h-4 w-4 animate-spin" /> : <RotateCcw className="h-4 w-4" />}
                Execute EP2 Compensating Reversal
              </button>
            </div>
          </div>

          <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
            {/* Maker Dispute Request */}
            <div className="border border-line bg-surface p-6">
              <div className="flex items-center gap-2 border-b border-line pb-3">
                <span className="border border-blue-500/30 bg-blue-500/10 px-2 py-0.5 font-mono text-2xs uppercase text-blue-400">
                  Step 1: Maker Role
                </span>
                <h2 className="text-sm font-semibold text-fg">Initiate Dispute Reversal Ticket</h2>
              </div>

            <form onSubmit={handleRequestReversal} className="mt-4 space-y-4">
              <div>
                <label className="block text-2xs font-semibold uppercase tracking-wider text-fg-subtle">
                  Original Transaction ID to Reverse
                </label>
                <input
                  type="text"
                  placeholder="e.g. TX-123456"
                  value={reversalForm.originalTransactionId}
                  onChange={(e) => setReversalForm({ ...reversalForm, originalTransactionId: e.target.value })}
                  className="mt-1 w-full border border-line bg-sunken px-3 py-2 font-mono text-xs text-fg focus:border-accent focus:outline-none"
                  required
                />
              </div>

              <div>
                <label className="block text-2xs font-semibold uppercase tracking-wider text-fg-subtle">
                  Maker ID (Operator)
                </label>
                <input
                  type="text"
                  value={reversalForm.makerId}
                  onChange={(e) => setReversalForm({ ...reversalForm, makerId: e.target.value })}
                  className="mt-1 w-full border border-line bg-sunken px-3 py-2 font-mono text-xs text-fg focus:border-accent focus:outline-none"
                  required
                />
              </div>

              <div>
                <label className="block text-2xs font-semibold uppercase tracking-wider text-fg-subtle">
                  Dispute Reason
                </label>
                <select
                  value={reversalForm.reason}
                  onChange={(e) => setReversalForm({ ...reversalForm, reason: e.target.value })}
                  className="mt-1 w-full border border-line bg-sunken px-3 py-2 text-xs text-fg focus:border-accent focus:outline-none"
                >
                  <option value="CUSTOMER_DISPUTE">CUSTOMER_DISPUTE (Customer Filed Unauthorized Debit)</option>
                  <option value="OPERATIONAL_ERROR">OPERATIONAL_ERROR (Duplicate Operator Posting)</option>
                  <option value="FRAUD_INVESTIGATION">FRAUD_INVESTIGATION (Compromised Destination Account)</option>
                </select>
              </div>

              <div>
                <label className="block text-2xs font-semibold uppercase tracking-wider text-fg-subtle">
                  Maker Notes
                </label>
                <textarea
                  rows="2"
                  value={reversalForm.notes}
                  onChange={(e) => setReversalForm({ ...reversalForm, notes: e.target.value })}
                  className="mt-1 w-full border border-line bg-sunken px-3 py-2 text-xs text-fg focus:border-accent focus:outline-none"
                />
              </div>

              <button
                type="submit"
                disabled={isReversing}
                className="flex w-full items-center justify-center gap-2 border border-blue-500/50 bg-blue-500/20 px-4 py-2.5 text-xs font-semibold uppercase tracking-wider text-blue-300 hover:bg-blue-500/30 disabled:opacity-50"
              >
                {isReversing ? <RefreshCw className="h-4 w-4 animate-spin" /> : <RotateCcw className="h-4 w-4" />}
                File Reversal Dispute Ticket
              </button>
            </form>
          </div>

          {/* Checker Authorization */}
          <div className="border border-line bg-surface p-6">
            <div className="flex items-center gap-2 border-b border-line pb-3">
              <span className="border border-emerald-500/30 bg-emerald-500/10 px-2 py-0.5 font-mono text-2xs uppercase text-emerald-400">
                Step 2: Checker Role
              </span>
              <h2 className="text-sm font-semibold text-fg">Dual-Control Approval (Segregation of Duties)</h2>
            </div>

            <div className="mt-4 space-y-4">
              <div>
                <label className="block text-2xs font-semibold uppercase tracking-wider text-fg-subtle">
                  Dispute Ticket ID
                </label>
                <input
                  type="text"
                  placeholder="Ticket ID from Step 1"
                  value={checkerForm.ticketId}
                  onChange={(e) => setCheckerForm({ ...checkerForm, ticketId: e.target.value })}
                  className="mt-1 w-full border border-line bg-sunken px-3 py-2 font-mono text-xs text-fg focus:border-accent focus:outline-none"
                />
              </div>

              <div>
                <label className="block text-2xs font-semibold uppercase tracking-wider text-fg-subtle">
                  Checker ID (Must Differ from Maker)
                </label>
                <input
                  type="text"
                  value={checkerForm.checkerId}
                  onChange={(e) => setCheckerForm({ ...checkerForm, checkerId: e.target.value })}
                  className="mt-1 w-full border border-line bg-sunken px-3 py-2 font-mono text-xs text-fg focus:border-accent focus:outline-none"
                />
                <p className="mt-1 text-2xs text-amber-400">
                  Dual Control Law: If Checker == `{reversalForm.makerId}`, CBS will reject with error.
                </p>
              </div>

              <div>
                <label className="block text-2xs font-semibold uppercase tracking-wider text-fg-subtle">
                  Checker Authorization Notes
                </label>
                <input
                  type="text"
                  value={checkerForm.checkerNotes}
                  onChange={(e) => setCheckerForm({ ...checkerForm, checkerNotes: e.target.value })}
                  className="mt-1 w-full border border-line bg-sunken px-3 py-2 text-xs text-fg focus:border-accent focus:outline-none"
                />
              </div>

              <div className="grid grid-cols-2 gap-3 pt-2">
                <button
                  type="button"
                  onClick={handleApproveReversal}
                  disabled={isReversing || !checkerForm.ticketId}
                  className="flex items-center justify-center gap-2 border border-emerald-500/50 bg-emerald-500/20 px-4 py-2.5 text-xs font-semibold uppercase tracking-wider text-emerald-300 hover:bg-emerald-500/30 disabled:opacity-50"
                >
                  <CheckCircle2 className="h-4 w-4" /> Approve Reversal
                </button>

                <button
                  type="button"
                  onClick={handleRejectReversal}
                  disabled={isReversing || !checkerForm.ticketId}
                  className="flex items-center justify-center gap-2 border border-rose-500/50 bg-rose-500/20 px-4 py-2.5 text-xs font-semibold uppercase tracking-wider text-rose-300 hover:bg-rose-500/30 disabled:opacity-50"
                >
                  <XCircle className="h-4 w-4" /> Reject Ticket
                </button>
              </div>
            </div>

            {/* Reversal Result Inspector */}
            {reversalResult && (
              <div className="mt-4 space-y-2 rounded border border-line bg-sunken p-3">
                <div className="font-mono text-2xs font-semibold text-fg">
                  Result Step: {reversalResult.step || 'Error'}
                </div>
                <pre className="overflow-x-auto text-2xs text-fg-muted">
                  {JSON.stringify(reversalResult, null, 2)}
                </pre>
              </div>
            )}
          </div>
        </div>
      </div>
      )}

      {/* TAB 4: RAW TEMENOS OFS PROTOCOL TERMINAL */}
      {activeTab === 'ofs' && (
        <div className="space-y-4 border border-line bg-surface p-6">
          <div className="flex flex-col justify-between gap-2 sm:flex-row sm:items-center">
            <div>
              <h2 className="flex items-center gap-2 text-base font-semibold text-fg">
                <Terminal className="h-4 w-4 text-accent" /> Temenos Open Financial Services (OFS) Wire Playground
              </h2>
              <p className="mt-1 text-xs text-fg-muted">
                Direct wire protocol endpoint (`POST /api/v1/cbs/ofs` with `Content-Type: text/plain`).
              </p>
            </div>

            {/* Template Presets */}
            <div className="flex flex-wrap gap-1.5">
              {[
                {
                  label: 'FT INITIATE',
                  cmd: 'FUNDS.TRANSFER,INITIATE/I/PROCESS//TX-9901,USER01/123456,TRANSACTION.TYPE=AC,DEBIT.ACCT.NO=ACC-1001,CREDIT.ACCT.NO=ACC-1002,AMOUNT=2500.00,CURRENCY=PHP,VALUE.DATE=20261008'
                },
                {
                  label: 'FT REVERSAL',
                  cmd: 'FUNDS.TRANSFER,REVERSAL/I/PROCESS//REV-01,MGR02/123456,ORIGINAL.FT.NO=TX-9901'
                },
                {
                  label: 'HOLD INPUT',
                  cmd: 'AC.LOCKED.EVENTS,INPUT/I/PROCESS/0/1,USER01/123456,,ACCOUNT.NUMBER=ACC-1001,FROM.DATE=20261008,TO.DATE=20261009,LOCKED.AMOUNT=4000.00,HOLD.REASON=MAKER_CHECKER_HOLD,EXT.REF=OFS-HLD'
                },
                {
                  label: 'HOLD REVERSE',
                  cmd: 'AC.LOCKED.EVENTS,REVERSE/I/PROCESS/0/1,USER01/123456,,HOLD.REF=HLD-DEMO,ACCOUNT.NUMBER=ACC-1001'
                },
                {
                  label: 'ENQUIRY',
                  cmd: 'ENQUIRY.SELECT,,USER01/123456,ACCOUNT.NUMBER:EQ=ACC-1001'
                }
              ].map((tmpl) => (
                <button
                  key={tmpl.label}
                  type="button"
                  onClick={() => setOfsInput(tmpl.cmd)}
                  className="rounded border border-line bg-sunken px-2 py-1 font-mono text-2xs text-fg-subtle hover:bg-surface-raised hover:text-fg"
                >
                  {tmpl.label}
                </button>
              ))}
            </div>
          </div>

          <div className="mt-4">
            <label className="block text-2xs font-semibold uppercase tracking-wider text-fg-subtle">
              OFS Wire Payload String
            </label>
            <textarea
              rows="4"
              value={ofsInput}
              onChange={(e) => setOfsInput(e.target.value)}
              className="mt-1 w-full border border-line bg-sunken p-3 font-mono text-xs text-accent-text focus:border-accent focus:outline-none"
            />
          </div>

          <div className="flex justify-end gap-3">
            <button
              onClick={() => copyToClipboard(ofsInput)}
              className="flex items-center gap-1.5 border border-line bg-sunken px-3 py-2 text-xs font-semibold text-fg-subtle hover:bg-surface-raised"
            >
              {copiedText ? <Check className="h-3.5 w-3.5 text-emerald-400" /> : <Copy className="h-3.5 w-3.5" />}
              Copy Wire String
            </button>
            <button
              onClick={handleExecuteOfs}
              disabled={isExecutingOfs}
              className="flex items-center gap-2 border border-accent bg-accent px-5 py-2 text-xs font-semibold uppercase tracking-wider text-fg-inverse hover:opacity-90 disabled:opacity-50"
            >
              {isExecutingOfs ? <RefreshCw className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
              Transmit OFS Payload
            </button>
          </div>

          {/* Response Terminal */}
          {ofsResponse && (
            <div className="mt-4 rounded border border-line bg-sunken p-4">
              <div className="flex items-center justify-between text-2xs uppercase tracking-wider text-fg-subtle">
                <span>Temenos Wire Protocol Response</span>
                <span className="font-mono text-emerald-400">HTTP 200 OK</span>
              </div>
              <pre className="mt-2 overflow-x-auto font-mono text-xs text-emerald-400">
                {ofsResponse}
              </pre>
            </div>
          )}
        </div>
      )}

      {/* TAB 5: COB & EOD LIFECYCLE */}
      {activeTab === 'cob' && (
        <div className="space-y-6 border border-line bg-surface p-6">
          <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
            <div>
              <h2 className="flex items-center gap-2 text-base font-semibold text-fg">
                <Moon className="h-4 w-4 text-purple-400" /> Close of Business (COB) & EOD Batch Simulation
              </h2>
              <p className="mt-1 text-xs text-fg-muted">
                Executes the 5-phase COB sequence: Cutoff &rarr; Zero-overdraft fee collection &rarr; BIR 20%
                tax withholding & interest accrual &rarr; EOD snapshot & GL reconciliation &rarr; Rollover to T+1.
              </p>
            </div>

            <button
              onClick={handleRunCob}
              disabled={isExecutingCob}
              className="flex items-center gap-2 border border-purple-500/50 bg-purple-500/20 px-5 py-2.5 text-xs font-semibold uppercase tracking-wider text-purple-300 hover:bg-purple-500/30 disabled:opacity-50"
            >
              {isExecutingCob ? <RefreshCw className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}
              Run Full COB Sequence
            </button>
          </div>

          {/* Phase progression cards */}
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-5">
            {[
              { phase: 'Phase 0', title: 'Cutoff Posting Window', desc: 'Closes window, sets EOD_CUTOFF' },
              { phase: 'Phase 1', title: 'Zero-Overdraft Fees', desc: 'Accounts < 5000 ADB, arrears safe' },
              { phase: 'Phase 2', title: 'Interest & BIR 20% Tax', desc: 'Daily accrual & 20% tax withhold' },
              { phase: 'Phase 3', title: 'GL Reconciliation', desc: 'Sum debits == credits check' },
              { phase: 'Phase 4', title: 'Rollover to T+1', desc: 'Advances system date to next day' }
            ].map((p, idx) => (
              <div key={p.phase} className="border border-line bg-sunken p-3">
                <div className="font-mono text-2xs text-purple-400">{p.phase}</div>
                <div className="mt-1 text-xs font-semibold text-fg">{p.title}</div>
                <div className="mt-1 text-2xs text-fg-subtle">{p.desc}</div>
              </div>
            ))}
          </div>

          {cobResult && (
            <div className="rounded border border-line bg-sunken p-4">
              <div className="font-mono text-xs font-semibold text-purple-400">
                COB Execution Summary
              </div>
              <pre className="mt-2 overflow-x-auto font-mono text-2xs text-fg">
                {JSON.stringify(cobResult, null, 2)}
              </pre>
            </div>
          )}

          {/* Azurite Reports Link */}
          <div className="flex flex-col sm:flex-row items-center justify-between gap-3 border-t border-line/60 pt-4">
            <span className="text-xs text-fg-muted">
              Generated BIR 2306 tax certificates, customer statements, and GL reconciliation reports are saved in Azurite Blob Storage:
            </span>
            <a
              href="/azurite-drive"
              className="flex items-center gap-2 border border-purple-500/40 bg-purple-950/20 px-3 py-1.5 text-xs font-semibold text-purple-300 hover:bg-purple-900/40"
            >
              <FileText className="h-4 w-4" /> Open Azurite Drive Storage
            </a>
          </div>
        </div>
      )}

      {/* TAB 6: DLQ & CIRCUIT BREAKER REPLAYS */}
      {activeTab === 'dlq' && (
        <div className="space-y-4 border border-line bg-surface p-6">
          <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
            <div>
              <h2 className="flex items-center gap-2 text-base font-semibold text-fg">
                <AlertTriangle className="h-4 w-4 text-rose-400" /> Dead Letter Queue (DLQ) Incident Replays &amp; Failed Transactions
              </h2>
              <p className="mt-1 text-xs text-fg-muted">
                Transactions queued to `banking.transfers.dlq` when CBS is unavailable, times out, or trips the circuit breaker.
              </p>
            </div>
            <button
              onClick={fetchDlqIncidents}
              className="flex items-center gap-1.5 border border-line bg-sunken px-3 py-1.5 text-xs text-fg-subtle hover:bg-surface-raised hover:text-fg"
            >
              <RefreshCw className={`h-3.5 w-3.5 ${isLoadingDlq ? 'animate-spin' : ''}`} /> Refresh Incidents
            </button>
          </div>

          {/* Failure Injection & Simulation Bar */}
          <div className="border border-line bg-sunken p-4 space-y-2">
            <div className="text-2xs font-semibold uppercase tracking-wider text-rose-400">
              ⚡ Failure Injection &amp; Telemetry Testing
            </div>
            <p className="text-2xs text-fg-muted">
              Inject simulated failure modes into the core ledger to observe circuit breaker trips, retry telemetry, and manual remediation:
            </p>
            <div className="flex flex-wrap gap-2 pt-1">
              <button
                type="button"
                onClick={() => handleSimulateFailure('NETWORK_TIMEOUT', 'HTTP_504', 'OPEN')}
                disabled={isSimulatingFailure}
                className="flex items-center gap-1.5 border border-rose-500/40 bg-rose-950/20 px-3 py-1.5 text-2xs font-semibold text-rose-300 hover:bg-rose-900/40 disabled:opacity-50"
              >
                <AlertTriangle className="h-3.5 w-3.5" /> Simulate Network Timeout (HTTP 504 / Circuit Breaker OPEN)
              </button>
              <button
                type="button"
                onClick={() => handleSimulateFailure('CBS_CUTOFF_REJECTION', 'EOD_CUTOFF_IN_PROGRESS', 'HALF_OPEN')}
                disabled={isSimulatingFailure}
                className="flex items-center gap-1.5 border border-amber-500/40 bg-amber-950/20 px-3 py-1.5 text-2xs font-semibold text-amber-300 hover:bg-amber-900/40 disabled:opacity-50"
              >
                <Moon className="h-3.5 w-3.5" /> Simulate Posting Cutoff Rejection (EOD_CUTOFF)
              </button>
              <button
                type="button"
                onClick={() => handleSimulateFailure('CORE_DOWN_503', 'HTTP_503', 'OPEN')}
                disabled={isSimulatingFailure}
                className="flex items-center gap-1.5 border border-purple-500/40 bg-purple-950/20 px-3 py-1.5 text-2xs font-semibold text-purple-300 hover:bg-purple-900/40 disabled:opacity-50"
              >
                <RotateCcw className="h-3.5 w-3.5" /> Simulate CBS Core Down (HTTP 503 / Trip Circuit Breaker)
              </button>
            </div>
          </div>

          {dlqIncidents.length > 0 ? (
            <div className="overflow-x-auto border border-line">
              <table className="w-full text-left font-mono text-xs">
                <thead className="bg-sunken text-2xs uppercase text-fg-subtle">
                  <tr>
                    <th className="p-3">Incident / Transfer ID</th>
                    <th className="p-3">Error / Reason</th>
                    <th className="p-3">Circuit Breaker</th>
                    <th className="p-3">Timestamp</th>
                    <th className="p-3 text-right">Action</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line bg-surface text-fg">
                  {dlqIncidents.map((inc) => (
                    <tr key={inc.incidentId || inc.transactionId} className="hover:bg-sunken">
                      <td className="p-3 font-semibold">{inc.transactionId || inc.incidentId}</td>
                      <td className="p-3 text-2xs text-rose-400">{inc.errorType || 'CBS_TIMEOUT'}</td>
                      <td className="p-3">
                        <span className="border border-rose-500/30 bg-rose-500/10 px-1.5 py-0.5 text-2xs text-rose-400">
                          {inc.circuitBreakerState || 'OPEN'}
                        </span>
                      </td>
                      <td className="p-3 text-2xs text-fg-subtle">{inc.failureTimestampUtc || 'Just now'}</td>
                      <td className="p-3 text-right">
                        <button
                          onClick={() => handleReplayDlq(inc.transactionId || inc.incidentId)}
                          className="border border-accent bg-accent/10 px-2.5 py-1 text-2xs font-semibold uppercase text-accent-text hover:bg-accent/20"
                        >
                          Replay
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="flex flex-col items-center justify-center p-8 text-center text-fg-subtle">
              <CheckCircle2 className="h-8 w-8 text-emerald-400" />
              <p className="mt-2 text-xs">No pending DLQ incidents. Circuit breaker is CLOSED and healthy.</p>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
