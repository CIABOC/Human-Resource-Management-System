import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Plus } from 'lucide-react';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, PageHeader, SelectField, StatusBadge, Tabs } from '@/components/ui';
import { DataTable, fmtDate } from '@/components/DataTable';
import type { Vacancy, VacancySuggestion } from './types';
import { VacancyDraft, VacancyForm } from './VacancyForm';

const EMPTY: VacancyDraft = { title: '', designationId: '', orgUnitCode: '', numPosts: '1' };

export default function VacanciesPage() {
  const { t } = useTranslation();
  const nav = useNavigate();
  const toast = useToast();
  const { can } = useAuth();
  const [tab, setTab] = useState<'vacancies' | 'suggestions'>('vacancies');
  const [status, setStatus] = useState('');
  const [form, setForm] = useState<VacancyDraft | null>(null);
  const { data, loading, error } = useApi<Vacancy[]>('/recruitment/vacancies', { status });
  const sugg = useApi<VacancySuggestion[]>(tab === 'suggestions' ? '/recruitment/vacancies/suggestions' : null);
  const manage = can('REC_MANAGE');

  return (
    <>
      <PageHeader eyebrow={t('rec.section', 'Recruitment')} title={t('rec.vacancies', 'Vacancies')}
        subtitle="Recruitment vacancies identified from the approved cadre"
        actions={manage && <button className="btn btn-gold" onClick={() => setForm(EMPTY)}><Plus /> New vacancy</button>} />
      <Tabs tabs={[{ key: 'vacancies', label: 'Vacancies' }, { key: 'suggestions', label: 'Cadre-based suggestions' }]} value={tab} onChange={setTab} />
      {tab === 'vacancies' && (
        <Card pad={false} title={
          <div className="filters" style={{ margin: 0 }}>
            <SelectField label="Status" value={status} onChange={setStatus} placeholder="All"
              options={['DRAFT', 'OPEN', 'CLOSED', 'FILLED', 'CANCELLED'].map((s) => ({ value: s, label: s }))} />
          </div>
        }>
          {error && <Alert kind="danger">{error}</Alert>}
          <DataTable<Vacancy> rows={data} loading={loading} onRowClick={(v) => nav(`/recruitment/vacancies/${v.id}`)}
            columns={[
              { key: 'referenceNo', header: 'Reference' },
              { key: 'title', header: 'Title' },
              { key: 'designationName', header: 'Designation' },
              { key: 'orgUnitName', header: 'Unit' },
              { key: 'numPosts', header: 'Posts', align: 'right' },
              { key: 'applicants', header: 'Applications', align: 'right' },
              { key: 'appointed', header: 'Appointed', align: 'right' },
              { key: 'closingDate', header: 'Closes', render: (v) => fmtDate(v.closingDate) },
              { key: 'status', header: 'Status', render: (v) => <StatusBadge status={v.status} /> },
            ]} />
        </Card>
      )}
      {tab === 'suggestions' && (
        <Card pad={false} title="Vacant approved cadre not yet under recruitment">
          {sugg.error && <Alert kind="danger">{sugg.error}</Alert>}
          <DataTable<VacancySuggestion> rows={sugg.data} loading={sugg.loading} rowKey={(r) => `${r.designationId}-${r.orgUnitCode}`}
            columns={[
              { key: 'orgUnitName', header: 'Unit' },
              { key: 'designationName', header: 'Designation' },
              { key: 'approved', header: 'Approved', align: 'right' },
              { key: 'occupied', header: 'Occupied', align: 'right' },
              { key: 'vacant', header: 'Vacant', align: 'right' },
              { key: 'underRecruitment', header: 'Under recruitment', align: 'right' },
              { key: 'suggestedPosts', header: 'Suggested', align: 'right', render: (r) => <strong>{r.suggestedPosts}</strong> },
              { key: 'act', header: '', render: (r) => manage && r.suggestedPosts > 0 && (
                <button className="btn btn-outline btn-sm" onClick={() => setForm({
                  title: r.designationName, designationId: String(r.designationId), orgUnitCode: r.orgUnitCode, numPosts: String(r.suggestedPosts),
                })}>Create vacancy</button>
              ) },
            ]} />
        </Card>
      )}
      {form && (
        <VacancyForm initial={form} onClose={() => setForm(null)} onSaved={(v) => {
          setForm(null);
          toast(`Vacancy ${v.referenceNo} created`, 'success');
          nav(`/recruitment/vacancies/${v.id}`);
        }} />
      )}
    </>
  );
}
