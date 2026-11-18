import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, errorMessage } from '@/api/client';
import type { Designation, OrgUnit, Position } from '@/api/types';
import { useToast } from '@/components/Toast';
import { Alert, Modal, SelectField, TextArea, TextField } from '@/components/ui';
import { DesignationSelect, MasterDataSelect, OrgUnitSelect } from '@/components/pickers';

export type ChangeType =
  | 'ORG_UNIT_CREATE' | 'ORG_UNIT_UPDATE' | 'ORG_UNIT_DEACTIVATE'
  | 'DESIGNATION_CREATE' | 'DESIGNATION_UPDATE' | 'DESIGNATION_DEACTIVATE'
  | 'POSITION_CREATE' | 'POSITION_UPDATE' | 'POSITION_DEACTIVATE'
  | 'CADRE_SET';

export interface ChangeTarget {
  type: ChangeType;
  unit?: OrgUnit;
  designation?: Designation;
  position?: Position;
  cadre?: { designationId: number; orgUnitCode: string; approved: number };
}

const TITLES: Record<ChangeType, string> = {
  ORG_UNIT_CREATE: 'Propose new organizational unit', ORG_UNIT_UPDATE: 'Propose unit change', ORG_UNIT_DEACTIVATE: 'Propose unit deactivation',
  DESIGNATION_CREATE: 'Propose new designation', DESIGNATION_UPDATE: 'Propose designation change', DESIGNATION_DEACTIVATE: 'Propose designation deactivation',
  POSITION_CREATE: 'Propose new position', POSITION_UPDATE: 'Propose position change', POSITION_DEACTIVATE: 'Propose position deactivation',
  CADRE_SET: 'Propose cadre change',
};

/**
 * One modal for every establishment change. Nothing is applied directly: the change is submitted to the
 * ESTABLISHMENT_CHANGE approval workflow and applied on its effective date once approved (FR-EST-023, -060).
 */
