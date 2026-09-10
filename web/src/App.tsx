import { useCallback, useEffect } from 'react';
import { Navigate, Route, Routes, useLocation, useNavigate } from 'react-router';
import { AppLayout } from './layout/AppLayout';
import { LoginPage } from './features/login/LoginPage';
import { DevicesPage } from './features/devices/DevicesPage';
import { SessionsPage } from './features/sessions/SessionsPage';
import { ChatPage } from './features/chat/ChatPage';
import { SettingsPage } from './features/settings/SettingsPage';
import { useServiceWorkerNavigation } from './push/useServiceWorkerNavigation';
import { useAuth } from './stores/auth';
import { useConnection } from './stores/connection';

export function App() {
  const status = useAuth((s) => s.status);
  const check = useAuth((s) => s.check);
  const markSignedOut = useAuth((s) => s.markSignedOut);
  const connect = useConnection((s) => s.connect);
  const disconnect = useConnection((s) => s.disconnect);
  const navigate = useNavigate();
  const location = useLocation();

  useServiceWorkerNavigation();

  useEffect(() => {
    void check();
  }, [check]);

  const onUnauthorized = useCallback(() => {
    markSignedOut();
    disconnect();
  }, [markSignedOut, disconnect]);

  useEffect(() => {
    if (status === 'signed-in') connect(onUnauthorized);
    if (status === 'signed-out') disconnect();
  }, [status, connect, disconnect, onUnauthorized]);

  useEffect(() => {
    if (status === 'signed-out' && location.pathname !== '/login') {
      navigate('/login', { replace: true, state: { from: location.pathname } });
    }
  }, [status, location.pathname, navigate]);

  if (status === 'unknown') return <div className="boot" aria-busy="true" />;

  // Signed out, every path is the login screen. Rendering the requested page
  // first and redirecting from an effect would mount it for one pass, and its
  // mount effects would fire authenticated requests that can only 401.
  if (status === 'signed-out') {
    return (
      <Routes>
        <Route path="*" element={<LoginPage />} />
      </Routes>
    );
  }

  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/sessions/:deviceId/:sessionId" element={<ChatPage />} />
      <Route element={<AppLayout />}>
        <Route path="/devices" element={<DevicesPage />} />
        <Route path="/sessions" element={<SessionsPage />} />
        <Route path="/settings" element={<SettingsPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/sessions" replace />} />
    </Routes>
  );
}
