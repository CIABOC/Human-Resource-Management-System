import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useApi } from '@/hooks/useApi';
import { Alert, Card, PageHeader, SelectField, StatusBadge } from '@/components/ui';
import { DataTable, fmtDate, fmtDateTime } from '@/components/DataTable';

export interface EstablishmentChange {
  id: number; changeType: string; targetRef?: string; payload: string; summary: string; effectiveDate: string; reason: string;
  status: string; workflowInstanceId?: number; appliedAt?: string; createdAt: string; createdBy: string;
}

/** Establishment change history and pending proposals (FR-EST-060, FR-AUD-003). */
export default function ChangesPage() {
  const nav = useNavigate();
  const [status, setStatus] = useState('');
  const { data, loading, error } = useApi<EstablishmentChange[]>('/establishment/changes', { status });
  return (
    <>
      <PageHeader eyebrow="Establishment" title="Establishment changes" subtitle="Every proposed and applied change to structure, designations, positions and cadre." />
      <div className="filters">
        <SelectField label="Status" value={status} onChange={setStatus} placeholder="— All —"
          options={['PENDING', 'RETURNED', 'APPLIED', 'REJECTED', 'CANCELLED'].map((s) => ({ value: s, label: s.toLowerCase() }))} />
      </div>
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false}>
        <DataTable<EstablishmentChange> rows={data} loading={loading} onRowClick={(c) => nav(`/establishment/changes/${c.id}`)} columns={[
          { key: 'id', header: 'Ref', render: (c) => <span className="mono">#{c.id}</span> },
          { key: 'summary', header: 'Change', render: (c) => <div><div style={{ fontWeight: 600 }}>{c.summary}</div><div className="faint" style={{ fontSize: 12 }}>{c.changeType.replace(/_/g, ' ').toLowerCase()}</div></div> },
          { key: 'effectiveDate', header: 'Effective', render: (c) => fmtDate(c.effectiveDate) },
          { key: 'createdBy', header: 'Proposed by' },
          { key: 'createdAt', header: 'Submitted', render: (c) => fmtDateTime(c.createdAt) },
          { key: 'status', header: 'Status', render: (c) => <StatusBadge status={c.status} /> },
        ]} />
      </Card>
    </>
  );
}
