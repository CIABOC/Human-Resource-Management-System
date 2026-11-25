import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Download, Plus, Trash2, Upload } from 'lucide-react';
import { api, download, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Card, ConfirmDialog, DL, Modal, SelectField, StatusBadge, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate, fmtDateTime } from '@/components/DataTable';
import { MasterDataSelect, useMasterDataLabels } from '@/components/pickers';
import type {
  AssignmentView, EmergencyContact, EmployeeDocument, EmployeeView, PreviousEmployment, ProfileChangeRequest, Qualification, ServiceEvent,
} from './types';

/** Building blocks shared by the HR employee screen and the employee's own profile. */

export function OverviewSection({ view, showBank }: { view: EmployeeView; showBank: boolean }) {
  const e = view.employee;
  const md = {
    status: useMasterDataLabels('EMPLOYEE_STATUS'), type: useMasterDataLabels('EMPLOYMENT_TYPE'), cat: useMasterDataLabels('EMPLOYEE_CATEGORY'),
    svc: useMasterDataLabels('SERVICE_CATEGORY'), gender: useMasterDataLabels('GENDER'), civil: useMasterDataLabels('CIVIL_STATUS'), bank: useMasterDataLabels('BANK'),
  };
  return (
    <div className="grid grid-2">
      <Card title="Personal" accent>
        <DL items={[
          ['Full name', e.fullName], ['Name with initials', e.nameWithInitials], ['Sinhala', e.nameSi], ['Tamil', e.nameTa], ['NIC', <span className="mono">{e.nic}</span>],
          ['Date of birth', fmtDate(e.dateOfBirth)], ['Gender', md.gender(e.gender)], ['Civil status', md.civil(e.civilStatus)],
        ]} />
      </Card>
      <Card title="Employment" accent>
        <DL items={[
          ['Designation', view.designationName], ['Unit', view.orgUnitName], ['Position', view.positionTitle], ['Status', <StatusBadge status={e.status} label={md.status(e.status)} />],
          ['Employment type', md.type(e.employmentType)], ['Category', md.cat(e.employeeCategory)], ['Service', md.svc(e.serviceCategory)],
          ['Appointed', fmtDate(e.appointmentDate)], ['Confirmed', e.confirmationDate ? fmtDate(e.confirmationDate) : ''], ['Retirement date', fmtDate(e.retirementDate)],
          ...(e.secondedFrom ? ([['Seconded from', `${e.secondedFrom}${e.secondmentEndDate ? ` until ${fmtDate(e.secondmentEndDate)}` : ''}`]] as [string, string][]) : []),
          ...(e.separationDate ? ([['Separated', `${fmtDate(e.separationDate)} · ${e.separationReason ?? ''}`]] as [string, string][]) : []),
        ]} />
      </Card>
      <Card title="Contact">
        <DL items={[['E-mail', e.email], ['Mobile', e.mobile], ['Phone', e.phone], ['Permanent address', e.permanentAddress], ['Residential address', e.residentialAddress]]} />
      </Card>
      {showBank && (
        <Card title="Bank">
          <DL items={[['Bank', md.bank(e.bankCode)], ['Branch', e.bankBranch], ['Account', e.bankAccountNo ? <span className="mono">{e.bankAccountNo}</span> : '']]} />
        </Card>
      )}
    </div>
  );
}

export function ServiceHistorySection({ employeeId }: { employeeId: number }) {
  const { data, loading, error } = useApi<{ assignments: AssignmentView[]; events: ServiceEvent[] }>(`/employees/${employeeId}/service-history`);
  if (error) return <Alert kind="danger">{error}</Alert>;
  return (
    <div className="grid grid-2">
      <Card title="Placements" pad={false}>
        <DataTable<AssignmentView> rows={data?.assignments} loading={loading} columns={[
          { key: 'period', header: 'Period', render: (a) => <span className="nowrap">{fmtDate(a.effectiveFrom)} – {a.effectiveTo ? fmtDate(a.effectiveTo) : 'present'}</span> },
          { key: 'designation', header: 'Designation / unit', render: (a) => <div><strong>{a.designation}</strong><div className="faint" style={{ fontSize: 12 }}>{a.orgUnitName}{a.position ? ` · ${a.position}` : ''}</div></div> },
          { key: 'reason', header: 'Reason', render: (a) => <span className="badge badge-neutral">{a.reason.toLowerCase()}</span> },
        ]} />
      </Card>
      <Card title="Service events">
        <ul className="timeline">
          {(data?.events ?? []).map((ev) => (
            <li key={ev.id}>
              <div className="row between"><strong>{ev.eventType.replace(/_/g, ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase())}</strong><span className="when">{fmtDate(ev.eventDate)}</span></div>
              <div className="muted">{ev.description}{ev.reference ? ` (Ref: ${ev.reference})` : ''}</div>
            </li>
          ))}
        </ul>
      </Card>
    </div>
  );
}

