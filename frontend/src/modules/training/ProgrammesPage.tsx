import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Plus } from 'lucide-react';
import { api, download, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, Modal, PageHeader, SelectField, StatusBadge, Tabs, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate, fmtMoney } from '@/components/DataTable';
import { EmployeeSelect } from '@/components/pickers';
import { Nomination, Programme, PROGRAMME_STATUSES, ProgrammeView } from './types';

type Draft = Record<string, string>;

export function ProgrammeForm({ programme, onClose, onSaved }: { programme?: Programme; onClose: () => void; onSaved: (p: ProgrammeView) => void }) {
  const [f, setF] = useState<Draft>(() => {
    const d: Draft = {};
    ['code', 'title', 'description', 'provider', 'startDate', 'endDate', 'venue', 'capacity', 'cost', 'nominationClosingDate']
      .forEach((k) => (d[k] = programme ? String((programme as unknown as Record<string, unknown>)[k] ?? '') : ''));
    return d;
  });
  const [err, setErr] = useState<string | null>(null);
  const set = (k: string) => (v: string) => setF((x) => ({ ...x, [k]: v }));
  const save = async () => {
    setErr(null);
    try {
      const body = { ...f, capacity: f.capacity ? Number(f.capacity) : null, cost: f.cost === '' ? null : Number(f.cost), nominationClosingDate: f.nominationClosingDate || null };
      const r = programme ? await api.put<ProgrammeView>(`/training/programmes/${programme.id}`, body) : await api.post<ProgrammeView>('/training/programmes', body);
      onSaved(r.data);
    } catch (e) {
      setErr(errorMessage(e));
    }
  };
  return (
    <Modal title={programme ? `Edit ${programme.code}` : 'New training programme'} onClose={onClose} wide footer={
      <><button className="btn btn-outline" onClick={onClose}>Cancel</button><button className="btn btn-primary" onClick={() => void save()}>Save</button></>
    }>
      {err && <Alert kind="danger">{err}</Alert>}
      <div className="form-grid">
        <TextField label="Code" value={f.code} onChange={(v) => set('code')(v.toUpperCase())} required />
        <TextField label="Title" value={f.title} onChange={set('title')} required />
        <TextField label="Provider" value={f.provider} onChange={set('provider')} />
        <TextField label="Venue" value={f.venue} onChange={set('venue')} />
        <TextField label="Start date" type="date" value={f.startDate} onChange={set('startDate')} required />
        <TextField label="End date" type="date" value={f.endDate} onChange={set('endDate')} required />
        <TextField label="Capacity (seats)" type="number" min={1} value={f.capacity} onChange={set('capacity')} required />
        <TextField label="Cost (LKR)" type="number" min={0} value={f.cost} onChange={set('cost')} />
        <TextField label="Nominations close" type="date" value={f.nominationClosingDate} onChange={set('nominationClosingDate')} />
        <TextArea label="Description" value={f.description} onChange={set('description')} className="span-all" />
      </div>
    </Modal>
  );
}

/** Programme catalogue and employee training history (FR-TRN-001, -004). */
export default function ProgrammesPage() {
  const { t } = useTranslation();
  const nav = useNavigate();
  const toast = useToast();
  const { can } = useAuth();
  const [tab, setTab] = useState<'programmes' | 'history'>('programmes');
  const [status, setStatus] = useState('');
  const [adding, setAdding] = useState(false);
  const [employeeId, setEmployeeId] = useState('');
  const { data, loading, error } = useApi<ProgrammeView[]>('/training/programmes', { status });
  const history = useApi<Nomination[]>(tab === 'history' && employeeId ? `/training/employees/${employeeId}/history` : null);

  return (
    <>
      <PageHeader eyebrow={t('trn.section', 'Training')} title={t('trn.programmes', 'Training programmes')}
        actions={can('TRN_MANAGE') && <button className="btn btn-gold" onClick={() => setAdding(true)}><Plus /> New programme</button>} />
      <Tabs tabs={[{ key: 'programmes', label: 'Programmes' }, { key: 'history', label: 'Employee training history' }]} value={tab} onChange={setTab} />
      {tab === 'programmes' && (
        <Card pad={false} title={<div className="filters" style={{ margin: 0 }}>
          <SelectField label="Status" value={status} onChange={setStatus} placeholder="All" options={PROGRAMME_STATUSES.map((s) => ({ value: s, label: s }))} />
        </div>}>
          {error && <Alert kind="danger">{error}</Alert>}
          <DataTable<ProgrammeView> rows={data} loading={loading} rowKey={(p) => p.programme.id} onRowClick={(p) => nav(`/training/programmes/${p.programme.id}`)}
            columns={[
              { key: 'code', header: 'Code', render: (p) => p.programme.code },
              { key: 'title', header: 'Programme', render: (p) => p.programme.title },
              { key: 'provider', header: 'Provider', render: (p) => p.programme.provider ?? '—' },
              { key: 'dates', header: 'Dates', render: (p) => `${fmtDate(p.programme.startDate)} – ${fmtDate(p.programme.endDate)}` },
              { key: 'seats', header: 'Seats', render: (p) => `${p.seatsTaken} / ${p.programme.capacity}` },
              { key: 'cost', header: 'Cost', align: 'right', render: (p) => fmtMoney(p.programme.cost) },
              { key: 'status', header: 'Status', render: (p) => <StatusBadge status={p.programme.status} /> },
            ]} />
        </Card>
      )}
      {tab === 'history' && (
        <Card pad={false} title={<div className="filters" style={{ margin: 0 }}><EmployeeSelect value={employeeId} onChange={setEmployeeId} /></div>}>
          {history.error && <Alert kind="danger">{history.error}</Alert>}
          <HistoryTable rows={employeeId ? history.data : []} loading={history.loading} />
        </Card>
      )}
      {adding && <ProgrammeForm onClose={() => setAdding(false)} onSaved={(p) => { setAdding(false); toast('Programme created', 'success'); nav(`/training/programmes/${p.programme.id}`); }} />}
    </>
  );
}

export function HistoryTable({ rows, loading }: { rows: Nomination[] | null | undefined; loading?: boolean }) {
  return (
    <DataTable<Nomination> rows={rows} loading={loading} empty="No training recorded."
      columns={[
        { key: 'programmeTitle', header: 'Programme' },
        { key: 'provider', header: 'Provider' },
        { key: 'dates', header: 'Dates', render: (n) => `${fmtDate(n.startDate)} – ${fmtDate(n.endDate)}` },
        { key: 'status', header: 'Nomination', render: (n) => <StatusBadge status={n.status} /> },
        { key: 'completionStatus', header: 'Completion', render: (n) => (n.completionStatus === 'NOT_RECORDED' ? '—' : <StatusBadge status={n.completionStatus} />) },
        { key: 'result', header: 'Result' },
        { key: 'cert', header: 'Certificate', render: (n) => n.hasCertificate && (
          <button className="btn btn-ghost btn-sm" onClick={() => void download(`/training/nominations/${n.id}/certificate`)}>{n.certificateFileName}</button>
        ) },
      ]} />
  );
}
