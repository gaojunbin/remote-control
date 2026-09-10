import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import { Button } from '../../components/Button';
import { Menu } from '../../components/Popover';
import { Switch } from '../../components/Switch';
import { languageLabel, strings } from '../../strings';
import { useAuth } from '../../stores/auth';
import { useConnection } from '../../stores/connection';
import { useSettings } from '../../stores/settings';
import { currentPushState, disablePush, enablePush, type PushState } from '../../push/webpush';
import './settings.css';

export function SettingsPage() {
  const username = useAuth((s) => s.username);
  const config = useAuth((s) => s.config);
  const version = useAuth((s) => s.version);
  const logout = useAuth((s) => s.logout);
  const socketStatus = useConnection((s) => s.status);
  const gatewayVersion = useConnection((s) => s.gatewayVersion);
  const protocol = useConnection((s) => s.protocol);
  const stt = useConnection((s) => s.stt);

  const language = useSettings((s) => s.sttLanguage);
  const setLanguage = useSettings((s) => s.setSttLanguage);
  const pushToTalk = useSettings((s) => s.pushToTalk);
  const setPushToTalk = useSettings((s) => s.setPushToTalk);

  const [push, setPush] = useState<PushState>('unsupported');
  const [pushBusy, setPushBusy] = useState(false);
  const navigate = useNavigate();

  useEffect(() => {
    void currentPushState().then(setPush);
  }, []);

  const webPushAvailable = config?.push.web_enabled !== false && push !== 'unsupported';

  return (
    <>
      <div className="page-head">
        <h1>{strings.settings.title}</h1>
      </div>

      <section className="settings-card card">
        <h2>{strings.settings.account}</h2>
        <div className="settings-row">
          <span>{strings.settings.signedInAs}</span>
          <strong>{username ?? '—'}</strong>
        </div>
        <div className="settings-row">
          <span>{strings.settings.connection}</span>
          <span className="hint">
            {socketStatus === 'open'
              ? strings.settings.connected
              : socketStatus === 'reconnecting' || socketStatus === 'connecting'
                ? strings.settings.connecting
                : strings.settings.offline}
          </span>
        </div>
        <div className="settings-actions">
          <Button
            onClick={async () => {
              await logout();
              navigate('/login', { replace: true });
            }}
          >
            {strings.settings.signOut}
          </Button>
        </div>
      </section>

      <section className="settings-card card">
        <h2>{strings.settings.notifications}</h2>
        <p className="hint">{strings.settings.pushDescription}</p>
        <div className="settings-row">
          <span>{strings.settings.pushEnable}</span>
          {!webPushAvailable ? (
            <span className="hint">
              {push === 'unsupported'
                ? strings.settings.pushUnsupported
                : strings.settings.pushServerDisabled}
            </span>
          ) : push === 'denied' ? (
            <span className="hint">{strings.settings.pushBlocked}</span>
          ) : (
            <Button
              small
              busy={pushBusy}
              onClick={async () => {
                setPushBusy(true);
                try {
                  setPush(push === 'subscribed' ? await disablePush() : await enablePush());
                } catch {
                  setPush(await currentPushState());
                } finally {
                  setPushBusy(false);
                }
              }}
            >
              {push === 'subscribed'
                ? strings.settings.pushDisableAction
                : strings.settings.pushEnableAction}
            </Button>
          )}
        </div>
      </section>

      <section className="settings-card card">
        <h2>{strings.settings.voice}</h2>
        {stt.enabled ? (
          <>
            <div className="settings-row">
              <span>{strings.settings.voiceLanguage}</span>
              <Menu
                align="end"
                ariaLabel={strings.settings.voiceLanguage}
                value={language}
                onSelect={setLanguage}
                options={stt.languages.map((code) => ({ id: code, label: languageLabel(code) }))}
                label={languageLabel(language)}
              />
            </div>
            <div className="settings-row">
              <span>{strings.settings.pushToTalk}</span>
              <Switch
                checked={pushToTalk}
                onChange={setPushToTalk}
                label={strings.settings.pushToTalk}
              />
            </div>
          </>
        ) : (
          <p className="hint">{strings.settings.voiceServerDisabled}</p>
        )}
      </section>

      <section className="settings-card card">
        <h2>{strings.settings.about}</h2>
        <div className="settings-row">
          <span>{strings.settings.origin}</span>
          <span className="mono hint">{config?.public_origin ?? window.location.origin}</span>
        </div>
        <div className="settings-row">
          <span>{strings.settings.gatewayVersion}</span>
          <span className="mono hint">{gatewayVersion ?? version ?? '—'}</span>
        </div>
        <div className="settings-row">
          <span>{strings.settings.protocolVersion}</span>
          <span className="mono hint">{protocol ?? '—'}</span>
        </div>
      </section>
    </>
  );
}
