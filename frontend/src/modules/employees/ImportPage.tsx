import { useState } from 'react';
import { CheckCircle2, Download, FileSearch, UploadCloud } from 'lucide-react';
import { api, download, errorMessage } from '@/api/client';
import { useToast } from '@/components/Toast';
import { Alert, Card, KpiCard, PageHeader } from '@/components/ui';
import { DataTable } from '@/components/DataTable';

interface ImportResult {
  committed: boolean; totalRows: number; validRows: number; errors: { row: number; employeeNo?: string; message: string }[];
  byOrgUnit: Record<string, number>; byStatus: Record<string, number>;
}

/** Legacy data migration with validation and reconciliation (FR-EMP-014, DR-06). All-or-nothing commit. */
export default function ImportPage() {
  const toast = useToast();
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);
  const [busy, setBusy] = useState(false);

  const run = async (commit: boolean) => {
    if (!file) return;
    setBusy(true);
    const fd = new FormData();
    fd.append('file', file);
    try {
      const { data } = await api.post<ImportResult>(`/employees/import?commit=${commit}`, fd);
      setResult(data);
      if (data.committed) toast(`${data.validRows} employees imported`, 'success');
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader eyebrow="Data migration" title="Bulk import employees"
        subtitle="Validate first, review the reconciliation, then import. Nothing is saved unless every row is valid."
        actions={<button className="btn btn-outline" onClick={() => void download('/employees/import/template')}><Download /> CSV template</button>} />
      <Card accent>
        <div className="row" style={{ alignItems: 'flex-end' }}>
          <div className="field" style={{ flex: 1, minWidth: 260 }}>
            <label htmlFor="csv">CSV file (UTF-8)</label>
            <input id="csv" type="file" accept=".csv" className="input" style={{ paddingTop: 7 }} onChange={(e) => { setFile(e.target.files?.[0] ?? null); setResult(null); }} />
          </div>
          <button className="btn btn-outline" disabled={!file || busy} onClick={() => void run(false)}><FileSearch /> Validate</button>
          <button className="btn btn-gold" disabled={!file || busy || !result || result.errors.length > 0 || result.committed} onClick={() => void run(true)}><UploadCloud /> Import</button>
        </div>
      </Card>
      {result && (
        <div className="stack" style={{ marginTop: 18 }}>
          {result.committed ? <Alert kind="success"><CheckCircle2 size={14} /> Import completed. Ask the data owner to sign off the reconciliation below.</Alert>
            : result.errors.length > 0 ? <Alert kind="danger">{result.errors.length} row(s) have errors. Correct the file and validate again.</Alert>
              : <Alert kind="info">All rows are valid. Review the reconciliation, then click Import.</Alert>}
          <div className="grid grid-3">
            <KpiCard label="Rows in file" value={result.totalRows} />
            <KpiCard label="Valid rows" value={result.validRows} tone="green" />
            <KpiCard label="Rows with errors" value={result.errors.length} tone="red" />
          </div>
          {result.errors.length > 0 && (
            <Card title="Errors" pad={false}>
              <DataTable rows={result.errors} rowKey={(r, i) => `${r.row}-${i}`} columns={[{ key: 'row', header: 'Line' }, { key: 'employeeNo', header: 'Emp. no' }, { key: 'message', header: 'Problem' }]} />
            </Card>
          )}
          <div className="grid grid-2">
            <Card title="Reconciliation by unit" pad={false}>
              <DataTable rows={Object.entries(result.byOrgUnit).map(([k, v]) => ({ k, v }))} rowKey={(r) => r.k} columns={[{ key: 'k', header: 'Unit' }, { key: 'v', header: 'Employees', align: 'right' }]} />
            </Card>
            <Card title="Reconciliation by status" pad={false}>
              <DataTable rows={Object.entries(result.byStatus).map(([k, v]) => ({ k, v }))} rowKey={(r) => r.k} columns={[{ key: 'k', header: 'Status' }, { key: 'v', header: 'Employees', align: 'right' }]} />
            </Card>
          </div>
        </div>
      )}
    </>
  );
}
