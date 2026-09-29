### Install the Mac app

1. Download `Remote-Control-<version>.dmg` below, open it, and drag **Remote Control** onto
   **Applications**.
2. The app is signed to run locally but not notarized, so the first time you open it macOS says it
   cannot verify it. Open **System Settings → Privacy & Security**, scroll to **Security** and choose
   **Open Anyway** — or run `xattr -dr com.apple.quarantine "/Applications/Remote Control.app"` once.
3. Sign in with your gateway's address (`https://…`), your username and your password.

macOS 15 or later, Apple silicon and Intel. The `.sha256` file beside the image holds its checksum
(`shasum -a 256 -c Remote-Control-<version>.dmg.sha256`). The gateway, the web app and the device
client install as before: `docker compose up -d` on the VPS and `install.sh` on each machine.
