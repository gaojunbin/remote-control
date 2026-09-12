/** Maps a rejected gateway request to a sentence the user can act on. */
import { strings } from '../strings';
import { RequestError } from './ws';

/**
 * Read when the failure happens, never at import: a table captured at module
 * scope would keep speaking the language the app booted in.
 */
function byCode(code: string): string | undefined {
  const sentences: Record<string, string> = {
    not_found: strings.errors.notFound,
    device_offline: strings.errors.deviceOffline,
    conflict: strings.errors.conflictTerminal,
    timeout: strings.errors.timeout,
    unsupported: strings.errors.unsupported,
    too_large: strings.errors.tooLarge,
  };
  return sentences[code];
}

export function errorText(error: unknown, fallback: string = strings.errors.generic): string {
  if (error instanceof RequestError) return byCode(error.code) ?? error.message ?? fallback;
  if (error instanceof Error && error.message) return error.message;
  return fallback;
}