export function ChangeModal({ target, onClose, onDone }: { target: ChangeTarget; onClose: () => void; onDone?: () => void }) {
  const toast = useToast();
  const nav = useNavigate();
  const today = new Date().toISOString().slice(0, 10);
  const u = target.unit;
  const d = target.designation;
  const p = target.position;
  const [payload, setPayload] = useState<Record<string, string>>((): Record<string, string> => {
    switch (target.type) {
      case 'ORG_UNIT_UPDATE': return { name: u?.name ?? '', nameSi: u?.nameSi ?? '', nameTa: u?.nameTa ?? '', unitType: u?.unitType ?? 'DIVISION', parentCode: u?.parentCode ?? '', sortOrder: String(u?.sortOrder ?? 0) };
      case 'DESIGNATION_UPDATE': return { name: d?.name ?? '', nameSi: d?.nameSi ?? '', nameTa: d?.nameTa ?? '', grade: d?.grade ?? '', serviceCategory: d?.serviceCategory ?? '', description: d?.description ?? '' };
      case 'POSITION_UPDATE': return { title: p?.title ?? '', designationId: String(p?.designationId ?? ''), orgUnitCode: p?.orgUnitCode ?? '', reportsToPositionCode: p?.reportsToPositionCode ?? '' };
      case 'CADRE_SET': return { designationId: String(target.cadre?.designationId ?? ''), orgUnitCode: target.cadre?.orgUnitCode ?? '', approvedQty: String(target.cadre?.approved ?? '') };
      case 'ORG_UNIT_CREATE': return { unitType: 'DIVISION', sortOrder: '0' };
      default: return {};
    }
  });
  const [effectiveDate, setEffectiveDate] = useState(today);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const set = (k: string) => (v: string) => setPayload((x) => ({ ...x, [k]: v }));

  const targetRef = u?.code ?? (d ? String(d.id) : p ? String(p.id) : undefined);

  const submit = async () => {
    setBusy(true);
    setError(null);
    const body: Record<string, unknown> = { ...payload };
    for (const k of ['designationId', 'approvedQty', 'sortOrder']) if (body[k] !== undefined && body[k] !== '') body[k] = Number(body[k]);
    try {
      const { data } = await api.post('/establishment/changes', { changeType: target.type, targetRef, payload: body, effectiveDate, reason });
      toast('Change submitted for approval', 'success');
      onDone?.();
      onClose();
      nav(`/establishment/changes/${data.id}`);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const isDeactivate = target.type.endsWith('_DEACTIVATE');
  return (
    <Modal title={TITLES[target.type]} onClose={onClose} wide footer={
      <>
        <button className="btn btn-outline" onClick={onClose}>Cancel</button>
        <button className="btn btn-gold" disabled={busy || !reason.trim()} onClick={() => void submit()}>Submit for approval</button>
      </>
    }>
      <div className="stack">
        <Alert kind="info">This change takes effect only after approval through the establishment approval chain, from the effective date below. Previous values are kept in history.</Alert>
        {error && <Alert kind="danger">{error}</Alert>}
        {isDeactivate && (
          <Alert kind="warning">
            You are proposing to deactivate <strong>{u?.name ?? d?.name ?? p?.title}</strong>. It must have no active employees, sub-units or positions.
          </Alert>
        )}
        <div className="form-grid">
          {(target.type === 'ORG_UNIT_CREATE' || target.type === 'ORG_UNIT_UPDATE') && (
            <>
              {target.type === 'ORG_UNIT_CREATE' && <TextField label="Code" required value={payload.code} onChange={(v) => set('code')(v.toUpperCase())} help="Upper-case letters, digits, - or _" />}
              <SelectField label="Unit type" required value={payload.unitType} onChange={set('unitType')} placeholder={null}
                options={['DIVISION', 'DEPARTMENT', 'SECTION', 'SUB_SECTION'].map((x) => ({ value: x, label: x.replace('_', '-').toLowerCase().replace(/^\w/, (c) => c.toUpperCase()) }))} />
              <TextField className="span-2" label="Name (English)" required value={payload.name} onChange={set('name')} />
              <TextField label="Name (Sinhala)" value={payload.nameSi} onChange={set('nameSi')} lang="si" />
              <TextField label="Name (Tamil)" value={payload.nameTa} onChange={set('nameTa')} lang="ta" />
              <OrgUnitSelect label="Parent unit" value={payload.parentCode} onChange={set('parentCode')} placeholder="— None (top level) —" />
              <TextField label="Display order" type="number" value={payload.sortOrder} onChange={set('sortOrder')} />
            </>
          )}
          {(target.type === 'DESIGNATION_CREATE' || target.type === 'DESIGNATION_UPDATE') && (
            <>
              {target.type === 'DESIGNATION_CREATE' && <TextField label="Code" required value={payload.code} onChange={(v) => set('code')(v.toUpperCase())} />}
              <TextField label="Name (English)" required value={payload.name} onChange={set('name')} className={target.type === 'DESIGNATION_UPDATE' ? 'span-2' : ''} />
              <TextField label="Name (Sinhala)" value={payload.nameSi} onChange={set('nameSi')} lang="si" />
              <TextField label="Name (Tamil)" value={payload.nameTa} onChange={set('nameTa')} lang="ta" />
              <MasterDataSelect category="GRADE" label="Grade" value={payload.grade} onChange={set('grade')} />
              <MasterDataSelect category="SERVICE_CATEGORY" label="Service category" value={payload.serviceCategory} onChange={set('serviceCategory')} />
              <TextArea className="span-2" label="Description" value={payload.description} onChange={set('description')} />
            </>
          )}
          {(target.type === 'POSITION_CREATE' || target.type === 'POSITION_UPDATE') && (
            <>
              {target.type === 'POSITION_CREATE' && <TextField label="Code" required value={payload.code} onChange={(v) => set('code')(v.toUpperCase())} />}
              <TextField label="Position title" required value={payload.title} onChange={set('title')} className={target.type === 'POSITION_UPDATE' ? 'span-2' : ''} />
              <DesignationSelect required value={payload.designationId} onChange={set('designationId')} />
              <OrgUnitSelect required value={payload.orgUnitCode} onChange={set('orgUnitCode')} />
              <TextField className="span-2" label="Reports to (position code)" value={payload.reportsToPositionCode} onChange={(v) => set('reportsToPositionCode')(v.toUpperCase())} />
            </>
          )}
          {target.type === 'CADRE_SET' && (
            <>
              <DesignationSelect required value={payload.designationId} onChange={set('designationId')} />
              <OrgUnitSelect required value={payload.orgUnitCode} onChange={set('orgUnitCode')} />
              <TextField label="Proposed approved cadre" type="number" min={0} required value={payload.approvedQty} onChange={set('approvedQty')}
                help={target.cadre ? `Currently approved: ${target.cadre.approved}` : undefined} />
            </>
          )}
          <TextField label="Effective date" type="date" required value={effectiveDate} onChange={setEffectiveDate} />
          <TextArea className="span-all" label="Reason / authority for the change" required value={reason} onChange={setReason}
            help="e.g. Cabinet decision, Management Services approval letter reference" />
        </div>
      </div>
    </Modal>
  );
}
