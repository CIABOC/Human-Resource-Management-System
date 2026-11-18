import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowRightLeft, Pencil, ShieldAlert, UserCog } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import type { Position } from '@/api/types';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Badge, Loading, Modal, PageHeader, SelectField, StatusBadge, Tabs, TextArea, TextField } from '@/components/ui';
import { DesignationSelect, MasterDataSelect, OrgUnitSelect, useMasterDataLabels } from '@/components/pickers';
import type { EmployeeView } from './types';
import { ChangeRequestsSection, DetailsSection, DocumentsSection, OverviewSection, ServiceHistorySection } from './EmployeeSections';

type Tab = 'overview' | 'service' | 'details' | 'documents' | 'requests';

export default function EmployeeDetailPage() {
  const { id } = useParams();
  const nav = useNavigate();
  const { can } = useAuth();
  const { data, loading, error, reload } = useApi<EmployeeView>(`/employees/${id}`);
  const [tab, setTab] = useState<Tab>('overview');
  const [modal, setModal] = useState<'assign' | 'status' | null>(null);
  const statusLabel = useMasterDataLabels('EMPLOYEE_STATUS');
  const manage = can('EMP_MANAGE');

  if (loading && !data) return <Loading />;
  if (error) return <Alert kind="danger">{error}</Alert>;
  if (!data) return null;
  const e = data.employee;

  return (
    <>
      <PageHeader eyebrow={`${e.employeeNo} · ${data.designationName ?? ''} · ${data.orgUnitName ?? ''}`} title={e.nameWithInitials}
        subtitle={<span className="row"><StatusBadge status={e.status} label={statusLabel(e.status)} />{e.restricted && <Badge tone="danger"><ShieldAlert size={12} /> Restricted record</Badge>}{!data.hasUserAccount && <Badge>No user account</Badge>}</span>}
        actions={manage ? (
          <>
            <button className="btn btn-outline" onClick={() => setModal('status')}><UserCog /> Change status</button>
            <button className="btn btn-outline" onClick={() => setModal('assign')}><ArrowRightLeft /> Change placement</button>
            <button className="btn btn-primary" onClick={() => nav(`/employees/${e.id}/edit`)}><Pencil /> Edit</button>
          </>
        ) : undefined} />
      <Tabs<Tab> value={tab} onChange={setTab} tabs={[
        { key: 'overview', label: 'Overview' }, { key: 'service', label: 'Service history' }, { key: 'details', label: 'Qualifications & contacts' },
        { key: 'documents', label: 'Personal file' }, { key: 'requests', label: 'Change requests' },
      ]} />
      {tab === 'overview' && <OverviewSection view={data} showBank={manage || can('PAY_VIEW')} />}
      {tab === 'service' && <ServiceHistorySection employeeId={e.id} />}
      {tab === 'details' && <DetailsSection employeeId={e.id} canEdit={manage} />}
      {tab === 'documents' && <DocumentsSection employeeId={e.id} canUpload={manage} canRestricted={can('EMP_RESTRICTED')} />}
      {tab === 'requests' && <ChangeRequestsSection employeeId={e.id} />}

      {modal === 'assign' && <AssignModal view={data} onClose={() => setModal(null)} onDone={() => void reload()} />}
      {modal === 'status' && <StatusModal view={data} onClose={() => setModal(null)} onDone={() => void reload()} />}
    </>
  );
}

