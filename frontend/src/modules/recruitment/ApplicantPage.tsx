import { useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Download, Printer, Upload, UserPlus } from 'lucide-react';
import { api, download, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, Checkbox, DL, Loading, Modal, PageHeader, SelectField, StatusBadge, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate, fmtDateTime } from '@/components/DataTable';
import { MasterDataSelect, OrgUnitSelect, DesignationSelect } from '@/components/pickers';
import { ApplicantForm } from './ApplicantForm';
import type { ApplicantDetail, Exam, Interview, LetterLog, LetterTemplate } from './types';
import { printLetter } from './types';

const MANUAL: Record<string, string[]> = {
  RECEIVED: ['SHORTLISTED', 'REJECTED', 'WITHDRAWN'],
  SHORTLISTED: ['RECEIVED', 'REJECTED', 'WITHDRAWN'],
  REJECTED: ['RECEIVED'],
  SELECTED: ['WITHDRAWN'],
};

export default function ApplicantPage() {
  const { id } = useParams();
  const { can } = useAuth();
  const toast = useToast();
  const manage = can('REC_MANAGE');
  const { data, error, reload } = useApi<ApplicantDetail>(`/recruitment/applicants/${id}`);
  const letters = useApi<LetterLog[]>(`/recruitment/applicants/${id}/letters`);
  const [editing, setEditing] = useState(false);
  const [statusTo, setStatusTo] = useState<string | null>(null);
  const [remarks, setRemarks] = useState('');
  const [appointing, setAppointing] = useState(false);
  const [lettering, setLettering] = useState(false);
  const [doc, setDoc] = useState<{ type: string; title: string } | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  if (error) return <Alert kind="danger">{error}</Alert>;
  if (!data) return <Loading />;
  const a = data.applicant;

  const changeStatus = async () => {
    try {
      await api.post(`/recruitment/applicants/${a.id}/status`, { status: statusTo, remarks });
      setStatusTo(null);
      setRemarks('');
      toast('Status updated', 'success');
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  const upload = async () => {
    const file = fileRef.current?.files?.[0];
    if (!doc || !file) return;
    const fd = new FormData();
    fd.append('file', file);
    fd.append('documentType', doc.type);
    fd.append('title', doc.title);
    try {
      await api.post(`/recruitment/applicants/${a.id}/documents`, fd);
      setDoc(null);
      toast('Document uploaded', 'success');
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  return (
    <>
      <PageHeader eyebrow={`${data.vacancy.referenceNo} · ${data.vacancy.title}`}
        title={a.fullName} subtitle={<><StatusBadge status={a.status} /> Application {a.applicationNo} · received {fmtDate(a.receivedDate)} ·{' '}
          <Link to={`/recruitment/vacancies/${data.vacancy.id}?tab=applicants`}>back to vacancy</Link></>}
        actions={manage && (
          <div className="row">
            {(MANUAL[a.status] ?? []).map((s) => (
              <button key={s} className="btn btn-outline" onClick={() => setStatusTo(s)}>{s === 'SHORTLISTED' ? 'Shortlist' : s.charAt(0) + s.slice(1).toLowerCase()}</button>
            ))}
            {a.status !== 'APPOINTED' && <button className="btn btn-outline" onClick={() => setEditing(true)}>Edit</button>}
            <button className="btn btn-outline" onClick={() => setLettering(true)}><Printer /> Letter</button>
            {a.status === 'SELECTED' && <button className="btn btn-gold" onClick={() => setAppointing(true)}><UserPlus /> Appoint</button>}
          </div>
        )} />
      {a.employeeId && <Alert kind="success">Appointed — <Link to={`/employees/${a.employeeId}`}>open employee record</Link>.</Alert>}
      <div className="grid grid-2">
        <Card title="Applicant">
          <DL items={[
            ['NIC', a.nic], ['Date of birth', fmtDate(a.dateOfBirth)], ['Gender', a.gender], ['E-mail', a.email], ['Mobile', a.mobile],
            ['Address', a.address], ['Qualifications', a.qualifications && <span style={{ whiteSpace: 'pre-wrap' }}>{a.qualifications}</span>],
            ['Source', a.source === 'HR_ENTERED' ? 'Entered by HR' : a.source], ['Remarks', a.remarks],
          ]} />
        </Card>
        <Card title="Assessment">
          <DL items={[
            ['Examinations', data.score.examPercent != null ? `${data.score.examPercent}%` : '—'],
            ['Interview', data.score.interviewPercent != null ? `${data.score.interviewPercent}%` : '—'],
            ['Ranking score', <strong key="t">{data.score.total}</strong>],
          ]} />
          <DataTable rows={data.examResults} rowKey={(r) => r.examId} columns={[
            { key: 'examTitle', header: 'Examination' },
            { key: 'examDate', header: 'Date', render: (r) => fmtDate(r.examDate) },
            { key: 'marks', header: 'Marks', render: (r) => (r.absent ? 'Absent' : r.marks != null ? `${r.marks} / ${r.maxMarks}` : '—') },
          ]} />
          <DataTable<Interview> rows={data.interviews} columns={[
            { key: 'interviewDate', header: 'Interview', render: (i) => fmtDate(i.interviewDate) },
            { key: 'panel', header: 'Panel' },
            { key: 'marks', header: 'Marks', render: (i) => (i.marks != null ? `${i.marks} / ${i.maxMarks}` : '—') },
            { key: 'status', header: 'Status', render: (i) => <StatusBadge status={i.status} /> },
          ]} />
        </Card>
        <Card title="Documents" actions={manage && <button className="btn btn-outline btn-sm" onClick={() => setDoc({ type: '', title: '' })}><Upload /> Upload</button>}>
          <DataTable rows={data.documents} columns={[
            { key: 'documentType', header: 'Type' },
            { key: 'title', header: 'Title' },
            { key: 'act', header: '', render: (d) => <button className="btn btn-ghost btn-sm"
              onClick={() => void download(`/recruitment/applicants/${a.id}/documents/${d.id}/file`)}><Download /> {d.fileName}</button> },
          ]} />
        </Card>
        <Card title="Status history">
          <ul className="timeline">
            {data.history.map((h) => (
              <li key={h.id}>
                <div className="row between"><strong>{h.fromStatus ? `${h.fromStatus} → ${h.toStatus}` : h.toStatus}</strong><span className="when">{fmtDateTime(h.changedAt)}</span></div>
                <div className="muted">{h.changedBy}{h.remarks ? ` · ${h.remarks}` : ''}</div>
              </li>
            ))}
          </ul>
        </Card>
      </div>
      <Card pad={false} title="Correspondence" className="mt">
        <DataTable<LetterLog> rows={letters.data} loading={letters.loading} columns={[
          { key: 'generatedAt', header: 'Generated', render: (l) => fmtDateTime(l.generatedAt) },
          { key: 'subject', header: 'Subject' },
          { key: 'channel', header: 'Channel' },
          { key: 'recipient', header: 'Recipient' },
          { key: 'status', header: 'Status', render: (l) => <StatusBadge status={l.deliveryStatus ?? l.status} /> },
          { key: 'act', header: '', render: (l) => <button className="btn btn-ghost btn-sm" onClick={() => void api.get<{ html: string }>(`/recruitment/letters/${l.id}`)
            .then((r) => printLetter(r.data.html, l.subject)).catch((e) => toast(errorMessage(e), 'error'))}><Printer /> Print</button> },
        ]} />
      </Card>

      {editing && <ApplicantForm vacancyId={a.vacancyId} applicant={a} onClose={() => setEditing(false)} onSaved={() => { setEditing(false); toast('Saved', 'success'); void reload(); }} />}
      {statusTo && (
        <Modal title={`Change status to ${statusTo.replace(/_/g, ' ').toLowerCase()}`} onClose={() => setStatusTo(null)} footer={
          <><button className="btn btn-outline" onClick={() => setStatusTo(null)}>Cancel</button><button className="btn btn-primary" onClick={() => void changeStatus()}>Confirm</button></>
        }>
          <TextArea label={statusTo === 'REJECTED' || statusTo === 'WITHDRAWN' ? 'Reason (required)' : 'Remarks'} value={remarks} onChange={setRemarks} />
        </Modal>
      )}
      {doc && (
        <Modal title="Upload document" onClose={() => setDoc(null)} footer={
          <><button className="btn btn-outline" onClick={() => setDoc(null)}>Cancel</button><button className="btn btn-primary" onClick={() => void upload()}>Upload</button></>
        }>
          <div className="form-grid">
            <TextField label="Document type" value={doc.type} onChange={(v) => setDoc({ ...doc, type: v })} required placeholder="e.g. Certificate, CV, NIC copy" />
            <TextField label="Title" value={doc.title} onChange={(v) => setDoc({ ...doc, title: v })} required />
            <input type="file" ref={fileRef} className="span-all" aria-label="File" />
          </div>
        </Modal>
      )}
      {appointing && <AppointModal detail={data} onClose={() => setAppointing(false)} onDone={() => { setAppointing(false); void reload(); }} />}
      {lettering && <LetterModal detail={data} onClose={() => setLettering(false)} onDone={() => { setLettering(false); void letters.reload(); }} />}
    </>
  );
}

function AppointModal({ detail, onClose, onDone }: { detail: ApplicantDetail; onClose: () => void; onDone: () => void }) {
  const toast = useToast();
  const v = detail.vacancy;
  const [f, setF] = useState<Record<string, string>>({
    employeeNo: '', appointmentDate: new Date().toISOString().slice(0, 10), orgUnitCode: v.orgUnitCode, designationId: String(v.designationId),
    status: 'PROBATION', employmentType: 'PERMANENT', employeeCategory: '', serviceCategory: '',
  });
  const [err, setErr] = useState<string | null>(null);
  const set = (k: string) => (val: string) => setF((x) => ({ ...x, [k]: val }));
  const save = async () => {
    setErr(null);
    try {
      const res = await api.post<{ employeeNo: string }>(`/recruitment/applicants/${detail.applicant.id}/appoint`, {
        ...f, designationId: Number(f.designationId), serviceCategory: f.serviceCategory || null,
      });
      toast(`Employee ${res.data.employeeNo} created`, 'success');
      onDone();
    } catch (e) {
      setErr(errorMessage(e));
    }
  };
  return (
    <Modal title="Appoint selected applicant" onClose={onClose} wide footer={
      <><button className="btn btn-outline" onClick={onClose}>Cancel</button><button className="btn btn-gold" onClick={() => void save()}>Create employee record</button></>
    }>
      <div className="stack">
        <Alert kind="info">Personal details are copied from the application — no re-entry is needed (FR-REC-008).</Alert>
        {err && <Alert kind="danger">{err}</Alert>}
        <div className="form-grid">
          <TextField label="Employee number" value={f.employeeNo} onChange={set('employeeNo')} required />
          <TextField label="Appointment date" type="date" value={f.appointmentDate} onChange={set('appointmentDate')} required />
          <OrgUnitSelect value={f.orgUnitCode} onChange={set('orgUnitCode')} required />
          <DesignationSelect value={f.designationId} onChange={set('designationId')} required />
          <MasterDataSelect category="EMPLOYEE_STATUS" label="Status" value={f.status} onChange={set('status')} required />
          <MasterDataSelect category="EMPLOYMENT_TYPE" label="Employment type" value={f.employmentType} onChange={set('employmentType')} required />
          <MasterDataSelect category="EMPLOYEE_CATEGORY" label="Employee category" value={f.employeeCategory} onChange={set('employeeCategory')} required />
          <MasterDataSelect category="SERVICE_CATEGORY" label="Service category" value={f.serviceCategory} onChange={set('serviceCategory')} />
        </div>
      </div>
    </Modal>
  );
}

function LetterModal({ detail, onClose, onDone }: { detail: ApplicantDetail; onClose: () => void; onDone: () => void }) {
  const toast = useToast();
  const { can } = useAuth();
  const templates = useApi<LetterTemplate[]>('/recruitment/letter-templates');
  const exams = useApi<Exam[]>(`/recruitment/vacancies/${detail.vacancy.id}/exams`);
  const [f, setF] = useState({ templateId: '', examId: '', interviewId: '', appointmentDate: '', sendEmail: false });
  const [preview, setPreview] = useState<{ subject: string; html: string; emailAvailable: boolean } | null>(null);
  const body = () => ({
    templateId: Number(f.templateId), examId: f.examId ? Number(f.examId) : null, interviewId: f.interviewId ? Number(f.interviewId) : null,
    appointmentDate: f.appointmentDate || null, sendEmail: f.sendEmail,
  });
  const doPreview = async () => {
    try {
      const res = await api.post<{ subject: string; html: string; emailAvailable: boolean }>(`/recruitment/applicants/${detail.applicant.id}/letters/preview`, body());
      setPreview(res.data);
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };
  const issue = async () => {
    try {
      const res = await api.post<LetterLog>(`/recruitment/applicants/${detail.applicant.id}/letters`, body());
      toast(`Letter recorded (${res.data.channel}: ${res.data.status.toLowerCase()})`, 'success');
      if (preview) printLetter(preview.html, preview.subject);
      onDone();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };
  return (
    <Modal title="Generate letter" onClose={onClose} wide footer={
      <>
        <button className="btn btn-outline" onClick={onClose}>Close</button>
        <button className="btn btn-outline" disabled={!f.templateId} onClick={() => void doPreview()}>Preview</button>
        {can('REC_MANAGE') && <button className="btn btn-gold" disabled={!preview} onClick={() => void issue()}>Issue {f.sendEmail ? '& e-mail' : '& print'}</button>}
      </>
    }>
      <div className="stack">
        <div className="form-grid">
          <SelectField label="Template" value={f.templateId} onChange={(v) => { setF({ ...f, templateId: v }); setPreview(null); }} required
            options={(templates.data ?? []).filter((t) => t.active).map((t) => ({ value: String(t.id), label: `${t.name} (${t.language})` }))} />
          <SelectField label="Examination" value={f.examId} onChange={(v) => { setF({ ...f, examId: v }); setPreview(null); }} placeholder="None"
            options={(exams.data ?? []).map((e) => ({ value: String(e.id), label: `${e.title} — ${fmtDate(e.examDate)}` }))} />
          <SelectField label="Interview" value={f.interviewId} onChange={(v) => { setF({ ...f, interviewId: v }); setPreview(null); }} placeholder="Latest scheduled"
            options={detail.interviews.map((i) => ({ value: String(i.id), label: `${fmtDate(i.interviewDate)} — ${i.status}` }))} />
          <TextField label="Appointment date (if not yet appointed)" type="date" value={f.appointmentDate} onChange={(v) => { setF({ ...f, appointmentDate: v }); setPreview(null); }} />
          <Checkbox label={`Send by e-mail to ${detail.applicant.email ?? '(no e-mail on file)'}`} checked={f.sendEmail} onChange={(v) => setF({ ...f, sendEmail: v })} />
        </div>
        {preview && (
          <>
            {f.sendEmail && !preview.emailAvailable && <Alert kind="warning">E-mail is not available; the letter will be recorded for printing and posting.</Alert>}
            <div className="card card-pad" style={{ background: '#fff' }} dangerouslySetInnerHTML={{ __html: preview.html }} />
          </>
        )}
      </div>
    </Modal>
  );
}
