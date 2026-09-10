/** Maps a rejected gateway request to a sentence the user can act on. */
import { strings } from '../strings';
import { RequestError } from './ws';

const BY_CODE: Record<string, string> = {
  not_found: strings.errors.notFound,
  device_offline: strings.errors.deviceOffline,
  conflict: strings.errors.conflictTerminal,
  timeout: strings.errors.timeout,
  unsupported: strings.errors.unsupported,
  too_large: strings.errors.tooLarge,
};

export function errorText(error: unknown, fallback: string = strings.errors.generic): string {
  if (error instanceof RequestError) return BY_CODE[error.code] ?? error.message ?? fallback;
  if (error instanceof Error && error.message) return error.message;
  return fallback;
}
