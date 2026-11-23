export type Theme = 'LIGHT' | 'DARK';

function resolveTheme(value: unknown): Theme {
  if (value === 'LIGHT' || value === 'DARK') return value;
  try {
    return sessionStorage.getItem('hrms.theme') === 'DARK' ? 'DARK' : 'LIGHT';
  } catch {
    return 'LIGHT';
  }
}

export function applyTheme(value: unknown): Theme {
  const theme = resolveTheme(value);
  document.documentElement.dataset.theme = theme.toLowerCase();
  try {
    sessionStorage.setItem('hrms.theme', theme);
  } catch {
    // The account API remains the persistent source when browser storage is unavailable.
  }
  return theme;
}
