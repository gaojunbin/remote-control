/**
 * The Timeline group in Settings: the detail level the transcript is drawn at,
 * `docs/DESIGN.md` § "The timeline". Simple is the default.
 */
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { SettingsPage } from '../src/features/settings/SettingsPage';
import { useSettings } from '../src/stores/settings';
import { strings } from '../src/strings';

const renderPage = () =>
  render(
    <MemoryRouter>
      <SettingsPage />
    </MemoryRouter>,
  );

describe('settings: timeline detail', () => {
  it('starts at Simple, before anything is rendered', () => {
    expect(useSettings.getState().timelineDetail).toBe('simple');
  });

  it('offers the two levels under a Timeline group, with the explanation', () => {
    renderPage();

    expect(screen.getByRole('heading', { name: strings.settings.timeline })).toBeInTheDocument();
    expect(screen.getByText(strings.settings.timelineDetail)).toBeInTheDocument();
    expect(screen.getByText(strings.settings.timelineDetailNote)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: strings.settings.timelineDetail })).toHaveTextContent(
      'Simple',
    );
  });

  it('writes the level the reader picks', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: strings.settings.timelineDetail }));
    await user.click(screen.getByRole('option', { name: 'Detailed' }));

    expect(useSettings.getState().timelineDetail).toBe('detailed');
    expect(screen.getByRole('button', { name: strings.settings.timelineDetail })).toHaveTextContent(
      'Detailed',
    );
  });
});
