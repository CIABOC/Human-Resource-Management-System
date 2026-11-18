import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Plus, Printer } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, Checkbox, Empty, Modal, SelectField, StatusBadge, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate, fmtDateTime } from '@/components/DataTable';
import { WorkflowTrail } from '@/components/WorkflowTrail';
import { ApplicantForm } from './ApplicantForm';
import {
  APPLICANT_STATUSES, ApplicantListItem, Exam, ExamResultRow, InterviewRow, LetterLog, LetterTemplate, printLetter, Selection, Vacancy,
} from './types';

const active = (v: Vacancy) => v.status !== 'FILLED' && v.status !== 'CANCELLED';

/* ------------------------------------------------------------------ applicants & pipeline */

export function ApplicantsTab({ vacancy, onChanged }: { vacancy: Vacancy; onChanged: () => void }) {
  const nav = useNavigate();
  const { can } = useAuth();
  const toast = useToast();
  const [status, setStatus] = useState('');
  const [q, setQ] = useState('');
  const [adding, setAdding] = useState(false);
  const { data, loading, error, reload } = useApi<ApplicantListItem[]>('/recruitment/applicants', { vacancyId: vacancy.id, status, q });
  const canAdd = can('REC_MANAGE') && (vacancy.status === 'OPEN' || vacancy.status === 'CLOSED');

  return (
    <Card pad={false} title={
      <div className="filters" style={{ margin: 0 }}>
        <SelectField label="Status" value={status} onChange={setStatus} placeholder="All" options={APPLICANT_STATUSES.map((s) => ({ value: s, label: s.replace(/_/g, ' ') }))} />
        <TextField label="Search" value={q} onChange={setQ} placeholder="Name, NIC or application no." />
      </div>
    } actions={canAdd && <button className="btn btn-primary" onClick={() => setAdding(true)}><Plus /> Record application</button>}>
      {error && <Alert kind="danger">{error}</Alert>}
      <div className="row" style={{ padding: '10px 16px' }}>
        {APPLICANT_STATUSES.filter((s) => vacancy.applicantsByStatus[s]).map((s) => (
          <span key={s} className="row" style={{ gap: 4 }}><StatusBadge status={s} /> {vacancy.applicantsByStatus[s]}</span>
        ))}
      </div>
      <DataTable<ApplicantListItem> rows={data} loading={loading} onRowClick={(a) => nav(`/recruitment/applicants/${a.id}`)}
        columns={[
          { key: 'applicationNo', header: 'Application' },
          { key: 'fullName', header: 'Name' },
          { key: 'nic', header: 'NIC' },
          { key: 'mobile', header: 'Mobile' },
          { key: 'receivedDate', header: 'Received', render: (a) => fmtDate(a.receivedDate) },
          { key: 'status', header: 'Status', render: (a) => <StatusBadge status={a.status} /> },
        ]} />
      {adding && <ApplicantForm vacancyId={vacancy.id} onClose={() => setAdding(false)} onSaved={(a) => {
        setAdding(false);
        toast(`Application ${a.applicationNo} recorded`, 'success');
        void reload();
        onChanged();
      }} />}
    </Card>
  );
}

/* ------------------------------------------------------------------ examinations & results */

