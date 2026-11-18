import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Plus } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, DL, Modal, PageHeader, SelectField, StatusBadge, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate, fmtMoney } from '@/components/DataTable';
import { num, today, type ClaimView, type CoverageView } from './types';

const NEXT: Record<string, string[]> = { SUBMITTED: ['IN_REVIEW', 'APPROVED', 'REJECTED'], IN_REVIEW: ['APPROVED', 'REJECTED'], APPROVED: ['PAID'] };

/** Insurance claims recorded in HRMS (FR-BEN-005). */
export default function ClaimsPage() {
  const { t } = useTranslation();
  const toast = useToast();
  const { can } = useAuth();
  const [status, setStatus] = useState('');
  const { data, loading, error, reload } = useApi<ClaimView[]>('/benefits/claims', { status });
  const coverages = useApi<CoverageView[]>('/benefits/coverages', { status: 'ACTIVE' });
  const [add, setAdd] = useState<null | { coverageId: string; claimType: string; amountClaimed: string; incidentDate: string; claimDate: string; description: string }>(null);
  const [upd, setUpd] = useState<null | { claim: ClaimView; status: string; amountApproved: string; providerRef: string; remarks: string }>(null);
  const [busy, setBusy] = useState(false);

  const call = async (fn: () => Promise<unknown>, ok: string) => {
    setBusy(true);
    try {
      await fn();
      toast(ok, 'success');
      setAdd(null);
      setUpd(null);
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader eyebrow={t('nav.compensation', 'Compensation')} title={t('ben.claims', 'Insurance claims')} subtitle="Claims lodged by or for employees and their processing status."
        actions={can('BEN_MANAGE') && <button className="btn btn-gold" onClick={() => setAdd({ coverageId: '', claimType: '', amountClaimed: '', incidentDate: today(), claimDate: today(), description: '' })}><Plus /> Record claim</button>} />
      <div className="filters">
        <SelectField label="Status" value={status} onChange={setStatus} placeholder="— All —"
          options={['SUBMITTED', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'PAID'].map((s) => ({ value: s, label: s.replace('_', ' ').toLowerCase() }))} />
      </div>
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false}>
        <DataTable<ClaimView> rows={data} loading={loading}
          onRowClick={can('BEN_MANAGE') ? (c) => setUpd({ claim: c, status: NEXT[c.status]?.[0] ?? '', amountApproved: String(c.amountClaimed), providerRef: c.providerRef ?? '', remarks: '' }) : undefined}
          columns={[
            { key: 'claimDate', header: 'Date', render: (c) => fmtDate(c.claimDate) },
            { key: 'employeeNo', header: 'Emp. no', render: (c) => <span className="mono">{c.employeeNo}</span> },
            { key: 'employeeName', header: 'Name' },
            { key: 'schemeName', header: 'Scheme' },
            { key: 'claimType', header: 'Claim' },
            { key: 'amountClaimed', header: 'Claimed', align: 'right', render: (c) => fmtMoney(c.amountClaimed) },
            { key: 'amountApproved', header: 'Approved', align: 'right', render: (c) => fmtMoney(c.amountApproved) },
            { key: 'status', header: 'Status', render: (c) => <StatusBadge status={c.status} /> },
          ]} />
      </Card>
      {add && (
        <Modal title="Record claim" onClose={() => setAdd(null)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setAdd(null)}>Cancel</button>
            <button className="btn btn-primary" disabled={busy} onClick={() => void call(() => api.post('/benefits/claims', { ...add, coverageId: num(add.coverageId), amountClaimed: num(add.amountClaimed) }), 'Claim recorded')}>Save</button>
          </>
        }>
          <div className="form-grid">
            <SelectField label="Policy" required value={add.coverageId} onChange={(v) => setAdd({ ...add, coverageId: v })}
              options={(coverages.data ?? []).map((c) => ({ value: String(c.id), label: `${c.employeeNo} — ${c.schemeName} ${c.policyNo ?? ''}` }))} />
            <TextField label="Claim type" required value={add.claimType} onChange={(v) => setAdd({ ...add, claimType: v })} placeholder="Hospitalisation" />
            <TextField label="Amount claimed (LKR)" type="number" required value={add.amountClaimed} onChange={(v) => setAdd({ ...add, amountClaimed: v })} />
            <TextField label="Incident date" type="date" value={add.incidentDate} onChange={(v) => setAdd({ ...add, incidentDate: v })} />
            <TextField label="Claim date" type="date" value={add.claimDate} onChange={(v) => setAdd({ ...add, claimDate: v })} />
            <TextArea label="Description" value={add.description} onChange={(v) => setAdd({ ...add, description: v })} />
          </div>
        </Modal>
      )}
      {upd && (
        <Modal title={`Claim #${upd.claim.id}`} onClose={() => setUpd(null)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setUpd(null)}>Close</button>
            {NEXT[upd.claim.status] && <button className="btn btn-primary" disabled={busy || !upd.status} onClick={() => void call(() => api.post(`/benefits/claims/${upd.claim.id}/status`, { status: upd.status, amountApproved: num(upd.amountApproved), providerRef: upd.providerRef, remarks: upd.remarks }), 'Claim updated')}>Update status</button>}
          </>
        }>
          <DL items={[
            ['Employee', `${upd.claim.employeeNo} — ${upd.claim.employeeName}`],
            ['Scheme / policy', `${upd.claim.schemeName} ${upd.claim.policyNo ?? ''}`],
            ['Claim', upd.claim.claimType],
            ['Incident date', fmtDate(upd.claim.incidentDate)],
            ['Claimed', `LKR ${fmtMoney(upd.claim.amountClaimed)}`],
            ['Status', <StatusBadge key="s" status={upd.claim.status} />],
            ['Description', upd.claim.description],
            ['Remarks', upd.claim.remarks],
          ]} />
          {NEXT[upd.claim.status] && (
            <div className="form-grid" style={{ marginTop: 14 }}>
              <SelectField label="New status" value={upd.status} onChange={(v) => setUpd({ ...upd, status: v })} placeholder={null}
                options={NEXT[upd.claim.status].map((s) => ({ value: s, label: s.replace('_', ' ').toLowerCase() }))} />
              {upd.status === 'APPROVED' && <TextField label="Approved amount (LKR)" type="number" value={upd.amountApproved} onChange={(v) => setUpd({ ...upd, amountApproved: v })} />}
              <TextField label="Provider reference" value={upd.providerRef} onChange={(v) => setUpd({ ...upd, providerRef: v })} />
              <TextArea label={upd.status === 'REJECTED' ? 'Remarks (required)' : 'Remarks'} value={upd.remarks} onChange={(v) => setUpd({ ...upd, remarks: v })} />
            </div>
          )}
        </Modal>
      )}
    </>
  );
}
