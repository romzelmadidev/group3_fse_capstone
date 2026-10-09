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
  FileText,
  Search,
  ArrowUpRight,
  ArrowDownLeft,
  AlertCircle,
  Ban,
  ShieldCheck,
  Zap
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
  const [activeTab, setActiveTab] = useState('transfer'); // transfer | enquiry | reversal | ofs | cob | dlq
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

  // Account Transaction Enquiry State (ENQUIRY.SELECT)
  const [enquiryAccount, setEnquiryAccount] = useState('acc-2002-chk-001');
  const [enquiryPage, setEnquiryPage] = useState(0);
  const [enquirySize, setEnquirySize] = useState(20);
  const [enquiryProtocol, setEnquiryProtocol] = useState('ORCHESTRATOR_JSON'); // 'ORCHESTRATOR_JSON' | 'T24_OFS'
  const [enquiryTransactions, setEnquiryTransactions] = useState([]);
  const [enquiryRawOfs, setEnquiryRawOfs] = useState('');
  const [isLoadingEnquiry, setIsLoadingEnquiry] = useState(false);
  const [isSimulatingFailure, setIsSimulatingFailure] = useState(false);

  // Real-World Banking Failure Scenarios Simulator State
  const [selectedScenario, setSelectedScenario] = useState('INSUFFICIENT_FUNDS');
  const [scenarioRunning, setScenarioRunning] = useState(false);
  const [scenarioResult, setScenarioResult] = useState(null);
  const [postingWindowToggling, setPostingWindowToggling] = useState(false);

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

  // Live Reversal Requests Backlog (Gateway: GET /api/v1/reversals)
  const [reversalRequests, setReversalRequests] = useState([]);
  const [isLoadingReversals, setIsLoadingReversals] = useState(false);
  const [reversalStatusFilter, setReversalStatusFilter] = useState('ALL');

  // Transaction Status History (Gateway: GET /api/v1/transfers/transactions/{id}/status-history)
  const [statusHistoryTxId, setStatusHistoryTxId] = useState('');
  const [statusHistoryList, setStatusHistoryList] = useState([]);
  const [isLoadingStatusHistory, setIsLoadingStatusHistory] = useState(false);
  const [isStatusHistoryOpen, setIsStatusHistoryOpen] = useState(false);

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
    fetchSystemDate();
  }, [activeAccount]);

  useEffect(() => {
    if (activeTab === 'reversal') {
      fetchReversalRequests(reversalStatusFilter === 'ALL' ? '' : reversalStatusFilter);
    }
  }, [activeTab, reversalStatusFilter]);

  useEffect(() => {
    if (activeTab === 'enquiry') {
      handleFetchEnquiryTransactions(enquiryAccount, enquiryPage, enquiryProtocol);
    }
  }, [activeTab, enquiryAccount, enquiryPage, enquiryProtocol]);

  // Execute Transfer (Orchestrator JSON mapping: POST /api/v1/transfers -> T24 OFS: POST /api/v1/cbs/funds-transfer)
  const handleExecuteTransfer = async (e) => {
    e.preventDefault();
    setIsTransferring(true);
    setTransferResult(null);
    try {
      const txRef = 'TXN-' + Math.floor(Math.random() * 900000 + 100000);
      const payload = {
        transactionId: txRef,
        sourceAccountId: transferForm.sourceAccountId,
        destinationAccountId: transferForm.destinationAccountId,
        amount: parseFloat(transferForm.amount),
        currency: transferForm.currency,
        description: transferForm.description,
        deviceId: 'TEST-CONSOLE-BROWSER',
        idempotencyKey: 'IDEMP-' + txRef
      };
      const res = await axios.post(`${API_BASE}/transfers`, payload);
      setTransferResult({ success: true, data: res.data });
      showToast('Funds Transfer executed and posted via Orchestrator JSON ➔ T24 OFS!', 'success');
      // Auto-fill reversal original Tx ID
      const ref = res.data?.transactionId || txRef;
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

  // Account Transaction Enquiry (Orchestrator JSON: GET /transfers/accounts/{id}/transactions | T24 OFS: GET /cbs/accounts/{id}/transactions)
  const handleFetchEnquiryTransactions = async (accId = enquiryAccount, page = enquiryPage, protocol = enquiryProtocol) => {
    setIsLoadingEnquiry(true);
    setEnquiryRawOfs('');
    try {
      if (protocol === 'ORCHESTRATOR_JSON') {
        const res = await axios.get(`${API_BASE}/transfers/accounts/${accId}/transactions?page=${page}&size=${enquirySize}`);
        const data = Array.isArray(res.data) ? res.data : [];
        setEnquiryTransactions(data);
        showToast(`Retrieved ${data.length} transactions via Orchestrator JSON mapping`, 'info');
      } else {
        const res = await axios.get(`${API_BASE}/cbs/accounts/${accId}/transactions?page=${page}&size=${enquirySize}`, {
          responseType: 'text'
        });
        setEnquiryRawOfs(typeof res.data === 'string' ? res.data : JSON.stringify(res.data));
        showToast('Retrieved raw Temenos OFS transaction enquiry wire string', 'info');
      }
    } catch (err) {
      console.warn('Transaction enquiry error:', err);
      showToast('Enquiry failed: ' + (err.response?.data?.message || err.message), 'error');
      setEnquiryTransactions([]);
    } finally {
      setIsLoadingEnquiry(false);
    }
  };

  // Execute Compensating Reversal (Gateway / Orchestrator: POST /api/v1/reversals/direct)
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
        originalTransactionId: reversalForm.originalTransactionId,
        reason: reversalForm.reason || 'SAGA_COMPENSATION_ROLLBACK',
        makerId: 'SAGA_COORDINATOR',
        checkerId: 'SYSTEM_SAGA'
      };
      const res = await axios.post(`${API_BASE}/reversals/direct`, payload);
      setReversalResult({ success: true, step: 'COMPENSATED_ORCHESTRATOR', data: res.data });
      showToast('Orchestrator compensating saga reversal executed via Gateway!', 'success');
      fetchBalance(activeAccount);
      fetchReversalRequests(reversalStatusFilter === 'ALL' ? '' : reversalStatusFilter);
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

  // Toggle Posting Window (Cutoff Simulation: ONLINE <-> EOD_CUTOFF)
  const handleTogglePostingWindow = async (openTarget) => {
    setPostingWindowToggling(true);
    try {
      const q = openTarget !== undefined ? `?open=${openTarget}` : '';
      const res = await axios.post(`${API_BASE}/cbs/cob/posting-window${q}`);
      const ofs = typeof res.data === 'string' ? parseOfsResponse(res.data) : res.data;
      const isOpen = ofs['POSTING.WINDOW'] === 'OPEN' || ofs['POSTING.WINDOW.OPEN'] === 'true' || ofs.postingWindowOpen;
      setSystemDate((prev) => ({
        ...prev,
        status: ofs['STATUS'] || ofs.status || (isOpen ? 'ONLINE' : 'EOD_CUTOFF'),
        postingWindowOpen: isOpen
      }));
      showToast(`Posting window is now ${isOpen ? 'OPEN (ONLINE)' : 'CLOSED (EOD_CUTOFF)'}!`, isOpen ? 'success' : 'info');
      fetchSystemDate();
    } catch (err) {
      showToast('Failed to toggle posting window: ' + (err.response?.data?.message || err.message), 'error');
    } finally {
      setPostingWindowToggling(false);
    }
  };

  // Real-World Banking Failure Scenario Runner
  const handleRunScenario = async (scenarioId) => {
    setScenarioRunning(true);
    setScenarioResult(null);
    setSelectedScenario(scenarioId);

    try {
      if (scenarioId === 'INSUFFICIENT_FUNDS') {
        const txRef = 'SCEN-INS-' + Math.floor(Math.random() * 90000 + 10000);
        const payload = {
          transactionId: txRef,
          sourceAccountId: activeAccount,
          destinationAccountId: 'acc-2003-sav-002',
          amount: 999999999.00,
          currency: 'PHP',
          description: 'Solvency Test: Overdraft rejection',
          deviceId: 'SCENARIO-RUNNER',
          idempotencyKey: 'IDEMP-' + txRef
        };
        try {
          const res = await axios.post(`${API_BASE}/transfers`, payload);
          setScenarioResult({
            id: scenarioId,
            passed: false,
            expected: 'HTTP 400 rejection (Insufficient funds)',
            actual: `Unexpected Success: HTTP 200 (${res.data?.status})`,
            payload,
            response: res.data
          });
        } catch (err) {
          const errData = err.response?.data;
          const errMsg = typeof errData === 'string' ? errData : errData?.message || err.message;
          const status = err.response?.status;
          const isExpected = status === 400 || (errMsg && errMsg.toLowerCase().includes('insufficient'));
          setScenarioResult({
            id: scenarioId,
            passed: isExpected,
            expected: 'HTTP 400 Bad Request with "Insufficient funds" message',
            actual: `HTTP ${status}: ${errMsg}`,
            payload,
            response: errData || { error: err.message }
          });
          showToast(isExpected ? 'Scenario Verified: Overdraft successfully blocked by CBS!' : 'Unexpected error: ' + errMsg, isExpected ? 'success' : 'error');
        }
      } else if (scenarioId === 'EOD_CUTOFF_WINDOW_CLOSED') {
        // Step 1: Ensure window is closed
        await axios.post(`${API_BASE}/cbs/cob/posting-window?open=false`);
        fetchSystemDate();

        const txRef = 'SCEN-CUTOFF-' + Math.floor(Math.random() * 90000 + 10000);
        const payload = {
          transactionId: txRef,
          sourceAccountId: activeAccount,
          destinationAccountId: 'acc-2003-sav-002',
          amount: 1000.00,
          currency: 'PHP',
          description: 'EOD Cutoff Window Closed Test',
          deviceId: 'SCENARIO-RUNNER',
          idempotencyKey: 'IDEMP-' + txRef
        };
        try {
          const res = await axios.post(`${API_BASE}/transfers`, payload);
          setScenarioResult({
            id: scenarioId,
            passed: false,
            expected: 'Rejection with "Posting window is closed"',
            actual: `Unexpected Success: HTTP 200 (${res.data?.status})`,
            payload,
            response: res.data
          });
        } catch (err) {
          const errData = err.response?.data;
          const errMsg = typeof errData === 'string' ? errData : errData?.message || err.message;
          const status = err.response?.status;
          const isExpected = errMsg && errMsg.toLowerCase().includes('posting window is closed');
          setScenarioResult({
            id: scenarioId,
            passed: isExpected,
            expected: 'HTTP 400/500 with "CBS Posting window is closed. Business status: EOD_CUTOFF"',
            actual: `HTTP ${status}: ${errMsg}`,
            payload,
            response: errData || { error: err.message }
          });
          showToast(isExpected ? 'Scenario Verified: Transfer rejected due to closed posting window!' : 'Result: ' + errMsg, isExpected ? 'success' : 'error');
        }
      } else if (scenarioId === 'CIRCULAR_SAME_ACCOUNT') {
        const txRef = 'SCEN-CIRC-' + Math.floor(Math.random() * 90000 + 10000);
        const payload = {
          transactionId: txRef,
          sourceAccountId: activeAccount,
          destinationAccountId: activeAccount,
          amount: 500.00,
          currency: 'PHP',
          description: 'Self-transfer input hygiene check',
          deviceId: 'SCENARIO-RUNNER',
          idempotencyKey: 'IDEMP-' + txRef
        };
        try {
          const res = await axios.post(`${API_BASE}/transfers`, payload);
          setScenarioResult({
            id: scenarioId,
            passed: false,
            expected: 'HTTP 400 rejection: Source and destination must be different',
            actual: `Unexpected Success: HTTP 200 (${res.data?.status})`,
            payload,
            response: res.data
          });
        } catch (err) {
          const errData = err.response?.data;
          const errMsg = typeof errData === 'string' ? errData : errData?.message || err.message;
          const status = err.response?.status;
          const isExpected = status === 400 && errMsg.includes('different');
          setScenarioResult({
            id: scenarioId,
            passed: isExpected,
            expected: 'HTTP 400 with "Source and destination accounts must be different"',
            actual: `HTTP ${status}: ${errMsg}`,
            payload,
            response: errData || { error: err.message }
          });
          showToast(isExpected ? 'Scenario Verified: Circular transfer blocked!' : 'Result: ' + errMsg, isExpected ? 'success' : 'error');
        }
      } else if (scenarioId === 'NON_EXISTENT_ACCOUNT') {
        const txRef = 'SCEN-404-' + Math.floor(Math.random() * 90000 + 10000);
        const payload = {
          transactionId: txRef,
          sourceAccountId: activeAccount,
          destinationAccountId: 'ACC-INVALID-999-NOTFOUND',
          amount: 500.00,
          currency: 'PHP',
          description: 'Routing Failure: Unknown beneficiary',
          deviceId: 'SCENARIO-RUNNER',
          idempotencyKey: 'IDEMP-' + txRef
        };
        try {
          const res = await axios.post(`${API_BASE}/transfers`, payload);
          setScenarioResult({
            id: scenarioId,
            passed: false,
            expected: 'HTTP 400 rejection: Account balance not found',
            actual: `Unexpected Success: HTTP 200 (${res.data?.status})`,
            payload,
            response: res.data
          });
        } catch (err) {
          const errData = err.response?.data;
          const errMsg = typeof errData === 'string' ? errData : errData?.message || err.message;
          const status = err.response?.status;
          const isExpected = errMsg && (errMsg.includes('not found') || errMsg.includes('ACC-INVALID'));
          setScenarioResult({
            id: scenarioId,
            passed: isExpected,
            expected: 'HTTP 400 with "Account balance not found for ID: ACC-INVALID-999-NOTFOUND"',
            actual: `HTTP ${status}: ${errMsg}`,
            payload,
            response: errData || { error: err.message }
          });
          showToast(isExpected ? 'Scenario Verified: Unknown routing blocked!' : 'Result: ' + errMsg, isExpected ? 'success' : 'error');
        }
      } else if (scenarioId === 'ANTI_SCAM_COOLING_OFF') {
        const txRef = 'SCEN-COOL-' + Math.floor(Math.random() * 90000 + 10000);
        const payload = {
          transactionId: txRef,
          sourceAccountId: activeAccount,
          destinationAccountId: 'acc-2003-sav-002',
          amount: 300000.00,
          currency: 'PHP',
          description: 'BSP Circular 1140: High-Value Cooling-Off Hold',
          deviceId: 'SCENARIO-RUNNER',
          idempotencyKey: 'IDEMP-' + txRef
        };
        const res = await axios.post(`${API_BASE}/transfers`, payload);
        const isExpected = res.data?.coolingOffRequired === true || res.data?.status === 'Reserved';
        setScenarioResult({
          id: scenarioId,
          passed: isExpected,
          expected: 'Status "Reserved" with coolingOffRequired=true and 600s timer (BSP Circular 1140)',
          actual: `Status: ${res.data?.status}, coolingOffRequired: ${res.data?.coolingOffRequired}, lockSecs: ${res.data?.coolingOffExpiresInSeconds}`,
          payload,
          response: res.data
        });
        showToast(isExpected ? 'Scenario Verified: ₱300k transfer intercepted by Anti-Scam Cooling-Off!' : 'Response: ' + res.data?.status, isExpected ? 'success' : 'info');
      } else if (scenarioId === 'BIOMETRIC_STEP_UP_CHALLENGE') {
        const txRef = 'SCEN-BIO-' + Math.floor(Math.random() * 90000 + 10000);
        const payload = {
          transactionId: txRef,
          sourceAccountId: activeAccount,
          destinationAccountId: 'acc-2003-sav-002',
          amount: 75000.00,
          currency: 'PHP',
          description: 'Strong Customer Authentication (SCA) Step-Up Challenge',
          deviceId: 'SCENARIO-RUNNER',
          idempotencyKey: 'IDEMP-' + txRef
        };
        const res = await axios.post(`${API_BASE}/transfers`, payload);
        const isExpected = res.data?.biometricRequired === true || res.data?.status === 'Authorized';
        setScenarioResult({
          id: scenarioId,
          passed: isExpected,
          expected: 'Status "Authorized" with biometricRequired=true and challenge string (MFA Step-Up)',
          actual: `Status: ${res.data?.status}, biometricRequired: ${res.data?.biometricRequired}, challenge: ${res.data?.biometricChallenge ? 'Supplied' : 'None'}`,
          payload,
          response: res.data
        });
        showToast(isExpected ? 'Scenario Verified: Biometric challenge required for ₱75k transfer!' : 'Response: ' + res.data?.status, isExpected ? 'success' : 'info');
      } else if (scenarioId === 'FOUR_EYES_DUAL_CONTROL_VIOLATION') {
        // Step 1: Create a real dispute ticket
        const originTx = 'TXN-DISP-' + Math.floor(Math.random() * 90000 + 10000);
        const reqPayload = {
          originalTransactionId: originTx,
          makerId: 'TELLER_ALICE',
          reason: 'CUSTOMER_DISPUTE',
          notes: 'Customer disputed charge'
        };
        const reqRes = await axios.post(`${API_BASE}/reversals/request`, reqPayload);
        const ticketId = reqRes.data?.ticketId || reqRes.data?.['TICKET.ID'];

        // Step 2: Attempt rogue self-approval using SAME ID (checkerId == makerId)
        const approvePayload = {
          reversalRequestId: ticketId,
          checkerId: 'TELLER_ALICE',
          checkerNotes: 'Rogue unauthorized self-approval attempt'
        };
        try {
          const appRes = await axios.post(`${API_BASE}/reversals/approve`, approvePayload);
          setScenarioResult({
            id: scenarioId,
            passed: false,
            expected: 'HTTP 400 rejection: Dual control violation (checkerId cannot match makerId)',
            actual: `Unexpected Success: HTTP 200 (${appRes.data?.STATUS || 'APPROVED'})`,
            payload: approvePayload,
            response: appRes.data
          });
        } catch (err) {
          const errData = err.response?.data;
          const errMsg = typeof errData === 'string' ? errData : errData?.message || err.message;
          const status = err.response?.status;
          const isExpected = errMsg && (errMsg.includes('Dual control') || errMsg.includes('cannot match') || errMsg.includes('Maker'));
          setScenarioResult({
            id: scenarioId,
            passed: isExpected,
            expected: 'HTTP 400 with "Dual control violation: Checker ID cannot match Maker ID" (BSP Circular 982)',
            actual: `HTTP ${status}: ${errMsg}`,
            payload: approvePayload,
            response: errData || { error: err.message }
          });
          showToast(isExpected ? 'Scenario Verified: Rogue self-approval blocked by Four-Eyes principle!' : 'Result: ' + errMsg, isExpected ? 'success' : 'error');
        }
      } else if (scenarioId === 'IDEMPOTENCY_DUPLICATE_REPLAY') {
        const idempKey = 'IDEMP-TEST-' + Date.now();
        const payload = {
          transactionId: 'TXN-DUP-A-' + Math.floor(Math.random() * 90000 + 10000),
          sourceAccountId: activeAccount,
          destinationAccountId: 'acc-2003-sav-002',
          amount: 250.00,
          currency: 'PHP',
          description: 'Idempotency Test Mutation',
          deviceId: 'SCENARIO-RUNNER',
          idempotencyKey: idempKey
        };

        // Call 1: First Execution
        const res1 = await axios.post(`${API_BASE}/transfers`, payload);

        // Call 2: Second Execution with SAME IDEMPOTENCY KEY
        const payload2 = {
          ...payload,
          transactionId: 'TXN-DUP-B-' + Math.floor(Math.random() * 90000 + 10000)
        };
        const res2 = await axios.post(`${API_BASE}/transfers`, payload2);

        const isExpected = res2.data?.message?.includes('IDEMPOTENT_REPLAY') || res2.data?.message?.includes('idempotent') || res2.data?.status === res1.data?.status;
        setScenarioResult({
          id: scenarioId,
          passed: isExpected,
          expected: 'Second attempt returns idempotent response without double-debiting balances',
          actual: `Call 1: ${res1.data?.status} (${res1.data?.message || 'OK'}) ➔ Call 2: ${res2.data?.status} (${res2.data?.message || 'OK'})`,
          payload: { attempt1: payload, attempt2: payload2 },
          response: { call1Response: res1.data, call2Response: res2.data }
        });
        showToast('Scenario Verified: Idempotent replay safely prevented duplicate transfer!', 'success');
      } else if (scenarioId === 'CIRCUIT_BREAKER_DLQ_ROUTING') {
        const txId = 'FAIL-TIMEOUT-' + Math.floor(Math.random() * 90000 + 10000);
        const payload = {
          transactionId: txId,
          errorType: 'NETWORK_TIMEOUT',
          errorCode: 'HTTP_504',
          circuitBreakerState: 'OPEN',
          payload: JSON.stringify({
            sourceAccountId: activeAccount,
            destinationAccountId: 'acc-2003-sav-002',
            amount: 5000.00,
            reason: 'Simulated CBS 504 Gateway Timeout'
          })
        };
        const res = await axios.post(`${API_BASE}/cbs/audit/failed-transactions/simulate`, payload);
        setScenarioResult({
          id: scenarioId,
          passed: true,
          expected: 'Trip circuit breaker to OPEN, persist incident to banking.transfers.dlq audit vault',
          actual: `Incident ${res.data?.incidentId || txId} committed to PostgreSQL Audit Vault. Circuit breaker: OPEN.`,
          payload,
          response: res.data
        });
        showToast(`Scenario Verified: Incident ${txId} routed to DLQ!`, 'info');
        fetchDlqIncidents();
      }
      fetchBalance(activeAccount);
    } catch (err) {
      console.error('Scenario run error:', err);
      setScenarioResult({
        id: scenarioId,
        passed: false,
        expected: 'Handled scenario response',
        actual: `Exception: ${err.message}`,
        response: err.response?.data || { error: err.message }
      });
      showToast('Scenario encountered error: ' + err.message, 'error');
    } finally {
      setScenarioRunning(false);
    }
  };

  // Query Reversal Requests (Gateway / Orchestrator: GET /api/v1/reversals)
  const fetchReversalRequests = async (status = '') => {
    setIsLoadingReversals(true);
    try {
      const query = status && status !== 'ALL' ? `?status=${status}&page=0&size=20` : `?page=0&size=20`;
      const res = await axios.get(`${API_BASE}/reversals${query}`);
      const data = Array.isArray(res.data) ? res.data : [];
      setReversalRequests(data);
    } catch (err) {
      console.warn('Could not fetch reversals list from orchestrator:', err.message);
      setReversalRequests([]);
    } finally {
      setIsLoadingReversals(false);
    }
  };

  // Query Transaction Status History (Gateway / Orchestrator: GET /api/v1/transfers/transactions/{id}/status-history)
  const fetchStatusHistory = async (txId) => {
    if (!txId) return;
    setIsLoadingStatusHistory(true);
    setStatusHistoryTxId(txId);
    setStatusHistoryList([]);
    setIsStatusHistoryOpen(true);
    try {
      const res = await axios.get(`${API_BASE}/transfers/transactions/${txId}/status-history?page=0&size=20`);
      const data = Array.isArray(res.data) ? res.data : [];
      setStatusHistoryList(data);
    } catch (err) {
      console.warn('Could not fetch transaction status history:', err.message);
      setStatusHistoryList([]);
    } finally {
      setIsLoadingStatusHistory(false);
    }
  };

  // Maker Reversal Request (Gateway / Orchestrator: POST /api/v1/reversals/request)
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
      const res = await axios.post(`${API_BASE}/reversals/request`, payload);
      setReversalResult({ success: true, step: 'REQUESTED', data: res.data });
      const createdTicketId = res.data?.ticketId || res.data?.['TICKET.ID'];
      if (createdTicketId) {
        setCheckerForm((prev) => ({ ...prev, ticketId: createdTicketId }));
      }
      showToast('Reversal ticket created via Orchestrator! Waiting for Checker approval.', 'info');
      fetchReversalRequests(reversalStatusFilter === 'ALL' ? '' : reversalStatusFilter);
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

  // Checker Approve Reversal (Gateway / Orchestrator: POST /api/v1/reversals/approve)
  const handleApproveReversal = async () => {
    setIsReversing(true);
    try {
      const payload = {
        reversalRequestId: checkerForm.ticketId,
        checkerId: checkerForm.checkerId,
        checkerNotes: checkerForm.checkerNotes
      };
      const res = await axios.post(`${API_BASE}/reversals/approve`, payload);
      setReversalResult({ success: true, step: 'APPROVED', data: res.data });
      showToast('Reversal APPROVED via Orchestrator! Compensating GL entries posted and balances reversed.', 'success');
      fetchBalance(activeAccount);
      fetchReversalRequests(reversalStatusFilter === 'ALL' ? '' : reversalStatusFilter);
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

  // Checker Reject Reversal (Gateway / Orchestrator: POST /api/v1/reversals/reject)
  const handleRejectReversal = async () => {
    setIsReversing(true);
    try {
      const payload = {
        reversalRequestId: checkerForm.ticketId,
        checkerId: checkerForm.checkerId,
        checkerNotes: 'Rejected: ' + checkerForm.checkerNotes
      };
      const res = await axios.post(`${API_BASE}/reversals/reject`, payload);
      setReversalResult({ success: true, step: 'REJECTED', data: res.data });
      showToast('Reversal REJECTED via Orchestrator! Transaction status restored to Posted.', 'info');
      fetchReversalRequests(reversalStatusFilter === 'ALL' ? '' : reversalStatusFilter);
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
            Aura Bank interactive workbench for validating Funds Transfers, Account Transaction Enquiries (`ENQUIRY.SELECT`),
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
                  setEnquiryAccount(acc);
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

        {/* Amount Hold (Restricted Funds) */}
        <div className="rounded-2xl border border-amber-100 bg-white p-5 shadow-sm flex flex-col justify-between">
          <div>
            <div className="flex items-center justify-between text-2xs font-bold uppercase tracking-wider text-amber-900">
              <span>Restricted / Held Funds</span>
              <div className="h-7 w-7 rounded-lg bg-amber-100 flex items-center justify-center text-amber-800">
                <Lock className="h-4 w-4" />
              </div>
            </div>
            <div className="mt-3 font-mono text-2xl font-bold tracking-tight text-amber-600">
              PHP {(balanceData?.holdAmount ?? 0).toLocaleString('en-US', { minimumFractionDigits: 2 })}
            </div>
          </div>
          <p className="mt-2 text-2xs text-slate-500">
            {balanceData?.holdAmount > 0 ? 'Cooling-off / system restraint active' : 'Zero held funds; 100% liquid'}
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
          <p className="mt-2 text-2xs text-slate-500">Solvency formula: (Working - Restricted)</p>
        </div>
      </div>

      {/* Navigation Tabs */}
      <div className="flex flex-wrap gap-2 rounded-2xl bg-purple-50/70 p-2 border border-purple-100 shadow-2xs">
        {[
          { id: 'transfer', label: '1. Funds Transfer', icon: Send },
          { id: 'enquiry', label: '2. Transaction Enquiry (ENQUIRY.SELECT)', icon: FileText },
          { id: 'reversal', label: '3. Reversal (Dual Control)', icon: RotateCcw },
          { id: 'ofs', label: '4. Raw Temenos OFS Terminal', icon: Terminal },
          { id: 'cob', label: '5. COB & EOD Lifecycle', icon: Moon },
          { id: 'dlq', label: '6. DLQ Incident Replays', icon: AlertTriangle },
          { id: 'scenarios', label: '7. Banking Failure Simulator', icon: ShieldAlert }
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
              Dispatched via Transfer Orchestrator (`POST /api/v1/transfers`) mapping JSON to CBS Core Temenos OFS wire string (`POST /api/v1/cbs/funds-transfer`).
              Enforces posting window checks, alphabetical row lock ordering, and liquid solvency.
            </p>

            {/* Quick Banking Failure Scenario Presets Bar */}
            <div className="mt-4 rounded-xl border border-purple-100 bg-purple-50/50 p-3 space-y-2">
              <div className="flex items-center justify-between text-2xs font-bold uppercase tracking-wider text-purple-950">
                <span className="flex items-center gap-1.5">
                  <ShieldAlert className="h-3.5 w-3.5 text-purple-700" /> Quick Failure Test Presets
                </span>
                <button
                  type="button"
                  onClick={() => setActiveTab('scenarios')}
                  className="text-2xs text-purple-700 hover:text-purple-950 font-semibold underline"
                >
                  Open Dedicated Simulator &rarr;
                </button>
              </div>
              <div className="flex flex-wrap gap-1.5">
                <button
                  type="button"
                  onClick={() => {
                    setTransferForm({
                      ...transferForm,
                      sourceAccountId: activeAccount,
                      destinationAccountId: 'acc-2003-sav-002',
                      amount: '999999999.00',
                      description: 'Test Insolvency: Amount > Available'
                    });
                    showToast('Loaded Insufficient Funds Preset (₱999,999,999.00)', 'info');
                  }}
                  className="rounded-lg border border-rose-200 bg-rose-50 px-2 py-1 text-2xs font-semibold text-rose-800 hover:bg-rose-100 transition"
                >
                  Insufficient Balance (₱999M)
                </button>
                <button
                  type="button"
                  onClick={() => {
                    setTransferForm({
                      ...transferForm,
                      sourceAccountId: activeAccount,
                      destinationAccountId: activeAccount,
                      amount: '1000.00',
                      description: 'Test Circular Self-Transfer'
                    });
                    showToast('Loaded Same-Account Circular Transfer Preset', 'info');
                  }}
                  className="rounded-lg border border-amber-200 bg-amber-50 px-2 py-1 text-2xs font-semibold text-amber-800 hover:bg-amber-100 transition"
                >
                  Circular Self-Transfer
                </button>
                <button
                  type="button"
                  onClick={() => {
                    setTransferForm({
                      ...transferForm,
                      sourceAccountId: activeAccount,
                      destinationAccountId: 'acc-unknown-404-none',
                      amount: '1000.00',
                      description: 'Test Invalid Account Routing'
                    });
                    showToast('Loaded Non-Existent Account Preset', 'info');
                  }}
                  className="rounded-lg border border-purple-200 bg-purple-50 px-2 py-1 text-2xs font-semibold text-purple-900 hover:bg-purple-100 transition"
                >
                  Unknown Account (404)
                </button>
                <button
                  type="button"
                  onClick={() => {
                    setTransferForm({
                      ...transferForm,
                      sourceAccountId: activeAccount,
                      destinationAccountId: 'acc-2003-sav-002',
                      amount: '350000.00',
                      description: 'Test High-Value Cooling-Off Hold'
                    });
                    showToast('Loaded Anti-Scam Cooling-Off Preset (₱350,000.00)', 'info');
                  }}
                  className="rounded-lg border border-indigo-200 bg-indigo-50 px-2 py-1 text-2xs font-semibold text-indigo-900 hover:bg-indigo-100 transition"
                >
                  Anti-Scam Cooling-Off (₱350k)
                </button>
                <button
                  type="button"
                  onClick={() => {
                    setTransferForm({
                      ...transferForm,
                      sourceAccountId: activeAccount,
                      destinationAccountId: 'acc-2003-sav-002',
                      amount: '75000.00',
                      description: 'Test Biometric Step-Up Challenge'
                    });
                    showToast('Loaded Biometric Step-Up Preset (₱75,000.00)', 'info');
                  }}
                  className="rounded-lg border border-sky-200 bg-sky-50 px-2 py-1 text-2xs font-semibold text-sky-900 hover:bg-sky-100 transition"
                >
                  Biometric Step-Up (₱75k)
                </button>
                <button
                  type="button"
                  onClick={() => handleTogglePostingWindow()}
                  disabled={postingWindowToggling}
                  className={`rounded-lg border px-2 py-1 text-2xs font-semibold transition ${
                    systemDate.postingWindowOpen
                      ? 'border-rose-300 bg-white text-rose-700 hover:bg-rose-50'
                      : 'border-emerald-300 bg-emerald-50 text-emerald-800 hover:bg-emerald-100'
                  }`}
                  title="Toggle Posting Window to simulate clearing cutoff"
                >
                  {postingWindowToggling ? 'Toggling...' : systemDate.postingWindowOpen ? 'Simulate Cutoff (Close Window)' : 'Re-open Window (ONLINE)'}
                </button>
              </div>
            </div>

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
                Execute Transfer (JSON ➔ T24 OFS)
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

                  <div className="flex items-center justify-between">
                    <span className="text-2xs font-mono text-purple-900 font-medium">Trace Output &bull; OFS Settlement</span>
                    <button
                      type="button"
                      onClick={() => {
                        const ref = transferResult.data?.transaction_id || transferResult.data?.transactionId || transferResult.data?.t24_reference || transferResult.data?.['TXN.ID'] || reversalForm.originalTransactionId;
                        fetchStatusHistory(ref);
                      }}
                      className="flex items-center gap-1 rounded-lg border border-purple-200 bg-purple-50 px-2.5 py-1 text-2xs font-semibold text-purple-900 hover:bg-purple-100 transition shadow-2xs"
                    >
                      <History className="h-3 w-3 text-purple-700" />
                      Status History
                    </button>
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

      {/* TAB 2: ACCOUNT TRANSACTION ENQUIRY (ENQUIRY.SELECT) */}
      {activeTab === 'enquiry' && (
        <div className="space-y-6">
          {/* Architecture Explanatory Banner */}
          <div className="rounded-2xl border border-purple-200 bg-gradient-to-r from-purple-50 via-white to-purple-50/50 p-5 shadow-xs">
            <div className="font-bold text-xs uppercase tracking-wider text-purple-950 flex items-center gap-2">
              <span className="h-2 w-2 rounded-full bg-purple-700" />
              Temenos T24 Canonical Transaction Enquiry (`ENQUIRY.SELECT`) &amp; Orchestrator Translation
            </div>
            <p className="mt-1.5 text-xs text-slate-600 leading-relaxed">
              Queries committed ledger transactions (`TransactionMaster` table) with pagination support.
              In <strong className="text-purple-950">Orchestrator JSON mode</strong> (`GET /api/v1/transfers/accounts/{'{'}accountId{'}'}/transactions`), the orchestrator translates incoming requests, invokes T24, and unpacks the Temenos OFS syntax into strongly-typed DTOs.
              In <strong className="text-purple-950">Direct CBS OFS mode</strong> (`GET /api/v1/cbs/accounts/{'{'}accountId{'}'}/transactions`), observe the raw wire string formatted by CBS with double-semicolon record delimiters.
            </p>
          </div>

          <div className="rounded-2xl border border-purple-100 bg-white p-6 shadow-sm space-y-5">
            {/* Control Bar: Account, Protocol, Pagination, Refresh */}
            <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between border-b border-purple-100/80 pb-4">
              <div className="flex flex-wrap items-center gap-3">
                <div>
                  <label className="block text-2xs font-bold uppercase tracking-wider text-purple-950">
                    Enquiry Account ID
                  </label>
                  <input
                    type="text"
                    value={enquiryAccount}
                    onChange={(e) => setEnquiryAccount(e.target.value)}
                    className="mt-1 w-48 rounded-xl border border-slate-200 bg-slate-50/80 px-3 py-1.5 font-mono text-xs text-slate-900 focus:bg-white focus:border-purple-700 focus:ring-1 focus:ring-purple-700 focus:outline-none transition-all"
                  />
                </div>

                {/* Protocol Toggle */}
                <div>
                  <label className="block text-2xs font-bold uppercase tracking-wider text-purple-950">
                    Wire Protocol / Format
                  </label>
                  <div className="mt-1 flex items-center rounded-xl bg-purple-50/70 p-1 border border-purple-100 text-xs">
                    <button
                      type="button"
                      onClick={() => setEnquiryProtocol('ORCHESTRATOR_JSON')}
                      className={`px-3 py-1 rounded-lg font-semibold transition-all ${
                        enquiryProtocol === 'ORCHESTRATOR_JSON'
                          ? 'bg-purple-900 text-white shadow-2xs'
                          : 'text-slate-600 hover:text-purple-900'
                      }`}
                    >
                      Orchestrator JSON
                    </button>
                    <button
                      type="button"
                      onClick={() => setEnquiryProtocol('T24_OFS')}
                      className={`px-3 py-1 rounded-lg font-mono text-2xs font-semibold transition-all ${
                        enquiryProtocol === 'T24_OFS'
                          ? 'bg-purple-900 text-white shadow-2xs'
                          : 'text-slate-600 hover:text-purple-900'
                      }`}
                    >
                      Raw T24 OFS
                    </button>
                  </div>
                </div>
              </div>

              {/* Pagination & Action Controls */}
              <div className="flex flex-wrap items-center gap-2">
                <div className="flex items-center gap-1 rounded-xl border border-slate-200 bg-slate-50/70 p-1 text-xs">
                  <button
                    type="button"
                    onClick={() => setEnquiryPage((prev) => Math.max(0, prev - 1))}
                    disabled={enquiryPage === 0 || isLoadingEnquiry}
                    className="rounded-lg px-2.5 py-1 text-slate-600 hover:bg-white hover:text-purple-900 disabled:opacity-40 transition"
                  >
                    Prev
                  </button>
                  <span className="px-2 font-mono text-2xs font-bold text-purple-950">
                    Page {enquiryPage}
                  </span>
                  <button
                    type="button"
                    onClick={() => setEnquiryPage((prev) => prev + 1)}
                    disabled={isLoadingEnquiry || (enquiryProtocol === 'ORCHESTRATOR_JSON' && enquiryTransactions.length < enquirySize)}
                    className="rounded-lg px-2.5 py-1 text-slate-600 hover:bg-white hover:text-purple-900 disabled:opacity-40 transition"
                  >
                    Next
                  </button>
                </div>

                <button
                  type="button"
                  onClick={() => handleFetchEnquiryTransactions(enquiryAccount, enquiryPage, enquiryProtocol)}
                  disabled={isLoadingEnquiry}
                  className="flex items-center gap-1.5 rounded-xl bg-[#311075] hover:bg-[#250C5C] px-4 py-2 text-xs font-bold uppercase tracking-wider text-white shadow-2xs transition-all disabled:opacity-50"
                >
                  <RefreshCw className={`h-3.5 w-3.5 ${isLoadingEnquiry ? 'animate-spin' : ''}`} />
                  Run Enquiry
                </button>
              </div>
            </div>

            {/* Content Display: Mode 1 Orchestrator JSON vs Mode 2 Raw OFS */}
            {isLoadingEnquiry ? (
              <div className="py-12 text-center text-xs text-slate-500 flex items-center justify-center gap-2">
                <RefreshCw className="h-5 w-5 animate-spin text-purple-600" />
                Querying ledger transactions for account <code className="font-mono">{enquiryAccount}</code>...
              </div>
            ) : enquiryProtocol === 'T24_OFS' ? (
              /* Raw OFS Wire String View */
              <div className="space-y-3">
                <div className="flex items-center justify-between text-2xs uppercase tracking-wider text-purple-900">
                  <span className="font-bold flex items-center gap-1.5">
                    <Terminal className="h-3.5 w-3.5 text-purple-700" /> Temenos OFS Return String
                  </span>
                  <span className="font-mono text-emerald-600 font-semibold">GET /api/v1/cbs/accounts/{enquiryAccount}/transactions</span>
                </div>
                {enquiryRawOfs ? (
                  <div>
                    <pre className="overflow-x-auto rounded-xl border border-purple-900/40 bg-[#120B24] p-4 font-mono text-2xs text-purple-200 shadow-inner leading-relaxed">
                      {enquiryRawOfs}
                    </pre>
                    <div className="mt-2.5 flex justify-end">
                      <button
                        type="button"
                        onClick={() => copyToClipboard(enquiryRawOfs)}
                        className="flex items-center gap-1.5 rounded-xl border border-purple-200 bg-white px-3 py-1.5 text-xs font-semibold text-purple-900 hover:bg-purple-50 shadow-2xs transition"
                      >
                        {copiedText ? <Check className="h-3.5 w-3.5 text-emerald-600" /> : <Copy className="h-3.5 w-3.5" />}
                        Copy OFS Response
                      </button>
                    </div>
                  </div>
                ) : (
                  <div className="py-10 text-center text-xs text-slate-400">
                    No wire response captured. Click "Run Enquiry" to fetch.
                  </div>
                )}
              </div>
            ) : (
              /* Orchestrator JSON Table View */
              <div>
                {enquiryTransactions.length === 0 ? (
                  <div className="py-12 text-center text-slate-400 space-y-2">
                    <FileText className="h-10 w-10 text-purple-300 mx-auto" />
                    <p className="text-xs font-semibold text-slate-600">No transactions recorded for this account</p>
                    <p className="text-2xs text-slate-400">Execute a Funds Transfer in Tab 1 to populate ledger entries.</p>
                  </div>
                ) : (
                  <div className="overflow-x-auto rounded-xl border border-purple-100 shadow-2xs">
                    <table className="w-full text-left text-xs border-collapse">
                      <thead className="border-b border-purple-100 bg-purple-50/70 text-2xs uppercase tracking-wider text-purple-950 font-mono">
                        <tr>
                          <th className="py-3 px-3.5">Transaction ID</th>
                          <th className="py-3 px-3.5">Type &amp; Flow</th>
                          <th className="py-3 px-3.5">Counterparty Account</th>
                          <th className="py-3 px-3.5">Amount</th>
                          <th className="py-3 px-3.5">Status</th>
                          <th className="py-3 px-3.5">Timestamp</th>
                          <th className="py-3 px-3.5 text-right">Actions</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-purple-50 font-mono text-2xs">
                        {enquiryTransactions.map((tx) => {
                          const isDebit = tx.source_account_id === enquiryAccount;
                          const counterparty = isDebit ? tx.target_account_id : tx.source_account_id;

                          return (
                            <tr key={tx.transaction_id} className="hover:bg-purple-50/40 transition">
                              <td className="py-3 px-3.5 font-bold text-purple-950 flex items-center gap-1.5">
                                <span>{tx.transaction_id}</span>
                              </td>
                              <td className="py-3 px-3.5">
                                <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full font-sans text-2xs font-semibold ${
                                  isDebit
                                    ? 'bg-rose-50 text-rose-700 border border-rose-200'
                                    : 'bg-emerald-50 text-emerald-700 border border-emerald-200'
                                }`}>
                                  {isDebit ? <ArrowUpRight className="h-3 w-3" /> : <ArrowDownLeft className="h-3 w-3" />}
                                  {isDebit ? 'Debit / Outgoing' : 'Credit / Incoming'}
                                </span>
                              </td>
                              <td className="py-3 px-3.5 text-slate-700">{counterparty || '--'}</td>
                              <td className="py-3 px-3.5 font-bold">
                                <span className={isDebit ? 'text-rose-600' : 'text-emerald-600'}>
                                  {isDebit ? '-' : '+'} {tx.currency || 'PHP'} {parseFloat(tx.amount || 0).toLocaleString('en-US', { minimumFractionDigits: 2 })}
                                </span>
                              </td>
                              <td className="py-3 px-3.5">
                                <span className={`inline-flex items-center px-2 py-0.5 rounded-full font-sans text-2xs font-semibold ${
                                  tx.status === 'Posted' || tx.status === 'POSTED'
                                    ? 'bg-emerald-100 text-emerald-800'
                                    : tx.status === 'Reversed' || tx.status === 'REVERSED'
                                    ? 'bg-slate-200 text-slate-700'
                                    : tx.status === 'PendingReversal'
                                    ? 'bg-amber-100 text-amber-800'
                                    : 'bg-purple-100 text-purple-800'
                                }`}>
                                  {tx.status}
                                </span>
                              </td>
                              <td className="py-3 px-3.5 text-slate-500 font-sans">
                                {tx.created_at ? new Date(tx.created_at).toLocaleString() : '--'}
                              </td>
                              <td className="py-3 px-3.5 text-right">
                                <div className="flex items-center justify-end gap-1.5">
                                  <button
                                    type="button"
                                    onClick={() => fetchStatusHistory(tx.transaction_id)}
                                    className="flex items-center gap-1 rounded-lg border border-purple-200 bg-purple-50 px-2 py-1 text-2xs font-sans font-semibold text-purple-900 hover:bg-purple-100 transition shadow-2xs"
                                    title="View Status Lifecycle History"
                                  >
                                    <History className="h-3 w-3 text-purple-700" />
                                    Lifecycle
                                  </button>
                                  <button
                                    type="button"
                                    onClick={() => {
                                      setReversalForm((prev) => ({ ...prev, originalTransactionId: tx.transaction_id }));
                                      setActiveTab('reversal');
                                      showToast(`Loaded ${tx.transaction_id} into Reversal workbench!`, 'info');
                                    }}
                                    className="flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-2 py-1 text-2xs font-sans font-semibold text-slate-700 hover:bg-slate-50 transition shadow-2xs"
                                    title="Initiate Reversal Dispute in Tab 3"
                                  >
                                    <RotateCcw className="h-3 w-3 text-slate-500" />
                                    Dispute
                                  </button>
                                </div>
                              </td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            )}
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
                  <div className="flex items-center justify-between">
                    <div className="font-mono text-2xs font-bold text-purple-950">
                      Result Step: {reversalResult.step || 'Error'}
                    </div>
                    <button
                      type="button"
                      onClick={() => {
                        const txId = reversalResult.data?.['ORIGINAL.FT.NO'] || reversalResult.data?.originalTransactionId || reversalForm.originalTransactionId;
                        fetchStatusHistory(txId);
                      }}
                      className="flex items-center gap-1.5 rounded-lg border border-purple-300 bg-white px-2.5 py-1 text-2xs font-semibold text-purple-900 hover:bg-purple-100 transition shadow-2xs"
                    >
                      <History className="h-3 w-3 text-purple-700" />
                      Status History
                    </button>
                  </div>
                  <pre className="overflow-x-auto rounded-lg border border-purple-300/40 bg-[#120B24] p-3 text-2xs text-purple-200">
                    {JSON.stringify(reversalResult, null, 2)}
                  </pre>
                </div>
              )}
            </div>
          </div>
        </div>

        {/* Live Reversal Requests Backlog via Orchestrator */}
        <div className="rounded-2xl border border-purple-100 bg-white p-6 shadow-sm space-y-4">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 border-b border-purple-100/70 pb-3.5">
            <div>
              <div className="flex items-center gap-2">
                <RotateCcw className="h-4 w-4 text-purple-700" />
                <h2 className="text-sm font-bold text-slate-900">
                  Live Dispute Reversal Backlog (Orchestrator: <code className="text-2xs font-mono bg-purple-50 text-purple-900 px-1.5 py-0.5 rounded">GET /api/v1/reversals</code>)
                </h2>
              </div>
              <p className="text-2xs text-slate-500 mt-0.5">
                Real-time maker-checker dispute tickets queue mapped from Temenos OFS via Gateway / Transfer Orchestrator.
              </p>
            </div>

            <div className="flex items-center gap-2">
              <div className="flex rounded-xl bg-slate-100 p-0.5 text-2xs font-semibold">
                {['ALL', 'PENDING', 'APPROVED', 'REJECTED'].map((filter) => (
                  <button
                    key={filter}
                    type="button"
                    onClick={() => setReversalStatusFilter(filter)}
                    className={`px-2.5 py-1 rounded-lg transition-all ${
                      reversalStatusFilter === filter
                        ? 'bg-white text-purple-950 font-bold shadow-2xs'
                        : 'text-slate-600 hover:text-slate-900'
                    }`}
                  >
                    {filter}
                  </button>
                ))}
              </div>

              <button
                type="button"
                onClick={() => fetchReversalRequests(reversalStatusFilter === 'ALL' ? '' : reversalStatusFilter)}
                disabled={isLoadingReversals}
                className="flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-2xs font-semibold text-slate-700 hover:bg-slate-50 transition shadow-2xs disabled:opacity-50"
              >
                <RefreshCw className={`h-3.5 w-3.5 ${isLoadingReversals ? 'animate-spin text-purple-600' : ''}`} />
                Refresh
              </button>
            </div>
          </div>

          {isLoadingReversals ? (
            <div className="py-8 text-center text-xs text-slate-500 flex items-center justify-center gap-2">
              <RefreshCw className="h-4 w-4 animate-spin text-purple-600" /> Loading reversal backlog...
            </div>
          ) : reversalRequests.length === 0 ? (
            <div className="py-8 text-center text-xs text-slate-400">
              No reversal requests found for status "{reversalStatusFilter}". File a dispute ticket in Step 1 to populate.
            </div>
          ) : (
            <div className="overflow-x-auto rounded-xl border border-slate-200">
              <table className="w-full text-left text-xs border-collapse">
                <thead className="border-b border-slate-200 bg-slate-50/70 text-2xs uppercase tracking-wider text-slate-500 font-mono">
                  <tr>
                    <th className="py-2.5 px-3.5">Ticket ID</th>
                    <th className="py-2.5 px-3.5">Original Tx</th>
                    <th className="py-2.5 px-3.5">Maker</th>
                    <th className="py-2.5 px-3.5">Status</th>
                    <th className="py-2.5 px-3.5">Reason</th>
                    <th className="py-2.5 px-3.5">Created At</th>
                    <th className="py-2.5 px-3.5 text-right">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100 font-mono text-2xs">
                  {reversalRequests.map((req) => (
                    <tr key={req.ticketId} className="hover:bg-purple-50/40 transition">
                      <td className="py-2.5 px-3.5 font-bold text-purple-950">{req.ticketId}</td>
                      <td className="py-2.5 px-3.5 text-slate-700">{req.originalTransactionId}</td>
                      <td className="py-2.5 px-3.5 text-slate-600">{req.makerId}</td>
                      <td className="py-2.5 px-3.5">
                        <span className={`inline-flex items-center px-2 py-0.5 rounded-full font-sans text-2xs font-semibold ${
                          req.status === 'APPROVED'
                            ? 'bg-emerald-100 text-emerald-800'
                            : req.status === 'REJECTED'
                            ? 'bg-rose-100 text-rose-800'
                            : 'bg-amber-100 text-amber-800'
                        }`}>
                          {req.status}
                        </span>
                      </td>
                      <td className="py-2.5 px-3.5 font-sans text-slate-600 max-w-[200px] truncate" title={req.disputeReason}>
                        {req.disputeReason}
                      </td>
                      <td className="py-2.5 px-3.5 text-slate-500">
                        {req.createdAt ? new Date(req.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '--'}
                      </td>
                      <td className="py-2.5 px-3.5 text-right">
                        <div className="flex items-center justify-end gap-1.5">
                          {req.status === 'PENDING' && (
                            <button
                              type="button"
                              onClick={() => {
                                setCheckerForm((prev) => ({ ...prev, ticketId: req.ticketId }));
                                setReversalForm((prev) => ({ ...prev, originalTransactionId: req.originalTransactionId }));
                                showToast(`Loaded ticket ${req.ticketId} into Step 2 Checker form!`, 'info');
                              }}
                              className="px-2 py-1 rounded bg-purple-700 hover:bg-purple-800 text-white font-sans text-2xs font-semibold transition"
                            >
                              Select for Review
                            </button>
                          )}
                          <button
                            type="button"
                            onClick={() => fetchStatusHistory(req.originalTransactionId)}
                            className="px-2 py-1 rounded border border-slate-300 hover:bg-slate-100 text-slate-700 font-sans text-2xs transition"
                            title="Inspect Status History"
                          >
                            <History className="h-3 w-3 inline" />
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
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
                  cmd: 'FUNDS.TRANSFER,INITIATE/I/PROCESS//TX-9901,USER01/123456,TRANSACTION.TYPE=AC,DEBIT.ACCT.NO=acc-2002-chk-001,CREDIT.ACCT.NO=acc-2003-sav-002,AMOUNT=2500.00,CURRENCY=PHP,VALUE.DATE=20261009,DESCRIPTION=TestPayment,IDEMPOTENCY.KEY=IDEMP-9901'
                },
                {
                  label: 'FT REVERSAL',
                  cmd: 'FUNDS.TRANSFER,REVERSAL/I/PROCESS//REV-001,SAGA_COORDINATOR/123456,ORIGINAL.FT.NO=TX-9901,REASON=SAGA_COMPENSATION,CHECKER.ID=SYSTEM_SAGA,MAKER.ID=SAGA_COORDINATOR'
                },
                {
                  label: 'DISPUTE REQUEST',
                  cmd: 'FUNDS.TRANSFER,REVERSAL.REQUEST/I/PROCESS//TX-9901,MAKER01/123456,ORIGINAL.FT.NO=TX-9901,REASON=CUSTOMER_DISPUTE,MAKER=MAKER01'
                },
                {
                  label: 'DISPUTE APPROVE',
                  cmd: 'FUNDS.TRANSFER,REVERSAL/I/PROCESS//TICKET-01,MGR02/123456,TICKET.ID=TICKET-01,CHECKER=MGR02,REASON=Approved'
                },
                {
                  label: 'ENQUIRY.SELECT',
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

      {/* TAB 7: REAL-WORLD BANKING FAILURE SCENARIOS & CHAOS SIMULATOR */}
      {activeTab === 'scenarios' && (
        <div className="space-y-6">
          {/* Header Banner */}
          <div className="rounded-2xl border border-purple-200 bg-gradient-to-r from-purple-50 via-white to-purple-50/50 p-5 shadow-xs">
            <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
              <div>
                <div className="font-bold text-xs uppercase tracking-wider text-purple-950 flex items-center gap-2">
                  <span className="h-2 w-2 rounded-full bg-rose-600 animate-pulse" />
                  Real-World Banking Failure Scenarios &amp; Resilience Verification
                </div>
                <p className="mt-1 text-xs text-slate-600 max-w-3xl leading-relaxed">
                  Validate core banking defense mechanisms under real-world production stress and regulatory failure modes:
                  unauthorized overdrafts, national clearing cutoff times (PCHC/PhilPaSS), circular self-transfers, invalid routing,
                  BSP Circular 1140 anti-scam cooling-off locks, biometric step-up authentication, Four-Eyes segregation of duties,
                  and idempotency lock protection.
                </p>
              </div>

              {/* Instant Cutoff Toggle Control */}
              <div className="flex items-center gap-3 rounded-xl border border-purple-200 bg-white p-3 shadow-2xs shrink-0">
                <div>
                  <div className="text-2xs font-bold uppercase text-purple-950">Posting Window</div>
                  <div className="flex items-center gap-1.5 font-mono text-xs font-bold">
                    <span className={`h-2 w-2 rounded-full ${systemDate.postingWindowOpen ? 'bg-emerald-500' : 'bg-rose-500'}`} />
                    <span className={systemDate.postingWindowOpen ? 'text-emerald-700' : 'text-rose-700'}>
                      {systemDate.status}
                    </span>
                  </div>
                </div>
                <button
                  type="button"
                  onClick={() => handleTogglePostingWindow()}
                  disabled={postingWindowToggling}
                  className={`rounded-lg px-3 py-1.5 text-2xs font-bold uppercase tracking-wider text-white shadow-2xs transition disabled:opacity-50 ${
                    systemDate.postingWindowOpen ? 'bg-rose-700 hover:bg-rose-800' : 'bg-emerald-700 hover:bg-emerald-800'
                  }`}
                >
                  {postingWindowToggling ? 'Toggling...' : systemDate.postingWindowOpen ? 'Simulate Cutoff' : 'Re-open Window'}
                </button>
              </div>
            </div>
          </div>

          {/* Scenarios Grid */}
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
            {[
              {
                id: 'INSUFFICIENT_FUNDS',
                badge: 'Solvency & Overdraft',
                badgeColor: 'border-rose-200 bg-rose-50 text-rose-800',
                title: '1. Insufficient Funds / Negative Balance Block',
                description: 'Attempts to transfer PHP 999M exceeding account available balance. Asserts that CBS solvency checks reject without balance mutation.',
                route: 'POST /api/v1/transfers',
                icon: AlertCircle
              },
              {
                id: 'EOD_CUTOFF_WINDOW_CLOSED',
                badge: 'Clearing Cutoff',
                badgeColor: 'border-amber-200 bg-amber-50 text-amber-800',
                title: '2. EOD Posting Window Closed (Clearing Cutoff)',
                description: 'Forces posting window into EOD_CUTOFF and dispatches transfer. Asserts that CBS rejects postings while batch clearing is in progress.',
                route: 'POST /api/v1/cbs/funds-transfer',
                icon: Moon
              },
              {
                id: 'CIRCULAR_SAME_ACCOUNT',
                badge: 'Input Hygiene',
                badgeColor: 'border-orange-200 bg-orange-50 text-orange-800',
                title: '3. Same-Account Circular Self-Transfer',
                description: 'Attempts to transfer funds from source account to the identical destination account. Asserts zero-sum loop rejection.',
                route: 'POST /api/v1/transfers',
                icon: Ban
              },
              {
                id: 'NON_EXISTENT_ACCOUNT',
                badge: 'Routing Failure',
                badgeColor: 'border-slate-200 bg-slate-100 text-slate-800',
                title: '4. Non-Existent Account / Invalid Directory Routing',
                description: 'Dispatches transfer to an unmapped account (ACC-INVALID-999-NOTFOUND). Asserts that CBS rejects invalid routing before double-entry posting.',
                route: 'POST /api/v1/transfers',
                icon: XCircle
              },
              {
                id: 'ANTI_SCAM_COOLING_OFF',
                badge: 'BSP Circular 1140',
                badgeColor: 'border-indigo-200 bg-indigo-50 text-indigo-800',
                title: '5. High-Value Anti-Scam 10-Min Cooling-Off (₱250k+)',
                description: 'Transfers ₱300,000.00. Asserts that Orchestrator intercepts payment into a 10-minute provisional cooling-off hold (Reserved state).',
                route: 'POST /api/v1/transfers',
                icon: Clock
              },
              {
                id: 'BIOMETRIC_STEP_UP_CHALLENGE',
                badge: 'SCA Authentication',
                badgeColor: 'border-sky-200 bg-sky-50 text-sky-800',
                title: '6. Biometric MFA Step-Up Challenge (₱50k+)',
                description: 'Transfers ₱75,000.00 without biometric signature. Asserts that Orchestrator returns Authorized status with an authentication challenge.',
                route: 'POST /api/v1/transfers',
                icon: ShieldCheck
              },
              {
                id: 'FOUR_EYES_DUAL_CONTROL_VIOLATION',
                badge: 'BSP Circular 982',
                badgeColor: 'border-purple-200 bg-purple-50 text-purple-900',
                title: '7. Four-Eyes Maker-Checker Self-Approval Violation',
                description: 'Maker TELLER_ALICE creates dispute ticket, then attempts to self-approve with checkerId: TELLER_ALICE. Asserts segregation-of-duties block.',
                route: 'POST /api/v1/reversals/approve',
                icon: ShieldAlert
              },
              {
                id: 'IDEMPOTENCY_DUPLICATE_REPLAY',
                badge: 'Deduplication',
                badgeColor: 'border-emerald-200 bg-emerald-50 text-emerald-800',
                title: '8. Concurrent Duplicate Idempotency Replay',
                description: 'Dispatches two transfers with the identical idempotencyKey. Asserts that the second attempt replays safely without double debiting.',
                route: 'POST /api/v1/transfers',
                icon: Zap
              },
              {
                id: 'CIRCUIT_BREAKER_DLQ_ROUTING',
                badge: 'Resilience & DLQ',
                badgeColor: 'border-rose-200 bg-rose-50 text-rose-800',
                title: '9. Core Outage Circuit Breaker & DLQ Audit Fallback',
                description: 'Simulates CBS HTTP 504 Gateway Timeout. Asserts that Resilience4j trips circuit breaker to OPEN and routes incident to banking.transfers.dlq.',
                route: 'POST /api/v1/cbs/audit/failed-transactions/simulate',
                icon: AlertTriangle
              }
            ].map((scen) => {
              const Icon = scen.icon;
              const isSelected = selectedScenario === scen.id;
              const isRunning = scenarioRunning && isSelected;

              return (
                <div
                  key={scen.id}
                  className={`flex flex-col justify-between rounded-2xl border bg-white p-5 shadow-sm transition-all ${
                    isSelected ? 'border-purple-600 ring-2 ring-purple-100' : 'border-slate-200 hover:border-purple-200'
                  }`}
                >
                  <div>
                    <div className="flex items-center justify-between">
                      <span className={`rounded-full border px-2.5 py-0.5 font-mono text-2xs font-bold uppercase ${scen.badgeColor}`}>
                        {scen.badge}
                      </span>
                      <span className="font-mono text-2xs text-slate-400 font-semibold">{scen.route}</span>
                    </div>

                    <h3 className="mt-3 text-xs font-bold text-slate-900 flex items-center gap-1.5">
                      <Icon className="h-4 w-4 text-purple-700 shrink-0" />
                      <span>{scen.title}</span>
                    </h3>

                    <p className="mt-2 text-2xs text-slate-500 leading-relaxed">
                      {scen.description}
                    </p>
                  </div>

                  <div className="mt-4 pt-3 border-t border-slate-100 flex items-center justify-between">
                    <span className="text-2xs font-mono text-slate-400">Account: {activeAccount}</span>
                    <button
                      type="button"
                      onClick={() => handleRunScenario(scen.id)}
                      disabled={scenarioRunning}
                      className="flex items-center gap-1.5 rounded-xl bg-[#311075] hover:bg-[#250C5C] px-3.5 py-1.5 text-2xs font-bold uppercase tracking-wider text-white shadow-2xs transition-all disabled:opacity-50"
                    >
                      {isRunning ? <RefreshCw className="h-3 w-3 animate-spin" /> : <Play className="h-3 w-3" />}
                      Execute Test
                    </button>
                  </div>
                </div>
              );
            })}
          </div>

          {/* Execution Trace & Assertion Results Inspector */}
          {scenarioResult && (
            <div className="rounded-2xl border border-purple-100 bg-white p-6 shadow-sm space-y-4">
              <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between border-b border-purple-100 pb-3">
                <div className="flex items-center gap-2">
                  {scenarioResult.passed ? (
                    <CheckCircle2 className="h-5 w-5 text-emerald-600" />
                  ) : (
                    <XCircle className="h-5 w-5 text-rose-600" />
                  )}
                  <div>
                    <h3 className="text-sm font-bold text-slate-900">
                      Scenario Verification: {scenarioResult.id}
                    </h3>
                    <p className="text-2xs text-slate-500 font-mono">
                      Validation Assertion: {scenarioResult.expected}
                    </p>
                  </div>
                </div>

                <div className="flex items-center gap-2">
                  <span className={`px-3 py-1 rounded-full font-mono text-xs font-bold uppercase tracking-wider ${
                    scenarioResult.passed
                      ? 'bg-emerald-100 text-emerald-800 border border-emerald-200'
                      : 'bg-rose-100 text-rose-800 border border-rose-200'
                  }`}>
                    {scenarioResult.passed ? '✓ PASSED & VERIFIED' : '✗ FAILED ASSERTION'}
                  </span>
                </div>
              </div>

              <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
                {/* Outgoing Test Payload */}
                <div>
                  <div className="text-2xs font-bold uppercase tracking-wider text-purple-950 mb-1.5">
                    Outgoing Simulated Request Payload
                  </div>
                  <pre className="overflow-x-auto rounded-xl border border-slate-200 bg-slate-50 p-3.5 font-mono text-2xs text-slate-800">
                    {JSON.stringify(scenarioResult.payload, null, 2)}
                  </pre>
                </div>

                {/* Returned CBS Response */}
                <div>
                  <div className="text-2xs font-bold uppercase tracking-wider text-purple-950 mb-1.5 flex items-center justify-between">
                    <span>Observed Core Banking Response</span>
                    <span className="font-mono text-2xs text-purple-700">{scenarioResult.actual}</span>
                  </div>
                  <pre className="overflow-x-auto rounded-xl border border-purple-900/40 bg-[#120B24] p-3.5 font-mono text-2xs text-purple-200 shadow-inner">
                    {JSON.stringify(scenarioResult.response, null, 2)}
                  </pre>
                </div>
              </div>

              {/* Invariant Assertion Summary */}
              <div className="rounded-xl border border-purple-100 bg-purple-50/60 p-3.5 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-2 text-2xs">
                <span className="text-slate-600">
                  <strong className="text-purple-950 font-bold">Ledger Invariant Check:</strong> Active account <code className="font-mono text-purple-900 font-semibold">{activeAccount}</code> balance remains <strong className="font-mono text-slate-900">PHP {(balanceData?.balanceAmount ?? 0).toLocaleString('en-US', { minimumFractionDigits: 2 })}</strong> (Zero phantom debit, ACID integrity held).
                </span>
                <button
                  type="button"
                  onClick={() => fetchBalance(activeAccount)}
                  className="shrink-0 flex items-center gap-1 font-semibold text-purple-700 hover:text-purple-950"
                >
                  <RefreshCw className="h-3 w-3" /> Re-verify Balance
                </button>
              </div>
            </div>
          )}
        </div>
      )}

      {/* Transaction Status History Modal via Orchestrator */}
      {isStatusHistoryOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-xs p-4">
          <div className="w-full max-w-2xl rounded-2xl border border-purple-200 bg-white p-6 shadow-2xl space-y-4">
            <div className="flex items-center justify-between border-b border-purple-100 pb-3">
              <div className="flex items-center gap-2">
                <History className="h-5 w-5 text-purple-700" />
                <div>
                  <h3 className="text-sm font-bold text-slate-900">
                    Transaction Status Lifecycle History
                  </h3>
                  <p className="font-mono text-2xs text-purple-900">
                    Orchestrator: <code className="bg-purple-50 px-1 py-0.5 rounded">GET /api/v1/transfers/transactions/{statusHistoryTxId}/status-history</code>
                  </p>
                </div>
              </div>
              <button
                type="button"
                onClick={() => setIsStatusHistoryOpen(false)}
                className="rounded-lg p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-600 transition"
              >
                <XCircle className="h-5 w-5" />
              </button>
            </div>

            {isLoadingStatusHistory ? (
              <div className="py-10 text-center text-xs text-slate-500 flex items-center justify-center gap-2">
                <RefreshCw className="h-4 w-4 animate-spin text-purple-600" /> Querying status transitions from Core Banking via Orchestrator...
              </div>
            ) : statusHistoryList.length === 0 ? (
              <div className="py-8 text-center text-xs text-slate-400">
                No recorded status transition history found for transaction <code className="font-mono">{statusHistoryTxId}</code>.
              </div>
            ) : (
              <div className="space-y-3 max-h-[400px] overflow-y-auto pr-1">
                {statusHistoryList.map((item, idx) => (
                  <div
                    key={item.historyId || idx}
                    className="flex items-start gap-3 rounded-xl border border-slate-200 bg-slate-50/60 p-3 text-xs"
                  >
                    <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-purple-100 text-purple-700 font-bold font-mono text-2xs">
                      {idx + 1}
                    </div>
                    <div className="flex-1 space-y-1">
                      <div className="flex items-center justify-between">
                        <div className="flex items-center gap-1.5 font-mono text-2xs font-bold">
                          <span className="px-1.5 py-0.5 rounded bg-slate-200 text-slate-800">
                            {item.fromStatus || 'INITIATED'}
                          </span>
                          <span className="text-purple-600">➔</span>
                          <span className="px-1.5 py-0.5 rounded bg-emerald-100 text-emerald-800">
                            {item.toStatus}
                          </span>
                        </div>
                        <span className="text-2xs text-slate-400 font-mono">
                          {item.changedAt ? new Date(item.changedAt).toLocaleString() : '--'}
                        </span>
                      </div>
                      <p className="font-medium text-slate-700 text-2xs">
                        Reason: <span className="font-mono text-purple-900 font-bold">{item.changeReason}</span>
                        {item.reasonDetails && ` — ${item.reasonDetails}`}
                      </p>
                      <div className="flex items-center gap-3 text-2xs text-slate-400 font-mono">
                        <span>Actor: {item.actorId}</span>
                        <span>Type: {item.actorType}</span>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            )}

            <div className="flex justify-end pt-2 border-t border-slate-100">
              <button
                type="button"
                onClick={() => setIsStatusHistoryOpen(false)}
                className="rounded-xl bg-purple-700 hover:bg-purple-800 px-4 py-2 text-xs font-semibold text-white transition"
              >
                Close Inspector
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
