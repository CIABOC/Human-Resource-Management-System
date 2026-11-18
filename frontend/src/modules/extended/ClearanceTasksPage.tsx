import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useApi } from '@/hooks/useApi';
import { Alert, Card, PageHeader } from '@/components/ui';
import { DataTable, fmtDate } from '@/components/DataTable';
import { employeeLabel } from '@/components/pickers';
import type { ClearanceTask } from './types';
import { titleCase } from './types';

/** Separation clearance items awaiting a role the current user holds. */
export default function ClearanceTasksPage() {
  const { t } = useTranslation();
  const nav = useNavigate();
  const { data, loading, error } = useApi<ClearanceTask[]>('/extended/clearance/my-tasks');
  return (
    <>
      <PageHeader title={t('extended.clearance', 'Clearance tasks')} subtitle="Offboarding clearance items assigned to your role" />
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false}>
        <DataTable<ClearanceTask>
          rows={data}
          loading={loading}
          rowKey={(r) => r.itemId}
          empty="Nothing awaiting your clearance"
          onRowClick={(r) => nav(`/extended/separations/${r.caseId}`)}
          columns={[
            { key: 'itemName', header: 'Clearance item' },
            { key: 'employee', header: 'Employee', render: (r) => employeeLabel(r.employee) },
            { key: 'unit', header: 'Unit', render: (r) => r.employee.orgUnitName ?? '—' },
            { key: 'separationType', header: 'Separation', render: (r) => titleCase(r.separationType) },
            { key: 'separationDate', header: 'Separation date', render: (r) => fmtDate(r.separationDate) },
            { key: 'role', header: 'Your role', render: (r) => titleCase(r.responsibleRole) },
          ]}
        />
      </Card>
    </>
  );
}
