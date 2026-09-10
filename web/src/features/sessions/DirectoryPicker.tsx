import { useCallback, useEffect, useState } from 'react';
import { ChevronUp, Folder, GitBranch } from 'lucide-react';
import { Button } from '../../components/Button';
import { Modal } from '../../components/Modal';
import { rpc } from '../../lib/gateway';
import { tildePath } from '../../lib/format';
import { strings } from '../../strings';
import type { DirsResult } from '../../protocol/frames';

interface Props {
  open: boolean;
  deviceId: string | null;
  startPath?: string;
  onClose: () => void;
  onPick: (path: string) => void;
}

/**
 * Mounting the picker only while it is open keeps the first listing out of an
 * effect that would otherwise have to reset state synchronously on every open.
 */
export function DirectoryPicker(props: Props) {
  if (!props.open) return null;
  return <Picker {...props} />;
}

function Picker({ deviceId, startPath, onClose, onPick }: Props) {
  const [listing, setListing] = useState<DirsResult | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(
    async (path?: string) => {
      if (!deviceId) return;
      try {
        const result = await rpc('device.dirs', {
          device_id: deviceId,
          ...(path ? { path } : {}),
        });
        setListing(result);
        setError(null);
      } catch (err) {
        setError(err instanceof Error ? err.message : strings.errors.generic);
      } finally {
        setLoading(false);
      }
    },
    [deviceId],
  );

  // The first listing resolves asynchronously, so the effect body itself never
  // touches state synchronously.
  useEffect(() => {
    if (!deviceId) return;
    let cancelled = false;
    void rpc('device.dirs', {
      device_id: deviceId,
      ...(startPath ? { path: startPath } : {}),
    })
      .then((result) => {
        if (cancelled) return;
        setListing(result);
        setLoading(false);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(err instanceof Error ? err.message : strings.errors.generic);
        setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [deviceId, startPath]);

  const openDir = (path?: string) => {
    setLoading(true);
    void load(path);
  };

  return (
    <Modal
      open
      onClose={onClose}
      title={strings.newSession.browseTitle}
      width={520}
      showClose
      footer={
        <>
          <Button onClick={onClose}>{strings.common.cancel}</Button>
          <Button
            variant="primary"
            disabled={!listing}
            onClick={() => {
              if (listing) onPick(listing.path);
              onClose();
            }}
          >
            {strings.newSession.browseUse}
          </Button>
        </>
      }
    >
      <div className="picker-path mono">
        {listing ? tildePath(listing.path) : strings.common.loading}
      </div>
      {error ? <p className="login-error">{error}</p> : null}
      <ul className="picker-list scroll-thin">
        {listing?.parent ? (
          <li>
            <button type="button" onClick={() => openDir(listing.parent ?? undefined)}>
              <ChevronUp size={15} aria-hidden />
              <span>{strings.newSession.browseUp}</span>
            </button>
          </li>
        ) : null}
        {listing?.entries.map((entry) => (
          <li key={entry.path}>
            <button type="button" onClick={() => openDir(entry.path)}>
              <Folder size={15} aria-hidden />
              <span className="mono">{entry.name}</span>
              {entry.is_git ? <GitBranch size={13} aria-hidden className="picker-git" /> : null}
            </button>
          </li>
        ))}
        {listing && listing.entries.length === 0 && !loading ? (
          <li className="picker-empty hint">{strings.newSession.browseEmpty}</li>
        ) : null}
      </ul>
    </Modal>
  );
}
