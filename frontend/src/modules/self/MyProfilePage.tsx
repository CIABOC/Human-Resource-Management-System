import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Send } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Loading, Modal, PageHeader, Tabs, TextArea, TextField } from '@/components/ui';
import { MasterDataSelect } from '@/components/pickers';
import type { EmployeeView } from '../employees/types';
import { ChangeRequestsSection, DetailsSection, DocumentsSection, OverviewSection, ServiceHistorySection } from '../employees/EmployeeSections';

type Tab = 'overview' | 'service' | 'details' | 'documents' | 'requests';
const SELF_FIELDS = ['mobile', 'phone', 'email', 'residentialAddress', 'permanentAddress', 'civilStatus'] as const;

/** Employee self-service profile (FR-EMP-013, FR-EXT-003). Changes go to HR for approval. */
export default function MyProfilePage() {
  const { t } = useTranslation();
  const { data, loading, error } = useApi<EmployeeView>('/employees/me');
  const [tab, setTab] = useState<Tab>('overview');
  const [open, setOpen] = useState(false);
  const [refresh, setRefresh] = useState(0);
  if (loading && !data) return <Loading />;
  if (error) return <Alert kind="danger">{error}</Alert>;
  if (!data) return null;
  const e = data.employee;
  return (
    <>
      <PageHeader eyebrow={`${e.employeeNo} · ${data.designationName ?? ''}`} title={t('profile.title', 'My profile')}
        subtitle="Your official record. If something is wrong, request a correction — HR will verify and approve it."
        actions={<button className="btn btn-gold" onClick={() => setOpen(true)}><Send /> Request a change</button>} />
      <Tabs<Tab> value={tab} onChange={setTab} tabs={[
        { key: 'overview', label: 'Overview' }, { key: 'service', label: 'Service history' }, { key: 'details', label: 'Qualifications & contacts' },
        { key: 'documents', label: 'My documents' }, { key: 'requests', label: 'My change requests' },
      ]} />
      {tab === 'overview' && <OverviewSection view={data} showBank />}
      {tab === 'service' && <ServiceHistorySection employeeId={e.id} />}
      {tab === 'details' && <DetailsSection employeeId={e.id} canEdit={false} />}
      {tab === 'documents' && <DocumentsSection employeeId={e.id} canUpload={false} canRestricted={false} />}
      {tab === 'requests' && <ChangeRequestsSection key={refresh} employeeId={e.id} />}
      {open && <ChangeModal view={data} onClose={() => setOpen(false)} onDone={() => { setRefresh((r) => r + 1); setTab('requests'); }} />}
    </>
  );
}

function ChangeModal({ view, onClose, onDone }: { view: EmployeeView; onClose: () => void; onDone: () => void }) {
  const toast = useToast();
  const e = view.employee as unknown as Record<string, string | undefined>;
  const [f, setF] = useState<Record<string, string>>(Object.fromEntries(SELF_FIELDS.map((k) => [k, e[k] ?? ''])));
  const [reason, setReason] = useState('');
  const set = (k: string) => (v: string) => setF({ ...f, [k]: v });
  const submit = async () => {
    const changes = Object.fromEntries(Object.entries(f).filter(([k, v]) => (e[k] ?? '') !== v));
    if (Object.keys(changes).length === 0) {
      toast('Nothing has changed', 'error');
      return;
    }
    try {
      await api.post('/employees/me/change-requests', { changes, reason });
      toast('Your request was sent to HR for approval', 'success');
      onDone();
      onClose();
    } catch (err) {
      toast(errorMessage(err), 'error');
    }
  };
  return (
    <Modal title="Request a profile change" onClose={onClose} wide footer={<><button className="btn btn-outline" onClick={onClose}>Cancel</button><button className="btn btn-primary" onClick={() => void submit()}>Submit</button></>}>
      <div className="stack">
        <Alert kind="info">You can request changes to your contact details here. For name, NIC, date of birth or bank details, contact HR with supporting documents.</Alert>
        <div className="form-grid">
          <TextField label="Mobile" value={f.mobile} onChange={set('mobile')} />
          <TextField label="Phone" value={f.phone} onChange={set('phone')} />
          <TextField className="span-2" label="E-mail" type="email" value={f.email} onChange={set('email')} />
          <TextArea label="Residential address" value={f.residentialAddress} onChange={set('residentialAddress')} rows={2} />
          <TextArea label="Permanent address" value={f.permanentAddress} onChange={set('permanentAddress')} rows={2} />
          <MasterDataSelect category="CIVIL_STATUS" label="Civil status" value={f.civilStatus} onChange={set('civilStatus')} />
          <TextArea className="span-2" label="Reason" value={reason} onChange={setReason} rows={2} />
        </div>
      </div>
    </Modal>
  );
}
