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

const parseOfsResponse = (text) => {
  if (!text || typeof text !== 'string') return {};
  const map = {};
  const parts = text.split(',');
  for (const part of parts) {
    const eqIdx = part.indexOf('=');
    if (eqIdx !== -1) {
      let key = part.slice(0, eqIdx).trim();
      key = key.replace(/:\d+:\d+$/, '');
      const val = part.slice(eqIdx + 1).trim();
      map[key] = val;
    }
  }
  return map;
};

export default function T24TestConsole() {
  const [activeTab, setActiveTab] = useState('transfer'); // transfer | hold | reversal | ofs | cob | dlq
  const [activeAccount, setActiveAccount] = useState('acc-2002-chk-001');
  const [balanceData, setBalanceData] = useState({
    accountId: 'acc-2002-chk-001',
    balanceAmount: 8500000.0,
    holdAmount: 0.0,
    availableBalance: 8500000.0
  });
  const [systemDate, setSystemDate] = useState({
    businessDate: '2026-10-09',
    status: 'ONLINE',
    postingWindowOpen: true
  });
  const [isLoadingBalance, setIsLoadingBalance] = useState(false);
  const [toastMessage, setToastMessage] = useState(null);
  const [copiedText, setCopiedText] = useState(false);

  // Transfer State
  const [transferForm, setTransferForm] = useState({
    sourceAccountId: 'acc-2002-chk-001',
    destinationAccountId: 'acc-2003-sav-002',
    amount: '5000.00',
    currency: 'PHP',
    description: 'Test Transfer via CBS Console'
  });
  const [transferResult, setTransferResult] = useState(null);
  const [isTransferring, setIsTransferring] = useState(false);

  // Amount Hold & Reservation State
  const [holdForm, setHoldForm] = useState({
    accountId: 'acc-2002-chk-001',
    targetAccountId: 'acc-2003-sav-002',
    transactionId: 'TX-RES-' + Math.floor(Math.random() * 90000 + 10000),
    holdAmount: '1000.00',
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
    'FUNDS.TRANSFER,INITIATE/I/PROCESS//TX-9901,USER01/123456,TRANSACTION.TYPE=AC,DEBIT.ACCT.NO=acc-2002-chk-001,CREDIT.ACCT.NO=acc-2003-sav-002,AMOUNT=2500.00,CURRENCY=PHP,VALUE.DATE=20261008'
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
      let bal = 0, hld = 0, avail = 0;
      if (typeof res.data === 'string') {
        const ofs = parseOfsResponse(res.data);
        bal = parseFloat(ofs['CURRENT.BALANCE'] || ofs['WORKING.BALANCE'] || 0);
        hld = parseFloat(ofs['HOLD.AMOUNT'] || ofs['LOCKED.AMOUNT'] || 0);
        avail = parseFloat(ofs['AVAILABLE.BALANCE'] !== undefined ? ofs['AVAILABLE.BALANCE'] : (bal - hld));
      } else if (res.data) {
        bal = parseFloat(res.data.balanceAmount ?? res.data.currentBalance ?? 0);
        hld = parseFloat(res.data.holdAmount ?? 0);
        avail = parseFloat(res.data.availableBalance ?? (bal - hld));
      }
      setBalanceData({
        accountId: accId,
        balanceAmount: isNaN(bal) ? 0 : bal,
        holdAmount: isNaN(hld) ? 0 : hld,
        availableBalance: isNaN(avail) ? 0 : avail
      });
    } catch (e) {
      // Fallback enquiry via OFS or simulated state
      try {
        const ofsRes = await axios.post(`${API_BASE}/cbs/ofs`, `ENQUIRY.SELECT,,USER01/123456,ACCOUNT.NUMBER:EQ=${accId}`, {
          headers: { 'Content-Type': 'text/plain' }
        });
        const ofs = parseOfsResponse(ofsRes.data);
        const bal = parseFloat(ofs['CURRENT.BALANCE'] || ofs['WORKING.BALANCE'] || 0);
        const hld = parseFloat(ofs['HOLD.AMOUNT'] || ofs['LOCKED.AMOUNT'] || 0);
        const avail = parseFloat(ofs['AVAILABLE.BALANCE'] !== undefined ? ofs['AVAILABLE.BALANCE'] : (bal - hld));
        setBalanceData({
          accountId: accId,
          balanceAmount: isNaN(bal) ? 0 : bal,
          holdAmount: isNaN(hld) ? 0 : hld,
          availableBalance: isNaN(avail) ? 0 : avail
        });
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
      } else {
        setActiveHolds([]);
      }
    } catch {
      setActiveHolds([]);
    }
  };

  // Fetch system date & COB status
  const fetchSystemDate = async () => {
    try {
      const res = await axios.get(`${API_BASE}/cbs/system-date`);
      if (typeof res.data === 'string') {
        const ofs = parseOfsResponse(res.data);
        setSystemDate({
          businessDate: ofs['BUSINESS.DATE'] || '2026-10-09',
          status: ofs['STATUS'] || 'ONLINE',
          postingWindowOpen: ofs['POSTING.WINDOW'] === 'OPEN' || ofs['POSTING.WINDOW.OPEN'] === 'true'
        });
      } else if (res.data) {
        setSystemDate({
          businessDate: res.data.businessDate || '2026-10-09',
          status: res.data.status || 'ONLINE',
          postingWindowOpen: res.data.postingWindowOpen ?? true
        });
      }
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
      let parsed = res.data;
      if (typeof res.data === 'string') {
        parsed = parseOfsResponse(res.data);
      }
      setCobResult({ success: true, data: parsed });
      const nextDate = parsed.businessDate || parsed['BUSINESS.DATE'] || 'T+1';
      showToast(`COB Batch finished! Rolled over to ${nextDate}`, 'success');
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
          className={`fixed bottom-6 right-6 z-50 flex items-center gap-3 rounded-2xl border px-5 py-3.5 shadow-2xl backdrop-blur-md transition-all ${
            toastMessage.type === 'success'
              ? 'border-emerald-500/40 bg-emerald-950/90 text-emerald-100'
              : toastMessage.type === 'error'
              ? 'border-rose-500/40 bg-rose-950/90 text-rose-100'
              : 'border-purple-500/40 bg-[#250C5C]/95 text-purple-100'
          }`}
        >
          {toastMessage.type === 'success' ? (
            <CheckCircle2 className="h-5 w-5 text-emerald-400" />
          ) : toastMessage.type === 'error' ? (
            <XCircle className="h-5 w-5 text-rose-400" />
          ) : (
            <Activity className="h-5 w-5 text-purple-300" />
          )}
          <span className="text-sm font-medium">{toastMessage.text}</span>
        </div>
      )}

      {/* Hero Header */}
      <div className="flex flex-col justify-between gap-4 rounded-2xl border border-purple-100 bg-white p-6 shadow-sm sm:flex-row sm:items-center">
        <div>
          <div className="flex items-center gap-2">
            <span className="inline-flex items-center gap-1.5 rounded-full border border-purple-200 bg-purple-50 px-2.5 py-0.5 font-mono text-2xs font-semibold uppercase tracking-wider text-purple-900">
              <Database className="h-3 w-3 text-purple-700" /> T24 CBS Core
            </span>
            <span className="inline-flex items-center gap-1 rounded-full border border-emerald-200 bg-emerald-50 px-2.5 py-0.5 font-mono text-2xs font-semibold uppercase tracking-wider text-emerald-800">
              <Activity className="h-3 w-3 text-emerald-600" /> Mock Runtime :8085
            </span>
          </div>
          <h1 className="mt-2 text-2xl font-bold tracking-tight text-slate-900">
            T24 Mock CBS Test Laboratory
          </h1>
          <p className="mt-1 text-sm text-slate-500">
            Aura Bank interactive workbench for validating Funds Transfers, Amount Holds (`AC.LOCKED.EVENTS`),
            Maker-Checker Reversals, Temenos OFS wire strings, COB lifecycle, and DLQ incident replays.
          </p>
        </div>

        {/* Live CBS System State */}
        <div className="flex items-center gap-4 rounded-xl border border-purple-100 bg-purple-50/60 p-3.5 shadow-xs">
          <div>
            <div className="text-2xs font-semibold uppercase tracking-wider text-purple-900/70">Business Date (T)</div>
            <div className="font-mono text-sm font-bold text-purple-950">{systemDate.businessDate}</div>
          </div>
          <div className="h-8 w-px bg-purple-200" />
          <div>
            <div className="text-2xs font-semibold uppercase tracking-wider text-purple-900/70">Posting Window</div>
            <div className="flex items-center gap-1.5 font-mono text-sm font-bold">
              <span
                className={`h-2.5 w-2.5 rounded-full ${
                  systemDate.postingWindowOpen ? 'bg-emerald-500 animate-pulse' : 'bg-rose-500'
                }`}
              />
              <span className={systemDate.postingWindowOpen ? 'text-emerald-700' : 'text-rose-700'}>
                {systemDate.status}
              </span>
            </div>
          </div>
          <button
            onClick={() => {
              fetchSystemDate();
              fetchBalance();
            }}
            className="rounded-lg border border-purple-200 bg-white p-2 text-purple-800 shadow-xs hover:bg-purple-100/60 hover:text-purple-950 transition-colors"
            title="Refresh System Status"
          >
            <RefreshCw className={`h-4 w-4 ${isLoadingBalance ? 'animate-spin' : ''}`} />
          </button>
        </div>
      </div>

      {/* Account Balances Card with Live Inspector */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-4">
        {/* Account Selector */}
        <div className="rounded-2xl border border-purple-100 bg-white p-5 shadow-sm">
          <label className="block text-2xs font-bold uppercase tracking-wider text-purple-900">
            Select Test Account
          </label>
          <div className="mt-2.5 flex flex-col gap-1.5">
            {['acc-2002-chk-001', 'acc-2001-sav-001', 'acc-2003-sav-002', '1000-2000-3001'].map((acc) => (
              <button
                key={acc}
                onClick={() => {
                  setActiveAccount(acc);
                  setTransferForm((prev) => ({ ...prev, sourceAccountId: acc }));
                  setHoldForm((prev) => ({ ...prev, accountId: acc }));
                }}
                className={`flex items-center justify-between rounded-xl px-3 py-2 text-left font-mono text-xs transition-all ${
                  activeAccount === acc
                    ? 'border border-purple-300 bg-purple-50/80 font-bold text-purple-950 shadow-xs'
                    : 'border border-transparent bg-slate-50 text-slate-600 hover:bg-purple-50/40 hover:text-purple-900'
                }`}
              >
                <span>{acc}</span>
                <span className={`text-2xs ${activeAccount === acc ? 'text-purple-700 font-semibold' : 'text-slate-400'}`}>
                  {acc === 'acc-2002-chk-001' ? 'Checking (8.5M)' : acc === 'acc-2001-sav-001' ? 'Savings (25M)' : acc === 'acc-2003-sav-002' ? 'Savings (12.3M)' : 'Primary'}
                </span>
              </button>
            ))}
          </div>
        </div>

        {/* Working Balance (Ledger Master) */}
        <div className="rounded-2xl border border-purple-100 bg-white p-5 shadow-sm flex flex-col justify-between">
          <div>
            <div className="flex items-center justify-between text-2xs font-bold uppercase tracking-wider text-purple-900">
              <span>Working Balance</span>
              <div className="h-7 w-7 rounded-lg bg-purple-100 flex items-center justify-center text-purple-800">
                <Database className="h-4 w-4" />
              </div>
            </div>
            <div className="mt-3 font-mono text-2xl font-bold tracking-tight text-slate-900">
              PHP {(balanceData?.balanceAmount ?? 0).toLocaleString('en-US', { minimumFractionDigits: 2 })}
            </div>
          </div>
          <p className="mt-2 text-2xs text-slate-500">Authoritative master balance in `balance_master`</p>
        </div>

        {/* Amount Hold (Locked Events) */}
        <div className="rounded-2xl border border-amber-100 bg-white p-5 shadow-sm flex flex-col justify-between">
          <div>
            <div className="flex items-center justify-between text-2xs font-bold uppercase tracking-wider text-amber-900">
              <span>Locked / Held Funds</span>
              <div className="h-7 w-7 rounded-lg bg-amber-100 flex items-center justify-center text-amber-800">
                <Lock className="h-4 w-4" />
              </div>
            </div>
            <div className="mt-3 font-mono text-2xl font-bold tracking-tight text-amber-600">
              PHP {(balanceData?.holdAmount ?? 0).toLocaleString('en-US', { minimumFractionDigits: 2 })}
            </div>
          </div>
          <p className="mt-2 text-2xs text-slate-500">
            {activeHolds.length} active hold(s) via `AC.LOCKED.EVENTS`
          </p>
        </div>

        {/* Spendable Available Balance */}
        <div className="rounded-2xl border border-emerald-100 bg-white p-5 shadow-sm flex flex-col justify-between">
          <div>
            <div className="flex items-center justify-between text-2xs font-bold uppercase tracking-wider text-emerald-900">
              <span>Available to Spend</span>
              <div className="h-7 w-7 rounded-lg bg-emerald-100 flex items-center justify-center text-emerald-800">
                <DollarSign className="h-4 w-4" />
              </div>
            </div>
            <div className="mt-3 font-mono text-2xl font-bold tracking-tight text-emerald-600">
              PHP {(balanceData?.availableBalance ?? 0).toLocaleString('en-US', { minimumFractionDigits: 2 })}
            </div>
          </div>
          <p className="mt-2 text-2xs text-slate-500">Solvency formula: (Working - Hold)</p>
        </div>
      </div>

      {/* Navigation Tabs */}
      <div className="flex flex-wrap gap-2 rounded-2xl bg-purple-50/70 p-2 border border-purple-100 shadow-2xs">
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
              className={`flex items-center gap-2 rounded-xl px-4 py-2.5 text-xs font-semibold tracking-wide transition-all ${
                active
                  ? 'bg-[#311075] text-white shadow-sm'
                  : 'text-slate-600 hover:bg-white hover:text-purple-950'
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
          <div className="rounded-2xl border border-purple-100 bg-white p-6 shadow-sm">
            <h2 className="flex items-center gap-2 text-base font-bold text-slate-900">
              <Send className="h-4 w-4 text-purple-700" /> Execute Funds Transfer (`FUNDS.TRANSFER`)
            </h2>
            <p className="mt-1 text-xs text-slate-500">
              Direct intra-bank transfer dispatched to CBS Core (`POST /api/v1/cbs/postings/transfer`).
              Enforces posting window checks, alphabetical row lock ordering, and liquid solvency.
            </p>

            <form onSubmit={handleExecuteTransfer} className="mt-5 space-y-4">
              <div>
                <label className="block text-2xs font-bold uppercase tracking-wider text-purple-950">
                  Debit / Source Account
                </label>
                <input
                  type="text"
                  value={transferForm.sourceAccountId}
                  onChange={(e) => setTransferForm({ ...transferForm, sourceAccountId: e.target.value })}
                  className="mt-1.5 w-full rounded-xl border border-slate-200 bg-slate-50/70 px-3.5 py-2 font-mono text-xs text-slate-900 focus:bg-white focus:border-purple-700 focus:ring-1 focus:ring-purple-700 focus:outline-none transition-all"
                  required
                />
              </div>

              <div>
                <label className="block text-2xs font-bold uppercase tracking-wider text-purple-950">
                  Credit / Destination Account
                </label>
                <input
                  type="text"
                  value={transferForm.destinationAccountId}
                  onChange={(e) => setTransferForm({ ...transferForm, destinationAccountId: e.target.value })}
                  className="mt-1.5 w-full rounded-xl border border-slate-200 bg-slate-50/70 px-3.5 py-2 font-mono text-xs text-slate-900 focus:bg-white focus:border-purple-700 focus:ring-1 focus:ring-purple-700 focus:outline-none transition-all"
                  required
                />
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-2xs font-bold uppercase tracking-wider text-purple-950">
                    Amount (PHP)
                  </label>
                  <input
                    type="number"
                    step="0.01"
                    value={transferForm.amount}
                    onChange={(e) => setTransferForm({ ...transferForm, amount: e.target.value })}
                    className="mt-1.5 w-full rounded-xl border border-slate-200 bg-slate-50/70 px-3.5 py-2 font-mono text-xs text-slate-900 focus:bg-white focus:border-purple-700 focus:ring-1 focus:ring-purple-700 focus:outline-none transition-all"
                    required
                  />
                </div>
                <div>
                  <label className="block text-2xs font-bold uppercase tracking-wider text-purple-950">
                    Currency
                  </label>
                  <input
                    type="text"
                    value={transferForm.currency}
                    readOnly
                    className="mt-1.5 w-full rounded-xl border border-slate-200 bg-slate-100/70 px-3.5 py-2 font-mono text-xs text-slate-500 cursor-not-allowed"
                  />
                </div>
              </div>

              <div>
                <label className="block text-2xs font-bold uppercase tracking-wider text-purple-950">
                  Description / Remarks
                </label>
                <input
                  type="text"
                  value={transferForm.description}
                  onChange={(e) => setTransferForm({ ...transferForm, description: e.target.value })}
                  className="mt-1.5 w-full rounded-xl border border-slate-200 bg-slate-50/70 px-3.5 py-2 text-xs text-slate-900 focus:bg-white focus:border-purple-700 focus:ring-1 focus:ring-purple-700 focus:outline-none transition-all"
                />
              </div>

              <button
                type="submit"
                disabled={isTransferring}
                className="flex w-full items-center justify-center gap-2 rounded-xl bg-[#311075] hover:bg-[#250C5C] px-5 py-3 text-xs font-bold uppercase tracking-wider text-white shadow-xs hover:shadow-md transition-all disabled:opacity-50"
              >
                {isTransferring ? <RefreshCw className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}
                Post to CBS Core
              </button>
            </form>
          </div>

          {/* Response Inspector */}
          <div className="rounded-2xl border border-purple-100 bg-white p-6 shadow-sm flex flex-col justify-between">
            <div>
              <h3 className="text-xs font-bold uppercase tracking-wider text-purple-950">
                CBS Core Execution Trace
              </h3>
              {transferResult ? (
                <div className="mt-4 space-y-3">
                  <div
                    className={`flex items-center gap-2 rounded-xl border p-3.5 ${
                      transferResult.success
                        ? 'border-emerald-200 bg-emerald-50 text-emerald-800'
                        : 'border-rose-200 bg-rose-50 text-rose-800'
                    }`}
                  >
                    {transferResult.success ? <CheckCircle2 className="h-4 w-4 text-emerald-600" /> : <XCircle className="h-4 w-4 text-rose-600" />}
                    <span className="font-mono text-xs font-bold">
                      {transferResult.success ? 'HTTP 200 OK — POSTED SUCCESSFULLY' : 'TRANSFER REJECTED BY CBS'}
                    </span>
                  </div>

                  <pre className="overflow-x-auto rounded-xl border border-purple-900/40 bg-[#120B24] p-4 font-mono text-2xs text-purple-200 shadow-inner">
                    {JSON.stringify(transferResult, null, 2)}
                  </pre>
                </div>
              ) : (
                <div className="mt-12 flex flex-col items-center justify-center text-center text-slate-400">
                  <div className="h-12 w-12 rounded-2xl bg-purple-50 flex items-center justify-center text-purple-400 mb-2">
                    <Send className="h-6 w-6" />
                  </div>
                  <p className="text-xs font-medium text-slate-600">No active trace yet</p>
                  <p className="mt-1 text-2xs text-slate-400 max-w-xs">Execute a transfer to inspect response payload and OFS return string.</p>
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* TAB 2: AMOUNT HOLD & FUNDS RESERVATION (AC.LOCKED.EVENTS) */}
      {activeTab === 'hold' && (
        <div className="space-y-6">
          {/* Architecture Explanatory Banner */}
          <div className="rounded-2xl border border-purple-200 bg-gradient-to-r from-purple-50 via-white to-purple-50/50 p-5 shadow-xs">
            <div className="font-bold text-xs uppercase tracking-wider text-purple-950 flex items-center gap-2">
              <span className="h-2 w-2 rounded-full bg-purple-700" />
              Two-Phase Transfer Lifecycle: Reserve &rarr; Capture / Release
            </div>
            <p className="mt-1.5 text-xs text-slate-600 leading-relaxed">
              In modern core banking orchestration, an Amount Hold is not just an isolated freeze&mdash;it represents{' '}
              <strong className="text-purple-900 font-semibold">Phase 1 (Provisional Reservation)</strong> of a high-value or risk-evaluated transfer.
              Available balance drops immediately to prevent double-spending while fraud or maker-checker checks execute. Once verified,{' '}
              <strong className="text-emerald-700 font-semibold">Phase 2 (Capture & Settle)</strong> debits the source, credits the beneficiary, and clears the hold into a posted transfer.
            </p>
          </div>

          <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
            {/* Phase 1: Reserve Funds Form */}
            <div className="rounded-2xl border border-purple-100 bg-white p-6 shadow-sm">
              <div className="flex items-center gap-2 border-b border-purple-100/70 pb-3.5">
                <span className="rounded-full border border-amber-200 bg-amber-50 px-2.5 py-0.5 font-mono text-2xs font-bold uppercase text-amber-900">
                  Phase 1: Reserve
                </span>
                <h2 className="text-sm font-bold text-slate-900">
                  Reserve Funds for Transfer (`RESERVED`)
                </h2>
              </div>

              <form onSubmit={handlePlaceHold} className="mt-5 space-y-4">
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="block text-2xs font-bold uppercase tracking-wider text-purple-950">
                      Source Account
                    </label>
                    <input
                      type="text"
                      value={holdForm.accountId}
                      onChange={(e) => setHoldForm({ ...holdForm, accountId: e.target.value })}
                      className="mt-1.5 w-full rounded-xl border border-slate-200 bg-slate-50/70 px-3.5 py-2 font-mono text-xs text-slate-900 focus:bg-white focus:border-purple-700 focus:ring-1 focus:ring-purple-700 focus:outline-none transition-all"
                      required
                    />
                  </div>
                  <div>
                    <label className="block text-2xs font-bold uppercase tracking-wider text-purple-950">
                      Beneficiary Account
                    </label>
                    <input
                      type="text"
                      value={holdForm.targetAccountId}
                      onChange={(e) => setHoldForm({ ...holdForm, targetAccountId: e.target.value })}
                      className="mt-1.5 w-full rounded-xl border border-slate-200 bg-slate-50/70 px-3.5 py-2 font-mono text-xs text-slate-900 focus:bg-white focus:border-purple-700 focus:ring-1 focus:ring-purple-700 focus:outline-none transition-all"
                      required
                    />
                  </div>
                </div>

                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="block text-2xs font-bold uppercase tracking-wider text-purple-950">
                      Transaction Reference ID
                    </label>
                    <input
                      type="text"
                      value={holdForm.transactionId}
                      onChange={(e) => setHoldForm({ ...holdForm, transactionId: e.target.value })}
                      className="mt-1.5 w-full rounded-xl border border-slate-200 bg-slate-50/70 px-3.5 py-2 font-mono text-xs text-purple-900 font-semibold focus:bg-white focus:border-purple-700 focus:ring-1 focus:ring-purple-700 focus:outline-none transition-all"
                      required
                    />
                  </div>
                  <div>
                    <label className="block text-2xs font-bold uppercase tracking-wider text-purple-950">
                      Hold / Transfer Amount (PHP)
                    </label>
                    <input
                      type="number"
                      step="0.01"
                      value={holdForm.holdAmount}
                      onChange={(e) => setHoldForm({ ...holdForm, holdAmount: e.target.value })}
                      className="mt-1.5 w-full rounded-xl border border-slate-200 bg-slate-50/70 px-3.5 py-2 font-mono text-xs text-slate-900 focus:bg-white focus:border-purple-700 focus:ring-1 focus:ring-purple-700 focus:outline-none transition-all"
                      required
                    />
                  </div>
                </div>

                <div>
                  <label className="block text-2xs font-bold uppercase tracking-wider text-purple-950">
                    Reservation Purpose / Reason
                  </label>
                  <select
                    value={holdForm.reason}
                    onChange={(e) => setHoldForm({ ...holdForm, reason: e.target.value })}
                    className="mt-1.5 w-full rounded-xl border border-slate-200 bg-slate-50/70 px-3.5 py-2 text-xs text-slate-900 focus:bg-white focus:border-purple-700 focus:ring-1 focus:ring-purple-700 focus:outline-none transition-all"
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
                  className="flex w-full items-center justify-center gap-2 rounded-xl bg-[#311075] hover:bg-[#250C5C] px-5 py-3 text-xs font-bold uppercase tracking-wider text-white shadow-xs hover:shadow-md transition-all disabled:opacity-50"
                >
                  {isHolding ? <RefreshCw className="h-4 w-4 animate-spin" /> : <Lock className="h-4 w-4" />}
                  Step 1: Reserve Funds via AC.LOCKED.EVENTS
                </button>
              </form>
            </div>

            {/* Phase 2: Active Reservations & Settlement Actions */}
            <div className="rounded-2xl border border-purple-100 bg-white p-6 shadow-sm flex flex-col justify-between">
              <div>
                <div className="flex items-center justify-between border-b border-purple-100/70 pb-3.5">
                  <div className="flex items-center gap-2">
                    <span className="rounded-full border border-emerald-200 bg-emerald-50 px-2.5 py-0.5 font-mono text-2xs font-bold uppercase text-emerald-900">
                      Phase 2: Capture / Cancel
                    </span>
                    <h3 className="text-sm font-bold text-slate-900">Active Holds on `{activeAccount}`</h3>
                  </div>
                  <button
                    onClick={() => fetchHolds(activeAccount)}
                    className="text-xs font-semibold text-purple-700 hover:text-purple-950 transition-colors"
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
                        <div key={h.holdId} className="rounded-xl border border-purple-100 bg-purple-50/40 p-4 transition-all">
                          <div className="flex items-start justify-between">
                            <div>
                              <div className="flex items-center gap-2 font-mono text-xs font-semibold text-slate-900">
                                <span className="text-purple-900 font-bold">{h.holdId}</span>
                                <span className="rounded border border-purple-200 bg-white px-2 py-0.5 text-2xs text-purple-700 font-medium">
                                  {h.t24LockReference}
                                </span>
                                <span className={`px-2 py-0.5 rounded-full text-2xs font-mono uppercase ${
                                  h.status === 'ACTIVE'
                                    ? 'bg-amber-100 text-amber-800 border border-amber-200 font-bold'
                                    : h.status === 'CAPTURED'
                                    ? 'bg-emerald-100 text-emerald-800 border border-emerald-200 font-bold'
                                    : 'bg-slate-100 text-slate-600 border border-slate-200'
                                }`}>
                                  {h.status}
                                </span>
                              </div>

                              <div className="mt-1.5 font-mono text-xs text-slate-700">
                                Reserved: <strong className="text-slate-900">PHP {parseFloat(h.holdAmount).toLocaleString('en-US', { minimumFractionDigits: 2 })}</strong>
                                {' '}&bull; Linked Tx: <span className="text-purple-700 font-medium">{linkedTx || 'N/A'}</span>
                                {' '}&bull; Beneficiary: <span className="text-indigo-700 font-medium">{beneficiaryAcc}</span>
                              </div>
                              <div className="text-2xs text-slate-500 mt-1">Reason: {h.reason}</div>
                            </div>
                          </div>

                          {h.status === 'ACTIVE' && (
                            <div className="mt-3.5 flex items-center justify-end gap-2.5 border-t border-purple-100/70 pt-2.5">
                              <button
                                onClick={() => handleReleaseHold(h.holdId)}
                                className="flex items-center gap-1.5 rounded-lg border border-rose-200 bg-white px-3 py-1.5 text-xs font-semibold text-rose-700 hover:bg-rose-50 shadow-2xs transition-all"
                                title="Cancel hold and restore customer available balance"
                              >
                                <Unlock className="h-3.5 w-3.5" /> Cancel & Void
                              </button>
                              <button
                                onClick={() => handleCaptureHold(h.holdId, beneficiaryAcc, h.holdAmount)}
                                disabled={isCapturing}
                                className="flex items-center gap-1.5 rounded-lg bg-emerald-600 hover:bg-emerald-700 px-3.5 py-1.5 text-xs font-semibold text-white shadow-2xs transition-all"
                                title="Debit source, credit beneficiary, clear hold, and mark transfer POSTED"
                              >
                                {isCapturing ? <RefreshCw className="h-3.5 w-3.5 animate-spin" /> : <CheckCircle2 className="h-3.5 w-3.5" />}
                                Capture & Settle Transfer
                              </button>
                            </div>
                          )}
                        </div>
                      );
                    })}
                  </div>
                ) : (
                  <div className="mt-12 flex flex-col items-center justify-center text-center text-slate-400">
                    <div className="h-12 w-12 rounded-2xl bg-purple-50 flex items-center justify-center text-purple-400 mb-2">
                      <Unlock className="h-6 w-6" />
                    </div>
                    <p className="text-xs font-medium text-slate-600">No active holds on this account</p>
                    <p className="mt-1 text-2xs text-slate-400">Funds are 100% available to spend.</p>
                  </div>
                )}

                {/* Settlement / Capture Trace Inspector */}
                {captureResult && (
                  <div className="mt-4 rounded-xl border border-emerald-200 bg-emerald-50/60 p-3.5">
                    <div className="flex items-center justify-between text-2xs font-bold uppercase tracking-wider text-emerald-800">
                      <span>Capture Execution Result</span>
                      <span className="font-mono">HTTP 200 OK &mdash; POSTED</span>
                    </div>
                    <pre className="mt-2 overflow-x-auto rounded-lg border border-emerald-300/40 bg-[#120B24] p-3 font-mono text-2xs text-emerald-300">
                      {JSON.stringify(captureResult.data, null, 2)}
                    </pre>
                  </div>
                )}

                {holdResult && !captureResult && (
                  <div className="mt-4 rounded-xl border border-amber-200 bg-amber-50/60 p-3.5">
                    <div className="text-2xs font-bold uppercase tracking-wider text-amber-800">
                      Provisional Reservation Confirmed
                    </div>
                    <pre className="mt-2 overflow-x-auto rounded-lg border border-amber-300/40 bg-[#120B24] p-3 font-mono text-2xs text-amber-300">
                      {JSON.stringify(holdResult.data, null, 2)}
                    </pre>
                  </div>
                )}
              </div>
            </div>
          </div>
        </div>
      )}


      {/* TAB 3: TRANSACTION REVERSAL (DUAL ENDPOINT 2 & MAKER-CHECKER) */}
      {activeTab === 'reversal' && (
        <div className="space-y-6">
          {/* Dual-Endpoint 2 Saga Compensation Quick Trigger */}
          <div className="rounded-2xl border border-purple-200 bg-gradient-to-r from-purple-50 via-white to-purple-50/60 p-5 shadow-xs">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="rounded-full border border-purple-300 bg-purple-100 px-2.5 py-0.5 font-mono text-2xs font-bold uppercase text-purple-950">
                  Dual Architecture Endpoint 2
                </span>
                <h3 className="text-sm font-bold text-slate-900">Automated Saga Compensating Reversal (`POST /t24/reversal`)</h3>
              </div>
              <span className="font-mono text-2xs font-semibold text-purple-800 bg-purple-100/60 px-2 py-0.5 rounded">ACID Rollback</span>
            </div>
            <p className="mt-1.5 text-xs text-slate-600 leading-relaxed">
              Instantly reverses transaction balances in the Oracle Master DB and commits audit records to the PostgreSQL Audit Vault without requiring manual teller maker-checker approval. Invoked by Saga Coordinator on downstream pipeline failure.
            </p>
            <div className="mt-4 flex flex-wrap items-center gap-3">
              <input
                type="text"
                placeholder="Transaction ID to reverse (e.g. FT123456 or TXN-...)"
                value={reversalForm.originalTransactionId}
                onChange={(e) => setReversalForm({ ...reversalForm, originalTransactionId: e.target.value })}
                className="flex-1 min-w-[240px] rounded-xl border border-slate-200 bg-white px-3.5 py-2 font-mono text-xs text-slate-900 focus:border-purple-700 focus:ring-1 focus:ring-purple-700 focus:outline-none transition-all"
              />
              <button
                type="button"
                onClick={handleCompensatingReversal}
                disabled={isReversing}
                className="flex items-center gap-2 rounded-xl bg-[#311075] hover:bg-[#250C5C] px-5 py-2.5 font-mono text-xs font-bold uppercase text-white shadow-xs hover:shadow-md transition-all disabled:opacity-50"
              >
                {isReversing ? <RefreshCw className="h-4 w-4 animate-spin" /> : <RotateCcw className="h-4 w-4" />}
                Execute EP2 Compensating Reversal
              </button>
            </div>
          </div>

          <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
            {/* Maker Dispute Request */}
            <div className="rounded-2xl border border-purple-100 bg-white p-6 shadow-sm">
              <div className="flex items-center gap-2 border-b border-purple-100/70 pb-3.5">
                <span className="rounded-full border border-purple-200 bg-purple-50 px-2.5 py-0.5 font-mono text-2xs font-bold uppercase text-purple-900">
                  Step 1: Maker Role
                </span>
                <h2 className="text-sm font-bold text-slate-900">Initiate Dispute Reversal Ticket</h2>
              </div>

            <form onSubmit={handleRequestReversal} className="mt-5 space-y-4">
              <div>
                <label className="block text-2xs font-bold uppercase tracking-wider text-purple-950">
                  Original Transaction ID to Reverse
                </label>
                <input
                  type="text"
                  placeholder="e.g. TX-123456"
                  value={reversalForm.originalTransactionId}
                  onChange={(e) => setReversalForm({ ...reversalForm, originalTransactionId: e.target.value })}
                  className="mt-1.5 w-full rounded-xl border border-slate-200 bg-slate-50/70 px-3.5 py-2 font-mono text-xs text-slate-900 focus:bg-white focus:border-purple-700 focus:ring-1 focus:ring-purple-700 focus:outline-none transition-all"
                  required
                />
              </div>

              <div>
                <label className="block text-2xs font-bold uppercase tracking-wider text-purple-950">
                  Maker ID (Operator)
                </label>
                <input
                  type="text"
                  value={reversalForm.makerId}
                  onChange={(e) => setReversalForm({ ...reversalForm, makerId: e.target.value })}
                  className="mt-1.5 w-full rounded-xl border border-slate-200 bg-slate-50/70 px-3.5 py-2 font-mono text-xs text-slate-900 focus:bg-white focus:border-purple-700 focus:ring-1 focus:ring-purple-700 focus:outline-none transition-all"
                  required
                />
              </div>

              <div>
                <label className="block text-2xs font-bold uppercase tracking-wider text-purple-950">
                  Dispute Reason
                </label>
                <select
                  value={reversalForm.reason}
                  onChange={(e) => setReversalForm({ ...reversalForm, reason: e.target.value })}
                  className="mt-1.5 w-full rounded-xl border border-slate-200 bg-slate-50/70 px-3.5 py-2 text-xs text-slate-900 focus:bg-white focus:border-purple-700 focus:ring-1 focus:ring-purple-700 focus:outline-none transition-all"
                >
                  <option value="CUSTOMER_DISPUTE">CUSTOMER_DISPUTE (Customer Filed Unauthorized Debit)</option>
                  <option value="OPERATIONAL_ERROR">OPERATIONAL_ERROR (Duplicate Operator Posting)</option>
                  <option value="FRAUD_INVESTIGATION">FRAUD_INVESTIGATION (Compromised Destination Account)</option>
                </select>
              </div>

              <div>
                <label className="block text-2xs font-bold uppercase tracking-wider text-purple-950">
                  Maker Notes
                </label>
                <textarea
                  rows="2"
                  value={reversalForm.notes}
                  onChange={(e) => setReversalForm({ ...reversalForm, notes: e.target.value })}
                  className="mt-1.5 w-full rounded-xl border border-slate-200 bg-slate-50/70 px-3.5 py-2 text-xs text-slate-900 focus:bg-white focus:border-purple-700 focus:ring-1 focus:ring-purple-700 focus:outline-none transition-all"
                />
              </div>

              <button
                type="submit"
                disabled={isReversing}
                className="flex w-full items-center justify-center gap-2 rounded-xl bg-[#311075] hover:bg-[#250C5C] px-5 py-3 text-xs font-bold uppercase tracking-wider text-white shadow-xs hover:shadow-md transition-all disabled:opacity-50"
              >
                {isReversing ? <RefreshCw className="h-4 w-4 animate-spin" /> : <RotateCcw className="h-4 w-4" />}
                File Reversal Dispute Ticket
              </button>
            </form>
          </div>

          {/* Checker Authorization */}
          <div className="rounded-2xl border border-purple-100 bg-white p-6 shadow-sm flex flex-col justify-between">
            <div>
              <div className="flex items-center gap-2 border-b border-purple-100/70 pb-3.5">
                <span className="rounded-full border border-emerald-200 bg-emerald-50 px-2.5 py-0.5 font-mono text-2xs font-bold uppercase text-emerald-900">
                  Step 2: Checker Role
                </span>
                <h2 className="text-sm font-bold text-slate-900">Dual-Control Approval (Segregation of Duties)</h2>
              </div>

              <div className="mt-5 space-y-4">
                <div>
                  <label className="block text-2xs font-bold uppercase tracking-wider text-purple-950">
                    Dispute Ticket ID
                  </label>
                  <input
                    type="text"
                    placeholder="Ticket ID from Step 1"
                    value={checkerForm.ticketId}
                    onChange={(e) => setCheckerForm({ ...checkerForm, ticketId: e.target.value })}
                    className="mt-1.5 w-full rounded-xl border border-slate-200 bg-slate-50/70 px-3.5 py-2 font-mono text-xs text-slate-900 focus:bg-white focus:border-purple-700 focus:ring-1 focus:ring-purple-700 focus:outline-none transition-all"
                  />
                </div>

                <div>
                  <label className="block text-2xs font-bold uppercase tracking-wider text-purple-950">
                    Checker ID (Must Differ from Maker)
                  </label>
                  <input
                    type="text"
                    value={checkerForm.checkerId}
                    onChange={(e) => setCheckerForm({ ...checkerForm, checkerId: e.target.value })}
                    className="mt-1.5 w-full rounded-xl border border-slate-200 bg-slate-50/70 px-3.5 py-2 font-mono text-xs text-slate-900 focus:bg-white focus:border-purple-700 focus:ring-1 focus:ring-purple-700 focus:outline-none transition-all"
                  />
                  <p className="mt-1.5 text-2xs font-medium text-amber-700 bg-amber-50 p-2 rounded-lg border border-amber-200">
                    Dual Control Rule: If Checker == `{reversalForm.makerId}`, CBS will reject with error.
                  </p>
                </div>

                <div>
                  <label className="block text-2xs font-bold uppercase tracking-wider text-purple-950">
                    Checker Authorization Notes
                  </label>
                  <input
                    type="text"
                    value={checkerForm.checkerNotes}
                    onChange={(e) => setCheckerForm({ ...checkerForm, checkerNotes: e.target.value })}
                    className="mt-1.5 w-full rounded-xl border border-slate-200 bg-slate-50/70 px-3.5 py-2 text-xs text-slate-900 focus:bg-white focus:border-purple-700 focus:ring-1 focus:ring-purple-700 focus:outline-none transition-all"
                  />
                </div>

                <div className="grid grid-cols-2 gap-3 pt-2">
                  <button
                    type="button"
                    onClick={handleApproveReversal}
                    disabled={isReversing || !checkerForm.ticketId}
                    className="flex items-center justify-center gap-2 rounded-xl bg-emerald-600 hover:bg-emerald-700 px-4 py-2.5 text-xs font-bold uppercase tracking-wider text-white shadow-xs transition-all disabled:opacity-50"
                  >
                    <CheckCircle2 className="h-4 w-4" /> Approve Reversal
                  </button>

                  <button
                    type="button"
                    onClick={handleRejectReversal}
                    disabled={isReversing || !checkerForm.ticketId}
                    className="flex items-center justify-center gap-2 rounded-xl border border-rose-200 bg-white hover:bg-rose-50 px-4 py-2.5 text-xs font-bold uppercase tracking-wider text-rose-700 shadow-2xs transition-all disabled:opacity-50"
                  >
                    <XCircle className="h-4 w-4" /> Reject Ticket
                  </button>
                </div>
              </div>

              {/* Reversal Result Inspector */}
              {reversalResult && (
                <div className="mt-4 space-y-2 rounded-xl border border-purple-200 bg-purple-50/60 p-3.5">
                  <div className="font-mono text-2xs font-bold text-purple-950">
                    Result Step: {reversalResult.step || 'Error'}
                  </div>
                  <pre className="overflow-x-auto rounded-lg border border-purple-300/40 bg-[#120B24] p-3 text-2xs text-purple-200">
                    {JSON.stringify(reversalResult, null, 2)}
                  </pre>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
      )}

      {/* TAB 4: RAW TEMENOS OFS PROTOCOL TERMINAL */}
      {activeTab === 'ofs' && (
        <div className="space-y-5 rounded-2xl border border-purple-100 bg-white p-6 shadow-sm">
          <div className="flex flex-col justify-between gap-3 sm:flex-row sm:items-center">
            <div>
              <h2 className="flex items-center gap-2 text-base font-bold text-slate-900">
                <Terminal className="h-4 w-4 text-purple-700" /> Temenos Open Financial Services (OFS) Wire Playground
              </h2>
              <p className="mt-1 text-xs text-slate-500">
                Direct wire protocol endpoint (`POST /api/v1/cbs/ofs` with `Content-Type: text/plain`).
              </p>
            </div>

            {/* Template Presets */}
            <div className="flex flex-wrap gap-1.5">
              {[
                {
                  label: 'FT INITIATE',
                  cmd: 'FUNDS.TRANSFER,INITIATE/I/PROCESS//TX-9901,USER01/123456,TRANSACTION.TYPE=AC,DEBIT.ACCT.NO=acc-2002-chk-001,CREDIT.ACCT.NO=acc-2003-sav-002,AMOUNT=2500.00,CURRENCY=PHP,VALUE.DATE=20261008'
                },
                {
                  label: 'FT REVERSAL',
                  cmd: 'FUNDS.TRANSFER,REVERSAL/I/PROCESS//REV-01,MGR02/123456,ORIGINAL.FT.NO=TX-9901'
                },
                {
                  label: 'HOLD INPUT',
                  cmd: 'AC.LOCKED.EVENTS,INPUT/I/PROCESS/0/1,USER01/123456,,ACCOUNT.NUMBER=acc-2002-chk-001,FROM.DATE=20261008,TO.DATE=20261009,LOCKED.AMOUNT=4000.00,HOLD.REASON=MAKER_CHECKER_HOLD,EXT.REF=OFS-HLD-01'
                },
                {
                  label: 'HOLD REVERSE',
                  cmd: 'AC.LOCKED.EVENTS,REVERSE/I/PROCESS/0/1,USER01/123456,,HOLD.REF=HLD-DEMO,ACCOUNT.NUMBER=acc-2002-chk-001'
                },
                {
                  label: 'ENQUIRY',
                  cmd: 'ENQUIRY.SELECT,,USER01/123456,ACCOUNT.NUMBER:EQ=acc-2002-chk-001'
                }
              ].map((tmpl) => (
                <button
                  key={tmpl.label}
                  type="button"
                  onClick={() => setOfsInput(tmpl.cmd)}
                  className="rounded-lg border border-purple-200 bg-purple-50/70 px-2.5 py-1 font-mono text-2xs font-semibold text-purple-900 hover:bg-purple-100 transition-colors"
                >
                  {tmpl.label}
                </button>
              ))}
            </div>
          </div>

          <div className="mt-3">
            <label className="block text-2xs font-bold uppercase tracking-wider text-purple-950">
              OFS Wire Payload String
            </label>
            <textarea
              rows="4"
              value={ofsInput}
              onChange={(e) => setOfsInput(e.target.value)}
              className="mt-1.5 w-full rounded-xl border border-slate-200 bg-slate-50/70 p-3.5 font-mono text-xs text-purple-950 focus:bg-white focus:border-purple-700 focus:ring-1 focus:ring-purple-700 focus:outline-none transition-all"
            />
          </div>

          <div className="flex justify-end gap-3">
            <button
              onClick={() => copyToClipboard(ofsInput)}
              className="flex items-center gap-1.5 rounded-xl border border-purple-200 bg-white px-4 py-2.5 text-xs font-semibold text-purple-900 hover:bg-purple-50 shadow-2xs transition-all"
            >
              {copiedText ? <Check className="h-3.5 w-3.5 text-emerald-600" /> : <Copy className="h-3.5 w-3.5" />}
              Copy Wire String
            </button>
            <button
              onClick={handleExecuteOfs}
              disabled={isExecutingOfs}
              className="flex items-center gap-2 rounded-xl bg-[#311075] hover:bg-[#250C5C] px-5 py-2.5 text-xs font-bold uppercase tracking-wider text-white shadow-xs hover:shadow-md transition-all disabled:opacity-50"
            >
              {isExecutingOfs ? <RefreshCw className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
              Transmit OFS Payload
            </button>
          </div>

          {/* Response Terminal */}
          {ofsResponse && (
            <div className="mt-4 rounded-xl border border-purple-900/40 bg-[#120B24] p-4 shadow-inner">
              <div className="flex items-center justify-between text-2xs uppercase tracking-wider text-purple-300">
                <span>Temenos Wire Protocol Response</span>
                <span className="font-mono text-emerald-400 font-bold">HTTP 200 OK</span>
              </div>
              <pre className="mt-2.5 overflow-x-auto font-mono text-xs text-emerald-300">
                {ofsResponse}
              </pre>
            </div>
          )}
        </div>
      )}

      {/* TAB 5: COB & EOD LIFECYCLE */}
      {activeTab === 'cob' && (
        <div className="space-y-6 rounded-2xl border border-purple-100 bg-white p-6 shadow-sm">
          <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
            <div>
              <h2 className="flex items-center gap-2 text-base font-bold text-slate-900">
                <Moon className="h-4 w-4 text-purple-700" /> Close of Business (COB) & EOD Batch Simulation
              </h2>
              <p className="mt-1 text-xs text-slate-500">
                Executes the 5-phase COB sequence: Cutoff &rarr; Zero-overdraft fee collection &rarr; BIR 20%
                tax withholding & interest accrual &rarr; EOD snapshot & GL reconciliation &rarr; Rollover to T+1.
              </p>
            </div>

            <button
              onClick={handleRunCob}
              disabled={isExecutingCob}
              className="flex items-center gap-2 rounded-xl bg-[#311075] hover:bg-[#250C5C] px-6 py-3 text-xs font-bold uppercase tracking-wider text-white shadow-xs hover:shadow-md transition-all disabled:opacity-50"
            >
              {isExecutingCob ? <RefreshCw className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}
              Run Full COB Sequence
            </button>
          </div>

          {/* Phase progression cards */}
          <div className="grid grid-cols-1 gap-3.5 sm:grid-cols-5">
            {[
              { phase: 'Phase 0', title: 'Cutoff Posting Window', desc: 'Closes window, sets EOD_CUTOFF' },
              { phase: 'Phase 1', title: 'Zero-Overdraft Fees', desc: 'Accounts < 5000 ADB, arrears safe' },
              { phase: 'Phase 2', title: 'Interest & BIR 20% Tax', desc: 'Daily accrual & 20% tax withhold' },
              { phase: 'Phase 3', title: 'GL Reconciliation', desc: 'Sum debits == credits check' },
              { phase: 'Phase 4', title: 'Rollover to T+1', desc: 'Advances system date to next day' }
            ].map((p) => (
              <div key={p.phase} className="rounded-xl border border-purple-100 bg-purple-50/40 p-4 shadow-2xs hover:bg-purple-50/70 transition-colors">
                <div className="font-mono text-2xs font-bold text-purple-700">{p.phase}</div>
                <div className="mt-1.5 text-xs font-bold text-slate-900">{p.title}</div>
                <div className="mt-1 text-2xs text-slate-500 leading-snug">{p.desc}</div>
              </div>
            ))}
          </div>

          {cobResult && (
            <div className="rounded-xl border border-purple-900/40 bg-[#120B24] p-4 shadow-inner">
              <div className="font-mono text-xs font-bold text-purple-300">
                COB Execution Summary
              </div>
              <pre className="mt-2 overflow-x-auto font-mono text-2xs text-purple-200">
                {JSON.stringify(cobResult, null, 2)}
              </pre>
            </div>
          )}

          {/* Azurite Reports Link */}
          <div className="flex flex-col sm:flex-row items-center justify-between gap-3 border-t border-purple-100 pt-4">
            <span className="text-xs text-slate-500">
              Generated BIR 2306 tax certificates, customer statements, and GL reconciliation reports are saved in Azurite Blob Storage:
            </span>
            <a
              href="/azurite-drive"
              className="flex items-center gap-2 rounded-xl border border-purple-200 bg-purple-50 px-4 py-2 text-xs font-bold text-purple-900 hover:bg-purple-100 shadow-2xs transition-all"
            >
              <FileText className="h-4 w-4 text-purple-700" /> Open Azurite Drive Storage
            </a>
          </div>
        </div>
      )}

      {/* TAB 6: DLQ & CIRCUIT BREAKER REPLAYS */}
      {activeTab === 'dlq' && (
        <div className="space-y-5 rounded-2xl border border-purple-100 bg-white p-6 shadow-sm">
          <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
            <div>
              <h2 className="flex items-center gap-2 text-base font-bold text-slate-900">
                <AlertTriangle className="h-4 w-4 text-rose-500" /> Dead Letter Queue (DLQ) Incident Replays &amp; Failed Transactions
              </h2>
              <p className="mt-1 text-xs text-slate-500">
                Transactions queued to `banking.transfers.dlq` when CBS is unavailable, times out, or trips the circuit breaker.
              </p>
            </div>
            <button
              onClick={fetchDlqIncidents}
              className="flex items-center gap-1.5 rounded-xl border border-purple-200 bg-white px-3.5 py-2 text-xs font-semibold text-purple-900 hover:bg-purple-50 shadow-2xs transition-all"
            >
              <RefreshCw className={`h-3.5 w-3.5 ${isLoadingDlq ? 'animate-spin' : ''}`} /> Refresh Incidents
            </button>
          </div>

          {/* Failure Injection & Simulation Bar */}
          <div className="rounded-xl border border-purple-100 bg-purple-50/50 p-4 space-y-2.5">
            <div className="text-2xs font-bold uppercase tracking-wider text-purple-950 flex items-center gap-1.5">
              <span>⚡ Failure Injection &amp; Telemetry Testing</span>
            </div>
            <p className="text-2xs text-slate-600">
              Inject simulated failure modes into the core ledger to observe circuit breaker trips, retry telemetry, and manual remediation:
            </p>
            <div className="flex flex-wrap gap-2.5 pt-1">
              <button
                type="button"
                onClick={() => handleSimulateFailure('NETWORK_TIMEOUT', 'HTTP_504', 'OPEN')}
                disabled={isSimulatingFailure}
                className="flex items-center gap-1.5 rounded-lg border border-rose-200 bg-rose-50 px-3 py-1.5 text-2xs font-bold text-rose-700 hover:bg-rose-100 shadow-2xs transition-all disabled:opacity-50"
              >
                <AlertTriangle className="h-3.5 w-3.5" /> Simulate Network Timeout (HTTP 504 / Circuit Breaker OPEN)
              </button>
              <button
                type="button"
                onClick={() => handleSimulateFailure('CBS_CUTOFF_REJECTION', 'EOD_CUTOFF_IN_PROGRESS', 'HALF_OPEN')}
                disabled={isSimulatingFailure}
                className="flex items-center gap-1.5 rounded-lg border border-amber-200 bg-amber-50 px-3 py-1.5 text-2xs font-bold text-amber-800 hover:bg-amber-100 shadow-2xs transition-all disabled:opacity-50"
              >
                <Moon className="h-3.5 w-3.5" /> Simulate Posting Cutoff Rejection (EOD_CUTOFF)
              </button>
              <button
                type="button"
                onClick={() => handleSimulateFailure('CORE_DOWN_503', 'HTTP_503', 'OPEN')}
                disabled={isSimulatingFailure}
                className="flex items-center gap-1.5 rounded-lg border border-purple-200 bg-purple-100/70 px-3 py-1.5 text-2xs font-bold text-purple-900 hover:bg-purple-200 shadow-2xs transition-all disabled:opacity-50"
              >
                <RotateCcw className="h-3.5 w-3.5" /> Simulate CBS Core Down (HTTP 503 / Trip Circuit Breaker)
              </button>
            </div>
          </div>

          {dlqIncidents.length > 0 ? (
            <div className="overflow-x-auto rounded-xl border border-purple-100 bg-white shadow-2xs">
              <table className="w-full text-left font-mono text-xs">
                <thead className="bg-purple-50/80 text-2xs uppercase text-purple-950 font-bold border-b border-purple-100">
                  <tr>
                    <th className="p-3.5">Incident / Transfer ID</th>
                    <th className="p-3.5">Error / Reason</th>
                    <th className="p-3.5">Circuit Breaker</th>
                    <th className="p-3.5">Timestamp</th>
                    <th className="p-3.5 text-right">Action</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-purple-50 bg-white text-slate-800">
                  {dlqIncidents.map((inc) => (
                    <tr key={inc.incidentId || inc.transactionId} className="hover:bg-purple-50/40 transition-colors">
                      <td className="p-3.5 font-bold text-slate-900">{inc.transactionId || inc.incidentId}</td>
                      <td className="p-3.5 text-2xs font-semibold text-rose-600">{inc.errorType || 'CBS_TIMEOUT'}</td>
                      <td className="p-3.5">
                        <span className="rounded-full border border-rose-200 bg-rose-50 px-2 py-0.5 text-2xs font-bold text-rose-700">
                          {inc.circuitBreakerState || 'OPEN'}
                        </span>
                      </td>
                      <td className="p-3.5 text-2xs text-slate-500">{inc.failureTimestampUtc || 'Just now'}</td>
                      <td className="p-3.5 text-right">
                        <button
                          onClick={() => handleReplayDlq(inc.transactionId || inc.incidentId)}
                          className="rounded-lg bg-[#311075] hover:bg-[#250C5C] px-3.5 py-1 text-2xs font-bold uppercase text-white shadow-2xs transition-all"
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
            <div className="flex flex-col items-center justify-center p-10 text-center text-slate-400">
              <CheckCircle2 className="h-10 w-10 text-emerald-500 mb-2" />
              <p className="text-xs font-semibold text-slate-700">No pending DLQ incidents</p>
              <p className="mt-1 text-2xs text-slate-400">Circuit breaker is CLOSED and all transfer pathways are healthy.</p>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
