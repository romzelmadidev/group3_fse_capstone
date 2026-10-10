import { useMemo, useState } from 'react';
import { ScrollText } from 'lucide-react';
import { api, errorMessage } from '../lib/api';
import { dateTime, money, titleCase } from '../lib/format';
import { Badge, Empty, ErrorNote, PageHeader, SearchBar, SkeletonRows, useLoad } from '../components/ui';

export default function Audit() {
  const { data, error, loading, reload } = useLoad(() => api.get('/ledger/audit').then((r) => r.data), []);
  const [q, setQ] = useState('');
  const allSorted = useMemo(() => [...(data || [])].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt)), [data]);
  const rows = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return (needle ? allSorted.filter((r) => JSON.stringify(r).toLowerCase().includes(needle)) : allSorted).slice(0, 200);
  }, [allSorted, q]);

  return (
    <>
      <PageHeader title="Audit trail" description="Append-only ledger journal from the PostgreSQL audit vault. Every debit and credit, as written." />
      <div className="panel overflow-hidden animate-rise" style={{ animationDelay: '60ms' }}>
        <SearchBar
          value={q}
          onChange={setQ}
          placeholder="Search by transaction, account, user or type"
          ariaLabel="Search audit trail"
          count={rows.length}
          total={allSorted.length}
        />
        {loading ? (
          <SkeletonRows />
        ) : error ? (
          <div className="p-5"><ErrorNote message={errorMessage(error)} onRetry={reload} /></div>
        ) : rows.length === 0 ? (
          <Empty icon={ScrollText} title={q ? 'No matches' : 'No entries yet'} />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-ink-100 text-left">
                  {['When', 'Entry', 'Account', 'Type', 'Amount', 'Balance after', 'By', 'Status'].map((h) => <th key={h} className="label whitespace-nowrap px-5 py-3 font-semibold">{h}</th>)}
                </tr>
              </thead>
              <tbody>
                {rows.map((r, i) => (
                  <tr key={r.auditId ?? r.transactionId} className="row-enter border-b border-ink-100 last:border-0" style={{ '--i': Math.min(i, 12) }}>
                    <td className="whitespace-nowrap px-5 py-3 text-ink-500">{dateTime(r.createdAt)}</td>
                    <td className="px-5 py-3 font-mono text-xs">{r.transactionId}</td>
                    <td className="px-5 py-3">{r.accountId}</td>
                    <td className="px-5 py-3">{titleCase(r.mutationType)}</td>
                    <td className={`px-5 py-3 font-semibold tabular-nums ${r.transactionId?.endsWith('-CR') ? 'text-ok' : ''}`}>{money(r.mutationAmount)}</td>
                    <td className="px-5 py-3 tabular-nums text-ink-500">{money(r.afterBalance)}</td>
                    <td className="px-5 py-3">{r.approvedByUserId || r.initiatorUserId}</td>
                    <td className="px-5 py-3"><Badge tone={r.status === 'COMMITTED' ? 'mint' : 'ember'}>{titleCase(r.status)}</Badge></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </>
  );
}
