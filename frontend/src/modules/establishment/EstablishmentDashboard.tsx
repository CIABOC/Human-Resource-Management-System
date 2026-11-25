import { Link } from 'react-router-dom';
import { AlarmClock, BellRing, ChevronRight, Inbox, Settings2 } from 'lucide-react';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { Alert, Badge, Card, Empty, KpiCard, Loading, PageHeader } from '@/components/ui';
import { DataTable, fmtDate } from '@/components/DataTable';
import { modules } from '../registry';

interface DivisionHealth { code: string; name: string; approved: number; filled: number; acting: number }
interface VacancyAlert { vacancyRef?: string; post: string; designation?: string; orgUnitName?: string; trigger: string; vacancyDate?: string; level: string; status?: string; link?: string }
interface DueItem { type: string; title: string; subject?: string; dueDate?: string; link?: string }
interface Dashboard {
  asOf: string; approved: number; filled: number; acting: number; vacant: number; excess: number; forecast: number;
  pendingChanges: number; draftChanges: number; pendingCategorizations: number; divisions: DivisionHealth[];
  vacancyAlerts: VacancyAlert[]; dueSoon: DueItem[];
}
interface InboxItem { id: number; title: string; processCode: string; requesterName?: string; currentStageName?: string; submittedAt?: string; dueAt?: string; overdue?: boolean; link?: string }

/** Matches configurable masters and rule sets for the Configuration Center (D-5). */
const CONFIG = /(polic|scheme|rule|workflow|kpi|rating|provider|template|master-data|notifications|settings|config|calendar|holiday)/i;
const LEVEL_TONE: Record<string, 'danger' | 'warning' | 'gold' | 'info'> = { OPEN: 'danger', '30': 'danger', '60': 'warning', '90': 'gold' };

