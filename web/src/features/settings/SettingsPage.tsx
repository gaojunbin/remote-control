import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import { Button } from '../../components/Button';
import { Menu } from '../../components/Popover';
import { Segmented } from '../../components/Segmented';
import { Switch } from '../../components/Switch';
import { api } from '../../lib/api';
import type { PolishModel, PolishStrength } from '../../protocol/types';
import {
  interfaceLanguageLabels,
  languageLabel,
  roleLabel,
  strings,
  timelineDetailLabel,
} from '../../strings';
import { useAuth } from '../../stores/auth';
import { useConnection } from '../../stores/connection';
import { INTERFACE_LANGUAGES, useSettings } from '../../stores/settings';
import type { InterfaceLanguage } from '../../stores/settings';
import type { TimelineDetail } from '../../stores/timeline';
import { currentPushState, disablePush, enablePush, type PushState } from '../../push/webpush';
import { ChangePasswordModal } from './ChangePasswordModal';
import './settings.css';

/** In the order Settings offers them; Simple is the default. */
const DETAIL_LEVELS: TimelineDetail[] = ['simple', 'detailed'];

export function SettingsPage() {
  const username = useAuth((s) => s.username);
  const role = useAuth((s) => s.role);
  const config = useAuth((s) => s.config);
  const version = useAuth((s) => s.version);
  const logout = useAuth((s) => s.logout);
  const socketStatus = useConnection((s) => s.status);
  const gatewayVersion = useConnection((s) => s.gatewayVersion);
  const protocol = useConnection((s) => s.protocol);
  const stt = useConnection((s) => s.stt);
  const polish = useConnection((s) => s.polish);

  const sttLanguage = useSettings((s) => s.sttLanguage);
  const setSttLanguage = useSettings((s) => s.setSttLanguage);
  const polishEnabled = useSettings((s) => s.polishEnabled);
  const setPolishEnabled = useSettings((s) => s.setPolishEnabled);
  const polishModel = useSettings((s) => s.polishModel);
  const setPolishModel = useSettings((s) => s.setPolishModel);
  const polishStrength = useSettings((s) => s.polishStrength);
  const setPolishStrength = useSettings((s) => s.setPolishStrength);
  const uiLanguage = useSettings((s) => s.language);
  const setUiLanguage = useSettings((s) => s.setLanguage);
  const detail = useSettings((s) => s.timelineDetail);
  const setDetail = useSettings((s) => s.setTimelineDetail);

  const [push, setPush] = useState<PushState>('unsupported');
  const [pushBusy, setPushBusy] = useState(false);
  const [changingPassword, setChangingPassword] = useState(false);
  // A29: the provider's models, and whether asking for them failed.
  const [polishModels, setPolishModels] = useState<PolishModel[]>([]);
  const [polishModelsFailed, setPolishModelsFailed] = useState(false);
  const navigate = useNavigate();

  useEffect(() => {
    void currentPushState().then(setPush);
  }, []);

  // A29: the list belongs to the gateway's provider, so it is asked for when
  // this screen is drawn and only when the gateway says it has one. A gateway
  // that lists nothing leaves the select where it is, with what was chosen
  // before; a list with nothing chosen yet settles on its first model, so the
  // switch is all a first-time reader has to touch.
  useEffect(() => {
    if (!polish.enabled) return;
    let live = true;
    api
      .polishModels()
      .then((result) => {
        if (!live) return;
        setPolishModels(result.models);
        setPolishModelsFailed(false);
        const first = result.models[0];
        if (first && useSettings.getState().polishModel.length === 0) setPolishModel(first.id);
      })
      .catch(() => {
        if (live) setPolishModelsFailed(true);
      });
    return () => {
      live = false;
    };
  }, [polish.enabled, setPolishModel]);

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
              <span className="settings-identity">
                <strong>{username ?? '—'}</strong>
                {role ? <span className="hint">{roleLabel(role)}</span> : null}
              </span>
            </div>
            <div className="settings-row">
              <span>{strings.settings.connection}</span>
              <span className="hint">{connection}</span>
            </div>
            {role === 'admin' ? (
              <button
                type="button"
                className="settings-row settings-action"
                onClick={() => navigate('/users')}
              >
                {strings.users.title}
              </button>
            ) : role === 'member' ? (
              <button
                type="button"
                className="settings-row settings-action"
                onClick={() => setChangingPassword(true)}
              >
                {strings.settings.changePassword}
              </button>
            ) : null}
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
            <>
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

                {/* A29: the switch is always shown, so the feature exists even
                    where this gateway cannot offer it; the model and the
                    strength appear once it is on, because they mean nothing
                    while it is off. */}
                <div className="settings-row">
                  <span>{strings.settings.polish}</span>
                  <Switch
                    label={strings.settings.polish}
                    checked={polish.enabled && polishEnabled}
                    disabled={!polish.enabled}
                    onChange={setPolishEnabled}
                  />
                </div>

                {polish.enabled && polishEnabled ? (
                  <>
                    <div className="settings-row">
                      <span>{strings.settings.polishModel}</span>
                      <Menu
                        align="end"
                        ariaLabel={strings.settings.polishModel}
                        value={polishModel}
                        onSelect={setPolishModel}
                        options={polishModels.map((model) => ({
                          id: model.id,
                          label: model.label,
                        }))}
                        label={
                          polishModels.find((model) => model.id === polishModel)?.label ||
                          polishModel ||
                          strings.settings.polishChooseModel
                        }
                      />
                    </div>
                    <div className="settings-row">
                      <span>{strings.settings.polishStrength}</span>
                      <Segmented<PolishStrength>
                        ariaLabel={strings.settings.polishStrength}
                        value={polishStrength}
                        onChange={setPolishStrength}
                        options={[
                          { value: 'moderate', label: strings.settings.polishModerate },
                          { value: 'strong', label: strings.settings.polishStrong },
                        ]}
                      />
                    </div>
                  </>
                ) : null}
              </div>
              <p className="settings-note">
                {polish.enabled ? strings.settings.polishNote : strings.settings.polishServerDisabled}
              </p>
              {polish.enabled && polishEnabled && polishModelsFailed ? (
                <p className="settings-note">{strings.settings.polishModelsFailed}</p>
              ) : null}
            </>
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

      {changingPassword ? (
        <ChangePasswordModal onClose={() => setChangingPassword(false)} />
      ) : null}
    </>
  );
}
