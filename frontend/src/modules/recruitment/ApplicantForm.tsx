import { useState } from 'react';
import { api, errorMessage } from '@/api/client';
import { Alert, Modal, TextArea, TextField } from '@/components/ui';
import { MasterDataSelect } from '@/components/pickers';
import type { Applicant } from './types';

type Draft = Record<string, string>;

const FIELDS = ['title', 'fullName', 'nameWithInitials', 'nic', 'dateOfBirth', 'gender', 'civilStatus', 'email', 'mobile', 'address', 'qualifications', 'receivedDate', 'remarks'];

/** HR-entered application (online applications are a future, optional item). */
export function ApplicantForm({ vacancyId, applicant, onClose, onSaved }: {
  vacancyId: number; applicant?: Applicant; onClose: () => void; onSaved: (a: Applicant) => void;
}) {
  const init: Draft = {};
  FIELDS.forEach((k) => (init[k] = applicant ? String((applicant as unknown as Record<string, unknown>)[k] ?? '') : ''));
  const [f, setF] = useState<Draft>(init);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const set = (k: string) => (v: string) => setF((x) => ({ ...x, [k]: v }));

  const save = async () => {
    setBusy(true);
    setErr(null);
    try {
      const body: Record<string, unknown> = { vacancyId };
      FIELDS.forEach((k) => (body[k] = f[k] === '' ? null : f[k]));
      const res = applicant ? await api.put<Applicant>(`/recruitment/applicants/${applicant.id}`, body) : await api.post<Applicant>('/recruitment/applicants', body);
      onSaved(res.data);
    } catch (e) {
      setErr(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title={applicant ? `Edit ${applicant.applicationNo}` : 'Record application'} onClose={onClose} wide footer={
      <>
        <button className="btn btn-outline" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" onClick={() => void save()} disabled={busy}>Save</button>
      </>
    }>
      <div className="stack">
        {err && <Alert kind="danger">{err}</Alert>}
        <div className="form-grid">
          <MasterDataSelect category="TITLE" label="Title" value={f.title} onChange={set('title')} />
          <TextField label="Full name" value={f.fullName} onChange={set('fullName')} required />
          <TextField label="Name with initials" value={f.nameWithInitials} onChange={set('nameWithInitials')} required />
          <TextField label="NIC" value={f.nic} onChange={set('nic')} required help="9 digits + V/X, or 12 digits" />
          <TextField label="Date of birth" type="date" value={f.dateOfBirth} onChange={set('dateOfBirth')} required />
          <MasterDataSelect category="GENDER" label="Gender" value={f.gender} onChange={set('gender')} />
          <MasterDataSelect category="CIVIL_STATUS" label="Civil status" value={f.civilStatus} onChange={set('civilStatus')} />
          <TextField label="Received on" type="date" value={f.receivedDate} onChange={set('receivedDate')} help="Defaults to today" />
          <TextField label="E-mail" type="email" value={f.email} onChange={set('email')} />
          <TextField label="Mobile" value={f.mobile} onChange={set('mobile')} />
          <TextArea label="Postal address" value={f.address} onChange={set('address')} className="span-all" rows={2} />
          <TextArea label="Qualifications" value={f.qualifications} onChange={set('qualifications')} className="span-all" />
          <TextArea label="Remarks" value={f.remarks} onChange={set('remarks')} className="span-all" rows={2} />
        </div>
      </div>
    </Modal>
  );
}
