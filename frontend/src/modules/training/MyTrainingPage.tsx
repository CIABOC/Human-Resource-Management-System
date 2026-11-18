import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Card, Empty, Modal, PageHeader, StatusBadge, TextArea } from '@/components/ui';
import { DataTable, fmtDate } from '@/components/DataTable';
import { HistoryTable } from './ProgrammesPage';
import type { Nomination, ProgrammeView } from './types';

/** Self-service: open programmes with self-nomination, my nominations and training history. */
export default function MyTrainingPage() {
  const { t } = useTranslation();
  const toast = useToast();
  const mine = useApi<Nomination[]>('/training/my');
  const open = useApi<ProgrammeView[]>('/training/programmes/open');
  const [apply, setApply] = useState<{ p: ProgrammeView; reason: string } | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const live = new Set((mine.data ?? []).filter((n) => ['PENDING', 'RETURNED', 'APPROVED'].includes(n.status)).map((n) => n.programmeId));

  const submit = async () => {
    if (!apply) return;
    setErr(null);
    try {
      await api.post('/training/my/nominations', { programmeId: apply.p.programme.id, reason: apply.reason });
      setApply(null);
      toast('Nomination submitted for approval', 'success');
      void mine.reload();
      void open.reload();
    } catch (e) {
      setErr(errorMessage(e));
    }
  };
  const withdraw = async (n: Nomination) => {
    const reason = window.prompt('Reason for withdrawing this nomination');
    if (reason === null) return;
    try {
      await api.post(`/training/nominations/${n.id}/cancel`, { reason });
      toast('Nomination withdrawn', 'success');
      void mine.reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  const upcoming = (mine.data ?? []).filter((n) => n.status !== 'REJECTED' && n.status !== 'CANCELLED' && n.completionStatus === 'NOT_RECORDED');

  return (
    <>
      <PageHeader title={t('trn.my', 'My training')} subtitle="Nominate yourself for open programmes and see your training history" />
      <div className="stack">
        <Card pad={false} title="My nominations">
          <DataTable<Nomination> rows={upcoming} loading={mine.loading} empty="No current nominations."
            columns={[
              { key: 'programmeTitle', header: 'Programme' },
              { key: 'dates', header: 'Dates', render: (n) => `${fmtDate(n.startDate)} – ${fmtDate(n.endDate)}` },
              { key: 'venue', header: 'Venue' },
              { key: 'status', header: 'Status', render: (n) => <StatusBadge status={n.status} /> },
              { key: 'act', header: '', render: (n) => (n.source === 'SELF' && (n.status === 'PENDING' || n.status === 'RETURNED')) || n.status === 'APPROVED'
                ? <button className="btn btn-ghost btn-sm" onClick={() => void withdraw(n)}>Withdraw</button> : null },
            ]} />
        </Card>
        <Card pad={false} title="Programmes open for nomination">
          {open.error && <Alert kind="danger">{open.error}</Alert>}
          {!open.loading && (open.data ?? []).length === 0 && <Empty text="No programmes are open for nomination." />}
          {(open.data ?? []).length > 0 && (
            <DataTable<ProgrammeView> rows={open.data} rowKey={(p) => p.programme.id}
              columns={[
                { key: 'title', header: 'Programme', render: (p) => <div><strong>{p.programme.title}</strong>{p.programme.description && <div className="faint">{p.programme.description}</div>}</div> },
                { key: 'provider', header: 'Provider', render: (p) => p.programme.provider ?? '—' },
                { key: 'dates', header: 'Dates', render: (p) => `${fmtDate(p.programme.startDate)} – ${fmtDate(p.programme.endDate)}` },
                { key: 'close', header: 'Closes', render: (p) => fmtDate(p.programme.nominationClosingDate) },
                { key: 'seats', header: 'Seats left', align: 'right', render: (p) => p.seatsAvailable },
                { key: 'act', header: '', render: (p) => live.has(p.programme.id) ? <span className="faint">Nominated</span>
                  : <button className="btn btn-primary btn-sm" disabled={p.seatsAvailable <= 0} onClick={() => { setErr(null); setApply({ p, reason: '' }); }}>Nominate me</button> },
              ]} />
          )}
        </Card>
        <Card pad={false} title="Training history">
          <HistoryTable rows={mine.data} loading={mine.loading} />
        </Card>
      </div>
      {apply && (
        <Modal title={`Nominate myself — ${apply.p.programme.title}`} onClose={() => setApply(null)} footer={
          <><button className="btn btn-outline" onClick={() => setApply(null)}>Cancel</button><button className="btn btn-gold" onClick={() => void submit()}>Submit</button></>
        }>
          {err && <Alert kind="danger">{err}</Alert>}
          <TextArea label="Why is this training relevant to your work?" value={apply.reason} onChange={(v) => setApply({ ...apply, reason: v })} required rows={4} />
          <p className="faint">Your division head and HR will review the nomination.</p>
        </Modal>
      )}
    </>
  );
}
