import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Checkbox, Modal, SelectField, TextArea, TextField } from '@/components/ui';
import { fmtDays, isoDate, type LeaveApplication, type LeaveType, type Preview } from './types';

/** Apply for leave, or revise an application returned for correction (FR-LEV-003, -004). */
export default function ApplyLeaveModal({ existing, onClose, onSaved }: { existing?: LeaveApplication | null; onClose: () => void; onSaved: () => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const { data: types } = useApi<LeaveType[]>('/leave/types');
  const today = isoDate(new Date());
  const [form, setForm] = useState({
    leaveTypeId: existing ? String(existing.leaveTypeId) : '',
    startDate: existing?.startDate ?? today,
    endDate: existing?.endDate ?? today,
    halfDay: existing?.halfDay ?? false,
    halfDaySession: existing?.halfDaySession ?? 'AM',
    reason: existing?.reason ?? '',
    contactDuringLeave: existing?.contactDuringLeave ?? '',
  });
  const [documentFileId, setDocumentFileId] = useState<number | null>(null);
  const [docName, setDocName] = useState<string | null>(existing?.hasDocument ? 'Existing document' : null);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [busy, setBusy] = useState(false);
  const type = (types ?? []).find((x) => String(x.id) === form.leaveTypeId);

  const payload = () => ({
    leaveTypeId: form.leaveTypeId ? Number(form.leaveTypeId) : null,
    startDate: form.startDate,
    endDate: form.halfDay ? form.startDate : form.endDate,
    halfDay: form.halfDay,
    halfDaySession: form.halfDay ? form.halfDaySession : null,
    reason: form.reason,
    contactDuringLeave: form.contactDuringLeave,
    documentFileId,
  });

  useEffect(() => {
    if (!form.leaveTypeId || !form.startDate || !form.endDate) {
      setPreview(null);
      return;
    }
    const h = setTimeout(() => {
      api.post<Preview>('/leave/preview', payload()).then((r) => setPreview(r.data)).catch(() => setPreview(null));
    }, 250);
    return () => clearTimeout(h);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [form.leaveTypeId, form.startDate, form.endDate, form.halfDay, form.halfDaySession]);

  const upload = async (f: File | undefined) => {
    if (!f) return;
    const fd = new FormData();
    fd.append('file', f);
    try {
      const r = await api.post<{ fileId: number; name: string }>('/leave/documents', fd);
      setDocumentFileId(r.data.fileId);
      setDocName(r.data.name);
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  const save = async () => {
    setBusy(true);
    try {
      const body = payload();
      if (existing) {
        await api.put(`/leave/applications/${existing.id}`, body);
        toast('Application revised and resubmitted', 'success');
      } else {
        await api.post('/leave/applications', body);
        toast('Leave application submitted for approval', 'success');
      }
      onSaved();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title={existing ? t('leave.revise', 'Revise leave application') : t('leave.apply', 'Apply for leave')} onClose={onClose} wide footer={
      <>
        <button className="btn btn-outline" onClick={onClose}>Cancel</button>
        <button className="btn btn-gold" disabled={busy || !form.leaveTypeId} onClick={() => void save()}>{existing ? 'Resubmit' : 'Submit application'}</button>
      </>
    }>
      <div className="form-grid">
        <SelectField className="span-2" label="Leave type" required value={form.leaveTypeId} onChange={(v) => setForm({ ...form, leaveTypeId: v, halfDay: false })}
          options={(types ?? []).map((x) => ({ value: String(x.id), label: x.name + (x.paid ? '' : ' (unpaid)') }))} />
        {type?.description && <div className="span-2 muted" style={{ fontSize: 13 }}>{type.description}</div>}
        <TextField label="From" type="date" required value={form.startDate} onChange={(v) => setForm({ ...form, startDate: v, endDate: form.endDate < v ? v : form.endDate })} />
        {!form.halfDay && <TextField label="To" type="date" required value={form.endDate} onChange={(v) => setForm({ ...form, endDate: v })} />}
        {type?.halfDayAllowed && (
          <div className="span-2 row">
            <Checkbox label="Half day" checked={form.halfDay} onChange={(v) => setForm({ ...form, halfDay: v, endDate: form.startDate })} />
            {form.halfDay && (
              <SelectField label="Session" value={form.halfDaySession} placeholder={null} onChange={(v) => setForm({ ...form, halfDaySession: v })}
                options={[{ value: 'AM', label: 'Morning (AM)' }, { value: 'PM', label: 'Afternoon (PM)' }]} />
            )}
          </div>
        )}
        <TextArea className="span-2" label="Reason" required={type?.requiresReason} value={form.reason} onChange={(v) => setForm({ ...form, reason: v })} />
        <TextField className="span-2" label="Contact during leave" value={form.contactDuringLeave} onChange={(v) => setForm({ ...form, contactDuringLeave: v })} />
        <div className="span-2 field">
          <label>Supporting document{preview?.documentRequired ? ' *' : ' (optional)'}</label>
          <input type="file" accept=".pdf,.png,.jpg,.jpeg" onChange={(e) => void upload(e.target.files?.[0])} />
          {docName && <span className="help">Attached: {docName}</span>}
        </div>
      </div>
      {preview && (
        <div style={{ marginTop: 14 }}>
          <Alert kind={preview.valid ? 'info' : 'warning'}>
            <strong>{fmtDays(preview.days)}</strong> working day(s) will be charged
            {preview.balanceTracked && preview.available !== null && preview.available !== undefined && <> · {fmtDays(preview.available)} day(s) currently available</>}
            {preview.problem && <div>{preview.problem}</div>}
          </Alert>
        </div>
      )}
    </Modal>
  );
}
