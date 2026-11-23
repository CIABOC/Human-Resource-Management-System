import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import { core } from './core';
import { modules } from '@/modules/registry';

/**
 * Trilingual UI (UI-02). Keys are flat ("leave.apply"); components call t(key, 'English default') so English never
 * needs a separate entry. Sinhala and Tamil strings are provided per module and should be reviewed by CIABOC's
 * official translators before go-live.
 */
const resources: Record<'en' | 'si' | 'ta', Record<string, string>> = { en: { ...core.en }, si: { ...core.si }, ta: { ...core.ta } };
for (const m of modules) {
  for (const lang of ['en', 'si', 'ta'] as const) Object.assign(resources[lang], m.i18n?.[lang] ?? {});
}

const saved = (() => {
  try {
    return sessionStorage.getItem('hrms.lang');
  } catch {
    return null;
  }
})();

void i18n.use(initReactI18next).init({
  resources: {
    en: { translation: resources.en },
    si: { translation: resources.si },
    ta: { translation: resources.ta },
  },
  lng: saved ?? 'en',
  fallbackLng: 'en',
  keySeparator: false,
  nsSeparator: false,
  interpolation: { escapeValue: false },
});

i18n.on('languageChanged', (lng) => {
  document.documentElement.lang = lng;
  try {
    sessionStorage.setItem('hrms.lang', lng);
  } catch {
    /* ignore */
  }
});
document.documentElement.lang = i18n.language;
try {
  document.documentElement.dataset.theme = sessionStorage.getItem('hrms.theme')?.toLowerCase() ?? 'light';
} catch {
  document.documentElement.dataset.theme = 'light';
}

export default i18n;
