import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Plus } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Badge, Card, Checkbox, Modal, PageHeader, SelectField, StatusBadge, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtMoney } from '@/components/DataTable';
import { MasterDataSelect, useMasterDataLabels } from '@/components/pickers';
import { num, type InsuranceScheme } from './types';

interface Form {
  id?: number; code: string; name: string; nameSi: string; nameTa: string; schemeType: string; category: string; provider: string;
  employeePremium: string; employerPremium: string; coverageAmount: string; payrollDeduction: boolean; claimsEnabled: boolean;
  eligibleEmploymentTypes: string; minServiceMonths: string; eligibilityNotes: string; active: boolean;
}

/** Agrahara and other insurance schemes (FR-BEN-001). */
export default function SchemesPage() {
  const { t } = useTranslation();
  const toast = useToast();
  const { can } = useAuth();
  const { data, loading, error, reload } = useApi<InsuranceScheme[]>('/benefits/schemes');
  const catLabel = useMasterDataLabels('INSURANCE_CATEGORY');
  const [f, setF] = useState<Form | null>(null);
  const [busy, setBusy] = useState(false);

  const edit = (s?: InsuranceScheme) => setF(s ? {
    id: s.id, code: s.code, name: s.name, nameSi: s.nameSi ?? '', nameTa: s.nameTa ?? '', schemeType: s.schemeType, category: s.category ?? '',
    provider: s.provider ?? '', employeePremium: String(s.employeePremium), employerPremium: String(s.employerPremium),
    coverageAmount: s.coverageAmount == null ? '' : String(s.coverageAmount), payrollDeduction: s.payrollDeduction, claimsEnabled: s.claimsEnabled,
    eligibleEmploymentTypes: s.eligibleEmploymentTypes ?? '', minServiceMonths: String(s.minServiceMonths), eligibilityNotes: s.eligibilityNotes ?? '', active: s.active,
  } : {
    code: '', name: '', nameSi: '', nameTa: '', schemeType: 'OTHER', category: '', provider: '', employeePremium: '0', employerPremium: '0',
    coverageAmount: '', payrollDeduction: true, claimsEnabled: true, eligibleEmploymentTypes: '', minServiceMonths: '0', eligibilityNotes: '', active: true,
  });

  const save = async () => {
    if (!f) return;
    setBusy(true);
    const body = { ...f, employeePremium: num(f.employeePremium), employerPremium: num(f.employerPremium), coverageAmount: num(f.coverageAmount), minServiceMonths: num(f.minServiceMonths) };
    try {
      if (f.id) await api.put(`/benefits/schemes/${f.id}`, body);
      else await api.post('/benefits/schemes', body);
      toast('Scheme saved', 'success');
      setF(null);
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader eyebrow={t('nav.compensation', 'Compensation')} title={t('ben.schemes', 'Insurance schemes')}
        subtitle="Agrahara and other insurance schemes, premiums, coverage and eligibility rules."
        actions={can('BEN_MANAGE') && <button className="btn btn-gold" onClick={() => edit()}><Plus /> New scheme</button>} />
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false}>
        <DataTable<InsuranceScheme> rows={data} loading={loading} onRowClick={can('BEN_MANAGE') ? edit : undefined}
          columns={[
            { key: 'code', header: 'Code', render: (s) => <span className="mono">{s.code}</span> },
            { key: 'name', header: 'Scheme', render: (s) => <>{s.name} {s.schemeType === 'AGRAHARA' && <Badge tone="gold">Agrahara</Badge>}</> },
            { key: 'category', header: 'Category', render: (s) => catLabel(s.category) },
            { key: 'provider', header: 'Provider' },
            { key: 'employeePremium', header: 'Employee premium', align: 'right', render: (s) => fmtMoney(s.employeePremium) },
            { key: 'employerPremium', header: 'Employer premium', align: 'right', render: (s) => fmtMoney(s.employerPremium) },
            { key: 'coverageAmount', header: 'Coverage', align: 'right', render: (s) => fmtMoney(s.coverageAmount) },
            { key: 'eligible', header: 'Eligible types', render: (s) => s.eligibleEmploymentTypes ?? 'All' },
            { key: 'active', header: 'Status', render: (s) => <StatusBadge status={s.active ? 'ACTIVE' : 'INACTIVE'} /> },
          ]} />
      </Card>
      {f && (
        <Modal wide title={f.id ? `Edit ${f.code}` : 'New insurance scheme'} onClose={() => setF(null)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setF(null)}>Cancel</button>
            <button className="btn btn-primary" disabled={busy} onClick={() => void save()}>Save</button>
          </>
        }>
          <div className="form-grid">
            <TextField label="Code" required disabled={!!f.id} value={f.code} onChange={(v) => setF({ ...f, code: v })} />
            <TextField label="Name (English)" required value={f.name} onChange={(v) => setF({ ...f, name: v })} />
            <TextField label="Name (Sinhala)" value={f.nameSi} onChange={(v) => setF({ ...f, nameSi: v })} />
            <TextField label="Name (Tamil)" value={f.nameTa} onChange={(v) => setF({ ...f, nameTa: v })} />
            <SelectField label="Type" value={f.schemeType} onChange={(v) => setF({ ...f, schemeType: v })} placeholder={null}
              options={[{ value: 'AGRAHARA', label: 'Agrahara' }, { value: 'OTHER', label: 'Other' }]} />
            <MasterDataSelect category="INSURANCE_CATEGORY" label="Category" value={f.category} onChange={(v) => setF({ ...f, category: v })} />
            <TextField label="Provider" value={f.provider} onChange={(v) => setF({ ...f, provider: v })} />
            <TextField label="Coverage amount (LKR)" type="number" value={f.coverageAmount} onChange={(v) => setF({ ...f, coverageAmount: v })} />
            <TextField label="Employee premium (LKR / month)" type="number" value={f.employeePremium} onChange={(v) => setF({ ...f, employeePremium: v })} />
            <TextField label="Employer premium (LKR / month)" type="number" value={f.employerPremium} onChange={(v) => setF({ ...f, employerPremium: v })} />
            <TextField label="Eligible employment types" value={f.eligibleEmploymentTypes} onChange={(v) => setF({ ...f, eligibleEmploymentTypes: v.toUpperCase() })} help="Comma-separated codes, e.g. PERMANENT,SECONDMENT; blank = all" />
            <TextField label="Minimum service (months)" type="number" value={f.minServiceMonths} onChange={(v) => setF({ ...f, minServiceMonths: v })} />
            <TextArea className="span-2" label="Eligibility notes" value={f.eligibilityNotes} onChange={(v) => setF({ ...f, eligibilityNotes: v })} />
          </div>
          <div className="row" style={{ marginTop: 12 }}>
            <Checkbox label="Recover employee premium through payroll" checked={f.payrollDeduction} onChange={(v) => setF({ ...f, payrollDeduction: v })} />
            <Checkbox label="Claims handled in HRMS" checked={f.claimsEnabled} onChange={(v) => setF({ ...f, claimsEnabled: v })} />
            <Checkbox label="Active" checked={f.active} onChange={(v) => setF({ ...f, active: v })} />
          </div>
        </Modal>
      )}
    </>
  );
}
