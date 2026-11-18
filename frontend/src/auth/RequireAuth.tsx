import { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { ShieldAlert } from 'lucide-react';
import { useAuth } from './AuthContext';

/** Route guard: requires a signed-in user and (optionally) any one of the listed permissions. */
export function RequireAuth({ anyOf, children }: { anyOf?: string[]; children: ReactNode }) {
  const { me, loading, can } = useAuth();
  const loc = useLocation();
  if (loading) {
    return (
      <div className="loading-block">
        <div className="spinner" />
      </div>
    );
  }
  if (!me) return <Navigate to="/login" replace state={{ from: loc.pathname + loc.search }} />;
  if (anyOf && anyOf.length > 0 && !can(...anyOf)) return <Forbidden />;
  return <>{children}</>;
}

export function Forbidden() {
  return (
    <div className="card card-pad empty">
      <ShieldAlert />
      <h2>Access restricted</h2>
      <p className="muted">You do not have permission to view this page. Contact the system administrator if you need access.</p>
    </div>
  );
}
