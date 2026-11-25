import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, errorMessage } from '@/api/client';
import type { Designation, OrgUnit, Position } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Checkbox, Modal, SelectField, TextArea, TextField } from '@/components/ui';
import { DesignationSelect, MasterDataSelect, OrgUnitSelect } from '@/components/pickers';

export type ChangeType =
  | 'ORG_UNIT_CREATE' | 'ORG_UNIT_UPDATE' | 'ORG_UNIT_DEACTIVATE'
  | 'DESIGNATION_CREATE' | 'DESIGNATION_UPDATE' | 'DESIGNATION_DEACTIVATE'
  | 'POSITION_CREATE' | 'POSITION_UPDATE' | 'POSITION_DEACTIVATE'
  | 'CADRE_SET';

type UnitX = OrgUnit & { location?: string | null; headTitle?: string | null };
type DesignationX = Designation & { level?: number | null; salaryCode?: string | null; dyingCadre?: boolean; statutory?: boolean; legalBasis?: string | null };

export interface ChangeTarget {
  type: ChangeType;
  unit?: UnitX;
  designation?: DesignationX;
  position?: Position;
  /** Pre-filled cadre line; any part may be missing (e.g. a designation dropped on a unit). */
  cadre?: { designationId?: number; orgUnitCode?: string; approved?: number };
  /** Parent for a new unit. */
  parentCode?: string;
}

export interface CadreImpact {
  designationId: number; designationName: string; orgUnitCode: string; currentApproved: number; proposed: number; filled: number; acting: number;
  minimumAllowed: number; belowFilled: boolean; dyingCadre: boolean; postsToCreate: number; newPositionCodes: string[]; postsToFreeze: number;
  postsToBeAbolished: number; blockedReason?: string | null;
}

const TITLES: Record<ChangeType, string> = {
  ORG_UNIT_CREATE: 'Propose new organizational unit', ORG_UNIT_UPDATE: 'Propose unit change', ORG_UNIT_DEACTIVATE: 'Propose unit deactivation',
  DESIGNATION_CREATE: 'Propose new designation', DESIGNATION_UPDATE: 'Propose designation change', DESIGNATION_DEACTIVATE: 'Propose designation deactivation',
  POSITION_CREATE: 'Propose new position', POSITION_UPDATE: 'Propose position change', POSITION_DEACTIVATE: 'Propose position deactivation',
  CADRE_SET: 'Cadre change request',
};

const NUMERIC = ['designationId', 'approvedQty', 'sortOrder', 'level'];
const BOOLEAN = ['dyingCadre', 'statutory'];

/**
 * One modal for every establishment change (REQ 6.4, 6.5, 14). Nothing is applied directly: the change is saved as a
 * draft or submitted to the ESTABLISHMENT_CHANGE approval workflow and applied on its effective date once approved.
 */
