import { Suspense } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { RequireAuth } from '@/auth/RequireAuth';
import { Layout } from '@/components/Layout';
import { Loading } from '@/components/ui';
import { modules } from '@/modules/registry';
import LoginPage from '@/modules/auth/LoginPage';

export default function App() {
  const routes = modules.flatMap((m) => m.routes);
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      {routes.map((r) => (
        <Route
          key={r.path}
          path={r.path}
          element={
            <RequireAuth anyOf={r.anyOf}>
              <Layout>
                <Suspense fallback={<Loading />}>{r.element}</Suspense>
              </Layout>
            </RequireAuth>
          }
        />
      ))}
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
