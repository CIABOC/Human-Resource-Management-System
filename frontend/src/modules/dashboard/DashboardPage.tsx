import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Bar, BarChart, CartesianGrid, Cell, Legend, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { Alert, Card, Empty, Loading, PageHeader } from '@/components/ui';

interface Tile { key: string; label: string; value: string; hint?: string; link?: string; tone: string }
interface Chart { key: string; title: string; type: 'bar' | 'pie'; data: { label: string; value: number; [k: string]: unknown }[] }

const PIE_COLORS = ['#0b1f4b', '#c9a227', '#1c4290', '#ddb94a', '#5b6478', '#8a6a0e', '#13306e', '#ecd27f'];

export default function DashboardPage() {
  const { t } = useTranslation();
  const { me } = useAuth();
  const { data, loading, error } = useApi<{ tiles: Tile[]; charts: Chart[] }>('/dashboard');
  const hour = new Date().getHours();
  const greet = hour < 12 ? 'Good morning' : hour < 17 ? 'Good afternoon' : 'Good evening';

  return (
    <>
      <PageHeader eyebrow={new Date().toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })}
        title={`${greet}, ${me?.fullName?.split(' ')[0] ?? ''}`} subtitle={t('dashboard.sub', 'Your workforce at a glance')} />
      {error && <Alert kind="danger">{error}</Alert>}
      {loading && !data ? <Loading /> : (
        <div className="stack">
          <div className="grid grid-4">
            {data?.tiles.map((tile) => {
              const card = (
                <div className={`card kpi ${tile.tone}`}>
                  <div className="label">{tile.label}</div>
                  <div className="value">{tile.value}</div>
                  {tile.hint && <div className="hint">{tile.hint}</div>}
                </div>
              );
              return tile.link ? <Link key={tile.key} to={tile.link} className="kpi-link">{card}</Link> : <div key={tile.key}>{card}</div>;
            })}
          </div>
          {data && data.charts.length > 0 ? (
            <div className="grid grid-2">
              {data.charts.map((c) => (
                <Card key={c.key} title={c.title} accent>
                  {c.data.length === 0 ? <Empty /> : (
                    <div style={{ width: '100%', height: 280 }}>
                      <ResponsiveContainer>
                        {c.type === 'pie' ? (
                          <PieChart>
                            <Pie data={c.data} dataKey="value" nameKey="label" innerRadius={60} outerRadius={100} paddingAngle={2}>
                              {c.data.map((_, i) => <Cell key={i} fill={PIE_COLORS[i % PIE_COLORS.length]} />)}
                            </Pie>
                            <Tooltip />
                            <Legend />
                          </PieChart>
                        ) : (
                          <BarChart data={c.data} margin={{ left: -10, right: 10 }}>
                            <CartesianGrid strokeDasharray="3 3" stroke="#e2e5ee" vertical={false} />
                            <XAxis dataKey="label" tick={{ fontSize: 11 }} interval={0} angle={-20} textAnchor="end" height={60} />
                            <YAxis allowDecimals={false} tick={{ fontSize: 11 }} />
                            <Tooltip />
                            <Bar dataKey="value" name={c.data[0]?.vacant !== undefined ? 'Occupied' : 'Count'} fill="#0b1f4b" radius={[4, 4, 0, 0]} />
                            {c.data[0]?.vacant !== undefined && <Bar dataKey="vacant" name="Vacant" fill="#c9a227" radius={[4, 4, 0, 0]} />}
                            {c.data[0]?.vacant !== undefined && <Legend />}
                          </BarChart>
                        )}
                      </ResponsiveContainer>
                    </div>
                  )}
                </Card>
              ))}
            </div>
          ) : null}
        </div>
      )}
    </>
  );
}
