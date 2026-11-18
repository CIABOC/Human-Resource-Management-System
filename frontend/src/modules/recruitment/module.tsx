import { lazy } from 'react';
import { Briefcase, FileText, Megaphone, Search } from 'lucide-react';
import type { ModuleDef } from '../types';

const VacanciesPage = lazy(() => import('./VacanciesPage'));
const VacancyDetailPage = lazy(() => import('./VacancyDetailPage'));
const ApplicantPage = lazy(() => import('./ApplicantPage'));
const AnnouncementsPage = lazy(() => import('./AnnouncementsPage'));
const LetterTemplatesPage = lazy(() => import('./LetterTemplatesPage'));
const PositionsPage = lazy(() => import('./PositionsPage'));

const mod: ModuleDef = {
  key: 'recruitment',
  nav: [
    { path: '/recruitment/positions', label: 'rec.positions', fallback: 'Available positions', icon: Search, section: 'self', order: 60 },
    { path: '/recruitment/vacancies', label: 'rec.vacancies', fallback: 'Recruitment', icon: Briefcase, section: 'talent', order: 10, anyOf: ['REC_VIEW'] },
    { path: '/recruitment/announcements', label: 'rec.announcements', fallback: 'Announcements', icon: Megaphone, section: 'talent', order: 11, anyOf: ['REC_VIEW'] },
    { path: '/recruitment/templates', label: 'rec.templates', fallback: 'Letter templates', icon: FileText, section: 'talent', order: 12, anyOf: ['REC_VIEW'] },
  ],
  routes: [
    { path: '/recruitment/positions', element: <PositionsPage /> },
    { path: '/recruitment/vacancies', element: <VacanciesPage />, anyOf: ['REC_VIEW'] },
    { path: '/recruitment/vacancies/:id', element: <VacancyDetailPage />, anyOf: ['REC_VIEW'] },
    { path: '/recruitment/applicants/:id', element: <ApplicantPage />, anyOf: ['REC_VIEW'] },
    { path: '/recruitment/announcements', element: <AnnouncementsPage />, anyOf: ['REC_VIEW'] },
    { path: '/recruitment/templates', element: <LetterTemplatesPage />, anyOf: ['REC_VIEW'] },
  ],
  i18n: {
    si: {
      'rec.section': 'බඳවා ගැනීම', 'rec.positions': 'පවතින තනතුරු', 'rec.vacancies': 'බඳවා ගැනීම',
      'rec.announcements': 'නිවේදන', 'rec.templates': 'ලිපි ආකෘති',
    },
    ta: {
      'rec.section': 'ஆட்சேர்ப்பு', 'rec.positions': 'கிடைக்கும் பதவிகள்', 'rec.vacancies': 'ஆட்சேர்ப்பு',
      'rec.announcements': 'அறிவிப்புகள்', 'rec.templates': 'கடித வார்ப்புருக்கள்',
    },
  },
};
export default mod;
