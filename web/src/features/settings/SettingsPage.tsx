import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import { Button } from '../../components/Button';
import { Menu } from '../../components/Popover';
import { interfaceLanguageLabels, languageLabel, strings, timelineDetailLabel } from '../../strings';
import { useAuth } from '../../stores/auth';
import { useConnection } from '../../stores/connection';
import { INTERFACE_LANGUAGES, useSettings } from '../../stores/settings';
import type { InterfaceLanguage } from '../../stores/settings';
import type { TimelineDetail } from '../../stores/timeline';
import { currentPushState, disablePush, enablePush, type PushState } from '../../push/webpush';
import './settings.css';

/** In the order Settings offers them; Simple is the default. */
const DETAIL_LEVELS: TimelineDetail[] = ['simple', 'detailed'];

export function SettingsPage() {
  const username = useAuth((s) => s.username);
  const config = useAuth((s) => s.config);
  const version = useAuth((s) => s.version);
  const logout = useAuth((s) => s.logout);
  const socketStatus = useConnection((s) => s.status);
  const gatewayVersion = useConnection((s) => s.gatewayVersion);
  const protocol = useConnection((s) => s.protocol);
  const stt = useConnection((s) => s.stt);

  const sttLanguage = useSettings((s) => s.sttLanguage);
  const setSttLanguage = useSettings((s) => s.setSttLanguage);
  const uiLanguage = useSettings((s) => s.language);
  const setUiLanguage = useSettings((s) => s.setLanguage);
  const detail = useSettings((s) => s.timelineDetail);
  const setDetail = useSettings((s) => s.setTimelineDetail);

  const [push, setPush] = useState<PushState>('unsupported');
  const [pushBusy, setPushBusy] = useState(false);
  const navigate = useNavigate();

  useEffect(() => {
    void currentPushState().then(setPush);
  }, []);

  const webPushAvailable = config?.push.web_enabled !== false && push !== 'unsupported';
  const connection =
    socketStatus === 'open'
      ? strings.settings.connected
      : socketStatus === 'reconnecting' || socketStatus === 'connecting'
        ? strings.settings.connecting
        : strings.settings.offline;

  return (
    <>
      <div className="page-head">
        <h1>{strings.settings.title}</h1>
      </div>

      <div className="settings">
        <section className="settings-section">
          <h2 className="group-title">{strings.settings.account}</h2>
          <div className="settings-group surface">
            <div className="settings-row">
              <span>{strings.settings.signedInAs}</span>
              <strong>{username ?? '—'}</strong>
            </div>
            <div className="settings-row">
              <span>{strings.settings.connection}</span>
              <span className="hint">{connection}</span>
            </div>
            <button
              type="button"
              className="settings-row settings-action"
              onClick={async () => {
                await logout();
                navigate('/login', { replace: true });
              }}
            >
              {strings.settings.signOut}
            </button>
          </div>
        </section>

        <section className="settings-section">
          <h2 className="group-title">{strings.settings.notifications}</h2>
          <div className="settings-group surface">
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
          </div>
          <p className="settings-note">{strings.settings.pushDescription}</p>
        </section>

        <section className="settings-section">
          <h2 className="group-title">{strings.settings.voice}</h2>
          {stt.enabled ? (
            <div className="settings-group surface">
              <div className="settings-row">
                <span>{strings.settings.voiceLanguage}</span>
                <Menu
                  align="end"
                  ariaLabel={strings.settings.voiceLanguage}
                  value={sttLanguage}
                  onSelect={setSttLanguage}
                  options={stt.languages.map((code) => ({ id: code, label: languageLabel(code) }))}
                  label={languageLabel(sttLanguage)}
                />
              </div>
            </div>
          ) : (
            <p className="settings-note">{strings.settings.voiceServerDisabled}</p>
          )}
        </section>

        <section className="settings-section">
          <h2 className="group-title">{strings.settings.language}</h2>
          <div className="settings-group surface">
            <div className="settings-row">
              <span>{strings.settings.language}</span>
              <Menu
                align="end"
                ariaLabel={strings.settings.language}
                value={uiLanguage}
                onSelect={(id) => setUiLanguage(id as InterfaceLanguage)}
                options={INTERFACE_LANGUAGES.map((code) => ({
                  id: code,
                  label: interfaceLanguageLabels[code],
                }))}
                label={interfaceLanguageLabels[uiLanguage]}
              />
            </div>
          </div>
        </section>

        <section className="settings-section">
          <h2 className="group-title">{strings.settings.timeline}</h2>
          <div className="settings-group surface">
            <div className="settings-row">
              <span>{strings.settings.timelineDetail}</span>
              <Menu
                align="end"
                ariaLabel={strings.settings.timelineDetail}
                value={detail}
                onSelect={(id) => setDetail(id as TimelineDetail)}
                options={DETAIL_LEVELS.map((level) => ({
                  id: level,
                  label: timelineDetailLabel(level),
                }))}
                label={timelineDetailLabel(detail)}
              />
            </div>
          </div>
          <p className="settings-note">{strings.settings.timelineDetailNote}</p>
        </section>

        <section className="settings-section">
          <h2 className="group-title">{strings.settings.about}</h2>
          <div className="settings-group surface">
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
          </div>
        </section>
      </div>
    </>
  );
}
