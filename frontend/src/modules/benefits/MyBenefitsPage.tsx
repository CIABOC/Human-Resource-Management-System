import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Plus } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Badge, Card, Loading, Modal, PageHeader, SelectField, StatusBadge, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate, fmtMoney } from '@/components/DataTable';
import { num, today, type ClaimView, type CoverageView, type Eligibility, type MyBenefits } from './types';

/** Employee self-service view of own insurance coverage, eligibility and claims. */
export default function MyBenefitsPage() {
  const { t } = useTranslation();
  const toast = useToast();
  const { data, loading, error, reload } = useApi<MyBenefits>('/benefits/my');
  const [claim, setClaim] = useState<null | { coverageId: string; claimType: string; amountClaimed: string; incidentDate: string; description: string }>(null);
  const [busy, setBusy] = useState(false);

  if (loading && !data) return <Loading />;
  const active = (data?.coverages ?? []).filter((c) => c.status === 'ACTIVE');

  const submit = async () => {
    if (!claim) return;
    setBusy(true);
    try {
      await api.post('/benefits/my/claims', { ...claim, coverageId: num(claim.coverageId), amountClaimed: num(claim.amountClaimed) });
      toast('Claim submitted to HR', 'success');
      setClaim(null);
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader eyebrow={t('nav.self', 'My space')} title={t('ben.my', 'My benefits')} subtitle="Your insurance coverage, eligibility and claims"
        actions={active.length > 0 && <button className="btn btn-gold" onClick={() => setClaim({ coverageId: String(active[0].id), claimType: '', amountClaimed: '', incidentDate: today(), description: '' })}><Plus /> Submit a claim</button>} />
      {error && <Alert kind="danger">{error}</Alert>}
      <div className="stack">
        <Card title="My policies" pad={false}>
          <DataTable<CoverageView> rows={data?.coverages} empty="You have no recorded insurance coverage"
            columns={[
              { key: 'schemeName', header: 'Scheme' },
              { key: 'policyNo', header: 'Policy no' },
              { key: 'startDate', header: 'Start', render: (c) => fmtDate(c.startDate) },
              { key: 'expiryDate', header: 'Expiry', render: (c) => <>{fmtDate(c.expiryDate)} {c.status === 'ACTIVE' && c.daysToExpiry != null && c.daysToExpiry <= 30 && <Badge tone="warning">Renew soon</Badge>}</> },
              { key: 'coverageAmount', header: 'Coverage', align: 'right', render: (c) => fmtMoney(c.coverageAmount) },
              { key: 'employeePremium', header: 'My premium / month', align: 'right', render: (c) => fmtMoney(c.employeePremium) },
              { key: 'status', header: 'Status', render: (c) => <StatusBadge status={c.status} /> },
            ]} />
        </Card>
        <Card title="Eligibility" pad={false}>
          <DataTable<Eligibility> rows={data?.eligibility} rowKey={(e) => e.schemeId}
            columns={[
              { key: 'schemeName', header: 'Scheme' },
              { key: 'eligible', header: 'Eligible', render: (e) => (e.eligible ? <Badge tone="success">Eligible</Badge> : <Badge tone="neutral">Not eligible</Badge>) },
              { key: 'covered', header: 'Covered', render: (e) => (e.covered ? 'Yes' : 'No') },
              { key: 'reasons', header: 'Notes', render: (e) => e.reasons.join('; ') || (e.eligible && !e.covered ? 'Contact HR to enrol' : '') },
            ]} />
        </Card>
        <Card title="My claims" pad={false}>
          <DataTable<ClaimView> rows={data?.claims} empty="No claims"
            columns={[
              { key: 'claimDate', header: 'Date', render: (c) => fmtDate(c.claimDate) },
              { key: 'schemeName', header: 'Scheme' },
              { key: 'claimType', header: 'Claim' },
              { key: 'amountClaimed', header: 'Claimed', align: 'right', render: (c) => fmtMoney(c.amountClaimed) },
              { key: 'amountApproved', header: 'Approved', align: 'right', render: (c) => fmtMoney(c.amountApproved) },
              { key: 'status', header: 'Status', render: (c) => <StatusBadge status={c.status} /> },
              { key: 'remarks', header: 'Remarks' },
            ]} />
        </Card>
      </div>
      {claim && (
        <Modal title="Submit a claim" onClose={() => setClaim(null)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setClaim(null)}>Cancel</button>
            <button className="btn btn-primary" disabled={busy} onClick={() => void submit()}>Submit</button>
          </>
        }>
          <div className="form-grid">
            <SelectField label="Policy" required value={claim.coverageId} onChange={(v) => setClaim({ ...claim, coverageId: v })} placeholder={null}
              options={active.map((c) => ({ value: String(c.id), label: `${c.schemeName} ${c.policyNo ?? ''}` }))} />
            <TextField label="Claim type" required value={claim.claimType} onChange={(v) => setClaim({ ...claim, claimType: v })} placeholder="Hospitalisation" />
            <TextField label="Amount (LKR)" type="number" required value={claim.amountClaimed} onChange={(v) => setClaim({ ...claim, amountClaimed: v })} />
            <TextField label="Incident date" type="date" value={claim.incidentDate} onChange={(v) => setClaim({ ...claim, incidentDate: v })} />
            <TextArea label="Description" value={claim.description} onChange={(v) => setClaim({ ...claim, description: v })} />
          </div>
          <Alert kind="info">Hand over the original bills and documents to HR as required by the scheme.</Alert>
        </Modal>
      )}
    </>
  );
}
