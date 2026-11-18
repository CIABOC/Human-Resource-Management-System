import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Printer } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Card, PageHeader } from '@/components/ui';
import { DataTable, fmtDate, fmtMoney } from '@/components/DataTable';
import { PayslipView } from './PayslipView';
import type { LineDetail, PayslipItem } from './types';

/** Employee self-service payslips (own finalized payslips only). */
export default function MyPayslipsPage() {
  const { t } = useTranslation();
  const toast = useToast();
  const { data, loading, error } = useApi<PayslipItem[]>('/payroll/my/payslips');
  const [slip, setSlip] = useState<LineDetail | null>(null);

  const open = async (p: PayslipItem) => {
    try {
      const { data: d } = await api.get<LineDetail>(`/payroll/my/payslips/${p.lineId}`);
      setSlip(d);
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  return (
    <>
      <PageHeader eyebrow={t('nav.self', 'My space')} title={t('pay.myPayslips', 'My payslips')} subtitle="Payslips of finalized payroll periods"
        actions={slip && <button className="btn btn-outline" onClick={() => window.print()}><Printer /> Print</button>} />
      {error && <Alert kind="danger">{error}</Alert>}
      <div className="grid" style={{ gridTemplateColumns: slip ? 'minmax(260px, 1fr) 2fr' : '1fr' }}>
        <Card pad={false} className="no-print">
          <DataTable<PayslipItem> rows={data} loading={loading} onRowClick={(p) => void open(p)} rowKey={(p) => p.lineId} empty="No payslips yet"
            columns={[
              { key: 'period', header: 'Period', render: (p) => <span className="mono" style={{ fontWeight: 700 }}>{p.period}</span> },
              { key: 'payDate', header: 'Pay date', render: (p) => fmtDate(p.payDate) },
              { key: 'netPay', header: 'Net pay', align: 'right', render: (p) => fmtMoney(p.netPay) },
            ]} />
        </Card>
        {slip && <Card><PayslipView d={slip} /></Card>}
      </div>
    </>
  );
}