export function ExamsTab({ vacancy }: { vacancy: Vacancy }) {
  const { can } = useAuth();
  const toast = useToast();
  const manage = can('REC_MANAGE');
  const { data, loading, reload } = useApi<Exam[]>(`/recruitment/vacancies/${vacancy.id}/exams`);
  const [edit, setEdit] = useState<Partial<Exam> | null>(null);
  const [selected, setSelected] = useState<Exam | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const save = async () => {
    if (!edit) return;
    setErr(null);
    try {
      const body = { ...edit, vacancyId: vacancy.id, startTime: edit.startTime || null, passMarks: edit.passMarks === undefined || String(edit.passMarks) === '' ? null : edit.passMarks };
      if (edit.id) await api.put(`/recruitment/exams/${edit.id}`, body);
      else await api.post('/recruitment/exams', body);
      setEdit(null);
      toast('Examination saved', 'success');
      void reload();
    } catch (e) {
      setErr(errorMessage(e));
    }
  };

  return (
    <div className="stack">
      <Card pad={false} title="Examinations" actions={manage && active(vacancy) && (
        <button className="btn btn-primary" onClick={() => setEdit({ examType: 'WRITTEN', maxMarks: 100 })}><Plus /> Schedule examination</button>
      )}>
        <DataTable<Exam> rows={data} loading={loading} onRowClick={setSelected}
          columns={[
            { key: 'title', header: 'Examination' },
            { key: 'examType', header: 'Type' },
            { key: 'examDate', header: 'Date', render: (e) => `${fmtDate(e.examDate)}${e.startTime ? ' ' + e.startTime.slice(0, 5) : ''}` },
            { key: 'venue', header: 'Venue' },
            { key: 'maxMarks', header: 'Max', align: 'right' },
            { key: 'passMarks', header: 'Pass', align: 'right' },
            { key: 'status', header: 'Status', render: (e) => <StatusBadge status={e.status} /> },
            { key: 'act', header: '', render: (e) => manage && <button className="btn btn-ghost btn-sm" onClick={(ev) => { ev.stopPropagation(); setEdit(e); }}>Edit</button> },
          ]} />
      </Card>
      {selected && <ExamResults key={selected.id} exam={selected} manage={manage} />}
      {edit && (
        <Modal title={edit.id ? 'Edit examination' : 'Schedule examination'} onClose={() => setEdit(null)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setEdit(null)}>Cancel</button>
            <button className="btn btn-primary" onClick={() => void save()}>Save</button>
          </>
        }>
          {err && <Alert kind="danger">{err}</Alert>}
          <div className="form-grid">
            <TextField label="Title" value={edit.title} onChange={(v) => setEdit({ ...edit, title: v })} required className="span-all" />
            <SelectField label="Type" value={edit.examType} onChange={(v) => setEdit({ ...edit, examType: v as Exam['examType'] })} placeholder={null}
              options={[{ value: 'WRITTEN', label: 'Written' }, { value: 'PRACTICAL', label: 'Practical' }]} />
            <TextField label="Date" type="date" value={edit.examDate} onChange={(v) => setEdit({ ...edit, examDate: v })} required />
            <TextField label="Start time" type="time" value={edit.startTime?.slice(0, 5)} onChange={(v) => setEdit({ ...edit, startTime: v })} />
            <TextField label="Venue" value={edit.venue} onChange={(v) => setEdit({ ...edit, venue: v })} />
            <TextField label="Maximum marks" type="number" value={edit.maxMarks} onChange={(v) => setEdit({ ...edit, maxMarks: Number(v) })} required />
            <TextField label="Pass marks" type="number" value={edit.passMarks} onChange={(v) => setEdit({ ...edit, passMarks: v === '' ? null : Number(v) })} />
            {edit.id && <SelectField label="Status" value={edit.status} onChange={(v) => setEdit({ ...edit, status: v })} placeholder={null}
              options={['SCHEDULED', 'COMPLETED', 'CANCELLED'].map((s) => ({ value: s, label: s }))} />}
          </div>
        </Modal>
      )}
    </div>
  );
}

