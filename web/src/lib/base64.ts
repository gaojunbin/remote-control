/**
 * Byte length of a base64 payload, without decoding it.
 *
 * `session.send` carries attachments as base64 and the device computes the size
 * (amendment A3), so an optimistic row has to work it out for itself.
 */
export function base64Size(data: string): number {
  const padding = data.endsWith('==') ? 2 : data.endsWith('=') ? 1 : 0;
  return Math.max(0, Math.floor(data.length / 4) * 3 - padding);
}
