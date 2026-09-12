import { useCallback, useEffect } from 'react';
import { Navigate, Route, Routes, useLocation, useNavigate } from 'react-router';
import { AppLayout } from './layout/AppLayout';
import { LoginPage } from './features/login/LoginPage';
import { DevicesPage } from './features/devices/DevicesPage';
import { PairPage } from './features/devices/PairPage';
import { SessionsPage } from './features/sessions/SessionsPage';
import { ChatPage } from './features/chat/ChatPage';
import { SettingsPage } from './features/settings/SettingsPage';
import { useServiceWorkerNavigation } from './push/useServiceWorkerNavigation';
import { useAuth } from './stores/auth';
import { useConnection } from './stores/connection';
import { useSettings } from './stores/settings';

export function App() {
  const status = useAuth((s) => s.status);
  const check = useAuth((s) => s.check);
  const markSignedOut = useAuth((s) => s.markSignedOut);
  const connect = useConnection((s) => s.connect);
  const disconnect = useConnection((s) => s.disconnect);
  const navigate = useNavigate();
  const location = useLocation();
  // Every screen reads `strings`, which is a view on the table this names. The
  // routes carry it as their key so the words change on every open screen at
  // once, while the socket and the session probe above stay untouched.
  const language = useSettings((s) => s.language);

  useServiceWorkerNavigation();

  useEffect(() => {
    document.documentElement.lang = language;
  }, [language]);

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
      // The hash carries the claim token of a `/pair` link (A23), so the
      // destination the login page returns to has to keep it.
      const from = `${location.pathname}${location.search}${location.hash}`;
      navigate('/login', { replace: true, state: { from } });
    }
  }, [status, location.pathname, location.search, location.hash, navigate]);

  if (status === 'unknown') return <div className="boot" aria-busy="true" />;

  // Signed out, every path is the login screen. Rendering the requested page
  // first and redirecting from an effect would mount it for one pass, and its
  // mount effects would fire authenticated requests that can only 401.
  if (status === 'signed-out') {
    return (
      <Routes key={language}>
        <Route path="*" element={<LoginPage />} />
      </Routes>
    );
  }

  return (
    <Routes key={language}>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/sessions/:deviceId/:sessionId" element={<ChatPage />} />
      <Route element={<AppLayout />}>
        <Route path="/devices" element={<DevicesPage />} />
        <Route path="/pair" element={<PairPage />} />
        <Route path="/sessions" element={<SessionsPage />} />
        <Route path="/settings" element={<SettingsPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/sessions" replace />} />
    </Routes>
  );
}
