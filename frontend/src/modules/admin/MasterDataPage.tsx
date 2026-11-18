import { useState } from 'react';
import { Pencil, Plus } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import type { MasterDataItem } from '@/api/types';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Card, Modal, PageHeader, SelectField, StatusBadge, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate } from '@/components/DataTable';

/** Administrators maintain reference data without developer involvement (FR-MDM-001..003). */
export default function MasterDataPage() {
  const toast = useToast();
  const cats = useApi<{ code: string; label: string }[]>('/master-data/categories');
  const [cat, setCat] = useState('EMPLOYEE_STATUS');
  const { data, loading, error, reload } = useApi<MasterDataItem[]>(`/master-data/${cat}`, { activeOnly: false });
  const [editing, setEditing] = useState<Partial<MasterDataItem> | null>(null);

  const save = async () => {
    if (!editing) return;
    const body = { ...editing, effectiveFrom: editing.effectiveFrom || null, effectiveTo: editing.effectiveTo || null };
    try {
      if (editing.id) await api.put(`/master-data/items/${editing.id}`, body);
      else await api.post(`/master-data/${cat}`, body);
      toast('Saved', 'success');
      setEditing(null);
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };
  const toggle = async (i: MasterDataItem) => {
    try {
      await api.post(`/master-data/items/${i.id}/status?active=${!i.active}`);
      toast(i.active ? 'Deactivated' : 'Activated', 'success');
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  return (
    <>
      <PageHeader eyebrow="Administration" title="Master data" subtitle="Values in use cannot be deactivated until the records using them are reassigned. All changes are audit-logged."
        actions={<button className="btn btn-gold" onClick={() => setEditing({ sortOrder: 0 })}><Plus /> Add value</button>} />
      <div className="filters">
        <SelectField label="Category" value={cat} onChange={setCat} placeholder={null} options={(cats.data ?? []).map((c) => ({ value: c.code, label: c.label }))} />
      </div>
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false}>
        <DataTable<MasterDataItem> rows={data} loading={loading} columns={[
          { key: 'code', header: 'Code', render: (i) => <span className="mono">{i.code}</span> },
          { key: 'nameEn', header: 'English', render: (i) => <strong>{i.nameEn}</strong> },
          { key: 'nameSi', header: 'සිංහල', render: (i) => <span lang="si">{i.nameSi ?? '—'}</span> },
          { key: 'nameTa', header: 'தமிழ்', render: (i) => <span lang="ta">{i.nameTa ?? '—'}</span> },
          { key: 'sortOrder', header: 'Order', align: 'right' },
          { key: 'effectiveFrom', header: 'Effective', render: (i) => `${fmtDate(i.effectiveFrom)}${i.effectiveTo ? ` – ${fmtDate(i.effectiveTo)}` : ''}` },
          { key: 'active', header: 'Status', render: (i) => <StatusBadge status={i.active ? 'ACTIVE' : 'INACTIVE'} /> },
          { key: 'a', header: '', render: (i) => (
            <div className="row" style={{ gap: 2 }}>
              <button className="btn btn-ghost btn-sm" aria-label="Edit" onClick={() => setEditing(i)}><Pencil /></button>
              <button className="btn btn-outline btn-sm" onClick={() => void toggle(i)}>{i.active ? 'Deactivate' : 'Activate'}</button>
            </div>
          ) },
        ]} />
      </Card>
      {editing && (
        <Modal title={editing.id ? `Edit ${editing.code}` : `New ${cat} value`} onClose={() => setEditing(null)} footer={<><button className="btn btn-outline" onClick={() => setEditing(null)}>Cancel</button><button className="btn btn-primary" onClick={() => void save()}>Save</button></>}>
          <div className="form-grid">
            <TextField label="Code" required value={editing.code} onChange={(v) => setEditing({ ...editing, code: v.toUpperCase() })} disabled={!!editing.id} />
            <TextField label="Display order" type="number" value={editing.sortOrder} onChange={(v) => setEditing({ ...editing, sortOrder: Number(v) })} />
            <TextField className="span-2" label="Name (English)" required value={editing.nameEn} onChange={(v) => setEditing({ ...editing, nameEn: v })} />
            <TextField label="Name (Sinhala)" lang="si" value={editing.nameSi} onChange={(v) => setEditing({ ...editing, nameSi: v })} />
            <TextField label="Name (Tamil)" lang="ta" value={editing.nameTa} onChange={(v) => setEditing({ ...editing, nameTa: v })} />
            <TextField label="Effective from" type="date" value={editing.effectiveFrom} onChange={(v) => setEditing({ ...editing, effectiveFrom: v })} />
            <TextField label="Effective to" type="date" value={editing.effectiveTo} onChange={(v) => setEditing({ ...editing, effectiveTo: v })} />
            <TextArea className="span-2" label="Description" value={editing.description} onChange={(v) => setEditing({ ...editing, description: v })} rows={2} />
          </div>
        </Modal>
      )}
    </>
  );
}
