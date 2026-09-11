/**
 * How dictation meets the message field. The controller itself is tested in
 * voice.test.ts; what matters here is the wiring: the mic button starts it,
 * Cancel hands the draft back, Done leaves the transcript to be edited, and
 * nothing reaches the agent until Send is clicked.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { claudeAgent } from '../mock/fixtures';
import { strings } from '../src/strings';
import type * as ReactModule from 'react';
import type { Session } from '../src/protocol/types';

const voice = vi.hoisted(() => ({
  publish: null as null | ((text: string, isFinal: boolean) => void),
}));

vi.mock('../src/features/voice/useVoice', async () => {
  const { useState } = await vi.importActual<typeof ReactModule>('react');
  return {
    useVoice: (options: { onTranscript: (text: string, isFinal: boolean) => void }) => {
      const [state, setState] = useState('idle');
      voice.publish = options.onTranscript;
      return {
        state,
        level: 0.4,
        elapsedMs: 12_000,
        error: null,
        start: () => setState('listening'),
        done: () => setState('idle'),
        cancel: () => setState('idle'),
        dismissError: () => setState('idle'),
      };
    },
  };
});

const { Composer } = await import('../src/features/chat/Composer');

const session: Session = {
  session_id: 'ses-1',
  device_id: 'dev-1',
  agent: 'claude',
  title: 'Fix flaky auth test',
  cwd: '/Users/me/dev/gateway',
  git: null,
  state: 'idle',
  state_detail: null,
  origin: 'remote',
  control: 'remote',
  model: 'claude-sonnet-4-5',
  permission_mode: 'default',
  effort: 'high',
  created_at: 1,
  updated_at: 2,
  last_seq: 0,
  archived: false,
  turn: null,
  todos: null,
  usage: null,
  queued: 0,
};

function setup() {
  const onSend = vi.fn().mockResolvedValue(undefined);
  render(
    <Composer
      session={session}
      agent={claudeAgent}
      deviceOnline
      queue={[]}
      sttEnabled
      sttLanguages={['auto']}
      onSend={onSend}
      onSetOption={vi.fn()}
      onRemoveQueued={vi.fn()}
      onTakeover={vi.fn()}
    />,
  );
  return { onSend, field: () => screen.getByLabelText(strings.composer.placeholder) };
}

const say = (text: string, isFinal = false) => act(() => voice.publish?.(text, isFinal));

const controlRowButtons = (): string[] =>
  [...document.querySelectorAll('.voice-controls button')].map((b) => b.textContent ?? '');

beforeEach(() => {
  voice.publish = null;
});

describe('dictation in the composer', () => {
  it('starts listening on the mic button, with no chord to hold', async () => {
    const user = userEvent.setup();
    setup();

    await user.keyboard('{Alt>} {/Alt}');
    expect(document.querySelector('.voice-controls')).toBeNull();

    await user.click(screen.getByRole('button', { name: strings.composer.micStart }));

    expect(document.querySelector('.voice-controls')).not.toBeNull();
    expect(screen.getByText(strings.voice.transcribing)).toBeInTheDocument();
  });

  it('offers exactly Cancel and Done, and an elapsed timer, while listening', async () => {
    const user = userEvent.setup();
    setup();
    await user.click(screen.getByRole('button', { name: strings.composer.micStart }));

    expect(controlRowButtons()).toEqual([strings.voice.cancel, strings.voice.done]);
    expect(screen.getByLabelText(strings.voice.listeningFor('0:12'))).toHaveTextContent('0:12');
    // The send button and the mic itself are not part of the listening row.
    expect(screen.queryByRole('button', { name: strings.composer.send })).toBeNull();
    expect(screen.queryByRole('button', { name: strings.composer.micStart })).toBeNull();
  });

  it('appends the transcript to the draft the mic was pressed on', async () => {
    const user = userEvent.setup();
    const { field } = setup();
    await user.click(field());
    await user.keyboard('after lunch');
    await user.click(screen.getByRole('button', { name: strings.composer.micStart }));

    say('run the');
    say('run the auth suite');

    expect(field()).toHaveValue('after lunch run the auth suite');
  });

  it('hands the draft back exactly as it was on Cancel', async () => {
    const user = userEvent.setup();
    const { field, onSend } = setup();
    await user.click(field());
    await user.keyboard('after lunch');
    await user.click(screen.getByRole('button', { name: strings.composer.micStart }));
    say('run the auth suite');

    await user.click(screen.getByRole('button', { name: strings.voice.cancel }));

    expect(field()).toHaveValue('after lunch');
    expect(onSend).not.toHaveBeenCalled();
    expect(document.querySelector('.voice-controls')).toBeNull();
  });

  it('leaves the transcript in the field on Done and sends nothing', async () => {
    const user = userEvent.setup();
    const { field, onSend } = setup();
    await user.click(screen.getByRole('button', { name: strings.composer.micStart }));
    say('run the auth suite');

    await user.click(screen.getByRole('button', { name: strings.voice.done }));
    say('run the auth suite on CI', true);

    expect(field()).toHaveValue('run the auth suite on CI');
    expect(onSend).not.toHaveBeenCalled();

    // It is an ordinary draft now: the ordinary Send button is what sends it.
    await user.click(screen.getByRole('button', { name: strings.composer.send }));
    expect(onSend).toHaveBeenCalledWith('run the auth suite on CI', [], 'auto');
  });

  it('lets a keystroke take the field back from dictation', async () => {
    const user = userEvent.setup();
    const { field } = setup();
    await user.click(screen.getByRole('button', { name: strings.composer.micStart }));
    say('run the auth suite');

    await user.click(field());
    await user.keyboard(' twice');
    say('run the auth suite again');

    expect(field()).toHaveValue('run the auth suite twice');
    expect(document.querySelector('.voice-controls')).toBeNull();
  });

  it('hides the microphone entirely when the gateway has no speech backend', () => {
    render(
      <Composer
        session={session}
        agent={claudeAgent}
        deviceOnline
        queue={[]}
        sttEnabled={false}
        sttLanguages={['auto']}
        onSend={vi.fn()}
        onSetOption={vi.fn()}
        onRemoveQueued={vi.fn()}
        onTakeover={vi.fn()}
      />,
    );
    expect(screen.queryByRole('button', { name: strings.composer.micStart })).toBeNull();
  });
});

describe('product name', () => {
  it('reads "Remote Control" wherever the user sees it', () => {
    expect(strings.productName).toBe('Remote Control');
    expect(strings.login.title).toBe('Remote Control');
  });
});
