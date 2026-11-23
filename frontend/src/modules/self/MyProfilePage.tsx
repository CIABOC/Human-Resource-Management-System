import { ChangeEvent, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Camera, Send } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Loading, Modal, PageHeader, SelectField, Tabs, TextArea, TextField } from '@/components/ui';
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
  const [requestOpen, setRequestOpen] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const [photoUrl, setPhotoUrl] = useState<string | null>(null);
  const [photoRefresh, setPhotoRefresh] = useState(0);
  const toast = useToast();
  useEffect(() => {
    let active = true;
    let objectUrl: string | null = null;
    api.get<Blob>('/account/profile-photo', { responseType: 'blob' }).then(({ data }) => {
      objectUrl = URL.createObjectURL(data);
      if (active) setPhotoUrl(objectUrl);
    }).catch((err) => {
      if (err?.response?.status === 404) {
        if (active) setPhotoUrl(null);
      } else if (active) {
        toast(errorMessage(err), 'error');
      }
    });
    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [photoRefresh, toast]);
  if (loading && !data) return <Loading />;
  if (error) return <Alert kind="danger">{error}</Alert>;
  if (!data) return null;
  const e = data.employee;
  return (
    <>
      <PageHeader eyebrow={`${e.employeeNo} · ${data.designationName ?? ''}`} title={t('profile.title', 'My profile')}
        subtitle="Your official record. If something is wrong, request a correction — HR will verify and approve it."
        actions={<button className="btn btn-gold" onClick={() => setOpen(true)}><Send /> Request a change</button>} />
      <ProfilePicture name={e.nameWithInitials || e.fullName} photoUrl={photoUrl} onUploaded={() => setPhotoRefresh((n) => n + 1)} />
      <Tabs<Tab> value={tab} onChange={setTab} tabs={[
        { key: 'overview', label: 'Overview' }, { key: 'service', label: 'Service history' }, { key: 'details', label: 'Qualifications & contacts' },
        { key: 'documents', label: 'My documents' }, { key: 'requests', label: 'My change requests' },
      ]} />
      {tab === 'overview' && <OverviewSection view={data} showBank />}
      {tab === 'service' && <ServiceHistorySection employeeId={e.id} />}
      {tab === 'details' && <DetailsSection employeeId={e.id} canEdit={false} />}
      {tab === 'documents' && <DocumentsSection employeeId={e.id} canUpload={false} canRestricted={false} />}
      {tab === 'requests' && (
        <>
          <div className="row" style={{ justifyContent: 'flex-end', marginBottom: 12 }}>
            <Link className="btn btn-outline" to="/self/letters">Request an employment document</Link>
            <button className="btn btn-gold" onClick={() => setRequestOpen(true)}>New qualification / service correction request</button>
          </div>
          <ChangeRequestsSection key={refresh} employeeId={e.id} />
        </>
      )}
      {open && <ChangeModal view={data} onClose={() => setOpen(false)} onDone={() => { setRefresh((r) => r + 1); setTab('requests'); }} />}
      {requestOpen && <AdditionalRequestModal onClose={() => setRequestOpen(false)} onDone={() => { setRefresh((r) => r + 1); setTab('requests'); }} />}
    </>
  );
}

function AdditionalRequestModal({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const toast = useToast();
  const [requestType, setRequestType] = useState('QUALIFICATION_UPDATE');
  const [details, setDetails] = useState('');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const submit = async () => {
    setBusy(true);
    try {
      await api.post('/employees/me/hr-requests', { requestType, details, reason });
      toast('Your request was sent to HR for review', 'success');
      onDone();
      onClose();
    } catch (err) {
      toast(errorMessage(err), 'error');
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal title="New HR request" onClose={onClose} wide footer={
      <><button className="btn btn-outline" onClick={onClose}>Cancel</button><button className="btn btn-primary" disabled={busy || !details.trim()} onClick={() => void submit()}>{busy ? 'Submitting…' : 'Submit request'}</button></>
    }>
      <div className="stack">
        <Alert kind="info">Requests are routed to HR for review. Approval records the decision; HR updates official records through the authorized employee-management tools.</Alert>
        <SelectField label="Request type" value={requestType} onChange={setRequestType} placeholder={null} options={[
          { value: 'QUALIFICATION_UPDATE', label: 'Qualification update' },
          { value: 'SERVICE_RECORD_CORRECTION', label: 'Service record correction' },
        ]} />
        <TextArea label="Request details" required value={details} onChange={setDetails} rows={5}
          help={requestType === 'QUALIFICATION_UPDATE' ? 'Include qualification, institution, year obtained, result, and supporting-document references.' : 'Identify the service-history entry and describe the correction requested.'} />
        <TextArea label="Reason" value={reason} onChange={setReason} rows={2} />
      </div>
    </Modal>
  );
}

function ProfilePicture({ name, photoUrl, onUploaded }: { name: string; photoUrl: string | null; onUploaded: () => void }) {
  const toast = useToast();
  const upload = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    const body = new FormData();
    body.append('file', file);
    try {
      await api.post('/account/profile-photo', body);
      onUploaded();
      toast('Profile picture updated', 'success');
    } catch (err) {
      toast(errorMessage(err), 'error');
    }
  };
  return (
    <div className="profile-picture">
      {photoUrl ? <img src={photoUrl} alt={`${name} profile`} /> : <div className="profile-picture-placeholder" aria-hidden>{name.split(/\s+/).filter(Boolean).slice(0, 2).map((part) => part[0]).join('').toUpperCase()}</div>}
      <div>
        <strong>Profile picture</strong>
        <div className="muted">JPG or PNG, up to 5 MB.</div>
      </div>
      <label className="btn btn-outline btn-sm">
        <Camera /> Change picture
        <input type="file" accept="image/jpeg,image/png,.jpg,.jpeg,.png" hidden onChange={(event) => void upload(event)} />
      </label>
    </div>
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
