import { useEffect, useMemo, useState } from 'react';
import { Plus } from 'lucide-react';
import { Button } from '../../components/Button';
import { ConfirmDialog, Modal } from '../../components/Modal';
import { strings } from '../../strings';
import { useDevices } from '../../stores/devices';
import { useSessions } from '../../stores/sessions';
import type { Device } from '../../protocol/types';
import { AddDeviceModal } from './AddDeviceModal';
import { DeviceRow } from './DeviceRow';
import './devices.css';

export function DevicesPage() {
  const devices = useDevices((s) => s.devices);
  const loaded = useDevices((s) => s.loaded);
  const load = useDevices((s) => s.load);
  const rename = useDevices((s) => s.rename);
  const revoke = useDevices((s) => s.revoke);
  const sessions = useSessions((s) => s.sessions);

  const [adding, setAdding] = useState(false);
  const [renaming, setRenaming] = useState<Device | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [revoking, setRevoking] = useState<Device | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!loaded) void load().catch(() => undefined);
  }, [loaded, load]);

  const sessionCounts = useMemo(() => {
    const counts: Record<string, number> = {};
    for (const session of Object.values(sessions)) {
      if (session.archived) continue;
      counts[session.device_id] = (counts[session.device_id] ?? 0) + 1;
    }
    return counts;
  }, [sessions]);

  const onlineCount = devices.filter((d) => d.online).length;

  return (
    <>
      <div className="page-head">
        <div>
          <h1>{strings.devices.title}</h1>
          <p className="hint">{strings.devices.subtitleCount(onlineCount, devices.length)}</p>
        </div>
        <Button variant="primary" onClick={() => setAdding(true)}>
          <Plus size={15} aria-hidden />
          {strings.devices.add}
        </Button>
      </div>

      {devices.length === 0 ? (
        <div className="card empty">
          <strong>{strings.devices.empty}</strong>
          {strings.devices.emptyHint}
        </div>
      ) : (
        <ul className="device-list card">
          {devices.map((device) => (
            <DeviceRow
              key={device.device_id}
              device={device}
              sessionCount={sessionCounts[device.device_id] ?? 0}
              onRename={() => {
                setRenaming(device);
                setRenameValue(device.name);
              }}
              onRevoke={() => setRevoking(device)}
            />
          ))}
        </ul>
      )}

      <AddDeviceModal open={adding} onClose={() => setAdding(false)} />

      <Modal
        open={renaming !== null}
        onClose={() => setRenaming(null)}
        title={strings.devices.renameTitle}
        width={420}
        footer={
          <>
            <Button onClick={() => setRenaming(null)}>{strings.common.cancel}</Button>
            <Button
              variant="primary"
              busy={busy}
              disabled={renameValue.trim().length === 0}
              onClick={async () => {
                if (!renaming) return;
                setBusy(true);
                try {
                  await rename(renaming.device_id, renameValue.trim());
                  setRenaming(null);
                } finally {
                  setBusy(false);
                }
              }}
            >
              {strings.common.save}
            </Button>
          </>
        }
      >
        <label className="label" htmlFor="rc-rename">
          {strings.devices.renameLabel}
        </label>
        <input
          id="rc-rename"
          className="field"
          value={renameValue}
          onChange={(e) => setRenameValue(e.target.value)}
        />
      </Modal>

      <ConfirmDialog
        open={revoking !== null}
        title={strings.devices.revokeTitle}
        body={revoking ? strings.devices.revokeBody(revoking.name) : ''}
        confirmLabel={strings.devices.revokeConfirm}
        danger
        busy={busy}
        onClose={() => setRevoking(null)}
        onConfirm={async () => {
          if (!revoking) return;
          setBusy(true);
          try {
            await revoke(revoking.device_id);
            setRevoking(null);
          } finally {
            setBusy(false);
          }
        }}
      />
    </>
  );
}