export function ChangeModal({ target, onClose, onDone }: { target: ChangeTarget; onClose: () => void; onDone?: () => void }) {
  const toast = useToast();
  const nav = useNavigate();
  const { can } = useAuth();
  const today = new Date().toISOString().slice(0, 10);
  const u = target.unit;
  const d = target.designation;
  const p = target.position;
  const [payload, setPayload] = useState<Record<string, string>>((): Record<string, string> => {
    switch (target.type) {
      case 'ORG_UNIT_UPDATE': return { name: u?.name ?? '', nameSi: u?.nameSi ?? '', nameTa: u?.nameTa ?? '', unitType: u?.unitType ?? 'DIVISION', parentCode: u?.parentCode ?? '', sortOrder: String(u?.sortOrder ?? 0), location: u?.location ?? '', headTitle: u?.headTitle ?? '' };
      case 'DESIGNATION_UPDATE': return {
        name: d?.name ?? '', nameSi: d?.nameSi ?? '', nameTa: d?.nameTa ?? '', grade: d?.grade ?? '', serviceCategory: d?.serviceCategory ?? '', description: d?.description ?? '',
        level: d?.level != null ? String(d.level) : '', salaryCode: d?.salaryCode ?? '', dyingCadre: String(!!d?.dyingCadre), statutory: String(!!d?.statutory), legalBasis: d?.legalBasis ?? '',
      };
      case 'POSITION_UPDATE': return { title: p?.title ?? '', designationId: String(p?.designationId ?? ''), orgUnitCode: p?.orgUnitCode ?? '', reportsToPositionCode: p?.reportsToPositionCode ?? '' };
      case 'CADRE_SET': return { designationId: target.cadre?.designationId ? String(target.cadre.designationId) : '', orgUnitCode: target.cadre?.orgUnitCode ?? '', approvedQty: target.cadre?.approved != null ? String(target.cadre.approved) : '' };
      case 'ORG_UNIT_CREATE': return { unitType: 'DIVISION', sortOrder: '0', parentCode: target.parentCode ?? '' };
      case 'DESIGNATION_CREATE': return { dyingCadre: 'false', statutory: 'false' };
      default: return {};
    }
  });
  const [effectiveDate, setEffectiveDate] = useState(today);
  const [reason, setReason] = useState('');
  const [approvingAuthority, setApprovingAuthority] = useState('');
  const [referenceNo, setReferenceNo] = useState('');
  const [exceptionReason, setExceptionReason] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [impact, setImpact] = useState<CadreImpact | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const set = (k: string) => (v: string) => setPayload((x) => ({ ...x, [k]: v }));
  const isCadre = target.type === 'CADRE_SET';

  const targetRef = u?.code ?? (d ? String(d.id) : p ? String(p.id) : undefined);
  const body = (draft: boolean) => {
    const pl: Record<string, unknown> = { ...payload };
    for (const k of NUMERIC) if (pl[k] !== undefined) pl[k] = pl[k] === '' ? null : Number(pl[k]);
    for (const k of BOOLEAN) if (pl[k] !== undefined) pl[k] = pl[k] === 'true';
    return {
      changeType: target.type, targetRef, payload: pl, effectiveDate, reason, approvingAuthority: approvingAuthority || null,
      referenceNo: referenceNo || null, exceptionReason: exceptionReason || null, draft,
    };
  };

  // Live impact preview for cadre changes (REQ 6.5).
  useEffect(() => {
    if (!isCadre || !payload.designationId || !payload.orgUnitCode || payload.approvedQty === '' || payload.approvedQty === undefined) {
      setImpact(null);
      setPreviewError(null);
      return;
    }
    const h = setTimeout(() => {
      api.post('/establishment/changes/preview', body(false))
        .then(({ data }) => { setImpact(data.impact ?? null); setPreviewError(data.valid ? null : data.error); })
        .catch((e) => { setImpact(null); setPreviewError(errorMessage(e)); });
    }, 350);
    return () => clearTimeout(h);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isCadre, payload.designationId, payload.orgUnitCode, payload.approvedQty, effectiveDate, exceptionReason]);

  const submit = async (draft: boolean) => {
    setBusy(true);
    setError(null);
    try {
      const { data } = await api.post('/establishment/changes', body(draft));
      if (file) {
        const fd = new FormData();
        fd.append('file', file);
        await api.post(`/establishment/changes/${data.id}/attachment`, fd);
      }
      toast(draft ? 'Draft saved — submit it from the change page when ready' : 'Change submitted for approval', 'success');
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
  const blocked = !!impact?.blockedReason;
  const needsException = !!impact?.belowFilled;
  return (
    <Modal title={TITLES[target.type]} onClose={onClose} wide footer={
      <>
        <button className="btn btn-outline" onClick={onClose}>Cancel</button>
        <button className="btn btn-outline" disabled={busy} onClick={() => void submit(true)}>Save as draft</button>
        <button className="btn btn-gold" disabled={busy || !reason.trim() || blocked || (needsException && !exceptionReason.trim())} onClick={() => void submit(false)}>Submit for approval</button>
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
              <MasterDataSelect category="LOCATION" label="Location" value={payload.location} onChange={set('location')} />
              <TextField label="Head of unit title" value={payload.headTitle} onChange={set('headTitle')} help="e.g. Director (Investigation)" />
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
              <TextField label="Level (1 = most senior)" type="number" min={1} value={payload.level} onChange={set('level')} />
              <TextField label="Salary code" value={payload.salaryCode} onChange={(v) => set('salaryCode')(v.toUpperCase())} help="e.g. SL-1-2016" />
              <div className="field"><Checkbox label="Dying cadre (no new posts; existing holders retained)" checked={payload.dyingCadre === 'true'} onChange={(v) => set('dyingCadre')(String(v))} /></div>
              <div className="field"><Checkbox label="Statutory post (created by law, outside the cadre total)" checked={payload.statutory === 'true'} onChange={(v) => set('statutory')(String(v))} /></div>
              {payload.statutory === 'true' && <TextField className="span-2" label="Legal basis" required value={payload.legalBasis} onChange={set('legalBasis')} help="Act and section establishing the post" />}
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
          {isCadre && (
            <>
              <DesignationSelect required value={payload.designationId} onChange={set('designationId')} />
              <OrgUnitSelect required value={payload.orgUnitCode} onChange={set('orgUnitCode')} />
              <TextField label="Proposed approved cadre" type="number" min={0} required value={payload.approvedQty} onChange={set('approvedQty')}
                help={impact ? `Currently approved: ${impact.currentApproved}` : target.cadre?.approved != null ? `Currently approved: ${target.cadre.approved}` : undefined} />
              <div />
            </>
          )}
          <TextField label="Effective date" type="date" required value={effectiveDate} onChange={setEffectiveDate} />
          <TextField label="Approving authority" value={approvingAuthority} onChange={setApprovingAuthority} help="e.g. Department of Management Services" />
          <TextField label="Reference no." value={referenceNo} onChange={setReferenceNo} help="Approval letter / Cabinet decision number" />
          <div className="field">
            <label htmlFor="chg-file">Supporting document</label>
            <input id="chg-file" type="file" className="input" accept=".pdf,.jpg,.jpeg,.png,.doc,.docx" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
          </div>
          <TextArea className="span-all" label="Reason / justification" required value={reason} onChange={setReason} />
        </div>

        {isCadre && previewError && !impact?.blockedReason && <Alert kind="danger">{previewError}</Alert>}
        {isCadre && impact && <ImpactPanel impact={impact} />}
        {isCadre && needsException && (
          can('EST_CADRE_EXCEPTION')
            ? <TextArea label="Exception reason (approved cadre below current holders)" required value={exceptionReason} onChange={setExceptionReason}
                help="Required: the excess holders stay in post and are reported as excess until they leave." />
            : <Alert kind="danger">The proposed cadre is below the {impact!.filled + impact!.acting} officers currently holding these posts. Only an officer with cadre-exception authority may submit this.</Alert>
        )}
      </div>
    </Modal>
  );
}

/** Shows what approving a cadre change would do to posts (REQ 6.5). */
export function ImpactPanel({ impact: i }: { impact: CadreImpact }) {
  const delta = i.proposed - i.currentApproved;
  return (
    <div className="card" style={{ borderLeft: '4px solid var(--gold-500)' }}>
      <div className="card-body stack" style={{ gap: 8 }}>
        <strong>Impact preview — {i.designationName}, {i.orgUnitCode}</strong>
        <div className="row" style={{ gap: 18, flexWrap: 'wrap', fontSize: 13 }}>
          <span>Approved <b>{i.currentApproved}</b> → <b>{i.proposed}</b> ({delta >= 0 ? '+' : ''}{delta})</span>
          <span>Filled <b>{i.filled}</b></span>
          <span>Acting <b>{i.acting}</b></span>
          <span>Minimum without exception <b>{i.minimumAllowed}</b></span>
        </div>
        {i.blockedReason && <Alert kind="danger">{i.blockedReason}</Alert>}
        {i.postsToCreate > 0 && <div style={{ fontSize: 13 }}>Creates <b>{i.postsToCreate}</b> new post(s): <span className="mono">{i.newPositionCodes.join(', ')}</span></div>}
        {i.postsToFreeze > 0 && <div style={{ fontSize: 13 }}>Freezes <b>{i.postsToFreeze}</b> vacant post(s).</div>}
        {i.postsToBeAbolished > 0 && <div style={{ fontSize: 13, color: 'var(--warning)' }}>Marks <b>{i.postsToBeAbolished}</b> occupied post(s) “to be abolished” when the holder leaves.</div>}
        {delta === 0 && <div className="muted" style={{ fontSize: 13 }}>No change to the approved quantity.</div>}
      </div>
    </div>
  );
}
