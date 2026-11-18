import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { History, Plus, Trash2 } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, Checkbox, ConfirmDialog, Modal, PageHeader, SelectField, StatusBadge, Tabs, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate } from '@/components/DataTable';
import { MasterDataSelect, OrgUnitSelect, useMasterDataLabels } from '@/components/pickers';
import { isoDate, type Holiday, type Schedule } from './types';

type Tab = 'schedules' | 'holidays';
const DAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];

/** Effective-dated working schedules and the holiday calendar (FR-ATT-010..013). */
export default function AttendanceConfigPage() {
  const { t } = useTranslation();
  const [tab, setTab] = useState<Tab>('schedules');
  return (
    <>
      <PageHeader eyebrow={t('nav.time', 'Time & attendance')} title={t('attendance.config', 'Working schedules & holidays')}
        subtitle="Working hours, grace periods and work days apply from an effective date; earlier versions are kept as history" />
      <Tabs tabs={[{ key: 'schedules', label: 'Working schedules' }, { key: 'holidays', label: 'Holiday calendar' }]} value={tab} onChange={setTab} />
      <div style={{ marginTop: 14 }}>{tab === 'schedules' ? <Schedules /> : <Holidays />}</div>
    </>
  );
}

interface SchedForm { code: string; name: string; startTime: string; endTime: string; lateGraceMinutes: string; earlyGraceMinutes: string; workDays: string[]; orgUnitCode: string; employeeCategory: string; effectiveFrom: string; remarks: string }

