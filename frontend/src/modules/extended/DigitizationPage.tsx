import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Plus } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, Modal, PageHeader, StatusBadge, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate } from '@/components/DataTable';
import type { BatchDetail, BatchSummary } from './types';

/** Controlled digitization of legacy personal files (FR-EXT-011). */
export default function DigitizationPage() {
  const { t } = useTranslation();
  const { can } = useAuth();
  const nav = useNavigate();
  const toast = useToast();
  const { data, loading, error } = useApi<BatchSummary[]>('/extended/digitization/batches');
  const [open, setOpen] = useState(false);
  const [f, setF] = useState({ title: '', sourceLocation: '', remarks: '' });

  const create = async () => {
    try {
      const r = await api.post<BatchDetail>('/extended/digitization/batches', f);
      toast(`Batch ${r.data.batch.batchNo} created`, 'success');
      nav(`/extended/digitization/${r.data.batch.id}`);
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  return (
    <>
      <PageHeader title={t('extended.digitization', 'Digitization')}
        subtitle="Scan legacy personal files in batches, verify by a second officer, then file into the digital personal file"
        actions={can('EXT_MANAGE') && <button className="btn btn-gold" onClick={() => setOpen(true)}><Plus /> New batch</button>} />
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false}>
        <DataTable<BatchSummary>
          rows={data}
          loading={loading}
          onRowClick={(b) => nav(`/extended/digitization/${b.id}`)}
          columns={[
            { key: 'batchNo', header: 'Batch', render: (b) => <span className="mono">{b.batchNo}</span> },
            { key: 'title', header: 'Title' },
            { key: 'createdAt', header: 'Created', render: (b) => `${fmtDate(b.createdAt)} · ${b.createdBy}` },
            { key: 'total', header: 'Scans', align: 'right' },
            { key: 'qa', header: 'Awaiting QA', align: 'right', render: (b) => b.counts.UPLOADED ?? 0 },
            { key: 'filed', header: 'Filed', align: 'right', render: (b) => b.counts.FILED ?? 0 },
            { key: 'rejected', header: 'Rejected', align: 'right', render: (b) => b.counts.REJECTED ?? 0 },
            { key: 'status', header: 'Status', render: (b) => <StatusBadge status={b.status} label={b.status === 'IN_QA' ? 'In QA' : undefined} /> },
          ]}
        />
      </Card>
      {open && (
        <Modal title="New digitization batch" onClose={() => setOpen(false)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setOpen(false)}>Cancel</button>
            <button className="btn btn-primary" disabled={!f.title.trim()} onClick={() => void create()}>Create</button>
          </>
        }>
          <div className="form-grid">
            <TextField label="Title" required value={f.title} onChange={(v) => setF({ ...f, title: v })} />
            <TextField label="Source location (record room / cabinet)" value={f.sourceLocation} onChange={(v) => setF({ ...f, sourceLocation: v })} />
            <TextArea label="Remarks" value={f.remarks} onChange={(v) => setF({ ...f, remarks: v })} />
          </div>
        </Modal>
      )}
    </>
  );
}
