import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Plus } from 'lucide-react';
import { useApi } from '@/hooks/useApi';
import { Alert, Badge, Card, PageHeader, StatusBadge, TextField } from '@/components/ui';
import { DataTable, fmtDate } from '@/components/DataTable';
import CorrectionModal from './CorrectionModal';
import { CorrectionTable } from './AttendancePage';
import { hm, isoDate, mins, type Correction, type DailyRow } from './types';

/** Employee self-service: own processed attendance and correction requests. */
export default function MyAttendancePage() {
  const { t } = useTranslation();
  const now = new Date();
  const [from, setFrom] = useState(isoDate(new Date(now.getFullYear(), now.getMonth(), 1)));
  const [to, setTo] = useState(isoDate(now));
  const rows = useApi<DailyRow[]>('/attendance/my', { from, to });
  const corr = useApi<Correction[]>('/attendance/corrections/my');
  const [open, setOpen] = useState<{ workDate?: string } | null>(null);
  return (
    <>
      <PageHeader eyebrow={t('nav.self', 'Self-service')} title={t('attendance.my', 'My attendance')} subtitle="Your recorded check-in / check-out and daily status"
        actions={<button className="btn btn-gold" onClick={() => setOpen({})}><Plus /> Request correction</button>} />
      {rows.error && <Alert kind="danger">{rows.error}</Alert>}
      <Card pad={false}>
        <div className="card-body">
          <div className="filters">
            <TextField label="From" type="date" value={from} onChange={setFrom} />
            <TextField label="To" type="date" value={to} onChange={setTo} />
          </div>
        </div>
        <DataTable<DailyRow>
          rows={rows.data}
          loading={rows.loading}
          empty="No processed attendance in this period"
          columns={[
            { key: 'workDate', header: 'Date', render: (r) => fmtDate(r.workDate) },
            { key: 'status', header: 'Status', render: (r) => <><StatusBadge status={r.status} />{r.corrected && <> <Badge tone="info">corrected</Badge></>}</> },
            { key: 'in', header: 'In', render: (r) => hm(r.checkIn) },
            { key: 'out', header: 'Out', render: (r) => hm(r.checkOut) },
            { key: 'worked', header: 'Worked', align: 'right', render: (r) => mins(r.workedMinutes) },
            { key: 'late', header: 'Late', align: 'right', render: (r) => mins(r.lateMinutes) },
            { key: 'leave', header: 'Leave', render: (r) => r.leaveTypeCode ?? '—' },
            { key: 'exceptionNote', header: 'Notes' },
            { key: 'x', header: '', render: (r) => ['ABSENT', 'LATE', 'EARLY'].includes(r.status) || r.exceptionNote?.includes('No check') ? (
              <button className="btn btn-ghost btn-sm" onClick={() => setOpen({ workDate: r.workDate })}>Correct</button>
            ) : null },
          ]}
        />
      </Card>
      <div style={{ marginTop: 16 }}>
        <Card pad={false} title="My correction requests">
          <CorrectionTable rows={corr.data} loading={corr.loading} />
        </Card>
      </div>
      {open && <CorrectionModal initial={open} onClose={() => setOpen(null)} onSaved={() => { setOpen(null); void corr.reload(); }} />}
    </>
  );
}
