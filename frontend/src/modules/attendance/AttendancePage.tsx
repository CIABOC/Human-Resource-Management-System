import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Check, FileUp, Play, Plus, X } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import type { Page } from '@/api/types';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Badge, Card, Checkbox, ConfirmDialog, KpiCard, Modal, PageHeader, SelectField, StatusBadge, Tabs, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate, fmtDateTime } from '@/components/DataTable';
import { OrgUnitSelect } from '@/components/pickers';
import CorrectionModal from './CorrectionModal';
import { empLabel, hm, isoDate, mins, STATUSES, type AttEvent, type Correction, type DailyRow, type IngestResult } from './types';

type Tab = 'daily' | 'events' | 'corrections';

/** Attendance management: daily results, processing, flagged-event review, corrections and import (FR-ATT-001..020). */
export default function AttendancePage({ initialTab = 'daily' }: { initialTab?: Tab }) {
  const { t } = useTranslation();
  const [tab, setTab] = useState<Tab>(initialTab);
  return (
    <>
      <PageHeader eyebrow={t('nav.time', 'Time & attendance')} title={t('attendance.title', 'Attendance')}
        subtitle="Processed daily attendance, device events awaiting review and manual corrections" />
      <Tabs tabs={[{ key: 'daily', label: 'Daily attendance' }, { key: 'events', label: 'Device events & review' }, { key: 'corrections', label: 'Corrections' }]}
        value={tab} onChange={setTab} />
      <div style={{ marginTop: 14 }}>
        {tab === 'daily' && <Daily />}
        {tab === 'events' && <Events />}
        {tab === 'corrections' && <Corrections />}
      </div>
    </>
  );
}

