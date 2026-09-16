/**
 * Signing out empties the tab.
 *
 * Closing the socket used to be the whole of it, which left the previous
 * account's transcripts, unsent messages, slash-command lists and question
 * drafts — including a field whose own placeholder says the value is not stored
 * — in memory until the page was reloaded. On a shared or unattended browser
 * the next person had all of it.
 *
 * This is the one place that knows the list. Every store that holds something
 * of an account's has a `reset()`; adding a store means adding it here.
 */
import { useAnswers } from './answers';
import { useChat } from './chat';
import { useCommands } from './commands';
import { useConnection } from './connection';
import { useDevices } from './devices';
import { useDrafts } from './drafts';
import { useOutbox } from './outbox';
import { useSessions } from './sessions';
import { useUsers } from './users';

export function signOut(): void {
  // First, so nothing arriving over the socket writes into a store behind us.
  useConnection.getState().disconnect();
  useChat.getState().reset();
  useDrafts.getState().reset();
  useOutbox.getState().reset();
  useAnswers.getState().reset();
  useCommands.getState().reset();
  useSessions.getState().reset();
  useDevices.getState().reset();
  useUsers.getState().reset();
}
