import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Plus } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, Modal, PageHeader, SelectField, StatusBadge, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate } from '@/components/DataTable';
import { DesignationSelect, EmployeeSelect, OrgUnitSelect, employeeLabel } from '@/components/pickers';
import type { CadreCheck, Movement, MovementType } from './types';
import { today, titleCase } from './types';

export default function MovementsPage() {
  const { t } = useTranslation();
  const nav = useNavigate();
  const { can } = useAuth();
  const [status, setStatus] = useState('');
  const [type, setType] = useState('');
  const { data, loading, error, reload } = useApi<Movement[]>('/extended/movements', { status, type });
  const [open, setOpen] = useState(false);

  return (
    <>
      <PageHeader title={t('extended.movements', 'Transfers & promotions')}
        subtitle="Requests are checked against the approved cadre, reviewed by HR and approved by the Director General"
        actions={can('EXT_MANAGE') && <button className="btn btn-gold" onClick={() => setOpen(true)}><Plus /> New request</button>} />
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false} title={
        <div className="filters">
          <SelectField label="Type" value={type} onChange={setType} placeholder="All types"
            options={['TRANSFER', 'PROMOTION', 'CONFIRMATION'].map((v) => ({ value: v, label: titleCase(v) }))} />
          <SelectField label="Status" value={status} onChange={setStatus} placeholder="All statuses"
            options={['PENDING', 'RETURNED', 'APPLIED', 'REJECTED', 'CANCELLED'].map((v) => ({ value: v, label: titleCase(v) }))} />
        </div>
      }>
        <DataTable<Movement>
          rows={data}
          loading={loading}
          onRowClick={(m) => nav(`/extended/movements/${m.id}`)}
          columns={[
            { key: 'id', header: 'Ref', render: (m) => `#${m.id}` },
            { key: 'requestType', header: 'Type', render: (m) => titleCase(m.requestType) },
            { key: 'employee', header: 'Employee', render: (m) => employeeLabel(m.employee) },
            { key: 'from', header: 'From', render: (m) => `${m.fromDesignation ?? '—'}, ${m.fromOrgUnitName ?? '—'}` },
            { key: 'to', header: 'To', render: (m) => m.requestType === 'CONFIRMATION' ? 'Confirmation in service' : `${m.targetDesignation ?? '—'}, ${m.targetOrgUnitName ?? '—'}` },
            { key: 'effectiveDate', header: 'Effective', render: (m) => fmtDate(m.effectiveDate) },
            { key: 'cadre', header: 'Cadre', render: (m) => m.cadreWarning ? <span className="badge badge-warning" title={m.cadreWarning}>No vacancy</span> : <span className="faint">OK</span> },
            { key: 'status', header: 'Status', render: (m) => <StatusBadge status={m.status} /> },
          ]}
        />
      </Card>
      {open && <MovementForm onClose={() => setOpen(false)} onSaved={(id) => { setOpen(false); void reload(); nav(`/extended/movements/${id}`); }} />}
    </>
  );
}

