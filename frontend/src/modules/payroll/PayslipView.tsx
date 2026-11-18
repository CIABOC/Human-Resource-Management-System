import { fmtDate, fmtMoney } from '@/components/DataTable';
import { Alert } from '@/components/ui';
import type { LineDetail, LineItem } from './types';

function Section({ title, items, total, totalLabel }: { title: string; items: LineItem[]; total?: number; totalLabel?: string }) {
  if (items.length === 0 && total === undefined) return null;
  return (
    <table className="table" style={{ marginBottom: 12 }}>
      <thead>
        <tr><th>{title}</th><th className="num">Amount (LKR)</th></tr>
      </thead>
      <tbody>
        {items.map((i) => (
          <tr key={i.id}><td>{i.description}</td><td className="num">{fmtMoney(i.amount)}</td></tr>
        ))}
        {total !== undefined && (
          <tr><td style={{ fontWeight: 700 }}>{totalLabel}</td><td className="num" style={{ fontWeight: 700 }}>{fmtMoney(total)}</td></tr>
        )}
      </tbody>
    </table>
  );
}

/** Itemised payslip; print-friendly (sidebar and actions are hidden by the theme's print styles). */
export function PayslipView({ d }: { d: LineDetail }) {
  const earnings = d.items.filter((i) => i.componentType === 'EARNING');
  const deductions = d.items.filter((i) => i.componentType === 'DEDUCTION');
  const employer = d.items.filter((i) => i.componentType === 'EMPLOYER');
  return (
    <div className="payslip">
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 14 }}>
        <div>
          <div className="eyebrow">Commission to Investigate Allegations of Bribery or Corruption</div>
          <h2 style={{ margin: '4px 0' }}>Payslip — {d.period}</h2>
          <div className="muted">{d.payDate ? `Pay date ${fmtDate(d.payDate)}` : ''} {d.runStatus !== 'FINALIZED' && <span className="badge badge-warning">{d.runStatus}</span>}</div>
        </div>
        <div style={{ textAlign: 'right' }}>
          <div style={{ fontWeight: 700 }}>{d.line.employeeName}</div>
          <div className="mono">{d.line.employeeNo}</div>
          <div className="muted">{d.line.designation ?? ''}{d.line.orgUnitCode ? ` · ${d.line.orgUnitCode}` : ''}</div>
          <div className="muted">{d.line.scaleCode ? `Scale ${d.line.scaleCode}, step ${d.line.step}` : ''}</div>
          <div className="muted">{d.bankCode ? `${d.bankCode} ${d.bankBranch ?? ''} ${d.bankAccountNo ?? ''}` : ''}</div>
        </div>
      </div>
      {d.line.warnings && <Alert kind="warning">{d.line.warnings}</Alert>}
      <div className="grid grid-2">
        <Section title="Earnings" items={earnings} total={d.line.grossPay} totalLabel="Gross pay" />
        <Section title="Deductions" items={deductions} total={d.line.totalDeductions} totalLabel="Total deductions" />
      </div>
      <div className="card kpi navy" style={{ margin: '6px 0 14px' }}>
        <div className="label">Net pay</div>
        <div className="value">LKR {fmtMoney(d.line.netPay)}</div>
      </div>
      {employer.length > 0 && <Section title="Employer contributions (not deducted from pay)" items={employer} />}
      <div className="faint" style={{ fontSize: 12 }}>This is a computer-generated payslip from CIABOC HRMS.</div>
    </div>
  );
}
