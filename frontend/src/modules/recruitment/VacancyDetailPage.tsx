import { useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { useApi } from '@/hooks/useApi';
import { api, errorMessage } from '@/api/client';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, DL, Loading, PageHeader, StatusBadge, Tabs } from '@/components/ui';
import { fmtDate } from '@/components/DataTable';
import type { Vacancy } from './types';
import { draftFrom, VacancyForm } from './VacancyForm';
import { ApplicantsTab, ExamsTab, InterviewsTab, LettersTab, SelectionTab } from './VacancyTabs';

type TabKey = 'overview' | 'applicants' | 'exams' | 'interviews' | 'selection' | 'letters';

const NEXT: Record<string, { to: string; label: string }[]> = {
  DRAFT: [{ to: 'OPEN', label: 'Open' }, { to: 'CANCELLED', label: 'Cancel' }],
  OPEN: [{ to: 'CLOSED', label: 'Close applications' }, { to: 'CANCELLED', label: 'Cancel' }],
  CLOSED: [{ to: 'OPEN', label: 'Re-open' }, { to: 'CANCELLED', label: 'Cancel' }],
};

export default function VacancyDetailPage() {
  const { id } = useParams();
  const [params, setParams] = useSearchParams();
  const tab = (params.get('tab') as TabKey) || 'overview';
  const { can } = useAuth();
  const toast = useToast();
  const manage = can('REC_MANAGE');
  const { data: v, error, reload, setData } = useApi<Vacancy>(`/recruitment/vacancies/${id}`);
  const [editing, setEditing] = useState(false);

  if (error) return <Alert kind="danger">{error}</Alert>;
  if (!v) return <Loading />;

  const setStatus = async (status: string) => {
    if (status === 'CANCELLED' && !window.confirm('Cancel this vacancy?')) return;
    try {
      const res = await api.post<Vacancy>(`/recruitment/vacancies/${v.id}/status`, { status });
      setData(res.data);
      toast(`Vacancy is now ${res.data.status.toLowerCase()}`, 'success');
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  return (
    <>
      <PageHeader eyebrow={`Vacancy ${v.referenceNo}`} title={v.title} subtitle={<><StatusBadge status={v.status} /> {v.designationName} · {v.orgUnitName}</>}
        actions={manage && (
          <div className="row">
            {(NEXT[v.status] ?? []).map((n) => (
              <button key={n.to} className={`btn ${n.to === 'OPEN' ? 'btn-gold' : 'btn-outline'}`} onClick={() => void setStatus(n.to)}>{n.label}</button>
            ))}
            {v.status !== 'FILLED' && v.status !== 'CANCELLED' && <button className="btn btn-outline" onClick={() => setEditing(true)}>Edit</button>}
          </div>
        )} />
      <Tabs<TabKey> value={tab} onChange={(k) => setParams({ tab: k })} tabs={[
        { key: 'overview', label: 'Overview' }, { key: 'applicants', label: `Applicants (${v.applicants})` },
        { key: 'exams', label: 'Examinations' }, { key: 'interviews', label: 'Interviews' },
        { key: 'selection', label: 'Selection' }, { key: 'letters', label: 'Correspondence' },
      ]} />
      {tab === 'overview' && (
        <div className="grid grid-2">
          <Card title="Vacancy">
            <DL items={[
              ['Reference', v.referenceNo], ['Designation', v.designationName], ['Unit', v.orgUnitName], ['Posts', v.numPosts],
              ['Opening date', fmtDate(v.openingDate)], ['Closing date', fmtDate(v.closingDate)],
              ['Ranking weights', `Examinations ${v.examWeight}% · Interview ${v.interviewWeight}%`],
              ['Created', `${fmtDate(v.createdAt)} by ${v.createdBy ?? '—'}`],
            ]} />
            {v.description && <><h4>Description</h4><p style={{ whiteSpace: 'pre-wrap' }}>{v.description}</p></>}
            {v.requirements && <><h4>Requirements</h4><p style={{ whiteSpace: 'pre-wrap' }}>{v.requirements}</p></>}
          </Card>
          <Card title="Application pipeline">
            <DL items={Object.entries(v.applicantsByStatus).map(([k, n]) => [k.replace(/_/g, ' ').toLowerCase(), n] as [string, number])} />
            {v.applicants === 0 && <div className="faint">No applications recorded yet.</div>}
          </Card>
        </div>
      )}
      {tab === 'applicants' && <ApplicantsTab vacancy={v} onChanged={reload} />}
      {tab === 'exams' && <ExamsTab vacancy={v} />}
      {tab === 'interviews' && <InterviewsTab vacancy={v} />}
      {tab === 'selection' && <SelectionTab vacancy={v} onChanged={reload} />}
      {tab === 'letters' && <LettersTab vacancy={v} />}
      {editing && (
        <VacancyForm id={v.id} initial={draftFrom(v)} onClose={() => setEditing(false)} onSaved={(nv) => {
          setEditing(false);
          setData(nv);
          toast('Vacancy saved', 'success');
        }} />
      )}
    </>
  );
}
