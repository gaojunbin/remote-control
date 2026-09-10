import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router';
import { ApiError } from '../../lib/api';
import { Mark } from '../../layout/Mark';
import { strings } from '../../strings';
import { useAuth } from '../../stores/auth';
import './login.css';

export function LoginPage() {
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const login = useAuth((s) => s.login);
  const status = useAuth((s) => s.status);
  const navigate = useNavigate();

  useEffect(() => {
    if (status === 'signed-in') navigate('/sessions', { replace: true });
  }, [status, navigate]);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    if (busy || password.length === 0) return;
    setBusy(true);
    setError(null);
    try {
      await login(password);
      navigate('/sessions', { replace: true });
    } catch (err) {
      setError(loginErrorText(err));
      setPassword('');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login">
      <form className="login-card card" onSubmit={onSubmit}>
        <div className="login-brand">
          <Mark size={26} />
          <h1>{strings.login.title}</h1>
        </div>
        <p className="hint">{strings.login.subtitle}</p>
        <label className="label" htmlFor="rc-password">
          {strings.login.password}
        </label>
        <input
          id="rc-password"
          className="field"
          type="password"
          autoComplete="current-password"
          placeholder={strings.login.passwordPlaceholder}
          value={password}
          autoFocus
          onChange={(e) => setPassword(e.target.value)}
        />
        {error ? (
          <p className="login-error" role="alert">
            {error}
          </p>
        ) : null}
        <button type="submit" className="btn primary block" disabled={busy || password.length === 0}>
          {busy ? strings.login.signingIn : strings.login.submit}
        </button>
      </form>
    </div>
  );
}

function loginErrorText(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 429) return strings.login.rateLimited;
    if (err.status === 401) return strings.login.failed;
    return err.message || strings.errors.generic;
  }
  return strings.login.unreachable;
}