function AssignModal({ view, onClose, onDone }: { view: EmployeeView; onClose: () => void; onDone: () => void }) {
  const toast = useToast();
  const e = view.employee;
  const [f, setF] = useState({ orgUnitCode: e.orgUnitCode ?? '', designationId: String(e.designationId ?? ''), positionId: '', effectiveDate: new Date().toISOString().slice(0, 10), reason: 'REASSIGNMENT', reference: '', remarks: '' });
  const positions = useApi<Position[]>(f.orgUnitCode ? '/establishment/positions' : null, { orgUnitCode: f.orgUnitCode });
  const save = async () => {
    try {
      await api.post(`/employees/${e.id}/assignments`, { ...f, designationId: Number(f.designationId), positionId: f.positionId ? Number(f.positionId) : null });
      toast('Placement updated; service history recorded', 'success');
      onDone();
      onClose();
    } catch (err) {
      toast(errorMessage(err), 'error');
    }
  };
  return (
    <Modal title="Change placement" onClose={onClose} wide footer={<><button className="btn btn-outline" onClick={onClose}>Cancel</button><button className="btn btn-primary" onClick={() => void save()}>Save</button></>}>
      <div className="stack">
        <Alert kind="info">Formal transfers and promotions should go through Transfers &amp; promotions for approval. Use this for administrative re-assignments and corrections.</Alert>
        <div className="form-grid">
          <OrgUnitSelect required value={f.orgUnitCode} onChange={(v) => setF({ ...f, orgUnitCode: v, positionId: '' })} />
          <DesignationSelect required value={f.designationId} onChange={(v) => setF({ ...f, designationId: v })} />
          <SelectField label="Position" value={f.positionId} onChange={(v) => setF({ ...f, positionId: v })}
            options={(positions.data ?? []).filter((p) => String(p.designationId) === f.designationId).map((p) => ({ value: String(p.id), label: `${p.title} (${p.code})` }))} />
          <TextField label="Effective date" type="date" required value={f.effectiveDate} onChange={(v) => setF({ ...f, effectiveDate: v })} />
          <SelectField label="Reason" required value={f.reason} onChange={(v) => setF({ ...f, reason: v })} placeholder={null}
            options={['REASSIGNMENT', 'TRANSFER', 'PROMOTION', 'SECONDMENT', 'CONFIRMATION', 'CORRECTION'].map((x) => ({ value: x, label: x.toLowerCase() }))} />
          <TextField label="Reference (letter no.)" value={f.reference} onChange={(v) => setF({ ...f, reference: v })} />
          <TextArea className="span-2" label="Remarks" value={f.remarks} onChange={(v) => setF({ ...f, remarks: v })} rows={2} />
        </div>
      </div>
    </Modal>
  );
}

function StatusModal({ view, onClose, onDone }: { view: EmployeeView; onClose: () => void; onDone: () => void }) {
  const toast = useToast();
  const e = view.employee;
  const [f, setF] = useState({ status: e.status, effectiveDate: new Date().toISOString().slice(0, 10), separationReason: '', remarks: '' });
  const separating = ['RETIRED', 'RESIGNED', 'TERMINATED', 'TRANSFERRED'].includes(f.status);
  const save = async () => {
    try {
      await api.post(`/employees/${e.id}/status`, { ...f, separationReason: f.separationReason || null });
      toast('Status updated', 'success');
      onDone();
      onClose();
    } catch (err) {
      toast(errorMessage(err), 'error');
    }
  };
  return (
    <Modal title="Change employment status" onClose={onClose} footer={<><button className="btn btn-outline" onClick={onClose}>Cancel</button><button className={`btn ${separating ? 'btn-danger' : 'btn-primary'}`} onClick={() => void save()}>Save</button></>}>
      <div className="stack">
        {separating && <Alert kind="warning">Separation closes the current placement and deactivates the employee's user account from the effective date.</Alert>}
        <div className="form-grid">
          <MasterDataSelect category="EMPLOYEE_STATUS" label="New status" required value={f.status} onChange={(v) => setF({ ...f, status: v })} />
          <TextField label="Effective date" type="date" required value={f.effectiveDate} onChange={(v) => setF({ ...f, effectiveDate: v })} />
          {separating && <MasterDataSelect className="span-2" category="SEPARATION_REASON" label="Separation reason" required value={f.separationReason} onChange={(v) => setF({ ...f, separationReason: v })} />}
          <TextArea className="span-2" label="Remarks" value={f.remarks} onChange={(v) => setF({ ...f, remarks: v })} rows={2} />
        </div>
      </div>
    </Modal>
  );
}
