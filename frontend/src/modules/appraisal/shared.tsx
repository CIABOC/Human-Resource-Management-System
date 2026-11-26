import { createContext, ReactNode, useContext } from 'react';
import { NavLink, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Lock } from 'lucide-react';
import { useApi } from '@/hooks/useApi';
import { Alert, Loading, PageHeader } from '@/components/ui';
import type { AprContext, Appraisal, MyDashboard } from './types';
import './appraisal.css';

/* ------------------------------------------------------------------ role context */

const Ctx = createContext<{ ctx: AprContext | null; reload: () => void }>({ ctx: null, reload: () => undefined });
export const useAprContext = () => useContext(Ctx);

interface SubLink { to: string; label: string; badge?: number }

function groups(c: AprContext): { title: string; links: SubLink[] }[] {
  const out: { title: string; links: SubLink[] }[] = [];
  if (c.employeeId) {
    out.push({
      title: 'My appraisal', links: [
        { to: '/appraisal/my', label: 'Dashboard' },
        { to: '/appraisal/agreement', label: 'Agreement' },
        { to: '/appraisal/kpis', label: 'My KPIs' },
        { to: '/appraisal/self-assessment', label: 'Self-assessment' },
        { to: '/appraisal/feedback', label: 'Check-ins & feedback' },
        { to: '/appraisal/appeals', label: 'Appeals' },
      ],
    });
  }
  if (c.supervisor || c.manage) {
    out.push({
      title: 'My team', links: [
        { to: '/appraisal/team', label: 'Supervisor dashboard', badge: c.pendingVerifications },
        ...(c.employeeId ? [] : [{ to: '/appraisal/feedback', label: 'Check-ins & feedback' }]),
        { to: '/appraisal/special-cases', label: 'Special cases' },
        { to: '/appraisal/delegation', label: 'Delegation & reopening' },
      ],
    });
  }
  if (c.reviewer) out.push({ title: 'Reviewing officer', links: [{ to: '/appraisal/approvals', label: 'Approvals', badge: c.pendingApprovals }] });
  if (c.overview) out.push({ title: 'Management', links: [{ to: '/appraisal/management', label: 'Management dashboard' }] });
  if (c.manage || c.policyApprove || c.admin) {
    const links: SubLink[] = [{ to: '/appraisal/config', label: 'Configuration' }];
    if (c.manage) links.push({ to: '/appraisal/appeals-review', label: 'Appeals review' });
    if (c.admin) links.push({ to: '/appraisal/sync', label: 'HRMS sync' });
    out.push({ title: 'Administration', links });
  }
  return out;
}

/** Page frame of every appraisal screen: header, the role-based sub-navigation of the wireframes, and the content. */
export function AppraisalShell({ title, subtitle, actions, children }: { title: string; subtitle?: ReactNode; actions?: ReactNode; children: ReactNode }) {
  const { t } = useTranslation();
  const { data, error, reload } = useApi<AprContext>('/appraisal/context');
  return (
    <Ctx.Provider value={{ ctx: data, reload }}>
      <PageHeader eyebrow={t('apr.title', 'Performance appraisal')} title={title} subtitle={subtitle} actions={actions} />
      <div className="apr-layout">
        <nav className="apr-subnav" aria-label="Performance appraisal">
          {data && groups(data).map((g) => (
            <div key={g.title} className="apr-subnav-group">
              <div className="apr-subnav-title">{g.title}</div>
              {g.links.map((l) => (
                <NavLink key={l.to + g.title} to={l.to} className={({ isActive }) => `apr-subnav-link ${isActive ? 'active' : ''}`}>
                  <span>{l.label}</span>
                  {!!l.badge && <span className="apr-count">{l.badge}</span>}
                </NavLink>
              ))}
            </div>
          ))}
        </nav>
        <div className="apr-main">
          {error && <Alert kind="danger">{error}</Alert>}
          {data ? children : !error && <Loading />}
        </div>
      </div>
    </Ctx.Provider>
  );
}

/* ------------------------------------------------------------------ chips, trail, panels */

export function Chip({ children, active, tone }: { children: ReactNode; active?: boolean; tone?: 'danger' | 'success' | 'info' | 'muted' }) {
  return <span className={`apr-chip ${active ? 'active' : ''} ${tone ? `tone-${tone}` : ''}`}>{children}</span>;
}

export const APPRAISAL_STEPS: [string, string][] = [
  ['DRAFT', 'Draft'], ['SUBMITTED', 'Submitted'], ['VERIFIED', 'Verified'], ['APPROVED', 'Approved'], ['ACKNOWLEDGED', 'Acknowledged'], ['FINALIZED', 'Finalized'],
];