/** Establishment dashboard (spec §4.1, D-1..D-7). */
export default function EstablishmentDashboard() {
  const { can } = useAuth();
  const { data, loading, error } = useApi<Dashboard>('/establishment/dashboard');
  const inbox = useApi<InboxItem[]>(can('WFL_APPROVE') ? '/workflow/inbox' : null);
  const overdue = (inbox.data ?? []).filter((i) => i.overdue).length;
  const allowed = (anyOf?: string[]) => !anyOf || anyOf.length === 0 || anyOf.some((p) => can(p));
  const configLinks = modules.flatMap((m) => m.nav).filter((n) => CONFIG.test(n.path) && allowed(n.anyOf))
    .filter((n, i, a) => a.findIndex((x) => x.path === n.path) === i);

  if (loading && !data) return <Loading />;
  if (error) return <Alert kind="danger">{error}</Alert>;
  if (!data) return null;
  const occ = data.filled + data.acting;

  return (
    <>
      <PageHeader eyebrow="Establishment & Cadre" title="Establishment dashboard" subtitle={`Live cadre position as at ${fmtDate(data.asOf)} across all active divisions.`} />
      <div className="grid grid-4" style={{ marginBottom: 14 }}>
        <KpiCard label="Approved cadre" value={data.approved} tone="navy" hint="Excludes statutory posts held outside the cadre" />
        <KpiCard label="Filled (incl. acting)" value={occ} hint={`${data.filled} substantive · ${data.acting} acting`} tone="green" />
        <KpiCard label="Vacant" value={data.vacant} hint={data.excess ? `${data.excess} excess holders` : undefined} tone="red" />
        <KpiCard label="Forecast vacancies" value={data.forecast} hint="Within the alert window" tone="gold" />
      </div>
      <div className="grid grid-4" style={{ marginBottom: 18 }}>
        <Link to="/establishment/changes" className="kpi-link"><KpiCard label="Cadre / structure changes pending" value={data.pendingChanges} hint={data.draftChanges ? `${data.draftChanges} drafts` : undefined} /></Link>
        <Link to="/establishment/org-chart" className="kpi-link"><KpiCard label="Categorization batches pending" value={data.pendingCategorizations} /></Link>
        <Link to="/approvals" className="kpi-link"><KpiCard label="Awaiting my approval" value={inbox.data?.length ?? '—'} tone="gold" /></Link>
        <Link to="/approvals" className="kpi-link"><KpiCard label="Overdue (SLA)" value={inbox.data ? overdue : '—'} tone={overdue ? 'red' : 'green'} /></Link>
      </div>

      <div className="grid grid-2">
        <Card title="Cadre health by division" actions={<Link to="/establishment/cadre" className="btn btn-ghost btn-sm">Details <ChevronRight /></Link>}>
          <div className="stack" style={{ gap: 10 }}>
            {data.divisions.length === 0 && <Empty text="No divisions." />}
            {data.divisions.map((d) => {
              const o = d.filled + d.acting;
              const pct = d.approved ? Math.round((o / d.approved) * 100) : 0;
              const w = (v: number) => `${d.approved ? Math.min(100, (v / d.approved) * 100) : 0}%`;
              return (
                <div key={d.code}>
                  <div className="row between" style={{ fontSize: 13 }}>
                    <span style={{ fontWeight: 600 }}>{d.name}</span>
                    <span className="muted">{o} / {d.approved} <b style={{ color: pct < 70 ? 'var(--danger)' : pct > 100 ? 'var(--warning)' : 'var(--success)' }}>{pct}%</b></span>
                  </div>
                  <div className={`util ${o > d.approved ? 'over' : ''}`} style={{ height: 8 }}><span className="f" style={{ width: w(d.filled) }} /><span className="a" style={{ width: w(d.acting) }} /></div>
                </div>
              );
            })}
            <div className="faint" style={{ fontSize: 11.5 }}><span className="cat-dot" style={{ background: 'var(--navy-700)' }} /> substantive &nbsp; <span className="cat-dot" style={{ background: 'var(--gold-500)' }} /> acting</div>
          </div>
        </Card>

        <Card title={<><Inbox size={16} style={{ verticalAlign: -3 }} /> Awaiting my approval</>} actions={<Link to="/approvals" className="btn btn-ghost btn-sm">Inbox <ChevronRight /></Link>}>
          {!can('WFL_APPROVE') ? <p className="muted">You do not approve requests.</p> : inbox.loading && !inbox.data ? <Loading /> : (
            (inbox.data ?? []).length === 0 ? <Empty text="Nothing is waiting for you." /> : (
              <div className="stack" style={{ gap: 8 }}>
                {(inbox.data ?? []).slice(0, 8).map((i) => (
                  <Link key={i.id} to={`/approvals/${i.id}`} className="oc-person" style={{ textDecoration: 'none', color: 'inherit' }}>
                    <span style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontWeight: 600 }}>{i.title}</div>
                      <div className="faint" style={{ fontSize: 11.5 }}>{i.processCode.replace(/_/g, ' ').toLowerCase()} · {i.requesterName ?? ''}{i.currentStageName ? ` · ${i.currentStageName}` : ''}</div>
                    </span>
                    {i.overdue ? <Badge tone="danger">overdue</Badge> : i.dueAt ? <span className="faint" style={{ fontSize: 11.5 }}>due {fmtDate(i.dueAt)}</span> : null}
                  </Link>
                ))}
              </div>
            )
          )}
        </Card>
      </div>

      <div style={{ marginTop: 18 }}>
        <Card title={<><BellRing size={16} style={{ verticalAlign: -3 }} /> Upcoming vacancy alerts</>} pad={false}>
          <DataTable<VacancyAlert> rows={data.vacancyAlerts} rowKey={(r) => `${r.vacancyRef ?? r.post}-${r.level}`} empty="No upcoming vacancies within the alert window." columns={[
            { key: 'post', header: 'Post', render: (r) => (r.link ? <Link to={r.link}><strong>{r.post}</strong></Link> : <strong>{r.post}</strong>) },
            { key: 'orgUnitName', header: 'Division' },
            { key: 'trigger', header: 'Trigger', render: (r) => r.trigger.replace(/_/g, ' ').toLowerCase() },
            { key: 'vacancyDate', header: 'Vacancy date', render: (r) => (r.vacancyDate ? fmtDate(r.vacancyDate) : '—') },
            { key: 'level', header: 'Alert', render: (r) => <Badge tone={LEVEL_TONE[r.level] ?? 'info'}>{r.level === 'OPEN' ? 'Open' : `${r.level}-day`}</Badge> },
          ]} />
        </Card>
      </div>

      <div className="grid grid-2" style={{ marginTop: 18 }}>
        <Card title={<><AlarmClock size={16} style={{ verticalAlign: -3 }} /> Expiring &amp; due soon</>} pad={false}>
          <DataTable<DueItem> rows={data.dueSoon} rowKey={(r) => `${r.type}-${r.title}-${r.dueDate}`} empty="Nothing due soon." columns={[
            { key: 'dueDate', header: 'Due', render: (r) => <span className="nowrap">{r.dueDate ? fmtDate(r.dueDate) : '—'}</span> },
            { key: 'type', header: 'Type', render: (r) => <Badge>{r.type.replace(/_/g, ' ').toLowerCase()}</Badge> },
            { key: 'title', header: 'Item', render: (r) => (r.link ? <Link to={r.link}>{r.title}</Link> : r.title) },
            { key: 'subject', header: 'Subject' },
          ]} />
        </Card>
        <Card title={<><Settings2 size={16} style={{ verticalAlign: -3 }} /> Configuration Center</>}>
          {configLinks.length === 0 ? <p className="muted">You do not manage any configuration.</p> : (
            <div className="grid grid-2" style={{ gap: 8 }}>
              {configLinks.map((n) => {
                const Icon = n.icon;
                return (
                  <Link key={n.path} to={n.path} className="oc-person" style={{ textDecoration: 'none', color: 'inherit' }}>
                    {Icon && <Icon size={16} />}<span style={{ flex: 1 }}>{n.fallback}</span><ChevronRight size={14} className="faint" />
                  </Link>
                );
              })}
            </div>
          )}
        </Card>
      </div>
    </>
  );
}