type DetailKind = 'qualifications' | 'previous-employment' | 'emergency-contacts';

export function DetailsSection({ employeeId, canEdit }: { employeeId: number; canEdit: boolean }) {
  const toast = useToast();
  const { data, reload } = useApi<{ qualifications: Qualification[]; previousEmployment: PreviousEmployment[]; emergencyContacts: EmergencyContact[] }>(`/employees/${employeeId}/details`);
  const [edit, setEdit] = useState<{ kind: DetailKind; row: Record<string, unknown> } | null>(null);
  const [del, setDel] = useState<{ kind: DetailKind; id: number } | null>(null);
  const qType = useMasterDataLabels('QUALIFICATION_TYPE');
  const rel = useMasterDataLabels('RELATIONSHIP');

  const save = async () => {
    if (!edit) return;
    const id = edit.row.id as number | undefined;
    const body = { ...edit.row };
    if (body.yearObtained === '') body.yearObtained = null;
    try {
      if (id) await api.put(`/employees/${employeeId}/${edit.kind}/${id}`, body);
      else await api.post(`/employees/${employeeId}/${edit.kind}`, body);
      toast('Saved', 'success');
      setEdit(null);
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };
  const remove = async () => {
    if (!del) return;
    try {
      await api.delete(`/employees/${employeeId}/${del.kind}/${del.id}`);
      toast('Removed', 'success');
      setDel(null);
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };
  const addBtn = (kind: DetailKind, row: Record<string, unknown>) => canEdit ? <button className="btn btn-outline btn-sm" onClick={() => setEdit({ kind, row })}><Plus /> Add</button> : undefined;
  const rowActions = (kind: DetailKind, r: { id?: number }) => canEdit ? (
    <div className="row" style={{ gap: 2 }}>
      <button className="btn btn-ghost btn-sm" onClick={() => setEdit({ kind, row: { ...r } })}>Edit</button>
      <button className="btn btn-ghost btn-sm" aria-label="Remove" onClick={() => setDel({ kind, id: r.id! })}><Trash2 /></button>
    </div>
  ) : null;
  const setRow = (k: string) => (v: string) => setEdit((x) => (x ? { ...x, row: { ...x.row, [k]: v } } : x));
  const r = (k: string) => (edit?.row[k] as string | undefined) ?? '';

  return (
    <div className="stack">
      <Card title="Educational and professional qualifications" pad={false} actions={addBtn('qualifications', { qualificationType: '', title: '' })}>
        <DataTable<Qualification> rows={data?.qualifications} columns={[
          { key: 'qualificationType', header: 'Type', render: (q) => qType(q.qualificationType) },
          { key: 'title', header: 'Qualification', render: (q) => <strong>{q.title}</strong> },
          { key: 'institution', header: 'Institution' },
          { key: 'yearObtained', header: 'Year' },
          { key: 'result', header: 'Result' },
          { key: 'a', header: '', render: (q) => rowActions('qualifications', q) },
        ]} />
      </Card>
      <Card title="Previous employment" pad={false} actions={addBtn('previous-employment', { organization: '' })}>
        <DataTable<PreviousEmployment> rows={data?.previousEmployment} columns={[
          { key: 'organization', header: 'Organization', render: (p) => <strong>{p.organization}</strong> },
          { key: 'designation', header: 'Designation' },
          { key: 'period', header: 'Period', render: (p) => `${fmtDate(p.fromDate)} – ${fmtDate(p.toDate)}` },
          { key: 'a', header: '', render: (p) => rowActions('previous-employment', p) },
        ]} />
      </Card>
      <Card title="Emergency contacts" pad={false} actions={addBtn('emergency-contacts', { name: '', phone: '' })}>
        <DataTable<EmergencyContact> rows={data?.emergencyContacts} columns={[
          { key: 'name', header: 'Name', render: (c) => <strong>{c.name}</strong> },
          { key: 'relationship', header: 'Relationship', render: (c) => rel(c.relationship) },
          { key: 'phone', header: 'Phone' },
          { key: 'address', header: 'Address' },
          { key: 'a', header: '', render: (c) => rowActions('emergency-contacts', c) },
        ]} />
      </Card>

      {edit && (
        <Modal title={edit.row.id ? 'Edit' : 'Add'} onClose={() => setEdit(null)} footer={
          <><button className="btn btn-outline" onClick={() => setEdit(null)}>Cancel</button><button className="btn btn-primary" onClick={() => void save()}>Save</button></>
        }>
          <div className="form-grid">
            {edit.kind === 'qualifications' && (
              <>
                <MasterDataSelect category="QUALIFICATION_TYPE" label="Type" required value={r('qualificationType')} onChange={setRow('qualificationType')} />
                <TextField label="Year obtained" type="number" value={r('yearObtained')} onChange={setRow('yearObtained')} />
                <TextField className="span-2" label="Qualification" required value={r('title')} onChange={setRow('title')} />
                <TextField label="Institution" value={r('institution')} onChange={setRow('institution')} />
                <TextField label="Result / class" value={r('result')} onChange={setRow('result')} />
              </>
            )}
            {edit.kind === 'previous-employment' && (
              <>
                <TextField className="span-2" label="Organization" required value={r('organization')} onChange={setRow('organization')} />
                <TextField className="span-2" label="Designation" value={r('designation')} onChange={setRow('designation')} />
                <TextField label="From" type="date" value={r('fromDate')} onChange={setRow('fromDate')} />
                <TextField label="To" type="date" value={r('toDate')} onChange={setRow('toDate')} />
              </>
            )}
            {edit.kind === 'emergency-contacts' && (
              <>
                <TextField label="Name" required value={r('name')} onChange={setRow('name')} />
                <MasterDataSelect category="RELATIONSHIP" label="Relationship" value={r('relationship')} onChange={setRow('relationship')} />
                <TextField label="Phone" required value={r('phone')} onChange={setRow('phone')} />
                <TextField label="Address" value={r('address')} onChange={setRow('address')} />
              </>
            )}
            <TextArea className="span-2" label="Remarks" value={r('remarks')} onChange={setRow('remarks')} rows={2} />
          </div>
        </Modal>
      )}
      {del && <ConfirmDialog title="Remove record" message="This removes the record from the employee file. The removal is audit-logged." danger confirmLabel="Remove" onCancel={() => setDel(null)} onConfirm={() => void remove()} />}
    </div>
  );
}

export function DocumentsSection({ employeeId, canUpload, canRestricted }: { employeeId: number; canUpload: boolean; canRestricted: boolean }) {
  const toast = useToast();
  const { data, loading, reload } = useApi<EmployeeDocument[]>(`/employees/${employeeId}/documents`);
  const docType = useMasterDataLabels('DOCUMENT_TYPE');
  const [open, setOpen] = useState(false);
  const [f, setF] = useState({ documentType: '', title: '', documentDate: '', classification: 'CONFIDENTIAL', remarks: '' });
  const [file, setFile] = useState<File | null>(null);

  const upload = async () => {
    if (!file) return;
    const fd = new FormData();
    Object.entries(f).forEach(([k, v]) => v && fd.append(k, v));
    fd.append('file', file);
    try {
      await api.post(`/employees/${employeeId}/documents`, fd);
      toast('Document filed', 'success');
      setOpen(false);
      setFile(null);
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  return (
    <Card title="Digital personal file" pad={false} actions={canUpload ? <button className="btn btn-gold btn-sm" onClick={() => setOpen(true)}><Upload /> File document</button> : undefined}>
      <DataTable<EmployeeDocument> rows={data} loading={loading} columns={[
        { key: 'documentType', header: 'Type', render: (d) => docType(d.documentType) },
        { key: 'title', header: 'Title', render: (d) => <strong>{d.title}</strong> },
        { key: 'documentDate', header: 'Date', render: (d) => fmtDate(d.documentDate) },
        { key: 'classification', header: 'Classification', render: (d) => <span className={`badge ${d.classification === 'HIGHLY_CONFIDENTIAL' ? 'badge-danger' : d.classification === 'CONFIDENTIAL' ? 'badge-warning' : 'badge-neutral'}`}>{d.classification.replace('_', ' ').toLowerCase()}</span> },
        { key: 'createdAt', header: 'Filed', render: (d) => <span className="nowrap">{fmtDateTime(d.createdAt)} · {d.createdBy}</span> },
        { key: 'a', header: '', render: (d) => <button className="btn btn-ghost btn-sm" aria-label="Download" onClick={() => void download(`/employees/${employeeId}/documents/${d.id}/file`).catch((e) => toast(errorMessage(e), 'error'))}><Download /></button> },
      ]} />
      {open && (
        <Modal title="File a document" onClose={() => setOpen(false)} footer={
          <><button className="btn btn-outline" onClick={() => setOpen(false)}>Cancel</button><button className="btn btn-primary" disabled={!file || !f.documentType || !f.title} onClick={() => void upload()}>Upload</button></>
        }>
          <div className="form-grid">
            <MasterDataSelect category="DOCUMENT_TYPE" label="Document type" required value={f.documentType} onChange={(v) => setF({ ...f, documentType: v })} />
            <TextField label="Document date" type="date" value={f.documentDate} onChange={(v) => setF({ ...f, documentDate: v })} />
            <TextField className="span-2" label="Title" required value={f.title} onChange={(v) => setF({ ...f, title: v })} />
            <SelectField label="Access classification" value={f.classification} onChange={(v) => setF({ ...f, classification: v })} placeholder={null}
              options={['PUBLIC', 'INTERNAL', 'CONFIDENTIAL', ...(canRestricted ? ['HIGHLY_CONFIDENTIAL'] : [])].map((c) => ({ value: c, label: c.replace('_', ' ').toLowerCase() }))} />
            <div className="field">
              <label htmlFor="doc-file">File</label>
              <input id="doc-file" type="file" className="input" style={{ paddingTop: 7 }} accept=".pdf,.png,.jpg,.jpeg,.doc,.docx" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
              <span className="help">PDF, image or Word; files are virus-scanned where enabled.</span>
            </div>
            <TextArea className="span-2" label="Remarks" value={f.remarks} onChange={(v) => setF({ ...f, remarks: v })} rows={2} />
          </div>
        </Modal>
      )}
    </Card>
  );
}

export function ChangeRequestsSection({ employeeId }: { employeeId: number }) {
  const { data, loading } = useApi<ProfileChangeRequest[]>(`/employees/${employeeId}/change-requests`);
  return (
    <Card title="Profile change requests" pad={false}>
      <DataTable<ProfileChangeRequest> rows={data} loading={loading} empty="No change requests." columns={[
        { key: 'createdAt', header: 'Requested', render: (c) => fmtDateTime(c.createdAt) },
        { key: 'source', header: 'Source', render: (c) => (c.source === 'SELF' ? 'Self-service' : 'HR (maker-checker)') },
        { key: 'changes', header: 'Changes', render: (c) => {
          try {
            const ch = JSON.parse(c.changes) as Record<string, { old: unknown; new: unknown }>;
            return <div>{Object.entries(ch).map(([k, v]) => <div key={k}><strong>{k}</strong>: <span className="faint">{String(v.old ?? '—')}</span> → {String(v.new ?? '—')}</div>)}</div>;
          } catch {
            return c.changes;
          }
        } },
        { key: 'status', header: 'Status', render: (c) => <StatusBadge status={c.status} /> },
        { key: 'a', header: '', render: (c) => (c.workflowInstanceId ? <Link to={`/approvals/${c.workflowInstanceId}`}>Request #{c.workflowInstanceId}</Link> : null) },
      ]} />
    </Card>
  );
}
