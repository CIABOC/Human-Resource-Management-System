import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts';
import {
  Activity, ArrowUpRight, Award, Banknote, Briefcase, Building2, CalendarDays, ClipboardCheck, Clock, FileText, GraduationCap,
  Hourglass, LucideIcon, Plane, ScanSearch, Send, ShieldAlert, UserMinus, Users, UserX, Wallet,
} from 'lucide-react';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { Alert, Card, Empty, Loading } from '@/components/ui';
import './dashboard.css';

interface Tile { key: string; label: string; value: string; hint?: string; link?: string; tone: string }
interface Point { label: string; value: number; vacant?: number; [k: string]: unknown }
interface Chart { key: string; title: string; type: 'bar' | 'pie'; data: Point[] }

/* Categorical palette for part-to-whole charts, validated for the light surface (dataviz validate_palette.js:
   lightness, chroma, CVD and normal-vision separation all pass). Slots are assigned in this fixed order; a sixth
   and later category folds into "Other" (neutral grey) instead of inventing a colour. */
const SERIES = ['#2a5bb0', '#c9962e', '#1baf7a', '#6250d6', '#e87ba4'];
const OTHER = '#9aa3b5';
const OCCUPIED = SERIES[0];
const VACANT = SERIES[1];

const ICONS: [string, LucideIcon][] = [
  ['approvals', ClipboardCheck], ['my-requests', Send], ['headcount', Users], ['retirements', Hourglass], ['ext-retirements', Hourglass],
  ['cadre', Building2], ['vacancies', UserMinus], ['att-absent', UserX], ['att-late', Clock], ['att-review', ScanSearch],
  ['ben-', ShieldAlert], ['ext-', FileText], ['on-leave', Plane], ['leave-', CalendarDays], ['pay-', Wallet], ['my-net-pay', Banknote],
  ['per-', Award], ['rec-', Briefcase], ['trn-', GraduationCap],
];
const iconFor = (key: string): LucideIcon => ICONS.find(([p]) => key.startsWith(p))?.[1] ?? Activity;

const num = (v: unknown) => (typeof v === 'number' ? v : Number(v) || 0);
const fmt = (n: number) => n.toLocaleString('en-GB', { maximumFractionDigits: 1 });
/** ACTIVE -> Active, ON_LEAVE -> On leave; names that are not codes are left as they are. */
const pretty = (s: string) => (/^[A-Z0-9_]+$/.test(s) ? s.charAt(0) + s.slice(1).toLowerCase().replace(/_/g, ' ') : s);

export default function DashboardPage() {
  const { t } = useTranslation();
  const { me } = useAuth();
  const { data, loading, error } = useApi<{ tiles: Tile[]; charts: Chart[] }>('/dashboard');
  const hour = new Date().getHours();
  const greet = hour < 12 ? 'Good morning' : hour < 17 ? 'Good afternoon' : 'Good evening';
  const tiles = data?.tiles ?? [];
  const charts = data?.charts ?? [];
  const cadre = charts.find((c) => c.data[0]?.vacant !== undefined);
  const others = charts.filter((c) => c !== cadre);

  return (
    <>
      <Hero greet={`${greet}, ${me?.fullName?.split(' ')[0] ?? ''}`} sub={t('dashboard.sub', 'Your workforce at a glance')} tiles={tiles} />
      {error && <Alert kind="danger">{error}</Alert>}
      {loading && !data ? <Loading /> : (
        <div className="stack" style={{ gap: 20 }}>
          {tiles.length > 0 && (
            <div className="dash-tiles">
              {tiles.map((tile) => <KpiTile key={tile.key} tile={tile} />)}
            </div>
          )}
          {cadre && <CadreCard chart={cadre} />}
          {others.length > 0 && (
            <div className="grid grid-2">
              {others.map((c) => (
                <Card key={c.key} title={c.title} accent>
                  {c.data.length === 0 ? <Empty /> : c.type === 'pie' ? <Donut data={c.data} /> : <BarList data={c.data} />}
                </Card>
              ))}
            </div>
          )}
        </div>
      )}
    </>
  );
}

/* ------------------------------------------------------------------ hero */

