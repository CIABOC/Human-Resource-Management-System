import { ReactNode } from 'react';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { Empty, Loading } from './ui';

export interface Column<T> {
  key: string;
  header: string;
  render?: (row: T) => ReactNode;
  align?: 'left' | 'right';
  width?: number | string;
}

/**
 * Generic table. Pass `page` + `totalPages` + `onPage` for server-side paging.
 * <DataTable rows={data} columns={[{ key: 'name', header: 'Name' }]} onRowClick={r => nav(`/x/${r.id}`)} />
 */
export function DataTable<T extends object>({ rows, columns, loading, onRowClick, empty, page, totalPages, totalElements, onPage, rowKey }: {
  rows: T[] | null | undefined;
  columns: Column<T>[];
  loading?: boolean;
  onRowClick?: (row: T) => void;
  empty?: string;
  page?: number;
  totalPages?: number;
  totalElements?: number;
  onPage?: (p: number) => void;
  rowKey?: (row: T, index: number) => string | number;
}) {
  if (loading && !rows) return <Loading />;
  return (
    <>
      <div className="table-wrap">
        <table className="table">
          <thead>
            <tr>
              {columns.map((c) => (
                <th key={c.key} className={c.align === 'right' ? 'num' : ''} style={{ width: c.width }}>{c.header}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {(rows ?? []).map((r, i) => (
              <tr
                key={rowKey ? rowKey(r, i) : ((r as { id?: number }).id ?? i)}
                className={onRowClick ? 'clickable' : ''}
                onClick={onRowClick ? () => onRowClick(r) : undefined}
                tabIndex={onRowClick ? 0 : undefined}
                onKeyDown={onRowClick ? (e) => e.key === 'Enter' && onRowClick(r) : undefined}
              >
                {columns.map((c) => (
                  <td key={c.key} className={c.align === 'right' ? 'num' : ''}>
                    {c.render ? c.render(r) : formatCell((r as Record<string, unknown>)[c.key])}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {!loading && (rows ?? []).length === 0 && <Empty text={empty} />}
      {onPage && totalPages !== undefined && totalPages > 1 && (
        <div className="pager">
          <span>{totalElements !== undefined ? `${totalElements} records` : ''}</span>
          <div className="row">
            <button className="btn btn-outline btn-sm" disabled={!page} onClick={() => onPage((page ?? 0) - 1)} aria-label="Previous page"><ChevronLeft /></button>
            <span>Page {(page ?? 0) + 1} of {totalPages}</span>
            <button className="btn btn-outline btn-sm" disabled={(page ?? 0) >= totalPages - 1} onClick={() => onPage((page ?? 0) + 1)} aria-label="Next page"><ChevronRight /></button>
          </div>
        </div>
      )}
    </>
  );
}

export function formatCell(v: unknown): ReactNode {
  if (v === null || v === undefined || v === '') return <span className="faint">—</span>;
  if (typeof v === 'boolean') return v ? 'Yes' : 'No';
  if (typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T/.test(v)) return fmtDateTime(v);
  return String(v);
}

export function fmtDate(v?: string | null) {
  if (!v) return '—';
  const d = new Date(v.length === 10 ? v + 'T00:00:00' : v);
  return d.toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
}

export function fmtDateTime(v?: string | null) {
  if (!v) return '—';
  return new Date(v).toLocaleString('en-GB', { day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}

/** LKR money formatting (NFR-LOC-02). */
export function fmtMoney(v?: number | string | null) {
  if (v === null || v === undefined || v === '') return '—';
  return Number(v).toLocaleString('en-LK', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
