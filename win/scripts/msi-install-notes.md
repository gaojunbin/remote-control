### Install the Windows app

1. Download `Remote-Control-<version>.msi` below and run it. It installs for your Windows account
   only, adds **Remote Control** to the Start menu, and a later installer upgrades it in place.
2. The installer is not code-signed, so the first time SmartScreen says "Windows protected your PC":
   choose **More info**, then **Run anyway**.
3. Sign in with your gateway's address (`https://…`), your username and your password.

Windows 10 or 11, x64; the installer carries its own Java runtime. The `.sha256` file beside it holds
its checksum (`certutil -hashfile Remote-Control-<version>.msi SHA256`).