function Daily() {
  const { can } = useAuth();
  const toast = useToast();
  const [params] = useSearchParams();
  const [date, setDate] = useState(isoDate(new Date()));
  const [orgUnitCode, setOrgUnitCode] = useState('');
  const [status, setStatus] = useState(params.get('status') ?? '');
  const { data, loading, error, reload } = useApi<DailyRow[]>('/attendance/daily', { date, orgUnitCode, status });
  const [busy, setBusy] = useState(false);
  const [correct, setCorrect] = useState<{ employeeId?: number; workDate?: string } | null>(null);

  const process = async () => {
    setBusy(true);
    try {
      const r = await api.post<{ processed: number }[]>('/attendance/process', { date });
      toast(`Processed ${r.data[0]?.processed ?? 0} employees for ${fmtDate(date)}`, 'success');
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  const count = (s: string) => (data ?? []).filter((r) => r.status === s).length;
  return (
    <>
      <div className="grid grid-4" style={{ marginBottom: 14 }}>
        <KpiCard label="Present" value={count('PRESENT') + count('EARLY')} tone="green" />
        <KpiCard label="Late" value={count('LATE')} tone="gold" />
        <KpiCard label="Absent" value={count('ABSENT')} tone="red" />
        <KpiCard label="On leave" value={count('ON_LEAVE')} tone="navy" />
      </div>
      <Card pad={false} actions={
        <>
          {can('SELF_SERVICE', 'ATT_MANAGE') && <button className="btn btn-outline" onClick={() => setCorrect({ workDate: date })}><Plus /> Correction</button>}
          {can('ATT_MANAGE') && <button className="btn btn-gold" disabled={busy} onClick={() => void process()}><Play /> Process day</button>}
        </>
      } title="Daily attendance">
        <div className="card-body">
          <div className="filters">
            <TextField label="Date" type="date" value={date} onChange={setDate} max={isoDate(new Date())} />
            <OrgUnitSelect label="Unit" value={orgUnitCode} onChange={setOrgUnitCode} placeholder="All in my scope" />
            <SelectField label="Status" value={status} onChange={setStatus} placeholder="All" options={STATUSES.map((s) => ({ value: s, label: s.replace('_', ' ').toLowerCase() }))} />
          </div>
          {error && <Alert kind="danger">{error}</Alert>}
          {!loading && data && data.length === 0 && !status && <Alert kind="info">No processed attendance for this date{can('ATT_MANAGE') ? ' — use "Process day".' : '.'}</Alert>}
        </div>
        <DataTable<DailyRow>
          rows={data}
          loading={loading}
          columns={[
            { key: 'emp', header: 'Employee', render: (r) => empLabel(r.employee, r.employeeId) },
            { key: 'unit', header: 'Unit', render: (r) => r.employee?.orgUnitName ?? '—' },
            { key: 'status', header: 'Status', render: (r) => <><StatusBadge status={r.status} />{r.corrected && <> <Badge tone="info">corrected</Badge></>}</> },
            { key: 'in', header: 'In', render: (r) => hm(r.checkIn) },
            { key: 'out', header: 'Out', render: (r) => hm(r.checkOut) },
            { key: 'worked', header: 'Worked', align: 'right', render: (r) => mins(r.workedMinutes) },
            { key: 'late', header: 'Late', align: 'right', render: (r) => mins(r.lateMinutes) },
            { key: 'early', header: 'Early', align: 'right', render: (r) => mins(r.earlyMinutes) },
            { key: 'leave', header: 'Leave', render: (r) => r.leaveTypeCode ? `${r.leaveTypeCode}${r.leaveHalfDay ? ' (½)' : ''}${r.unpaidLeave ? ' · unpaid' : ''}` : '—' },
            { key: 'exceptionNote', header: 'Notes' },
            { key: 'x', header: '', render: (r) => can('ATT_MANAGE') ? <button className="btn btn-ghost btn-sm" onClick={() => setCorrect({ employeeId: r.employeeId, workDate: r.workDate })}>Correct</button> : null },
          ]}
        />
      </Card>
      {correct && <CorrectionModal forOthers={can('ATT_MANAGE')} initial={correct} onClose={() => setCorrect(null)} onSaved={() => setCorrect(null)} />}
    </>
  );
}

function Events() {
  const { can } = useAuth();
  const toast = useToast();
  const [from, setFrom] = useState(isoDate(new Date(Date.now() - 7 * 864e5)));
  const [to, setTo] = useState(isoDate(new Date()));
  const [awaiting, setAwaiting] = useState(true);
  const [page, setPage] = useState(0);
  const { data, loading, error, reload } = useApi<Page<AttEvent>>('/attendance/events', { from, to, awaitingReview: awaiting, flaggedOnly: false, page, size: 50 });
  const [review, setReview] = useState<{ ev: AttEvent; decision: 'ACCEPT' | 'REJECT' } | null>(null);
  const [comment, setComment] = useState('');
  const [importOpen, setImportOpen] = useState(false);
  const integration = useApi<{ enabled: boolean }>(can('ATT_MANAGE') ? '/attendance/device-integration' : null);

  const doReview = async () => {
    if (!review) return;
    try {
      await api.post(`/attendance/events/${review.ev.id}/review`, { decision: review.decision, comment });
      toast(review.decision === 'ACCEPT' ? 'Event accepted' : 'Event rejected', 'success');
      setReview(null);
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  return (
    <Card pad={false} title="Device events" actions={can('ATT_MANAGE') ? <button className="btn btn-primary" onClick={() => setImportOpen(true)}><FileUp /> Import CSV</button> : undefined}>
      <div className="card-body">
        {integration.data && (
          <Alert kind={integration.data.enabled ? 'success' : 'info'}>
            Device integration (INT-04) is {integration.data.enabled ? 'enabled: devices post batches to /api/attendance/device-events' : 'disabled — events are loaded by CSV import'}.
          </Alert>
        )}
        <div className="filters">
          <TextField label="From" type="date" value={from} onChange={(v) => { setFrom(v); setPage(0); }} />
          <TextField label="To" type="date" value={to} onChange={(v) => { setTo(v); setPage(0); }} />
          <Checkbox label="Only flagged events awaiting review" checked={awaiting} onChange={(v) => { setAwaiting(v); setPage(0); }} />
        </div>
        {error && <Alert kind="danger">{error}</Alert>}
      </div>
      <DataTable<AttEvent>
        rows={data?.content}
        loading={loading}
        page={data?.page}
        totalPages={data?.totalPages}
        totalElements={data?.totalElements}
        onPage={setPage}
        empty={awaiting ? 'No events awaiting review' : 'No events'}
        columns={[
          { key: 'time', header: 'Time', render: (e) => <span className="nowrap">{fmtDateTime(e.eventTime)}</span> },
          { key: 'emp', header: 'Employee', render: (e) => e.employeeName ? `${e.employeeNo} — ${e.employeeName}` : <span>{e.employeeNo} <Badge tone="danger">unknown</Badge></span> },
          { key: 'direction', header: 'Dir.' },
          { key: 'source', header: 'Source', render: (e) => `${e.source}${e.deviceId ? ` · ${e.deviceId}` : ''}` },
          { key: 'flag', header: 'Flag', render: (e) => (e.flagged ? <span className="muted">{e.flagReason}</span> : '—') },
          { key: 'review', header: 'Review', render: (e) => e.reviewStatus ? <span title={e.reviewComment ?? ''}><StatusBadge status={e.reviewStatus === 'ACCEPTED' ? 'APPROVED' : e.reviewStatus === 'REJECTED' ? 'REJECTED' : 'SUPERSEDED'} label={e.reviewStatus.toLowerCase()} /> {e.reviewedBy}</span> : e.flagged ? <StatusBadge status="PENDING" label="awaiting" /> : '—' },
          { key: 'x', header: '', render: (e) => e.flagged && !e.reviewStatus && can('ATT_MANAGE') ? (
            <div className="row">
              {e.employeeId && <button className="btn btn-outline btn-sm" onClick={() => { setComment(''); setReview({ ev: e, decision: 'ACCEPT' }); }}><Check /> Accept</button>}
              <button className="btn btn-outline btn-sm" onClick={() => { setComment(''); setReview({ ev: e, decision: 'REJECT' }); }}><X /> Reject</button>
            </div>
          ) : null },
        ]}
      />
      {review && (
        <ConfirmDialog title={review.decision === 'ACCEPT' ? 'Accept event' : 'Reject event'} danger={review.decision === 'REJECT'}
          message={`${review.ev.employeeNo} ${review.ev.direction} at ${fmtDateTime(review.ev.eventTime)} — ${review.ev.flagReason}`}
          confirmLabel={review.decision === 'ACCEPT' ? 'Accept' : 'Reject'} busy={review.decision === 'REJECT' && !comment.trim()}
          onCancel={() => setReview(null)} onConfirm={() => void doReview()}>
          <TextArea label={review.decision === 'REJECT' ? 'Reason' : 'Comment (optional)'} required={review.decision === 'REJECT'} value={comment} onChange={setComment} />
        </ConfirmDialog>
      )}
      {importOpen && <ImportModal onClose={() => setImportOpen(false)} onDone={() => void reload()} />}
    </Card>
  );
}

function ImportModal({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const toast = useToast();
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<IngestResult | null>(null);
  const [busy, setBusy] = useState(false);
  const run = async () => {
    if (!file) return;
    setBusy(true);
    const fd = new FormData();
    fd.append('file', file);
    try {
      const r = await api.post<IngestResult>('/attendance/import', fd);
      setResult(r.data);
      onDone();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal title="Import attendance events (CSV)" onClose={onClose} footer={
      <>
        <button className="btn btn-outline" onClick={onClose}>Close</button>
        {!result && <button className="btn btn-primary" disabled={!file || busy} onClick={() => void run()}>Import</button>}
      </>
    }>
      {!result ? (
        <div className="stack">
          <Alert kind="info">Columns: <code>employeeNo,timestamp,direction,deviceId</code> — timestamp as <code>yyyy-MM-dd HH:mm[:ss]</code> (Sri Lanka time) or ISO-8601; direction IN or OUT. Re-importing the same events is safe: exact duplicates are skipped.</Alert>
          <input type="file" accept=".csv,.txt" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
        </div>
      ) : (
        <div className="stack">
          <Alert kind={result.rejected ? 'warning' : 'success'}>
            {result.received} rows read · {result.accepted} stored ({result.flagged} flagged for review) · {result.skippedDuplicates} duplicates skipped · {result.rejected} rejected
            {result.reprocessedEmployeeDays ? ` · ${result.reprocessedEmployeeDays} processed days re-evaluated` : ''}
          </Alert>
          {result.errors.length > 0 && <ul className="muted" style={{ fontSize: 13 }}>{result.errors.map((e) => <li key={e}>{e}</li>)}</ul>}
        </div>
      )}
    </Modal>
  );
}

function Corrections() {
  const { can } = useAuth();
  const [status, setStatus] = useState('');
  const { data, loading, error, reload } = useApi<Correction[]>('/attendance/corrections', { status });
  const [open, setOpen] = useState(false);
  return (
    <Card pad={false} title="Attendance corrections" actions={can('SELF_SERVICE', 'ATT_MANAGE') ? <button className="btn btn-primary" onClick={() => setOpen(true)}><Plus /> Request correction</button> : undefined}>
      <div className="card-body">
        <div className="filters">
          <SelectField label="Status" value={status} onChange={setStatus} placeholder="All" options={['PENDING', 'RETURNED', 'APPROVED', 'REJECTED', 'CANCELLED'].map((s) => ({ value: s, label: s.toLowerCase() }))} />
        </div>
        {error && <Alert kind="danger">{error}</Alert>}
      </div>
      <CorrectionTable rows={data} loading={loading} />
      {open && <CorrectionModal forOthers={can('ATT_MANAGE')} onClose={() => setOpen(false)} onSaved={() => { setOpen(false); void reload(); }} />}
    </Card>
  );
}

export function CorrectionTable({ rows, loading }: { rows: Correction[] | null; loading: boolean }) {
  return (
    <DataTable<Correction>
      rows={rows}
      loading={loading}
      empty="No correction requests"
      columns={[
        { key: 'emp', header: 'Employee', render: (c) => empLabel(c.employee, c.employeeId) },
        { key: 'workDate', header: 'Date', render: (c) => fmtDate(c.workDate) },
        { key: 'in', header: 'Check-in', render: (c) => c.checkIn?.slice(0, 5) ?? '—' },
        { key: 'out', header: 'Check-out', render: (c) => c.checkOut?.slice(0, 5) ?? '—' },
        { key: 'reason', header: 'Reason' },
        { key: 'status', header: 'Status', render: (c) => <StatusBadge status={c.status} /> },
        { key: 'wf', header: '', render: (c) => (c.workflowInstanceId ? <Link to={`/approvals/${c.workflowInstanceId}`}>Request #{c.workflowInstanceId}</Link> : null) },
      ]}
    />
  );
}
