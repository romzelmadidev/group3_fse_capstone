import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Archive,
  Braces,
  CheckCircle2,
  ChevronRight,
  Cloud,
  Copy,
  Download,
  FileCode,
  FileSpreadsheet,
  FileText,
  Folder,
  FolderArchive,
  Grid,
  HardDrive,
  Hash,
  Image as ImageIcon,
  Layers,
  List,
  RefreshCw,
  RotateCcw,
  Search,
  ShieldCheck,
  Table,
  Upload,
  X,
} from 'lucide-react';
import { api, errorMessage } from '../lib/api';
import { dateTime, titleCase } from '../lib/format';
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
} from './ui';

export default function AzuriteDrive() {
  const toast = useToast();
  const [blobs, setBlobs] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [cobRunning, setCobRunning] = useState(false);

  // Filters
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedFolder, setSelectedFolder] = useState('ALL');
  const [selectedTypeFilter, setSelectedTypeFilter] = useState('ALL'); // ALL, PDF, SPREADSHEET, XML, IMAGE, DATA
  const [viewMode, setViewMode] = useState('list'); // 'list' | 'grid'

  // Selected Blob for Drawer
  const [selectedBlob, setSelectedBlob] = useState(null);
  const [previewContent, setPreviewContent] = useState(null);
  const [previewLoading, setPreviewLoading] = useState(false);

  // Upload Modal State
  const [showUploadModal, setShowUploadModal] = useState(false);
  const [uploadFile, setUploadFile] = useState(null);
  const [uploadFolder, setUploadFolder] = useState('compliance');
  const [isUploading, setIsUploading] = useState(false);

  // Fetch blobs from compliance-service Azurite storage
  const fetchBlobs = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await api.get('/compliance/azurite/blobs');
      const list = Array.isArray(res.data) ? res.data : [];
      setBlobs(list);
    } catch (err) {
      console.error('Failed to fetch Azurite blobs:', err);
      setError(err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchBlobs();
  }, [fetchBlobs]);

  // Determine file format metadata
  const getFileMeta = useCallback((blobName, contentType) => {
    const lower = (blobName || '').toLowerCase();
    const type = (contentType || '').toLowerCase();

    if (lower.endsWith('.pdf') || type.includes('pdf')) {
      return {
        category: 'PDF Document',
        typeKey: 'PDF',
        icon: FileText,
        iconTone: 'text-danger bg-danger-wash',
        badgeTone: 'danger',
        isPreviewableText: false,
        isImage: false,
      };
    }
    if (lower.endsWith('.xlsx') || lower.endsWith('.xls') || type.includes('sheet') || type.includes('excel')) {
      return {
        category: 'Excel Workbook',
        typeKey: 'SPREADSHEET',
        icon: FileSpreadsheet,
        iconTone: 'text-mint-deep bg-mint-wash',
        badgeTone: 'mint',
        isPreviewableText: false,
        isImage: false,
      };
    }
    if (lower.endsWith('.xml') || type.includes('xml')) {
      return {
        category: 'AMLA XML Filing',
        typeKey: 'XML',
        icon: FileCode,
        iconTone: 'text-sky-deep bg-sky-wash',
        badgeTone: 'sky',
        isPreviewableText: true,
        isImage: false,
      };
    }
    if (lower.endsWith('.json') || type.includes('json')) {
      return {
        category: 'JSON Data',
        typeKey: 'DATA',
        icon: Braces,
        iconTone: 'text-sky-deep bg-sky-wash',
        badgeTone: 'sky',
        isPreviewableText: true,
        isImage: false,
      };
    }
    if (lower.endsWith('.csv') || type.includes('csv')) {
      return {
        category: 'CSV Dataset',
        typeKey: 'DATA',
        icon: Table,
        iconTone: 'text-mint-deep bg-mint-wash',
        badgeTone: 'mint',
        isPreviewableText: true,
        isImage: false,
      };
    }
    if (
      lower.endsWith('.jpg') ||
      lower.endsWith('.jpeg') ||
      lower.endsWith('.png') ||
      lower.endsWith('.webp') ||
      type.includes('image')
    ) {
      return {
        category: 'KYC Photo / Evidence',
        typeKey: 'IMAGE',
        icon: ImageIcon,
        iconTone: 'text-ember-deep bg-ember-wash',
        badgeTone: 'ember',
        isPreviewableText: false,
        isImage: true,
      };
    }
    if (lower.endsWith('.zip') || lower.endsWith('.tar.gz') || type.includes('zip') || type.includes('compressed')) {
      return {
        category: 'ZIP Bundle',
        typeKey: 'ARCHIVE',
        icon: Archive,
        iconTone: 'text-ink-600 bg-ink-100',
        badgeTone: 'neutral',
        isPreviewableText: false,
        isImage: false,
      };
    }
    return {
      category: 'Plaintext Log',
      typeKey: 'TEXT',
      icon: FileText,
      iconTone: 'text-ink-500 bg-ink-100',
      badgeTone: 'neutral',
      isPreviewableText: true,
      isImage: false,
    };
  }, []);

  // Compute folders dynamically
  const folders = useMemo(() => {
    const map = new Map();
    map.set('ALL', { id: 'ALL', label: 'All Artifacts', count: blobs.length });
    map.set('eod', { id: 'eod', label: 'EOD Batch Filings', count: 0 });
    map.set('statements', { id: 'statements', label: 'Customer Statements', count: 0 });
    map.set('amla', { id: 'amla', label: 'AMLA CTR Filings', count: 0 });
    map.set('users', { id: 'users', label: 'KYC Identity Evidence', count: 0 });
    map.set('compliance', { id: 'compliance', label: 'Regulatory Vault', count: 0 });

    for (const b of blobs) {
      const prefix = b.blobName?.includes('/') ? b.blobName.split('/')[0] : 'root';
      if (map.has(prefix)) {
        map.get(prefix).count++;
      } else {
        map.set(prefix, { id: prefix, label: `${titleCase(prefix)} Vault`, count: 1 });
      }
    }

    return Array.from(map.values());
  }, [blobs]);

  // Filter blobs
  const filteredBlobs = useMemo(() => {
    return blobs.filter((b) => {
      // Folder filter
      if (selectedFolder !== 'ALL') {
        if (!b.blobName.startsWith(selectedFolder + '/') && b.blobName !== selectedFolder) {
          return false;
        }
      }

      // Type filter
      if (selectedTypeFilter !== 'ALL') {
        const meta = getFileMeta(b.blobName, b.contentType);
        if (meta.typeKey !== selectedTypeFilter) return false;
      }

      // Search query
      if (searchQuery.trim()) {
        const needle = searchQuery.toLowerCase().trim();
        const nameMatches = b.blobName?.toLowerCase().includes(needle);
        const hashMatches = b.sha256Checksum?.toLowerCase().includes(needle);
        const uriMatches = b.storageUri?.toLowerCase().includes(needle);
        if (!nameMatches && !hashMatches && !uriMatches) return false;
      }

      return true;
    });
  }, [blobs, selectedFolder, selectedTypeFilter, searchQuery, getFileMeta]);

  // Total Storage Size
  const totalSizeBytes = useMemo(() => {
    return blobs.reduce((sum, b) => sum + (b.sizeBytes || 0), 0);
  }, [blobs]);

  // Download blob
  const handleDownload = async (blob, e) => {
    if (e) e.stopPropagation();
    try {
      const response = await api.get(
        `/compliance/reports/download?blobName=${encodeURIComponent(blob.blobName)}`,
        { responseType: 'blob' }
      );
      const contentType = blob.contentType || response.headers['content-type'] || 'application/octet-stream';
      const fileBlob = new Blob([response.data], { type: contentType });
      const url = window.URL.createObjectURL(fileBlob);
      const link = document.createElement('a');
      link.href = url;
      const fileName = blob.blobName.split('/').pop() || 'artifact';
      link.setAttribute('download', fileName);
      document.body.appendChild(link);
      link.click();
      setTimeout(() => {
        document.body.removeChild(link);
        window.URL.revokeObjectURL(url);
      }, 200);
      toast(`Downloaded ${fileName}`, 'ok');
    } catch (err) {
      console.error('Download error:', err);
      toast(`Failed to download ${blob.blobName}: ${errorMessage(err)}`, 'error');
    }
  };

  // Run Close of Business (COB) batch
  const handleRunCob = async () => {
    setCobRunning(true);
    try {
      await api.post('/cbs/cob/run');
      toast('COB batch executed successfully. Generating EOD filings...', 'ok');
      // Wait for asynchronous generation
      setTimeout(async () => {
        await fetchBlobs();
        setCobRunning(false);
      }, 2000);
    } catch (err) {
      console.error('COB batch error:', err);
      toast(`COB batch failed: ${errorMessage(err)}`, 'error');
      setCobRunning(false);
    }
  };

  // Inspect blob & fetch preview if text/json/xml
  const handleSelectBlob = async (blob) => {
    setSelectedBlob(blob);
    setPreviewContent(null);
    const meta = getFileMeta(blob.blobName, blob.contentType);

    if (meta.isPreviewableText) {
      setPreviewLoading(true);
      try {
        const res = await api.get(`/compliance/reports/download?blobName=${encodeURIComponent(blob.blobName)}`, {
          responseType: 'text',
        });
        setPreviewContent(res.data);
      } catch (err) {
        setPreviewContent('Preview unavailable.');
      } finally {
        setPreviewLoading(false);
      }
    }
  };

  // Upload Document
  const handleUploadSubmit = async (e) => {
    e.preventDefault();
    if (!uploadFile) return;

    setIsUploading(true);
    try {
      const formData = new FormData();
      formData.append('file', uploadFile);
      formData.append('folder', uploadFolder);

      await api.post('/compliance/azurite/upload', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      });

      toast(`Uploaded ${uploadFile.name} to ${uploadFolder}/`, 'ok');
      setShowUploadModal(false);
      setUploadFile(null);
      await fetchBlobs();
    } catch (err) {
      console.error('Upload error:', err);
      toast(`Upload failed: ${errorMessage(err)}`, 'error');
    } finally {
      setIsUploading(false);
    }
  };

  const selectedMeta = selectedBlob ? getFileMeta(selectedBlob.blobName, selectedBlob.contentType) : null;

  return (
    <>
      <PageHeader
        title="Compliance Drive"
        description="Centralized write-once regulatory vault in Azure Blob Storage (Azurite). Search, inspect, and retrieve audit packages, customer statements, EOD reconciliations, and AMLA filings."
        actions={
          <>
            <Button
              variant="mint"
              icon={RefreshCw}
              loading={cobRunning}
              onClick={handleRunCob}
              title="Execute Close of Business (COB) batch to generate EOD compliance filings"
            >
              Run COB Batch
            </Button>
            <Button
              variant="primary"
              icon={Upload}
              onClick={() => setShowUploadModal(true)}
            >
              Upload Document
            </Button>
            <Button
              variant="ghost"
              icon={RotateCcw}
              onClick={fetchBlobs}
              loading={loading}
            >
              Refresh
            </Button>
          </>
        }
      />

      {/* Main Drive Workspace */}
      <div className="panel overflow-hidden animate-rise" style={{ animationDelay: '40ms' }}>
        {/* Top Operational Status Bar */}
        <div className="flex flex-wrap items-center justify-between gap-4 border-b border-ink-100 bg-white px-6 py-4">
          <div className="flex items-center gap-3">
            <div className="grid size-10 place-items-center rounded-xl bg-sky-wash text-sky-deep">
              <Cloud className="size-5" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <span className="font-semibold text-ink text-sm">Azurite Compliance Vault</span>
                <span className="rounded-full bg-mint-wash px-2 py-0.5 text-2xs font-semibold text-mint-deep">
                  WORM Immutable Archive
                </span>
                <span className="rounded-full bg-ink-100 px-2 py-0.5 text-2xs font-mono text-ink-500">
                  container: compliance-vault
                </span>
              </div>
              <p className="mt-0.5 text-xs text-ink-500">
                Zero-DB stateless compliance storage &bull; {(totalSizeBytes / 1024).toFixed(1)} KB consumed of 10 GB quota
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <div className="flex rounded-xl bg-ink-100 p-1">
              <button
                onClick={() => setViewMode('list')}
                className={`flex items-center gap-1.5 rounded-lg px-3 py-1 text-xs font-semibold transition-colors ${
                  viewMode === 'list' ? 'bg-white text-ink shadow-sm' : 'text-ink-400 hover:text-ink'
                }`}
              >
                <List className="size-3.5" /> List
              </button>
              <button
                onClick={() => setViewMode('grid')}
                className={`flex items-center gap-1.5 rounded-lg px-3 py-1 text-xs font-semibold transition-colors ${
                  viewMode === 'grid' ? 'bg-white text-ink shadow-sm' : 'text-ink-400 hover:text-ink'
                }`}
              >
                <Grid className="size-3.5" /> Grid
              </button>
            </div>
          </div>
        </div>

        {/* Folder Navigation Pills */}
        <div className="flex flex-wrap items-center gap-2 border-b border-ink-100 bg-paper/60 px-6 py-3">
          <span className="text-2xs font-semibold uppercase tracking-wider text-ink-400 mr-2">Vaults:</span>
          {folders.map((f) => {
            const active = selectedFolder === f.id;
            return (
              <button
                key={f.id}
                onClick={() => setSelectedFolder(f.id)}
                className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-medium transition-colors ${
                  active
                    ? 'bg-ink text-white shadow-sm'
                    : 'bg-white border border-ink-100 text-ink-600 hover:bg-ink-100 hover:text-ink'
                }`}
              >
                <Folder className={`size-3.5 ${active ? 'text-mint' : 'text-ink-400'}`} />
                <span>{f.label}</span>
                <span className={`rounded-full px-1.5 py-0.2 text-2xs font-mono tabular-nums ${active ? 'bg-white/20 text-white' : 'bg-ink-100 text-ink-500'}`}>
                  {f.count}
                </span>
              </button>
            );
          })}
        </div>

        {/* File Type Filter Chips */}
        <div className="flex flex-wrap items-center gap-1.5 border-b border-ink-100 bg-white px-6 py-2.5">
          <span className="text-2xs font-semibold uppercase tracking-wider text-ink-400 mr-2">Formats:</span>
          {[
            { id: 'ALL', label: 'All Formats' },
            { id: 'PDF', label: 'PDFs' },
            { id: 'SPREADSHEET', label: 'Excel Workbooks' },
            { id: 'XML', label: 'AMLA XML' },
            { id: 'IMAGE', label: 'KYC Images' },
            { id: 'DATA', label: 'Data (JSON/CSV)' },
          ].map((type) => (
            <button
              key={type.id}
              onClick={() => setSelectedTypeFilter(type.id)}
              className={`rounded-lg px-2.5 py-1 text-xs font-medium transition-colors ${
                selectedTypeFilter === type.id
                  ? 'bg-mint-wash text-mint-deep font-semibold'
                  : 'text-ink-500 hover:bg-ink-50 hover:text-ink'
              }`}
            >
              {type.label}
            </button>
          ))}
        </div>

        {/* Native Search Bar */}
        <SearchBar
          value={searchQuery}
          onChange={setSearchQuery}
          placeholder="Search by file name, category, SHA-256 checksum, or Azure URI..."
          ariaLabel="Search compliance drive"
          count={filteredBlobs.length}
          total={blobs.length}
        />

        {/* Content Table / Grid */}
        {loading ? (
          <SkeletonRows rows={6} />
        ) : error ? (
          <div className="p-6">
            <ErrorNote message={errorMessage(error)} onRetry={fetchBlobs} />
          </div>
        ) : filteredBlobs.length === 0 ? (
          <Empty
            icon={FolderArchive}
            title={searchQuery ? 'No matching documents' : 'Vault is empty'}
            body={
              searchQuery
                ? 'Try a different search query or clear the filter.'
                : 'Click "Run COB Batch" to process Close of Business and generate EOD filings, or click "Upload Document".'
            }
          />
        ) : viewMode === 'list' ? (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-ink-100 text-left">
                  <th className="label whitespace-nowrap px-6 py-3 font-semibold">Document Name</th>
                  <th className="label whitespace-nowrap px-5 py-3 font-semibold">Category</th>
                  <th className="label whitespace-nowrap px-5 py-3 font-semibold">Folder</th>
                  <th className="label whitespace-nowrap px-5 py-3 font-semibold">SHA-256 Integrity</th>
                  <th className="label whitespace-nowrap px-5 py-3 font-semibold">Size</th>
                  <th className="label whitespace-nowrap px-6 py-3 font-semibold text-right">Action</th>
                </tr>
              </thead>
              <tbody>
                {filteredBlobs.map((blob, idx) => {
                  const meta = getFileMeta(blob.blobName, blob.contentType);
                  const Icon = meta.icon;
                  const folder = blob.blobName.includes('/') ? blob.blobName.split('/')[0] : 'root';
                  const fileName = blob.blobName.split('/').pop();

                  return (
                    <tr
                      key={blob.blobName}
                      onClick={() => handleSelectBlob(blob)}
                      className="row-enter cursor-pointer border-b border-ink-100 transition-colors hover:bg-ink-50/50 last:border-0"
                      style={{ '--i': Math.min(idx, 15) }}
                    >
                      <td className="px-6 py-3.5">
                        <div className="flex items-center gap-3">
                          <div className={`grid size-9 shrink-0 place-items-center rounded-xl ${meta.iconTone}`}>
                            <Icon className="size-4" />
                          </div>
                          <div className="min-w-0">
                            <span className="font-semibold text-ink text-xs block truncate max-w-sm" title={fileName}>
                              {fileName}
                            </span>
                            <span className="text-2xs text-ink-400 font-mono truncate block max-w-sm">
                              {blob.blobName}
                            </span>
                          </div>
                        </div>
                      </td>
                      <td className="px-5 py-3.5">
                        <Badge tone={meta.badgeTone}>{meta.category}</Badge>
                      </td>
                      <td className="px-5 py-3.5">
                        <span className="rounded-md bg-ink-100 px-2 py-0.5 font-mono text-2xs uppercase text-ink-600">
                          {folder}
                        </span>
                      </td>
                      <td className="px-5 py-3.5">
                        <span className="font-mono text-2xs text-ink-400 truncate block max-w-[140px]" title={blob.sha256Checksum}>
                          {blob.sha256Checksum ? `${blob.sha256Checksum.substring(0, 14)}...` : 'Calculated'}
                        </span>
                      </td>
                      <td className="px-5 py-3.5 font-mono text-xs tabular-nums text-ink-500">
                        {(blob.sizeBytes / 1024).toFixed(1)} KB
                      </td>
                      <td className="px-6 py-3.5 text-right">
                        <Button
                          variant="outline"
                          size="sm"
                          icon={Download}
                          onClick={(e) => handleDownload(blob, e)}
                        >
                          Download
                        </Button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        ) : (
          /* Grid View */
          <div className="grid grid-cols-2 gap-4 p-6 sm:grid-cols-3 md:grid-cols-4">
            {filteredBlobs.map((blob) => {
              const meta = getFileMeta(blob.blobName, blob.contentType);
              const Icon = meta.icon;
              const fileName = blob.blobName.split('/').pop();

              return (
                <div
                  key={blob.blobName}
                  onClick={() => handleSelectBlob(blob)}
                  className="group cursor-pointer rounded-2xl border border-ink-100 bg-white p-4 shadow-sm transition-all hover:border-ink-200 hover:shadow-card"
                >
                  <div className={`mb-3 flex h-28 items-center justify-center rounded-xl ${meta.iconTone} transition-transform group-hover:scale-[1.02]`}>
                    <Icon className="size-10" />
                  </div>
                  <Badge tone={meta.badgeTone} className="mb-2">{meta.category}</Badge>
                  <p className="truncate text-xs font-semibold text-ink" title={fileName}>
                    {fileName}
                  </p>
                  <div className="mt-2 flex items-center justify-between border-t border-ink-100 pt-2 text-2xs text-ink-400 font-mono">
                    <span>{(blob.sizeBytes / 1024).toFixed(1)} KB</span>
                    <button
                      onClick={(e) => handleDownload(blob, e)}
                      className="inline-flex items-center gap-1 font-sans font-semibold text-mint-deep hover:underline"
                    >
                      <Download className="size-3" /> Download
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Interactive File Inspector Drawer */}
      <Drawer
        open={Boolean(selectedBlob)}
        onClose={() => {
          setSelectedBlob(null);
          setPreviewContent(null);
        }}
        title={selectedBlob?.blobName.split('/').pop() || 'File Details'}
        subtitle={selectedBlob?.storageUri || ''}
        footer={
          selectedBlob && (
            <div className="flex items-center justify-between gap-4">
              <span className="text-xs font-mono text-ink-400">
                {(selectedBlob.sizeBytes / 1024).toFixed(2)} KB ({selectedBlob.sizeBytes} bytes)
              </span>
              <div className="flex gap-2">
                <Button variant="ghost" size="sm" onClick={() => setSelectedBlob(null)}>
                  Close
                </Button>
                <Button
                  variant="primary"
                  size="sm"
                  icon={Download}
                  onClick={() => handleDownload(selectedBlob)}
                >
                  Download Artifact
                </Button>
              </div>
            </div>
          )
        }
      >
        {selectedBlob && selectedMeta && (
          <div className="space-y-6">
            {/* Header Identity Card */}
            <div className="flex items-center gap-4 rounded-2xl border border-ink-100 bg-white p-5 shadow-card">
              <div className={`grid size-14 place-items-center rounded-2xl ${selectedMeta.iconTone}`}>
                <selectedMeta.icon className="size-7" />
              </div>
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <Badge tone={selectedMeta.badgeTone}>{selectedMeta.category}</Badge>
                  <span className="rounded-full bg-ink-100 px-2 py-0.5 text-2xs font-mono text-ink-500">
                    {selectedBlob.contentType || 'application/octet-stream'}
                  </span>
                </div>
                <h3 className="mt-1 text-sm font-semibold text-ink break-all">
                  {selectedBlob.blobName.split('/').pop()}
                </h3>
              </div>
            </div>

            {/* Metadata Fields */}
            <div className="rounded-2xl border border-ink-100 bg-white p-5 shadow-card space-y-3 text-xs">
              <h4 className="text-xs font-semibold uppercase tracking-wider text-ink-400 mb-3">Storage Metadata</h4>

              <div>
                <span className="block text-ink-400">Full Blob Path</span>
                <span className="font-mono font-semibold text-ink break-all">{selectedBlob.blobName}</span>
              </div>

              <div>
                <div className="flex items-center justify-between">
                  <span className="text-ink-400">Azurite Storage URI</span>
                  <button
                    onClick={() => {
                      navigator.clipboard.writeText(selectedBlob.storageUri);
                      toast('Storage URI copied to clipboard', 'ok');
                    }}
                    className="inline-flex items-center gap-1 text-2xs font-semibold text-ink-500 hover:text-ink"
                  >
                    <Copy className="size-3" /> Copy
                  </button>
                </div>
                <span className="font-mono text-2xs text-sky-deep break-all">{selectedBlob.storageUri}</span>
              </div>

              <div>
                <div className="flex items-center justify-between">
                  <span className="text-ink-400">SHA-256 Checksum (WORM Integrity)</span>
                  <button
                    onClick={() => {
                      navigator.clipboard.writeText(selectedBlob.sha256Checksum || '');
                      toast('SHA-256 checksum copied', 'ok');
                    }}
                    className="inline-flex items-center gap-1 text-2xs font-semibold text-ink-500 hover:text-ink"
                  >
                    <Copy className="size-3" /> Copy
                  </button>
                </div>
                <span className="font-mono text-2xs text-ink-600 break-all">
                  {selectedBlob.sha256Checksum || 'Generated at commit'}
                </span>
              </div>

              <div>
                <span className="block text-ink-400">File Size</span>
                <span className="font-mono font-semibold text-ink">
                  {(selectedBlob.sizeBytes / 1024).toFixed(2)} KB ({selectedBlob.sizeBytes} bytes)
                </span>
              </div>
            </div>

            {/* Inline Preview (For Image) */}
            {selectedMeta.isImage && (
              <div className="rounded-2xl border border-ink-100 bg-white p-5 shadow-card">
                <h4 className="text-xs font-semibold uppercase tracking-wider text-ink-400 mb-3">Image Evidence Preview</h4>
                <div className="overflow-hidden rounded-xl border border-ink-100 bg-paper p-2">
                  <img
                    src={`/api/v1/compliance/reports/download?blobName=${encodeURIComponent(selectedBlob.blobName)}`}
                    alt={selectedBlob.blobName}
                    className="max-h-72 w-full object-contain rounded-lg"
                  />
                </div>
              </div>
            )}

            {/* Inline Preview (For XML, JSON, or Text) */}
            {selectedMeta.isPreviewableText && (
              <div className="rounded-2xl border border-ink-100 bg-white p-5 shadow-card">
                <div className="flex items-center justify-between mb-3">
                  <h4 className="text-xs font-semibold uppercase tracking-wider text-ink-400">Payload Preview</h4>
                  {previewContent && (
                    <button
                      onClick={() => {
                        navigator.clipboard.writeText(previewContent);
                        toast('Content copied to clipboard', 'ok');
                      }}
                      className="inline-flex items-center gap-1 text-2xs font-semibold text-ink-500 hover:text-ink"
                    >
                      <Copy className="size-3" /> Copy
                    </button>
                  )}
                </div>

                {previewLoading ? (
                  <p className="text-xs text-ink-400 animate-pulse">Loading preview...</p>
                ) : (
                  <pre className="max-h-64 overflow-y-auto rounded-xl bg-ink p-3 font-mono text-2xs text-white/90 leading-relaxed whitespace-pre-wrap">
                    {previewContent || 'Empty content'}
                  </pre>
                )}
              </div>
            )}
          </div>
        )}
      </Drawer>

      {/* Direct File Upload Modal */}
      {showUploadModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-ink/40 backdrop-blur-sm" onClick={() => setShowUploadModal(false)} />
          <div className="relative w-full max-w-lg rounded-2xl bg-white p-6 shadow-lift animate-rise">
            <div className="flex items-start justify-between border-b border-ink-100 pb-4">
              <div>
                <h3 className="text-lg font-semibold text-ink">Upload Compliance Document</h3>
                <p className="text-xs text-ink-500 mt-0.5">
                  Direct immutable upload to Azurite compliance vault.
                </p>
              </div>
              <button
                onClick={() => setShowUploadModal(false)}
                className="grid size-8 place-items-center rounded-full text-ink-400 hover:bg-ink-100 hover:text-ink"
              >
                <X className="size-4" />
              </button>
            </div>

            <form onSubmit={handleUploadSubmit} className="mt-5 space-y-4">
              <div>
                <label className="block text-xs font-semibold text-ink mb-1.5">Target Vault Folder</label>
                <select
                  value={uploadFolder}
                  onChange={(e) => setUploadFolder(e.target.value)}
                  className="w-full rounded-xl border border-ink-200 bg-white px-3 py-2 text-xs font-medium text-ink focus:border-ink focus:outline-none"
                >
                  <option value="compliance">compliance (General Regulatory Vault)</option>
                  <option value="amla">amla (AMLA CTR Filings)</option>
                  <option value="eod">eod (End-of-Day Filings)</option>
                  <option value="statements">statements (Customer Statements)</option>
                  <option value="users">users (KYC Identity Records)</option>
                </select>
              </div>

              <div>
                <label className="block text-xs font-semibold text-ink mb-1.5">Select File</label>
                <input
                  type="file"
                  required
                  onChange={(e) => setUploadFile(e.target.files?.[0] || null)}
                  className="w-full rounded-xl border border-ink-200 bg-paper p-3 text-xs text-ink file:mr-3 file:rounded-lg file:border-0 file:bg-ink file:px-3 file:py-1 file:text-xs file:font-semibold file:text-white"
                />
                <p className="mt-1 text-2xs text-ink-400">
                  Accepts PDF, XLSX, XML, JSON, CSV, JPG, PNG, and ZIP archives.
                </p>
              </div>

              <div className="flex justify-end gap-2 pt-3 border-t border-ink-100">
                <Button variant="ghost" size="sm" type="button" onClick={() => setShowUploadModal(false)}>
                  Cancel
                </Button>
                <Button variant="primary" size="sm" type="submit" loading={isUploading} disabled={!uploadFile}>
                  Upload Artifact
                </Button>
              </div>
            </form>
          </div>
        </div>
      )}
    </>
  );
}
