import { useState } from 'react';
import { api, errorMessage } from '@/api/client';
import { Alert, Modal, TextArea, TextField } from '@/components/ui';
import { DesignationSelect, OrgUnitSelect } from '@/components/pickers';
import type { Vacancy } from './types';

export interface VacancyDraft {
  referenceNo?: string;
  title: string;
  designationId: string;
  orgUnitCode: string;
  numPosts: string;
  openingDate?: string;
  closingDate?: string;
  description?: string;
  requirements?: string;
  examWeight?: string;
  interviewWeight?: string;
}

export function draftFrom(v: Vacancy): VacancyDraft {
  return {
    referenceNo: v.referenceNo, title: v.title, designationId: String(v.designationId), orgUnitCode: v.orgUnitCode,
    numPosts: String(v.numPosts), openingDate: v.openingDate ?? '', closingDate: v.closingDate ?? '', description: v.description ?? '',
    requirements: v.requirements ?? '', examWeight: String(v.examWeight), interviewWeight: String(v.interviewWeight),
  };
}

/** Create / edit a recruitment vacancy. The server enforces the approved-cadre limit (FR-REC-001). */
export function VacancyForm({ id, initial, onClose, onSaved }: { id?: number; initial: VacancyDraft; onClose: () => void; onSaved: (v: Vacancy) => void }) {
  const [f, setF] = useState<VacancyDraft>(initial);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const set = (k: keyof VacancyDraft) => (v: string) => setF((x) => ({ ...x, [k]: v }));

  const save = async () => {
    setBusy(true);
    setErr(null);
    try {
      const body = {
        ...f,
        designationId: f.designationId ? Number(f.designationId) : null,
        numPosts: f.numPosts ? Number(f.numPosts) : null,
        openingDate: f.openingDate || null,
        closingDate: f.closingDate || null,
        examWeight: f.examWeight ? Number(f.examWeight) : null,
        interviewWeight: f.interviewWeight ? Number(f.interviewWeight) : null,
        referenceNo: f.referenceNo || null,
      };
      const res = id ? await api.put<Vacancy>(`/recruitment/vacancies/${id}`, body) : await api.post<Vacancy>('/recruitment/vacancies', body);
      onSaved(res.data);
    } catch (e) {
      setErr(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title={id ? 'Edit vacancy' : 'New vacancy'} onClose={onClose} wide footer={
      <>
        <button className="btn btn-outline" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" onClick={() => void save()} disabled={busy}>Save</button>
      </>
    }>
      <div className="stack">
        {err && <Alert kind="danger">{err}</Alert>}
        <div className="form-grid">
          <TextField label="Reference no." value={f.referenceNo} onChange={set('referenceNo')} help="Leave blank to generate" disabled={!!id} />
          <TextField label="Title" value={f.title} onChange={set('title')} required />
          <DesignationSelect value={f.designationId} onChange={set('designationId')} required />
          <OrgUnitSelect value={f.orgUnitCode} onChange={set('orgUnitCode')} required />
          <TextField label="Number of posts" type="number" min={1} value={f.numPosts} onChange={set('numPosts')} required />
          <div />
          <TextField label="Opening date" type="date" value={f.openingDate} onChange={set('openingDate')} />
          <TextField label="Closing date" type="date" value={f.closingDate} onChange={set('closingDate')} />
          <TextField label="Examination weight (%)" type="number" min={0} max={100} value={f.examWeight} onChange={set('examWeight')}
            help="Used only to rank applicants; defaults from settings" />
          <TextField label="Interview weight (%)" type="number" min={0} max={100} value={f.interviewWeight} onChange={set('interviewWeight')} />
          <TextArea label="Description" value={f.description} onChange={set('description')} className="span-all" />
          <TextArea label="Requirements" value={f.requirements} onChange={set('requirements')} className="span-all" />
        </div>
      </div>
    </Modal>
  );
}