function Schedules() {
  const { can } = useAuth();
  const toast = useToast();
  const [all, setAll] = useState(false);
  const { data, loading, error, reload } = useApi<Schedule[]>('/attendance/schedules', { allVersions: all });
  const cat = useMasterDataLabels('EMPLOYEE_CATEGORY');
  const [edit, setEdit] = useState<{ revise?: string; f: SchedForm } | null>(null);
  const [hist, setHist] = useState<string | null>(null);
  const history = useApi<Schedule[]>(hist ? `/attendance/schedules/${hist}/history` : null);
  const [end, setEnd] = useState<{ code: string; date: string } | null>(null);
  const today = isoDate(new Date());

  const open = (s?: Schedule) => setEdit(s ? {
    revise: s.code,
    f: { code: s.code, name: s.name, startTime: s.startTime.slice(0, 5), endTime: s.endTime.slice(0, 5), lateGraceMinutes: String(s.lateGraceMinutes),
      earlyGraceMinutes: String(s.earlyGraceMinutes), workDays: s.workDays.split(','), orgUnitCode: s.orgUnitCode ?? '', employeeCategory: s.employeeCategory ?? '',
      effectiveFrom: today, remarks: '' },
  } : { f: { code: '', name: '', startTime: '08:30', endTime: '16:15', lateGraceMinutes: '10', earlyGraceMinutes: '10', workDays: DAYS.slice(0, 5), orgUnitCode: '', employeeCategory: '', effectiveFrom: today, remarks: '' } });

  const save = async () => {
    if (!edit) return;
    const f = edit.f;
    const body = { ...f, lateGraceMinutes: Number(f.lateGraceMinutes), earlyGraceMinutes: Number(f.earlyGraceMinutes), orgUnitCode: f.orgUnitCode || null, employeeCategory: f.employeeCategory || null };
    try {
      if (edit.revise) await api.put(`/attendance/schedules/${edit.revise}`, body);
      else await api.post('/attendance/schedules', body);
      toast(edit.revise ? 'New schedule version saved' : 'Schedule created', 'success');
      setEdit(null);
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  const doEnd = async () => {
    if (!end) return;
    try {
      await api.post(`/attendance/schedules/${end.code}/end`, { effectiveTo: end.date });
      toast('Schedule end date set', 'success');
      setEnd(null);
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  const F = edit?.f;
  const setF = (p: Partial<SchedForm>) => edit && setEdit({ ...edit, f: { ...edit.f, ...p } });
  const applies = (s: Schedule) => [s.orgUnitCode ? `Unit ${s.orgUnitCode} and sub-units` : null, s.employeeCategory ? cat(s.employeeCategory) : null].filter(Boolean).join(' · ') || 'Everyone (default)';

  return (
    <Card pad={false} title={<Checkbox label="Show all versions (history and future)" checked={all} onChange={setAll} />}
      actions={can('ATT_CONFIG') ? <button className="btn btn-primary" onClick={() => open()}><Plus /> New schedule</button> : undefined}>
      {error && <div className="card-body"><Alert kind="danger">{error}</Alert></div>}
      <DataTable<Schedule>
        rows={data}
        loading={loading}
        columns={[
          { key: 'code', header: 'Code' },
          { key: 'name', header: 'Name' },
          { key: 'hours', header: 'Hours', render: (s) => `${s.startTime.slice(0, 5)} – ${s.endTime.slice(0, 5)}` },
          { key: 'grace', header: 'Grace (late / early)', render: (s) => `${s.lateGraceMinutes} / ${s.earlyGraceMinutes} min` },
          { key: 'workDays', header: 'Work days' },
          { key: 'applies', header: 'Applies to', render: applies },
          { key: 'eff', header: 'Effective', render: (s) => `${fmtDate(s.effectiveFrom)}${s.effectiveTo ? ` – ${fmtDate(s.effectiveTo)}` : ''}` },
          { key: 'st', header: '', render: (s) => <StatusBadge status={s.effectiveTo && s.effectiveTo <= today ? 'CLOSED' : s.effectiveFrom > today ? 'SCHEDULED' : 'ACTIVE'} /> },
          { key: 'x', header: '', render: (s) => (
            <div className="row">
              <button className="btn btn-ghost btn-sm" onClick={() => setHist(s.code)} aria-label="History"><History /></button>
              {can('ATT_CONFIG') && !s.effectiveTo && <button className="btn btn-outline btn-sm" onClick={() => open(s)}>Change</button>}
              {can('ATT_CONFIG') && !s.effectiveTo && <button className="btn btn-ghost btn-sm" onClick={() => setEnd({ code: s.code, date: today })}>End</button>}
            </div>
          ) },
        ]}
      />
      {edit && F && (
        <Modal wide title={edit.revise ? `Change schedule ${edit.revise}` : 'New working schedule'} onClose={() => setEdit(null)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setEdit(null)}>Cancel</button>
            <button className="btn btn-primary" disabled={!F.code || !F.name || F.workDays.length === 0} onClick={() => void save()}>Save</button>
          </>
        }>
          {edit.revise && <Alert kind="info">A new version takes effect from the date below; the current version is closed on that date and kept as history.</Alert>}
          <div className="form-grid cols-3">
            <TextField label="Code" required disabled={!!edit.revise} value={F.code} onChange={(v) => setF({ code: v.toUpperCase() })} />
            <TextField className="span-2" label="Name" required value={F.name} onChange={(v) => setF({ name: v })} />
            <TextField label="Start time" type="time" required value={F.startTime} onChange={(v) => setF({ startTime: v })} />
            <TextField label="End time" type="time" required value={F.endTime} onChange={(v) => setF({ endTime: v })} />
            <TextField label="Effective from" type="date" required value={F.effectiveFrom} onChange={(v) => setF({ effectiveFrom: v })} />
            <TextField label="Late grace (min)" type="number" value={F.lateGraceMinutes} onChange={(v) => setF({ lateGraceMinutes: v })} />
            <TextField label="Early-leave grace (min)" type="number" value={F.earlyGraceMinutes} onChange={(v) => setF({ earlyGraceMinutes: v })} />
            <div />
            <div className="span-all field">
              <label>Work days</label>
              <div className="row">{DAYS.map((d) => <Checkbox key={d} label={d} checked={F.workDays.includes(d)} onChange={(v) => setF({ workDays: v ? [...F.workDays, d] : F.workDays.filter((x) => x !== d) })} />)}</div>
            </div>
            <OrgUnitSelect label="Org unit (optional)" value={F.orgUnitCode} onChange={(v) => setF({ orgUnitCode: v })} placeholder="All units" />
            <MasterDataSelect category="EMPLOYEE_CATEGORY" label="Employee category (optional)" value={F.employeeCategory} onChange={(v) => setF({ employeeCategory: v })} placeholder="All categories" />
            <TextArea className="span-all" label="Remarks / authority" value={F.remarks} onChange={(v) => setF({ remarks: v })} />
          </div>
        </Modal>
      )}
      {hist && (
        <Modal wide title={`History · ${hist}`} onClose={() => setHist(null)}>
          <DataTable<Schedule> rows={history.data} loading={history.loading} columns={[
            { key: 'eff', header: 'Effective', render: (s) => `${fmtDate(s.effectiveFrom)} – ${s.effectiveTo ? fmtDate(s.effectiveTo) : 'open'}` },
            { key: 'hours', header: 'Hours', render: (s) => `${s.startTime.slice(0, 5)} – ${s.endTime.slice(0, 5)}` },
            { key: 'grace', header: 'Grace', render: (s) => `${s.lateGraceMinutes} / ${s.earlyGraceMinutes}` },
            { key: 'workDays', header: 'Work days' },
            { key: 'applies', header: 'Applies to', render: applies },
            { key: 'remarks', header: 'Remarks' },
          ]} />
        </Modal>
      )}
      {end && (
        <ConfirmDialog title={`End schedule ${end.code}`} message="The schedule no longer applies from this date (employees fall back to the next most specific schedule)."
          confirmLabel="Set end date" onCancel={() => setEnd(null)} onConfirm={() => void doEnd()}>
          <TextField label="No longer applies from" type="date" value={end.date} onChange={(v) => setEnd({ ...end, date: v })} />
        </ConfirmDialog>
      )}
    </Card>
  );
}

function Holidays() {
  const { can } = useAuth();
  const toast = useToast();
  const y = new Date().getFullYear();
  const [year, setYear] = useState(String(y));
  const { data, loading, reload } = useApi<Holiday[]>('/attendance/holidays', { year });
  const [edit, setEdit] = useState<{ id?: number; holidayDate: string; name: string; nameSi: string; nameTa: string; holidayType: string; remarks: string } | null>(null);
  const [del, setDel] = useState<Holiday | null>(null);

  const save = async () => {
    if (!edit) return;
    try {
      if (edit.id) await api.put(`/attendance/holidays/${edit.id}`, edit);
      else await api.post('/attendance/holidays', edit);
      toast('Holiday saved', 'success');
      setEdit(null);
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };
  const remove = async () => {
    if (!del) return;
    try {
      await api.delete(`/attendance/holidays/${del.id}`);
      toast('Holiday removed', 'success');
      setDel(null);
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  return (
    <Card pad={false} title={<SelectField label="Year" value={year} placeholder={null} onChange={setYear} options={[y - 1, y, y + 1].map((v) => ({ value: String(v), label: String(v) }))} />}
      actions={can('ATT_CONFIG') ? <button className="btn btn-primary" onClick={() => setEdit({ holidayDate: `${year}-01-01`, name: '', nameSi: '', nameTa: '', holidayType: 'PUBLIC', remarks: '' })}><Plus /> Add holiday</button> : undefined}>
      <DataTable<Holiday>
        rows={data}
        loading={loading}
        empty="No holidays defined for this year"
        columns={[
          { key: 'holidayDate', header: 'Date', render: (h) => fmtDate(h.holidayDate) },
          { key: 'day', header: 'Day', render: (h) => new Date(h.holidayDate + 'T00:00:00').toLocaleDateString('en-GB', { weekday: 'long' }) },
          { key: 'name', header: 'Holiday' },
          { key: 'holidayType', header: 'Type' },
          { key: 'remarks', header: 'Remarks' },
          { key: 'x', header: '', render: (h) => can('ATT_CONFIG') ? (
            <div className="row">
              <button className="btn btn-outline btn-sm" onClick={() => setEdit({ id: h.id, holidayDate: h.holidayDate, name: h.name, nameSi: h.nameSi ?? '', nameTa: h.nameTa ?? '', holidayType: h.holidayType, remarks: h.remarks ?? '' })}>Edit</button>
              <button className="btn btn-ghost btn-sm" aria-label="Remove" onClick={() => setDel(h)}><Trash2 /></button>
            </div>
          ) : null },
        ]}
      />
      {edit && (
        <Modal title={edit.id ? 'Edit holiday' : 'Add holiday'} onClose={() => setEdit(null)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setEdit(null)}>Cancel</button>
            <button className="btn btn-primary" disabled={!edit.holidayDate || !edit.name.trim()} onClick={() => void save()}>Save</button>
          </>
        }>
          <div className="form-grid">
            <TextField label="Date" type="date" required value={edit.holidayDate} onChange={(v) => setEdit({ ...edit, holidayDate: v })} />
            <SelectField label="Type" placeholder={null} value={edit.holidayType} onChange={(v) => setEdit({ ...edit, holidayType: v })}
              options={['PUBLIC', 'BANK', 'MERCANTILE', 'SPECIAL'].map((v) => ({ value: v, label: v.charAt(0) + v.slice(1).toLowerCase() }))} />
            <TextField className="span-2" label="Name (English)" required value={edit.name} onChange={(v) => setEdit({ ...edit, name: v })} />
            <TextField label="Name (Sinhala)" value={edit.nameSi} onChange={(v) => setEdit({ ...edit, nameSi: v })} />
            <TextField label="Name (Tamil)" value={edit.nameTa} onChange={(v) => setEdit({ ...edit, nameTa: v })} />
            <TextArea className="span-2" label="Remarks (e.g. Gazette reference)" value={edit.remarks} onChange={(v) => setEdit({ ...edit, remarks: v })} />
          </div>
        </Modal>
      )}
      {del && <ConfirmDialog title="Remove holiday" danger message={`Remove ${del.name} (${fmtDate(del.holidayDate)})? Days already processed are not re-evaluated automatically.`}
        confirmLabel="Remove" onCancel={() => setDel(null)} onConfirm={() => void remove()} />}
    </Card>
  );
}
