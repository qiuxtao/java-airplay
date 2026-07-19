# AirPlay Receiver

AirPlay Receiver turns a Windows PC into a receiver for iPhone, iPad, and Mac
screen mirroring with audio. It provides a modern desktop control center,
system tray operation, light and dark themes, and Chinese/English UI.

> This is an independent open-source project and is not affiliated with or
> endorsed by Apple Inc.

## Install on Windows

Download the Windows x64 installer from the latest workflow or release and run
it normally. The installer contains both Java and a private GStreamer runtime;
users do not need to install either dependency.

On first launch, allow AirPlay Receiver through Windows Defender Firewall on
private networks. Keep the PC and Apple device on the same Wi-Fi or Ethernet
network.

## Mirror an Apple device

1. Open AirPlay Receiver and wait until its status reads **Ready**.
2. Open Control Center on the iPhone or iPad and choose **Screen Mirroring**.
   On macOS, use Control Center → **Screen Mirroring**.
3. Select the receiver name shown in the application.

The receiver advertises the primary Windows display as its maximum capability,
then preserves the source device's real aspect ratio and orientation. The
player includes full screen, volume/mute, always-on-top, and stop controls.

This release intentionally focuses on live screen mirroring and its audio. It
does not promise media-URL/HLS casting, recording, screenshots, PIN pairing, or
screen casting from the PC to another device.

## Settings and troubleshooting

Settings are stored at `%APPDATA%\AirPlay Receiver\settings.json`. Logs are at
`%LOCALAPPDATA%\AirPlay Receiver\logs`; the log directory can be opened directly
from the app.

If the receiver does not appear:

- verify both devices are on the same trusted LAN;
- make sure the receiver status is **Ready**;
- allow the application on private networks in Windows Defender Firewall;
- disable Wi-Fi client isolation or guest-network isolation;
- restart the receiver after changing network adapters.

Closing the window keeps the receiver in the system tray by default. Use the
tray menu's **Exit** action to stop it completely.

## Build from source

Requirements for normal development:

- JDK 21;
- Windows, macOS, or Linux for unit tests;
- GStreamer for launching the desktop application from source.

```shell
./gradlew test
./gradlew :player:app:run
```

On Windows, set `GSTREAMER_1_0_ROOT_MSVC_X86_64` or pass
`-Dgstreamer.path=C:\path\to\gstreamer\1.0\msvc_x86_64` when running from
source.

To create the Windows installer, install/stage the official GStreamer 1.28.5
MSVC x86_64 runtime and point `GSTREAMER_RUNTIME_DIR` at its root:

```powershell
$env:GSTREAMER_RUNTIME_DIR='C:\path\to\gstreamer'
.\gradlew.bat :player:app:packageWindows
```

The output is written to `player/app/build/package/installer`.
Import a signing certificate into the Windows certificate store and set
`WINDOWS_SIGNING_KEY_USER` to its subject name to sign with `signtool`.
`WINDOWS_SIGNTOOL` and `WINDOWS_TIMESTAMP_URL` can override their defaults.
When the signing subject is absent, the build produces an unsigned installer.

## Modules

- `lib`: AirPlay pairing, FairPlay, and RTSP primitives.
- `server`: receiver control, audio, video, Bonjour, and session lifecycle.
- `player:gstreamer`: embedded Swing/GStreamer playback backend.
- `player:app`: Windows-oriented desktop product.
- `client`, `player:ffmpeg`, `player:vlc`, and `player:h264-dump`: retained
  experimental/developer modules; they are not included in the installer.

## License

The project is licensed under the MIT License. See [LICENSE](LICENSE) and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