function Hero({ greet, sub, tiles }: { greet: string; sub: string; tiles: Tile[] }) {
  const val = (k: string) => {
    const tile = tiles.find((x) => x.key === k);
    return tile ? num(tile.value.replace(/,/g, '')) : null;
  };
  const approved = val('cadre');
  const vacant = val('vacancies');
  const head = val('headcount');
  const pending = val('approvals');
  const fill = approved && vacant !== null ? Math.max(0, Math.min(1, (approved - vacant) / approved)) : null;
  const date = new Date().toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });

  return (
    <section className="dash-hero">
      <div className="dash-hero-text">
        <div className="eyebrow">{date}</div>
        <h1>{greet}</h1>
        <p>{sub}</p>
        <div className="dash-hero-stats">
          {head !== null && <div><b>{fmt(head)}</b><span>Employees in service</span></div>}
          {approved !== null && <div><b>{fmt(approved)}</b><span>Approved cadre</span></div>}
          {pending !== null && (
            <Link to="/approvals"><b>{fmt(pending)}</b><span>Awaiting my approval <ArrowUpRight size={13} /></span></Link>
          )}
        </div>
      </div>
      {fill !== null && <FillRing fill={fill} occupied={approved! - vacant!} approved={approved!} />}
    </section>
  );
}

function FillRing({ fill, occupied, approved }: { fill: number; occupied: number; approved: number }) {
  const r = 52;
  const c = 2 * Math.PI * r;
  return (
    <div className="dash-ring" role="img" aria-label={`Cadre fill rate ${Math.round(fill * 100)} percent: ${occupied} of ${approved} posts occupied`}>
      <svg viewBox="0 0 128 128" width="128" height="128" aria-hidden>
        <defs>
          <linearGradient id="ringGold" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stopColor="#ecca7f" />
            <stop offset="100%" stopColor="#c9952f" />
          </linearGradient>
        </defs>
        <circle cx="64" cy="64" r={r} fill="none" stroke="rgba(255,255,255,.12)" strokeWidth="12" />
        <circle cx="64" cy="64" r={r} fill="none" stroke="url(#ringGold)" strokeWidth="12" strokeLinecap="round"
          strokeDasharray={`${c * fill} ${c}`} transform="rotate(-90 64 64)" />
      </svg>
      <div className="dash-ring-label">
        <b>{Math.round(fill * 100)}%</b>
        <span>cadre filled</span>
      </div>
      <div className="dash-ring-cap">{fmt(occupied)} of {fmt(approved)} posts occupied</div>
    </div>
  );
}

/* ------------------------------------------------------------------ KPI tiles */

function KpiTile({ tile }: { tile: Tile }) {
  const Icon = iconFor(tile.key);
  const body = (
    <div className={`dash-tile tone-${tile.tone}`}>
      <span className="dash-tile-icon"><Icon /></span>
      <div className="dash-tile-main">
        <div className="dash-tile-label">{tile.label}</div>
        <div className="dash-tile-value">{/^\d+(\.\d+)?$/.test(tile.value) ? fmt(Number(tile.value)) : tile.value}</div>
        {tile.hint && <div className="dash-tile-hint">{tile.hint}</div>}
      </div>
      {tile.link && <ArrowUpRight className="dash-tile-go" aria-hidden />}
    </div>
  );
  return tile.link ? <Link to={tile.link} className="dash-tile-link">{body}</Link> : body;
}

/* ------------------------------------------------------------------ cadre: occupied vs vacant per unit */

function CadreCard({ chart }: { chart: Chart }) {
  const [all, setAll] = useState(false);
  const rows = useMemo(() => chart.data
    .map((d) => ({ label: d.label, occupied: num(d.value), vacant: num(d.vacant) }))
    .map((d) => ({ ...d, approved: d.occupied + d.vacant }))
    .filter((d) => d.approved > 0)
    .sort((a, b) => b.vacant - a.vacant || b.approved - a.approved), [chart.data]);
  const tot = rows.reduce((s, r) => ({ occupied: s.occupied + r.occupied, vacant: s.vacant + r.vacant }), { occupied: 0, vacant: 0 });
  const approved = tot.occupied + tot.vacant;
  const shown = all ? rows : rows.slice(0, 10);

  return (
    <Card accent title={
      <div>
        <h3>{chart.title}</h3>
        <div className="muted" style={{ fontSize: 12.5, marginTop: 2 }}>By unit, most vacancies first</div>
      </div>
    } actions={
      <div className="dash-legend">
        <span><i style={{ background: OCCUPIED }} />Occupied</span>
        <span><i style={{ background: VACANT }} />Vacant</span>
      </div>
    }>
      {rows.length === 0 ? <Empty /> : (
        <>
          <div className="dash-cadre-summary">
            <div><span>Approved</span><b>{fmt(approved)}</b></div>
            <div><span><i style={{ background: OCCUPIED }} />Occupied</span><b>{fmt(tot.occupied)}</b></div>
            <div><span><i style={{ background: VACANT }} />Vacant</span><b>{fmt(tot.vacant)}</b></div>
            <div><span>Fill rate</span><b>{approved ? Math.round((tot.occupied / approved) * 100) : 0}%</b></div>
          </div>
          <div className="dash-cadre-head" aria-hidden>
            <span>Unit</span><span>Occupied · vacant</span><span className="right">Filled</span>
          </div>
          <ul className="dash-cadre">
            {shown.map((r) => {
              const pct = Math.round((r.occupied / r.approved) * 100);
              return (
                <li key={r.label} title={`${r.label}: ${r.occupied} occupied, ${r.vacant} vacant of ${r.approved} approved (${pct}% filled)`}>
                  <span className="dash-cadre-name">{r.label}</span>
                  <span className="dash-cadre-bar" aria-hidden>
                    {r.occupied > 0 && <span style={{ flexGrow: r.occupied, background: OCCUPIED }} />}
                    {r.vacant > 0 && <span style={{ flexGrow: r.vacant, background: VACANT }} />}
                  </span>
                  <span className="dash-cadre-nums">
                    <b>{fmt(r.occupied)}</b> / {fmt(r.approved)}
                    <em className={pct < 50 ? 'low' : ''}>{pct}%</em>
                  </span>
                </li>
              );
            })}
          </ul>
          {rows.length > 10 && (
            <button type="button" className="btn btn-ghost btn-sm dash-more" onClick={() => setAll((a) => !a)}>
              {all ? 'Show top 10' : `Show all ${rows.length} units`}
            </button>
          )}
        </>
      )}
    </Card>
  );
}