export const AGREEMENT_STEPS: [string, string][] = [
  ['DRAFT', 'Draft'], ['SUBMITTED', 'Submitted'], ['SUPERVISOR_SIGNED', 'Supervisor signed'], ['REVIEWER', 'Reviewing officer approved'], ['FINAL', 'Final'],
];

/** "Status: Draft > Submitted > ..." with the current step highlighted and earlier steps marked done. */
export function StatusTrail({ steps, current, label = 'Status' }: { steps: [string, string][]; current: string; label?: string }) {
  const cur = current === 'FINAL' ? steps.length - 1 : steps.findIndex(([k]) => k === current);
  return (
    <div className="apr-trail" aria-label={`${label}: ${steps[cur]?.[1] ?? 'not started'}`}>
      <span className="apr-trail-label">{label}:</span>
      {steps.map(([k, l], i) => (
        <span key={k} className="apr-trail-step">
          {i > 0 && <span className="apr-trail-sep" aria-hidden>›</span>}
          <span className={`apr-chip ${i === cur ? 'active' : i < cur ? 'done' : ''}`} aria-current={i === cur ? 'step' : undefined}>{l}</span>
        </span>
      ))}
    </div>
  );
}

export function Confidential({ text = 'Confidential: you and authorized officers only' }: { text?: string }) {
  return <Chip><Lock size={11} aria-hidden /> {text}</Chip>;
}

export function SummaryCard({ label, value, hint }: { label: string; value: ReactNode; hint?: ReactNode }) {
  return (
    <div className="apr-card">
      <div className="apr-card-label">{label}</div>
      <div className="apr-card-value">{value}</div>
      {hint && <div className="apr-card-hint">{hint}</div>}
    </div>
  );
}

export function CalcPanel({ kpiWeight, behaviourWeight }: { kpiWeight?: number | null; behaviourWeight?: number | null }) {
  return (
    <section className="apr-panel">
      <h4>How the result is calculated</h4>
      <p>
        Overall KPI achievement = verified achievement of each KPI × its weight, added together. KPI weights total 100%. The final
        score combines the KPI result and the behavioural result using the approved weighting (KPI {fmtPct(kpiWeight, 0)}, behaviour{' '}
        {fmtPct(behaviourWeight, 0)}). The behavioural result is the average of the applicable ratings on the 1–5 scale expressed out of
        100; N/A criteria are left out. The system calculates it and it cannot be edited by hand.
      </p>
    </section>
  );
}

export function Note({ children, kind = 'info' }: { children: ReactNode; kind?: 'info' | 'danger' | 'success' }) {
  return <div className={`apr-note apr-note-${kind}`}>{children}</div>;
}

export function Bar({ value, tone = 'gold' }: { value?: number | null; tone?: 'gold' | 'navy' }) {
  const v = Math.max(0, Math.min(100, Number(value ?? 0)));
  return (
    <span className={`apr-bar tone-${tone}`} role="img" aria-label={`${fmtPct(value)}`}>
      <span style={{ width: `${v}%` }} />
    </span>
  );
}

export const fmtPct = (v?: number | null, digits = 1) =>
  v === null || v === undefined || Number.isNaN(Number(v)) ? '—' : `${Number(v).toLocaleString('en-GB', { maximumFractionDigits: digits })}%`;
export const fmtNum = (v?: number | null) => (v === null || v === undefined ? '—' : Number(v).toLocaleString('en-GB', { maximumFractionDigits: 2 }));
export const pretty = (s?: string | null) => (s ? s.charAt(0) + s.slice(1).toLowerCase().replace(/_/g, ' ') : '');
export const fmtDayMonth = (v?: string | null) => (v ? new Date(v).toLocaleDateString('en-GB', { day: '2-digit', month: 'short' }) : '—');

/* ------------------------------------------------------------------ data hooks */

/**
 * The appraisal a screen works on: ?appraisal=<id> (supervisors, reviewing officers, HR), otherwise the signed-in
 * employee's appraisal for the current period.
 */
export function useTargetAppraisal() {
  const [params] = useSearchParams();
  const id = params.get('appraisal');
  const mine = useApi<MyDashboard>(id ? null : '/appraisal/my');
  const other = useApi<Appraisal>(id ? `/appraisal/appraisals/${id}` : null);
  const data = id ? other.data : mine.data?.appraisal ?? null;
  const setData = (a: Appraisal) => (id ? other.setData(a) : mine.setData((m) => (m ? { ...m, appraisal: a } : m)));
  return {
    appraisal: data, loading: id ? other.loading : mine.loading, error: id ? other.error : mine.error,
    reload: id ? other.reload : mine.reload, setAppraisal: setData, foreign: !!id,
  };
}

export function NoAppraisal() {
  return (
    <Alert kind="info">
      You do not have an appraisal for the current period yet. Appraisals are started by HR at the beginning of each appraisal period.
    </Alert>
  );
}
