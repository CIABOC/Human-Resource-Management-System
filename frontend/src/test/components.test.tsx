import { render, screen } from '@testing-library/react';
import { fmtMoney, formatCell, DataTable } from '@/components/DataTable';
import { StatusBadge } from '@/components/ui';
import { errorMessage } from '@/api/client';

describe('formatting', () => {
  it('formats LKR amounts with two decimals', () => {
    expect(fmtMoney(1234567.5)).toBe('1,234,567.50');
    expect(fmtMoney(null)).toBe('—');
  });

  it('renders booleans as Yes/No', () => {
    expect(formatCell(true)).toBe('Yes');
    expect(formatCell(false)).toBe('No');
  });
});

describe('StatusBadge', () => {
  it('maps approval statuses to tones', () => {
    render(<StatusBadge status="REJECTED" />);
    const el = screen.getByText('Rejected');
    expect(el).toHaveClass('badge-danger');
  });
});

describe('DataTable', () => {
  it('shows an empty state when there are no rows', () => {
    render(<DataTable rows={[]} columns={[{ key: 'name', header: 'Name' }]} />);
    expect(screen.getByText('Name')).toBeInTheDocument();
  });

  it('renders rows', () => {
    render(<DataTable rows={[{ id: 1, name: 'Investigation Division' }]} columns={[{ key: 'name', header: 'Name' }]} />);
    expect(screen.getByText('Investigation Division')).toBeInTheDocument();
  });
});

describe('errorMessage', () => {
  it('prefers the API envelope message and field errors', () => {
    const err = { response: { data: { status: 400, code: 'VALIDATION_FAILED', message: 'Invalid', fieldErrors: { nic: 'bad' } } } };
    expect(errorMessage(err)).toBe('Invalid — nic: bad');
  });
});