export function MovementForm({ initial, onClose, onSaved }: { initial?: Movement; onClose: () => void; onSaved: (id: number) => void }) {
  const toast = useToast();
  const [f, setF] = useState({
    requestType: (initial?.requestType ?? 'TRANSFER') as MovementType,
    employeeId: initial ? String(initial.employee.id) : '',
    targetOrgUnitCode: initial?.targetOrgUnitCode ?? '',
    targetDesignationId: initial?.targetDesignationId ? String(initial.targetDesignationId) : '',
    targetPositionId: initial?.targetPositionId ? String(initial.targetPositionId) : '',
    effectiveDate: initial?.effectiveDate ?? today(),
    reason: initial?.reason ?? '',
    reference: initial?.reference ?? '',
  });
  const [check, setCheck] = useState<CadreCheck | null>(null);
  const [busy, setBusy] = useState(false);
  const payload = {
    ...f,
    employeeId: f.employeeId ? Number(f.employeeId) : null,
    targetDesignationId: f.targetDesignationId ? Number(f.targetDesignationId) : null,
    targetPositionId: f.targetPositionId ? Number(f.targetPositionId) : null,
    targetOrgUnitCode: f.targetOrgUnitCode || null,
  };

  useEffect(() => {
    if (!f.employeeId || f.requestType === 'CONFIRMATION') {
      setCheck(null);
      return;
    }
    const h = setTimeout(() => {
      api.post<CadreCheck>('/extended/movements/cadre-check', payload).then((r) => setCheck(r.data)).catch(() => setCheck(null));
    }, 300);
    return () => clearTimeout(h);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [f.employeeId, f.requestType, f.targetOrgUnitCode, f.targetDesignationId, f.effectiveDate]);

  const save = async () => {
    setBusy(true);
    try {
      const r = initial
        ? await api.put<Movement>(`/extended/movements/${initial.id}`, payload)
        : await api.post<Movement>('/extended/movements', payload);
      toast(initial ? 'Request revised — resubmit it from Approvals' : 'Request submitted for approval', 'success');
      onSaved(r.data.id);
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  const confirmation = f.requestType === 'CONFIRMATION';
  return (
    <Modal wide title={initial ? `Revise request #${initial.id}` : 'New transfer, promotion or confirmation'} onClose={onClose} footer={
      <>
        <button className="btn btn-outline" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" onClick={() => void save()}
          disabled={busy || !f.employeeId || !f.reason.trim() || !f.effectiveDate || (check?.blocking === true && !check.vacancyAvailable)}>
          {initial ? 'Save changes' : 'Submit for approval'}
        </button>
      </>
    }>
      <div className="form-grid">
        <SelectField label="Request type" required value={f.requestType} placeholder={null} disabled={!!initial}
          onChange={(v) => setF({ ...f, requestType: v as MovementType })}
          options={[{ value: 'TRANSFER', label: 'Transfer' }, { value: 'PROMOTION', label: 'Promotion' }, { value: 'CONFIRMATION', label: 'Confirmation in service' }]} />
        {initial ? <TextField label="Employee" value={employeeLabel(initial.employee)} onChange={() => undefined} disabled />
          : <EmployeeSelect required value={f.employeeId} onChange={(v) => setF({ ...f, employeeId: v })} />}
        {!confirmation && (
          <>
            <OrgUnitSelect label={f.requestType === 'TRANSFER' ? 'Target unit' : 'Target unit (blank = current)'} required={f.requestType === 'TRANSFER'}
              value={f.targetOrgUnitCode} onChange={(v) => setF({ ...f, targetOrgUnitCode: v, targetPositionId: '' })} />
            <DesignationSelect label={f.requestType === 'PROMOTION' ? 'Target designation' : 'Designation (blank = current)'} required={f.requestType === 'PROMOTION'}
              value={f.targetDesignationId} onChange={(v) => setF({ ...f, targetDesignationId: v, targetPositionId: '' })} />
            <PositionSelect unit={f.targetOrgUnitCode} designationId={f.targetDesignationId} value={f.targetPositionId}
              onChange={(v) => setF({ ...f, targetPositionId: v })} />
          </>
        )}
        <TextField label={confirmation ? 'Confirmation date' : 'Proposed effective date'} type="date" required value={f.effectiveDate}
          onChange={(v) => setF({ ...f, effectiveDate: v })} />
        <TextField label="Reference (file / board decision)" value={f.reference} onChange={(v) => setF({ ...f, reference: v })} maxLength={100} />
        <TextArea label="Reason" required value={f.reason} onChange={(v) => setF({ ...f, reason: v })} />
      </div>
      {check && (
        <Alert kind={check.vacancyAvailable ? 'success' : check.blocking ? 'danger' : 'warning'}>
          <strong>Cadre check:</strong> {check.message}
          {!check.vacancyAvailable && !check.blocking && ' — you may still submit; approvers will see this warning.'}
        </Alert>
      )}
    </Modal>
  );
}

function PositionSelect({ unit, designationId, value, onChange }: { unit: string; designationId: string; value: string; onChange: (v: string) => void }) {
  const { data } = useApi<{ id: number; code: string; title: string; designationId: number; orgUnitCode: string; active: boolean }[]>(
    unit ? '/establishment/positions' : null, { orgUnitCode: unit });
  const options = (data ?? []).filter((p) => p.active && p.orgUnitCode === unit && (!designationId || String(p.designationId) === designationId));
  return (
    <SelectField label="Position (optional)" value={value} onChange={onChange} placeholder="— None —"
      options={options.map((p) => ({ value: String(p.id), label: `${p.title} (${p.code})` }))} />
  );
}
