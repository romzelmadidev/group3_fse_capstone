import React, { useState, useEffect } from 'react';
import {
  Folder,
  FileText,
  FileSpreadsheet,
  Download,
  Upload,
  Search,
  Grid,
  List,
  HardDrive,
  Clock,
  Trash2,
  Share2,
  Star,
  Eye,
  RefreshCw,
  Plus,
  ChevronRight,
  ExternalLink,
  CheckCircle2,
  FileCheck2,
  Database,
  Cloud,
  File
} from 'lucide-react';
import axios from 'axios';

const API_BASE = '/api/v1';

export default function AzuriteDrive() {
  const [blobs, setBlobs] = useState([]);
  const [isLoading, setIsLoading] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedFolder, setSelectedFolder] = useState('ALL'); // ALL | eod | statements | compliance
  const [viewMode, setViewMode] = useState('list'); // list | grid
  const [selectedBlob, setSelectedBlob] = useState(null);
  const [uploadProgress, setUploadProgress] = useState(null);

  // Fetch blobs from Compliance Service / Azurite
  const fetchBlobs = async () => {
    setIsLoading(true);
    try {
      // 1. Fetch from azurite blobs endpoint
      const res = await axios.get(`${API_BASE}/compliance/azurite/blobs`);
      if (Array.isArray(res.data) && res.data.length > 0) {
        setBlobs(res.data);
      } else {
        // Fallback to compliance reports list
        const reportsRes = await axios.get(`${API_BASE}/compliance/reports`);
        if (Array.isArray(reportsRes.data) && reportsRes.data.length > 0) {
          const mapped = reportsRes.data.map((r) => ({
            blobName: r.fileName ? `eod/${r.fileName}` : r.blobName || 'report.pdf',
            storageUri: r.storageUri || `azure-blob://compliance-vault/eod/${r.fileName}`,
            sizeBytes: r.fileSize || 145000,
            contentType: (r.fileName || '').endsWith('.xlsx')
              ? 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
              : 'application/pdf',
            lastModified: r.generatedAt || new Date().toISOString()
          }));
          setBlobs(mapped);
        } else {
          // Provide default sample files so drive is never empty for testing
          setBlobs(SAMPLE_FILES);
        }
      }
    } catch {
      setBlobs(SAMPLE_FILES);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchBlobs();
  }, []);

  // Handle file download
  const handleDownload = async (blob) => {
    try {
      const response = await axios.get(
        `${API_BASE}/compliance/reports/download?blobName=${encodeURIComponent(blob.blobName)}`,
        { responseType: 'blob' }
      );
      const url = window.URL.createObjectURL(new Blob([response.data]));
      const link = document.createElement('a');
      link.href = url;
      const fileName = blob.blobName.split('/').pop() || 'download';
      link.setAttribute('download', fileName);
      document.body.appendChild(link);
      link.click();
      link.remove();
    } catch (e) {
      alert('Could not download blob from Azurite directly: ' + e.message);
    }
  };

  // Generate Sample Report into Azurite
  const handleGenerateSample = async (type) => {
    setIsLoading(true);
    try {
      if (type === 'STATEMENT') {
        await axios.get(`${API_BASE}/compliance/statements/ACC-1001/pdf`);
      } else {
        // Trigger EOD reports
        await axios.get(`${API_BASE}/compliance/reports?eodDate=2026-10-08`);
      }
      await fetchBlobs();
    } catch {
      fetchBlobs();
    } finally {
      setIsLoading(false);
    }
  };

  // Filter blobs
  const filteredBlobs = blobs.filter((b) => {
    const matchesSearch = b.blobName.toLowerCase().includes(searchQuery.toLowerCase());
    if (selectedFolder === 'ALL') return matchesSearch;
    return matchesSearch && b.blobName.startsWith(selectedFolder);
  });

  const totalSize = blobs.reduce((sum, b) => sum + (b.sizeBytes || 0), 0);

  return (
    <div className="flex h-[820px] flex-col overflow-hidden border border-line bg-surface shadow-2xl">
      {/* Top Google Drive Style Bar */}
      <div className="flex h-16 items-center justify-between border-b border-line px-6 bg-surface">
        <div className="flex items-center gap-3">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-blue-600 text-white shadow-md">
            <Cloud className="h-5 w-5" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <span className="font-semibold tracking-tight text-fg text-base">Azurite Cloud Drive</span>
              <span className="rounded bg-blue-500/10 px-2 py-0.5 font-mono text-2xs font-medium text-blue-400">
                Azure Blob Storage Emulator
              </span>
            </div>
            <p className="text-2xs text-fg-subtle">
              Container: <code className="font-mono text-fg">compliance-vault</code> &bull; Local Emulator Port 10000
            </p>
          </div>
        </div>

        {/* Google Drive Search Input */}
        <div className="relative mx-4 flex-1 max-w-md">
          <Search className="absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-fg-subtle" />
          <input
            type="text"
            placeholder="Search in Azurite Drive (PDFs, Excel filings, statements)..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full rounded-full border border-line bg-sunken py-2 pl-10 pr-4 text-xs text-fg placeholder:text-fg-subtle focus:border-accent focus:bg-surface focus:outline-none"
          />
        </div>

        {/* View Toggle & Actions */}
        <div className="flex items-center gap-2">
          <button
            onClick={fetchBlobs}
            className="flex items-center gap-1.5 rounded-lg border border-line bg-sunken px-3 py-1.5 text-xs text-fg-muted hover:bg-surface-raised hover:text-fg"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${isLoading ? 'animate-spin' : ''}`} /> Refresh
          </button>
          <div className="flex rounded-lg border border-line bg-sunken p-0.5">
            <button
              onClick={() => setViewMode('list')}
              className={`rounded p-1.5 text-xs ${viewMode === 'list' ? 'bg-surface text-fg shadow-sm' : 'text-fg-subtle'}`}
              title="List view"
            >
              <List className="h-4 w-4" />
            </button>
            <button
              onClick={() => setViewMode('grid')}
              className={`rounded p-1.5 text-xs ${viewMode === 'grid' ? 'bg-surface text-fg shadow-sm' : 'text-fg-subtle'}`}
              title="Grid view"
            >
              <Grid className="h-4 w-4" />
            </button>
          </div>
        </div>
      </div>

      {/* Main Drive Layout: Left Sidebar + File Workspace */}
      <div className="flex flex-1 overflow-hidden">
        {/* Left Sidebar */}
        <div className="flex w-64 flex-col justify-between border-r border-line bg-sunken/40 p-4">
          <div className="space-y-4">
            {/* Quick Action Button */}
            <div className="relative">
              <button
                onClick={() => handleGenerateSample('STATEMENT')}
                className="flex w-full items-center justify-center gap-2 rounded-xl border border-line bg-surface py-2.5 text-xs font-semibold text-fg shadow-sm hover:bg-surface-raised"
              >
                <Plus className="h-4 w-4 text-blue-500" /> Generate Statement PDF
              </button>
            </div>

            {/* Folder Navigation */}
            <nav className="space-y-1">
              {[
                { id: 'ALL', label: 'My Drive (All Files)', icon: HardDrive, count: blobs.length },
                { id: 'eod', label: 'EOD Batch Filings', icon: Folder, count: blobs.filter(b => b.blobName.startsWith('eod')).length },
                { id: 'statements', label: 'Customer Statements', icon: Folder, count: blobs.filter(b => b.blobName.startsWith('statements')).length },
                { id: 'compliance', label: 'Regulatory Vault', icon: Folder, count: blobs.filter(b => b.blobName.startsWith('compliance')).length }
              ].map((item) => {
                const Icon = item.icon;
                const active = selectedFolder === item.id;
                return (
                  <button
                    key={item.id}
                    onClick={() => setSelectedFolder(item.id)}
                    className={`flex w-full items-center justify-between rounded-lg px-3 py-2 text-xs font-medium transition-colors ${
                      active
                        ? 'bg-blue-500/10 font-semibold text-blue-500'
                        : 'text-fg-muted hover:bg-surface-raised hover:text-fg'
                    }`}
                  >
                    <div className="flex items-center gap-2.5">
                      <Icon className={`h-4 w-4 ${active ? 'text-blue-500' : 'text-fg-subtle'}`} />
                      <span>{item.label}</span>
                    </div>
                    <span className="font-mono text-2xs text-fg-subtle">{item.count}</span>
                  </button>
                );
              })}
            </nav>
          </div>

          {/* Storage Quota Card */}
          <div className="rounded-xl border border-line bg-surface p-3 shadow-sm">
            <div className="flex items-center justify-between text-2xs text-fg-subtle">
              <span>Azurite Storage</span>
              <span className="font-mono font-medium text-fg">
                {(totalSize / 1024).toFixed(1)} KB / 10 GB
              </span>
            </div>
            <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-sunken">
              <div
                className="h-full bg-blue-500 transition-all"
                style={{ width: `${Math.min(100, Math.max(5, (totalSize / (1024 * 1024)) * 10))}%` }}
              />
            </div>
            <p className="mt-2 text-2xs text-fg-subtle">
              Stores write-once compliance PDFs, statements, and AMLA CTR audit workbooks.
            </p>
          </div>
        </div>

        {/* Center File Area */}
        <div className="flex flex-1 flex-col overflow-y-auto p-6 bg-canvas">
          {/* Breadcrumbs */}
          <div className="flex items-center gap-1.5 text-xs text-fg-subtle">
            <span>Drive</span>
            <ChevronRight className="h-3.5 w-3.5" />
            <span>compliance-vault</span>
            {selectedFolder !== 'ALL' && (
              <>
                <ChevronRight className="h-3.5 w-3.5" />
                <span className="font-semibold text-fg">{selectedFolder}</span>
              </>
            )}
          </div>

          {/* Suggested / Quick Access Cards */}
          <div className="mt-4">
            <h2 className="text-2xs font-semibold uppercase tracking-wider text-fg-subtle">
              Suggested Documents & Reports
            </h2>
            <div className="mt-2 grid grid-cols-1 gap-3 sm:grid-cols-3">
              {filteredBlobs.slice(0, 3).map((blob) => {
                const isExcel = blob.blobName.endsWith('.xlsx');
                return (
                  <div
                    key={blob.blobName}
                    onClick={() => setSelectedBlob(blob)}
                    className="cursor-pointer rounded-xl border border-line bg-surface p-4 shadow-sm transition-all hover:border-accent hover:shadow-md"
                  >
                    <div className="flex items-start justify-between">
                      <div className="flex items-center gap-2">
                        {isExcel ? (
                          <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-emerald-500/10 text-emerald-500">
                            <FileSpreadsheet className="h-4 w-4" />
                          </div>
                        ) : (
                          <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-rose-500/10 text-rose-500">
                            <FileText className="h-4 w-4" />
                          </div>
                        )}
                        <div className="min-w-0">
                          <p className="truncate text-xs font-semibold text-fg">
                            {blob.blobName.split('/').pop()}
                          </p>
                          <p className="text-2xs text-fg-subtle font-mono">
                            {(blob.sizeBytes / 1024).toFixed(1)} KB
                          </p>
                        </div>
                      </div>
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          handleDownload(blob);
                        }}
                        className="rounded p-1 text-fg-subtle hover:bg-sunken hover:text-fg"
                        title="Download File"
                      >
                        <Download className="h-4 w-4" />
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>

          {/* Files List / Grid */}
          <div className="mt-6 flex-1">
            <h2 className="text-2xs font-semibold uppercase tracking-wider text-fg-subtle">
              All Artifacts ({filteredBlobs.length})
            </h2>

            {viewMode === 'list' ? (
              <div className="mt-2 overflow-hidden rounded-xl border border-line bg-surface shadow-sm">
                <table className="w-full text-left text-xs">
                  <thead className="border-b border-line bg-sunken text-2xs uppercase text-fg-subtle">
                    <tr>
                      <th className="p-3 pl-4">Name</th>
                      <th className="p-3">Category</th>
                      <th className="p-3">Storage URI</th>
                      <th className="p-3">File Size</th>
                      <th className="p-3 text-right pr-4">Actions</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-line text-fg">
                    {filteredBlobs.map((blob) => {
                      const isExcel = blob.blobName.endsWith('.xlsx');
                      const folderName = blob.blobName.includes('/') ? blob.blobName.split('/')[0] : 'root';
                      return (
                        <tr
                          key={blob.blobName}
                          onClick={() => setSelectedBlob(blob)}
                          className="cursor-pointer transition-colors hover:bg-sunken"
                        >
                          <td className="p-3 pl-4">
                            <div className="flex items-center gap-2.5">
                              {isExcel ? (
                                <FileSpreadsheet className="h-4 w-4 text-emerald-500 shrink-0" />
                              ) : (
                                <FileText className="h-4 w-4 text-rose-500 shrink-0" />
                              )}
                              <span className="font-medium text-fg truncate max-w-xs">
                                {blob.blobName.split('/').pop()}
                              </span>
                            </div>
                          </td>
                          <td className="p-3">
                            <span className="rounded bg-sunken px-2 py-0.5 font-mono text-2xs text-fg-subtle uppercase">
                              {folderName}
                            </span>
                          </td>
                          <td className="p-3 font-mono text-2xs text-fg-subtle truncate max-w-xs">
                            {blob.storageUri}
                          </td>
                          <td className="p-3 font-mono text-2xs text-fg-subtle">
                            {(blob.sizeBytes / 1024).toFixed(1)} KB
                          </td>
                          <td className="p-3 text-right pr-4">
                            <button
                              onClick={(e) => {
                                e.stopPropagation();
                                handleDownload(blob);
                              }}
                              className="inline-flex items-center gap-1 rounded border border-line bg-surface px-2.5 py-1 text-2xs font-semibold text-fg hover:bg-surface-raised"
                            >
                              <Download className="h-3 w-3" /> Download
                            </button>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            ) : (
              <div className="mt-2 grid grid-cols-2 gap-4 sm:grid-cols-4">
                {filteredBlobs.map((blob) => {
                  const isExcel = blob.blobName.endsWith('.xlsx');
                  return (
                    <div
                      key={blob.blobName}
                      onClick={() => setSelectedBlob(blob)}
                      className="cursor-pointer rounded-xl border border-line bg-surface p-4 shadow-sm hover:border-accent hover:shadow-md"
                    >
                      <div className="flex h-24 items-center justify-center rounded-lg bg-sunken">
                        {isExcel ? (
                          <FileSpreadsheet className="h-10 w-10 text-emerald-500" />
                        ) : (
                          <FileText className="h-10 w-10 text-rose-500" />
                        )}
                      </div>
                      <p className="mt-2 truncate text-xs font-semibold text-fg">
                        {blob.blobName.split('/').pop()}
                      </p>
                      <div className="mt-1 flex items-center justify-between text-2xs text-fg-subtle">
                        <span>{(blob.sizeBytes / 1024).toFixed(1)} KB</span>
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            handleDownload(blob);
                          }}
                          className="text-accent-text hover:underline"
                        >
                          Download
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        </div>

        {/* Right Details Panel for Selected Blob */}
        {selectedBlob && (
          <div className="w-80 border-l border-line bg-surface p-6 shadow-xl">
            <div className="flex items-center justify-between">
              <h3 className="text-xs font-semibold uppercase tracking-wider text-fg-subtle">
                File Details
              </h3>
              <button
                onClick={() => setSelectedBlob(null)}
                className="text-fg-subtle hover:text-fg text-xs"
              >
                &times; Close
              </button>
            </div>

            <div className="mt-4 flex flex-col items-center p-4 rounded-xl bg-sunken border border-line">
              {selectedBlob.blobName.endsWith('.xlsx') ? (
                <FileSpreadsheet className="h-12 w-12 text-emerald-500" />
              ) : (
                <FileText className="h-12 w-12 text-rose-500" />
              )}
              <p className="mt-2 text-center text-xs font-bold text-fg break-all">
                {selectedBlob.blobName.split('/').pop()}
              </p>
            </div>

            <div className="mt-6 space-y-3 text-xs">
              <div>
                <span className="block text-2xs uppercase text-fg-subtle">Blob Name</span>
                <span className="font-mono text-2xs text-fg break-all">{selectedBlob.blobName}</span>
              </div>
              <div>
                <span className="block text-2xs uppercase text-fg-subtle">Storage URI</span>
                <span className="font-mono text-2xs text-blue-400 break-all">{selectedBlob.storageUri}</span>
              </div>
              <div>
                <span className="block text-2xs uppercase text-fg-subtle">Content Type</span>
                <span className="font-mono text-2xs text-fg">{selectedBlob.contentType}</span>
              </div>
              <div>
                <span className="block text-2xs uppercase text-fg-subtle">Size</span>
                <span className="font-mono text-2xs text-fg">
                  {(selectedBlob.sizeBytes / 1024).toFixed(2)} KB ({selectedBlob.sizeBytes} bytes)
                </span>
              </div>
            </div>

            <div className="mt-6">
              <button
                onClick={() => handleDownload(selectedBlob)}
                className="flex w-full items-center justify-center gap-2 rounded-lg border border-accent bg-accent py-2 text-xs font-semibold uppercase tracking-wider text-fg-inverse hover:opacity-90"
              >
                <Download className="h-4 w-4" /> Download Artifact
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

// Fallback sample files for demonstration
const SAMPLE_FILES = [
  {
    blobName: 'eod/2026-10-08/bir_2306_withholding_2026-10-08.pdf',
    storageUri: 'azure-blob://compliance-vault/eod/2026-10-08/bir_2306_withholding_2026-10-08.pdf',
    sizeBytes: 245760,
    contentType: 'application/pdf',
    lastModified: '2026-10-08T14:00:00.000Z'
  },
  {
    blobName: 'eod/2026-10-08/amla_ctr_filing_2026-10-08.xlsx',
    storageUri: 'azure-blob://compliance-vault/eod/2026-10-08/amla_ctr_filing_2026-10-08.xlsx',
    sizeBytes: 182300,
    contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    lastModified: '2026-10-08T14:00:00.000Z'
  },
  {
    blobName: 'eod/2026-10-08/gl_eod_reconciliation_2026-10-08.pdf',
    storageUri: 'azure-blob://compliance-vault/eod/2026-10-08/gl_eod_reconciliation_2026-10-08.pdf',
    sizeBytes: 198400,
    contentType: 'application/pdf',
    lastModified: '2026-10-08T14:00:00.000Z'
  },
  {
    blobName: 'statements/ACC-1001/statement-2026-10-08.pdf',
    storageUri: 'azure-blob://compliance-vault/statements/ACC-1001/statement-2026-10-08.pdf',
    sizeBytes: 154200,
    contentType: 'application/pdf',
    lastModified: '2026-10-08T13:45:00.000Z'
  }
];
