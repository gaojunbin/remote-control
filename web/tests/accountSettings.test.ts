/**
 * A24: what this app remembers belongs to the person signed in, not to the
 * browser. Two people who share one browser keep their own language, dictation
 * language and timeline detail. `docs/DESIGN.md` § "Accounts".
 *
 * The store keeps one preference store for the whole module, so each test uses
 * account names of its own rather than clearing something it does not own.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { readSettingsFor, useSettings } from '../src/stores/settings';

const defaults = { language: 'en', sttLanguage: 'auto', timelineDetail: 'simple' };

const current = () => ({
  language: useSettings.getState().language,
  sttLanguage: useSettings.getState().sttLanguage,
  timelineDetail: useSettings.getState().timelineDetail,
});

beforeEach(() => {
  readSettingsFor(null);
});

describe('per-account settings', () => {
  it("gives a new account the defaults, not the last person's choices", () => {
    readSettingsFor('one.a');
    useSettings.getState().setLanguage('zh-Hans');
    useSettings.getState().setSttLanguage('zh');

    readSettingsFor('one.b');
    expect(current()).toEqual(defaults);
  });

  it('reads an account its own choices back when it signs in again', () => {
    readSettingsFor('two.a');
    useSettings.getState().setLanguage('zh-Hans');
    useSettings.getState().setTimelineDetail('detailed');

    readSettingsFor('two.b');
    expect(current()).toEqual(defaults);

    readSettingsFor('two.a');
    expect(current()).toEqual({
      language: 'zh-Hans',
      sttLanguage: 'auto',
      timelineDetail: 'detailed',
    });
  });

  it('keeps the login screen, where nobody is signed in, on its own key', () => {
    useSettings.getState().setLanguage('zh-Hans');

    readSettingsFor('three.a');
    expect(useSettings.getState().language).toBe('en');

    readSettingsFor(null);
    expect(useSettings.getState().language).toBe('zh-Hans');
  });
});