/* ------------------------------------------------------------------ ranked horizontal bars (single measure) */

function BarList({ data }: { data: Point[] }) {
  const [all, setAll] = useState(false);
  const rows = data.map((d) => ({ label: pretty(String(d.label ?? '—')), value: num(d.value) })).sort((a, b) => b.value - a.value);
  const max = Math.max(1, ...rows.map((r) => r.value));
  const shown = all ? rows : rows.slice(0, 8);
  return (
    <>
      <ul className="dash-bars">
        {shown.map((r) => (
          <li key={r.label} title={`${r.label}: ${fmt(r.value)}`}>
            <span className="dash-bars-name">{r.label}</span>
            <span className="dash-bars-track" aria-hidden><span style={{ width: `${(r.value / max) * 100}%` }} /></span>
            <b className="dash-bars-val">{fmt(r.value)}</b>
          </li>
        ))}
      </ul>
      {rows.length > 8 && (
        <button type="button" className="btn btn-ghost btn-sm dash-more" onClick={() => setAll((a) => !a)}>
          {all ? 'Show fewer' : `Show all ${rows.length}`}
        </button>
      )}
    </>
  );
}

/* ------------------------------------------------------------------ donut with total and value legend */

function Donut({ data }: { data: Point[] }) {
  const slices = useMemo(() => {
    const sorted = data.map((d) => ({ label: pretty(String(d.label ?? '—')), value: num(d.value) })).filter((d) => d.value > 0)
      .sort((a, b) => b.value - a.value);
    const head = sorted.slice(0, sorted.length > SERIES.length ? SERIES.length - 1 : SERIES.length)
      .map((d, i) => ({ ...d, color: SERIES[i] }));
    const rest = sorted.slice(head.length);
    if (rest.length) head.push({ label: 'Other', value: rest.reduce((s, d) => s + d.value, 0), color: OTHER });
    return head;
  }, [data]);
  const total = slices.reduce((s, d) => s + d.value, 0);

  return (
    <div className="dash-donut">
      <div className="dash-donut-chart">
        <ResponsiveContainer>
          <PieChart>
            <Pie data={slices} dataKey="value" nameKey="label" innerRadius="66%" outerRadius="96%" paddingAngle={slices.length > 1 ? 2 : 0}
              stroke="#fff" strokeWidth={2} startAngle={90} endAngle={-270} isAnimationActive={false}>
              {slices.map((s) => <Cell key={s.label} fill={s.color} />)}
            </Pie>
            <Tooltip formatter={(v: number, n: string) => [`${fmt(v)} (${Math.round((v / total) * 100)}%)`, n]}
              contentStyle={{ borderRadius: 10, border: '1px solid var(--border)', boxShadow: 'var(--shadow)', fontSize: 13 }} />
          </PieChart>
        </ResponsiveContainer>
        <div className="dash-donut-total"><b>{fmt(total)}</b><span>Total</span></div>
      </div>
      <ul className="dash-donut-legend">
        {slices.map((s) => (
          <li key={s.label}>
            <i style={{ background: s.color }} />
            <span>{s.label}</span>
            <b>{fmt(s.value)}</b>
            <em>{total ? Math.round((s.value / total) * 100) : 0}%</em>
          </li>
        ))}
      </ul>
    </div>
  );
}
