import { useState } from 'react';
import { api, errorMessage } from '@/api/client';
import { useToast } from '@/components/Toast';
import { Modal, TextArea, TextField } from '@/components/ui';
import { EmployeeSelect } from '@/components/pickers';

/** Manual correction request; applied only after approval (workflow ATTENDANCE_CORRECTION, FR-ATT-019). */
export default function CorrectionModal({ forOthers, initial, onClose, onSaved }: {
  forOthers?: boolean; initial?: { employeeId?: number; workDate?: string }; onClose: () => void; onSaved: () => void;
}) {
  const toast = useToast();
  const [f, setF] = useState({ employeeId: initial?.employeeId ? String(initial.employeeId) : '', workDate: initial?.workDate ?? '', checkIn: '', checkOut: '', reason: '' });
  const [busy, setBusy] = useState(false);
  const save = async () => {
    setBusy(true);
    try {
      await api.post('/attendance/corrections', {
        employeeId: forOthers && f.employeeId ? Number(f.employeeId) : null,
        workDate: f.workDate, checkIn: f.checkIn || null, checkOut: f.checkOut || null, reason: f.reason,
      });
      toast('Correction submitted for approval', 'success');
      onSaved();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal title="Request attendance correction" onClose={onClose} footer={
      <>
        <button className="btn btn-outline" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" disabled={busy || !f.workDate || !f.reason.trim() || (!f.checkIn && !f.checkOut)} onClick={() => void save()}>Submit for approval</button>
      </>
    }>
      <div className="form-grid">
        {forOthers && <EmployeeSelect className="span-2" label="Employee (leave empty for yourself)" value={f.employeeId} onChange={(v) => setF({ ...f, employeeId: v })} />}
        <TextField className="span-2" label="Date" type="date" required value={f.workDate} onChange={(v) => setF({ ...f, workDate: v })} />
        <TextField label="Correct check-in" type="time" value={f.checkIn} onChange={(v) => setF({ ...f, checkIn: v })} />
        <TextField label="Correct check-out" type="time" value={f.checkOut} onChange={(v) => setF({ ...f, checkOut: v })} />
        <TextArea className="span-2" label="Reason" required value={f.reason} onChange={(v) => setF({ ...f, reason: v })} help="Explain why the recorded attendance is wrong; the request is routed to the division head" />
      </div>
    </Modal>
  );
}
