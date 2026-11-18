import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Download, Search, UserPlus } from 'lucide-react';
import { download } from '@/api/client';
import type { Page } from '@/api/types';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { Alert, Card, PageHeader, StatusBadge } from '@/components/ui';
import { DataTable, fmtDate } from '@/components/DataTable';
import { DesignationSelect, MasterDataSelect, OrgUnitSelect, useMasterDataLabels } from '@/components/pickers';
import type { EmployeeListItem } from './types';

/** Employee search, restricted by the user's data scope (FR-EMP-010). */
export default function EmployeeListPage() {
  const { t } = useTranslation();
  const nav = useNavigate();
  const { can } = useAuth();
  const [q, setQ] = useState('');
  const [debounced, setDebounced] = useState('');
  const [f, setF] = useState({ orgUnitCode: '', designationId: '', status: '', category: '' });
  const [page, setPage] = useState(0);
  useEffect(() => {
    const id = setTimeout(() => { setDebounced(q); setPage(0); }, 350);
    return () => clearTimeout(id);
  }, [q]);
  const { data, loading, error } = useApi<Page<EmployeeListItem>>('/employees', { q: debounced, ...f, page, size: 25 });
  const statusLabel = useMasterDataLabels('EMPLOYEE_STATUS');
  const catLabel = useMasterDataLabels('EMPLOYEE_CATEGORY');
  const set = (k: keyof typeof f) => (v: string) => { setF({ ...f, [k]: v }); setPage(0); };

  return (
    <>
      <PageHeader eyebrow={t('nav.people', 'People')} title={t('emp.title', 'Employees')} subtitle="The single authoritative employee record. Results are limited to your data scope."
        actions={
          <>
            {can('RPT_VIEW') && <button className="btn btn-outline" onClick={() => void download('/reports/EMP_DETAILS/export?format=xlsx', { method: 'post', body: { orgUnitCode: f.orgUnitCode || null, status: f.status || null, designationId: f.designationId ? Number(f.designationId) : null } })}><Download /> Export</button>}
            {can('EMP_MANAGE') && <button className="btn btn-gold" onClick={() => nav('/employees/new')}><UserPlus /> Register employee</button>}
          </>
        } />
      <div className="filters">
        <div className="field">
          <label htmlFor="emp-q">Search</label>
          <div className="search">
            <Search />
            <input id="emp-q" className="input" placeholder="Name, employee no. or NIC" value={q} onChange={(e) => setQ(e.target.value)} />
          </div>
        </div>
        <OrgUnitSelect label="Unit" value={f.orgUnitCode} onChange={set('orgUnitCode')} placeholder="— All —" />
        <DesignationSelect value={f.designationId} onChange={set('designationId')} placeholder="— All —" />
        <MasterDataSelect category="EMPLOYEE_STATUS" label="Status" value={f.status} onChange={set('status')} placeholder="— All —" />
        <MasterDataSelect category="EMPLOYEE_CATEGORY" label="Category" value={f.category} onChange={set('category')} placeholder="— All —" />
      </div>
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false}>
        <DataTable<EmployeeListItem> rows={data?.content} loading={loading} onRowClick={(e) => nav(`/employees/${e.id}`)}
          page={data?.page} totalPages={data?.totalPages} totalElements={data?.totalElements} onPage={setPage}
          columns={[
            { key: 'employeeNo', header: 'Emp. no', render: (e) => <span className="mono">{e.employeeNo}</span> },
            { key: 'nameWithInitials', header: 'Name', render: (e) => <div><div style={{ fontWeight: 600, color: 'var(--navy-800)' }}>{e.nameWithInitials}</div><div className="faint" style={{ fontSize: 12 }}>{e.fullName}</div></div> },
            { key: 'designation', header: 'Designation' },
            { key: 'orgUnitName', header: 'Unit' },
            { key: 'employeeCategory', header: 'Category', render: (e) => catLabel(e.employeeCategory) },
            { key: 'appointmentDate', header: 'Appointed', render: (e) => fmtDate(e.appointmentDate) },
            { key: 'status', header: 'Status', render: (e) => <StatusBadge status={e.status} label={statusLabel(e.status)} /> },
          ]} />
      </Card>
    </>
  );
}