function ExamResults({ exam, manage }: { exam: Exam; manage: boolean }) {
  const toast = useToast();
  const { data, loading, setData } = useApi<ExamResultRow[]>(`/recruitment/exams/${exam.id}/results`);
  const [rows, setRows] = useState<ExamResultRow[]>([]);
  useEffect(() => setRows(data ?? []), [data]);
  const upd = (i: number, p: Partial<ExamResultRow>) => setRows((rs) => rs.map((r, j) => (j === i ? { ...r, ...p } : r)));

  const save = async () => {
    try {
      const res = await api.put<ExamResultRow[]>(`/recruitment/exams/${exam.id}/results`,
        rows.map((r) => ({ applicantId: r.applicantId, marks: r.absent ? null : r.marks, absent: r.absent, remarks: r.remarks })));
      setData(res.data);
      toast('Results saved', 'success');
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  return (
    <Card pad={false} title={`Results — ${exam.title} (max ${exam.maxMarks})`} actions={manage && exam.status !== 'CANCELLED' &&
      <button className="btn btn-gold" onClick={() => void save()}>Save results</button>}>
      <DataTable<ExamResultRow> rows={rows} loading={loading} rowKey={(r) => r.applicantId}
        columns={[
          { key: 'applicationNo', header: 'Application' },
          { key: 'name', header: 'Applicant' },
          { key: 'marks', header: 'Marks', render: (r) => manage ? (
            <input className="input" style={{ width: 100 }} type="number" min={0} max={exam.maxMarks} disabled={r.absent} value={r.marks ?? ''}
              onChange={(e) => upd(rows.indexOf(r), { marks: e.target.value === '' ? null : Number(e.target.value) })} aria-label={`Marks for ${r.applicationNo}`} />
          ) : r.marks ?? '—' },
          { key: 'absent', header: 'Absent', render: (r) => manage ? <Checkbox label="" checked={r.absent} onChange={(v) => upd(rows.indexOf(r), { absent: v })} /> : r.absent ? 'Yes' : '' },
          { key: 'remarks', header: 'Remarks', render: (r) => manage ? (
            <input className="input" value={r.remarks ?? ''} onChange={(e) => upd(rows.indexOf(r), { remarks: e.target.value })} aria-label="Remarks" />
          ) : r.remarks },
          { key: 'passed', header: 'Result', render: (r) => r.passed === null || r.passed === undefined ? '—' : r.passed ? <span className="badge badge-success">Pass</span> : <span className="badge badge-danger">Fail</span> },
        ]} />
      {!loading && rows.length === 0 && <Empty text="No shortlisted applicants yet." />}
    </Card>
  );
}

/* ------------------------------------------------------------------ interviews */

export function InterviewsTab({ vacancy }: { vacancy: Vacancy }) {
  const { can } = useAuth();
  const toast = useToast();
  const manage = can('REC_MANAGE');
  const { data, loading, reload } = useApi<InterviewRow[]>(`/recruitment/vacancies/${vacancy.id}/interviews`);
  const shortlisted = useApi<ApplicantListItem[]>(manage ? '/recruitment/applicants' : null, { vacancyId: vacancy.id, status: 'SHORTLISTED' });
  const [sched, setSched] = useState<Record<string, string> | null>(null);
  const [result, setResult] = useState<{ row: InterviewRow; status: string; marks: string; remarks: string } | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const schedule = async () => {
    if (!sched) return;
    setErr(null);
    try {
      await api.post('/recruitment/interviews', {
        applicantId: Number(sched.applicantId), interviewDate: sched.interviewDate, interviewTime: sched.interviewTime || null,
        venue: sched.venue, panel: sched.panel, maxMarks: Number(sched.maxMarks),
      });
      setSched(null);
      toast('Interview scheduled', 'success');
      void reload();
    } catch (e) {
      setErr(errorMessage(e));
    }
  };

  const record = async () => {
    if (!result) return;
    setErr(null);
    try {
      await api.put(`/recruitment/interviews/${result.row.id}/result`, {
        status: result.status, marks: result.marks === '' ? null : Number(result.marks), remarks: result.remarks,
      });
      setResult(null);
      toast('Interview recorded', 'success');
      void reload();
    } catch (e) {
      setErr(errorMessage(e));
    }
  };

  return (
    <Card pad={false} title="Interviews" actions={manage && active(vacancy) && (
      <button className="btn btn-primary" onClick={() => setSched({ maxMarks: '50' })}><Plus /> Schedule interview</button>
    )}>
      <DataTable<InterviewRow> rows={data} loading={loading}
        columns={[
          { key: 'applicationNo', header: 'Application' },
          { key: 'applicantName', header: 'Applicant' },
          { key: 'interviewDate', header: 'Date', render: (i) => `${fmtDate(i.interviewDate)}${i.interviewTime ? ' ' + i.interviewTime.slice(0, 5) : ''}` },
          { key: 'venue', header: 'Venue' },
          { key: 'panel', header: 'Panel' },
          { key: 'marks', header: 'Marks', render: (i) => (i.marks !== null && i.marks !== undefined ? `${i.marks} / ${i.maxMarks}` : '—') },
          { key: 'status', header: 'Status', render: (i) => <StatusBadge status={i.status} /> },
          { key: 'act', header: '', render: (i) => manage && <button className="btn btn-outline btn-sm"
            onClick={() => setResult({ row: i, status: 'COMPLETED', marks: i.marks == null ? '' : String(i.marks), remarks: i.remarks ?? '' })}>Record</button> },
        ]} />
      {sched && (
        <Modal title="Schedule interview" onClose={() => setSched(null)} footer={
          <><button className="btn btn-outline" onClick={() => setSched(null)}>Cancel</button><button className="btn btn-primary" onClick={() => void schedule()}>Schedule</button></>
        }>
          {err && <Alert kind="danger">{err}</Alert>}
          <div className="form-grid">
            <SelectField label="Applicant" value={sched.applicantId} onChange={(v) => setSched({ ...sched, applicantId: v })} required className="span-all"
              options={(shortlisted.data ?? []).map((a) => ({ value: String(a.id), label: `${a.applicationNo} — ${a.nameWithInitials}` }))} />
            <TextField label="Date" type="date" value={sched.interviewDate} onChange={(v) => setSched({ ...sched, interviewDate: v })} required />
            <TextField label="Time" type="time" value={sched.interviewTime} onChange={(v) => setSched({ ...sched, interviewTime: v })} />
            <TextField label="Venue" value={sched.venue} onChange={(v) => setSched({ ...sched, venue: v })} />
            <TextField label="Maximum marks" type="number" value={sched.maxMarks} onChange={(v) => setSched({ ...sched, maxMarks: v })} required />
            <TextArea label="Panel members" value={sched.panel} onChange={(v) => setSched({ ...sched, panel: v })} required className="span-all" rows={2} />
          </div>
        </Modal>
      )}
      {result && (
        <Modal title={`Interview — ${result.row.applicationNo}`} onClose={() => setResult(null)} footer={
          <><button className="btn btn-outline" onClick={() => setResult(null)}>Cancel</button><button className="btn btn-primary" onClick={() => void record()}>Save</button></>
        }>
          {err && <Alert kind="danger">{err}</Alert>}
          <div className="form-grid">
            <SelectField label="Outcome" value={result.status} onChange={(v) => setResult({ ...result, status: v })} placeholder={null}
              options={['COMPLETED', 'ABSENT', 'CANCELLED'].map((s) => ({ value: s, label: s }))} />
            <TextField label={`Marks (max ${result.row.maxMarks})`} type="number" value={result.marks} onChange={(v) => setResult({ ...result, marks: v })}
              disabled={result.status !== 'COMPLETED'} />
            <TextArea label="Panel remarks" value={result.remarks} onChange={(v) => setResult({ ...result, remarks: v })} className="span-all" />
          </div>
        </Modal>
      )}
    </Card>
  );
}

/* ------------------------------------------------------------------ selection (FR-REC-007) */

export function SelectionTab({ vacancy, onChanged }: { vacancy: Vacancy; onChanged: () => void }) {
  const { can } = useAuth();
  const toast = useToast();
  const manage = can('REC_MANAGE');
  const { data, loading, reload } = useApi<Selection[]>(`/recruitment/vacancies/${vacancy.id}/selections`);
  const current = data?.[0] ?? null;
  const [draft, setDraft] = useState<Selection | null>(null);
  useEffect(() => setDraft(current), [current]);
  const editable = manage && !!draft && (draft.status === 'DRAFT' || draft.status === 'RETURNED');

  const call = async (fn: () => Promise<unknown>, msg: string) => {
    try {
      await fn();
      toast(msg, 'success');
      void reload();
      onChanged();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };
  const save = () => api.put(`/recruitment/selections/${draft!.id}`, {
    remarks: draft!.remarks, items: draft!.items.map((i) => ({ applicantId: i.applicantId, recommended: i.recommended, remarks: i.remarks })),
  });
  const upd = (applicantId: number, p: Partial<Selection['items'][number]>) =>
    setDraft((d) => d && { ...d, items: d.items.map((i) => (i.applicantId === applicantId ? { ...i, ...p } : i)) });

  return (
    <div className="stack">
      <Alert kind="info">
        The ranking is computed from recorded examination and interview marks (weights {vacancy.examWeight}% / {vacancy.interviewWeight}%).
        It supports, and never replaces, the selection decision: the panel recommends and Management approves.
      </Alert>
      <Card pad={false} title={draft ? <span>Selection list #{draft.id} <StatusBadge status={draft.status} /></span> : 'Selection list'} actions={manage && active(vacancy) && (
        <>
          {(!draft || draft.status === 'DRAFT' || draft.status === 'RETURNED' || draft.status === 'REJECTED' || draft.status === 'APPROVED') && (
            <button className="btn btn-outline" onClick={() => void call(() => api.post(`/recruitment/vacancies/${vacancy.id}/selections`), 'Ranking refreshed')}>
              {draft && (draft.status === 'DRAFT' || draft.status === 'RETURNED') ? 'Refresh ranking' : 'Prepare ranked list'}
            </button>
          )}
          {editable && <button className="btn btn-primary" onClick={() => void call(save, 'Decisions saved')}>Save decisions</button>}
          {editable && <button className="btn btn-gold" onClick={() => void call(async () => { await save(); await api.post(`/recruitment/selections/${draft!.id}/submit`); }, 'Submitted for approval')}>Submit for approval</button>}
        </>
      )}>
        {!loading && !draft && <Empty text="No selection list yet. Shortlist applicants and record marks, then prepare the ranked list." />}
        {draft && (
          <>
            <DataTable rows={draft.items} rowKey={(i) => i.applicantId}
              columns={[
                { key: 'rankNo', header: 'Rank', align: 'right' },
                { key: 'applicationNo', header: 'Application', render: (i) => <Link to={`/recruitment/applicants/${i.applicantId}`}>{i.applicationNo}</Link> },
                { key: 'applicantName', header: 'Applicant' },
                { key: 'examScore', header: 'Exam %', align: 'right' },
                { key: 'interviewScore', header: 'Interview %', align: 'right' },
                { key: 'totalScore', header: 'Score', align: 'right', render: (i) => <strong>{i.totalScore}</strong> },
                { key: 'recommended', header: 'Recommended', render: (i) => editable
                  ? <Checkbox label="" checked={i.recommended} onChange={(v) => upd(i.applicantId, { recommended: v })} />
                  : i.recommended ? <span className="badge badge-gold">Recommended</span> : '' },
                { key: 'remarks', header: 'Justification / remarks', render: (i) => editable
                  ? <input className="input" value={i.remarks ?? ''} onChange={(e) => upd(i.applicantId, { remarks: e.target.value })} aria-label="Remarks" />
                  : i.remarks },
                { key: 'applicantStatus', header: 'Status', render: (i) => <StatusBadge status={i.applicantStatus} /> },
              ]} />
            <div className="card-body">
              {editable
                ? <TextArea label="Panel remarks" value={draft.remarks} onChange={(v) => setDraft({ ...draft, remarks: v })} />
                : draft.remarks && <p><strong>Panel remarks:</strong> {draft.remarks}</p>}
              <div className="faint">{draft.numPosts} post(s); {draft.alreadyAppointed} already appointed.
                {draft.submittedAt && ` Submitted ${fmtDateTime(draft.submittedAt)}.`}</div>
            </div>
          </>
        )}
      </Card>
      {draft?.workflowInstanceId && <Card title="Approval trail"><WorkflowTrail entityType="SelectionList" entityId={draft.id} /></Card>}
    </div>
  );
}

/* ------------------------------------------------------------------ correspondence (bulk) */

export function LettersTab({ vacancy }: { vacancy: Vacancy }) {
  const { can } = useAuth();
  const toast = useToast();
  const manage = can('REC_MANAGE');
  const templates = useApi<LetterTemplate[]>('/recruitment/letter-templates');
  const exams = useApi<Exam[]>(`/recruitment/vacancies/${vacancy.id}/exams`);
  const { data, loading, reload } = useApi<LetterLog[]>(`/recruitment/vacancies/${vacancy.id}/letters`);
  const [f, setF] = useState({ templateId: '', applicantStatus: 'SHORTLISTED', examId: '', sendEmail: false });

  const generate = async () => {
    try {
      const res = await api.post<{ generated: number; queued: number }>(`/recruitment/vacancies/${vacancy.id}/letters`, {
        templateId: Number(f.templateId), applicantStatus: f.applicantStatus, examId: f.examId ? Number(f.examId) : null, sendEmail: f.sendEmail,
      });
      toast(`${res.data.generated} letter(s) generated, ${res.data.queued} queued for e-mail`, 'success');
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  const print = async (l: LetterLog) => {
    try {
      const res = await api.get<{ html: string }>(`/recruitment/letters/${l.id}`);
      printLetter(res.data.html, l.subject);
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  return (
    <div className="stack">
      {manage && (
        <Card title="Generate letters for a group of applicants">
          <div className="form-grid cols-3">
            <SelectField label="Template" value={f.templateId} onChange={(v) => setF({ ...f, templateId: v })} required
              options={(templates.data ?? []).filter((t) => t.active).map((t) => ({ value: String(t.id), label: `${t.name} (${t.language})` }))} />
            <SelectField label="Applicants with status" value={f.applicantStatus} onChange={(v) => setF({ ...f, applicantStatus: v })} placeholder={null}
              options={APPLICANT_STATUSES.map((s) => ({ value: s, label: s.replace(/_/g, ' ') }))} />
            <SelectField label="Examination (for call letters)" value={f.examId} onChange={(v) => setF({ ...f, examId: v })} placeholder="None"
              options={(exams.data ?? []).map((e) => ({ value: String(e.id), label: `${e.title} — ${fmtDate(e.examDate)}` }))} />
          </div>
          <div className="row between" style={{ marginTop: 12 }}>
            <Checkbox label="Also send by e-mail (queued; printed copy otherwise)" checked={f.sendEmail} onChange={(v) => setF({ ...f, sendEmail: v })} />
            <button className="btn btn-gold" disabled={!f.templateId} onClick={() => void generate()}>Generate</button>
          </div>
        </Card>
      )}
      <Card pad={false} title="Correspondence log">
        <DataTable<LetterLog> rows={data} loading={loading}
          columns={[
            { key: 'generatedAt', header: 'Generated', render: (l) => fmtDateTime(l.generatedAt) },
            { key: 'applicationNo', header: 'Application' },
            { key: 'applicantName', header: 'Applicant' },
            { key: 'category', header: 'Letter' },
            { key: 'language', header: 'Lang.' },
            { key: 'channel', header: 'Channel' },
            { key: 'status', header: 'Status', render: (l) => <StatusBadge status={l.deliveryStatus ?? l.status} /> },
            { key: 'act', header: '', render: (l) => <button className="btn btn-ghost btn-sm" onClick={() => void print(l)}><Printer /> Print</button> },
          ]} />
      </Card>
    </div>
  );
}
