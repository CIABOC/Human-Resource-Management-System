import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { useApi } from '@/hooks/useApi';
import { Alert, Badge, Card, Empty, Loading, PageHeader } from '@/components/ui';
import { OrgUnitSelect } from '@/components/pickers';
import { isoDate, type TeamCalendar } from './types';

/** Manager self-service: month view of approved and pending leave in the user's scope (FR-EXT-004). */
export default function TeamCalendarPage() {
  const { t } = useTranslation();
  const nav = useNavigate();
  const [month, setMonth] = useState(() => { const d = new Date(); return new Date(d.getFullYear(), d.getMonth(), 1); });
  const [orgUnitCode, setOrgUnitCode] = useState('');
  const from = isoDate(month);
  const to = isoDate(new Date(month.getFullYear(), month.getMonth() + 1, 0));
  const { data, loading, error } = useApi<TeamCalendar>('/leave/team-calendar', { from, to, orgUnitCode });

  const days = useMemo(() => {
    const out: Date[] = [];
    for (let d = new Date(month); d.getMonth() === month.getMonth(); d = new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1)) out.push(d);
    return out;
  }, [month]);

  const people = useMemo(() => {
    const m = new Map<number, { no: string; name: string; unit?: string | null }>();
    (data?.entries ?? []).forEach((e) => m.set(e.employeeId, { no: e.employeeNo, name: e.employeeName, unit: e.orgUnitName }));
    return [...m.entries()].sort((a, b) => a[1].no.localeCompare(b[1].no));
  }, [data]);

  const holidays = new Set(data?.holidays ?? []);
  const shift = (n: number) => setMonth(new Date(month.getFullYear(), month.getMonth() + n, 1));
  const cell = (empId: number, iso: string) => (data?.entries ?? []).find((e) => e.employeeId === empId && e.startDate <= iso && e.endDate >= iso);

  return (
    <>
      <PageHeader eyebrow={t('nav.time', 'Time & attendance')} title={t('leave.teamCalendar', 'Team leave calendar')} subtitle="Approved and pending leave of employees in your area of responsibility" />
      <div className="filters">
        <button className="btn btn-outline" onClick={() => shift(-1)} aria-label="Previous month"><ChevronLeft /></button>
        <strong style={{ minWidth: 150, textAlign: 'center' }}>{month.toLocaleDateString('en-GB', { month: 'long', year: 'numeric' })}</strong>
        <button className="btn btn-outline" onClick={() => shift(1)} aria-label="Next month"><ChevronRight /></button>
        <OrgUnitSelect label="Unit" value={orgUnitCode} onChange={setOrgUnitCode} placeholder="All in my scope" />
        <span className="row"><Badge tone="success">Approved</Badge><Badge tone="gold">Pending</Badge><Badge tone="neutral">Weekend / holiday</Badge></span>
      </div>
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false}>
        {loading && !data ? <Loading /> : people.length === 0 ? <Empty text="No leave in this month" /> : (
          <div className="table-wrap">
            <table className="table" style={{ fontSize: 12 }}>
              <thead>
                <tr>
                  <th style={{ minWidth: 200 }}>Employee</th>
                  {days.map((d) => {
                    const iso = isoDate(d);
                    const off = d.getDay() === 0 || d.getDay() === 6 || holidays.has(iso);
                    return <th key={iso} style={{ textAlign: 'center', padding: '6px 2px', opacity: off ? 0.5 : 1 }} title={holidays.has(iso) ? 'Holiday' : undefined}>{d.getDate()}</th>;
                  })}
                </tr>
              </thead>
              <tbody>
                {people.map(([id, p]) => (
                  <tr key={id}>
                    <td><div style={{ fontWeight: 600 }}>{p.name}</div><div className="faint">{p.no}{p.unit ? ` · ${p.unit}` : ''}</div></td>
                    {days.map((d) => {
                      const iso = isoDate(d);
                      const e = cell(id, iso);
                      const off = d.getDay() === 0 || d.getDay() === 6 || holidays.has(iso);
                      const bg = e && !off ? (e.status === 'APPROVED' ? 'var(--success-bg, #d9f2e3)' : 'var(--gold-100, #f8ecc9)') : off ? 'var(--surface-2, #f1f3f7)' : undefined;
                      return (
                        <td key={iso} style={{ background: bg, padding: 2, textAlign: 'center', cursor: e ? 'pointer' : undefined }}
                          title={e ? `${e.leaveTypeName} (${e.status.toLowerCase()})${e.halfDay ? ' – half day' : ''}` : undefined}
                          onClick={e ? () => nav(`/leave/applications/${e.applicationId}`) : undefined}>
                          {e && !off ? e.leaveTypeCode.charAt(0) + (e.halfDay ? '½' : '') : ''}
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}
