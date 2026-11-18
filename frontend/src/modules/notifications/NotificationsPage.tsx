import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { CheckCheck } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import type { Page } from '@/api/types';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Card, Checkbox, PageHeader } from '@/components/ui';
import { DataTable, fmtDateTime } from '@/components/DataTable';

interface Notification { id: number; title: string; body?: string; link?: string; read: boolean; createdAt: string }

export default function NotificationsPage() {
  const { t } = useTranslation();
  const nav = useNavigate();
  const toast = useToast();
  const [page, setPage] = useState(0);
  const [unreadOnly, setUnreadOnly] = useState(false);
  const { data, loading, error, reload } = useApi<Page<Notification>>('/notifications', { page, size: 20, unreadOnly });

  const changed = () => window.dispatchEvent(new Event('hrms:notifications-changed'));

  const open = async (n: Notification) => {
    if (!n.read) {
      await api.post(`/notifications/${n.id}/read`).catch(() => undefined);
      changed();
    }
    if (n.link) nav(n.link);
    else void reload();
  };

  const markAll = async () => {
    try {
      await api.post('/notifications/read-all');
      changed();
      toast('All notifications marked as read', 'success');
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  return (
    <>
      <PageHeader title={t('notifications.title', 'Notifications')} subtitle="Updates on your requests, approvals and HR events"
        actions={<button className="btn btn-outline" onClick={() => void markAll()}><CheckCheck /> Mark all as read</button>} />
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false} title={<Checkbox label="Unread only" checked={unreadOnly} onChange={(v) => { setUnreadOnly(v); setPage(0); }} />}>
        <DataTable<Notification>
          rows={data?.content}
          loading={loading}
          onRowClick={(n) => void open(n)}
          page={data?.page}
          totalPages={data?.totalPages}
          totalElements={data?.totalElements}
          onPage={setPage}
          columns={[
            { key: 'title', header: 'Notification', render: (n) => (
              <div>
                <div style={{ fontWeight: n.read ? 400 : 700, color: 'var(--navy-800)' }}>{n.title}</div>
                {n.body && <div className="muted" style={{ fontSize: 12.5 }}>{n.body}</div>}
              </div>
            ) },
            { key: 'createdAt', header: 'Received', render: (n) => <span className="nowrap">{fmtDateTime(n.createdAt)}</span> },
            { key: 'read', header: '', render: (n) => (n.read ? <span className="faint">Read</span> : <span className="badge badge-gold">New</span>) },
          ]}
        />
      </Card>
    </>
  );
}
