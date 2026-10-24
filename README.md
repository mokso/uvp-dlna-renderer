# UVP Renderer

A minimal UPnP/DLNA MediaRenderer for old Android devices (Android 4.4 / API 19 and up). Written for the
UniFi UVP desk phone so Home Assistant can play TTS announcements on it, but it should work on any
Android 4.4+ device. The APK is about 30 KB, with no dependencies and no UI.

Why: on Android 4.4, Play Store apps no longer install, and BubbleUPnP's free version stops remote control after
20 minutes.

## Features

- Runs as a foreground service, starts at boot, and has no settings
- SSDP discovery, plus AVTransport, RenderingControl (volume and mute) and ConnectionManager
- GENA events (LastChange), so control points see the state without polling
- Plays anything Android's `MediaPlayer` can play (mp3, aac/m4a, flac, wav, ogg) over HTTP
- Supports `SetNextAVTransportURI`, pauses when another app (e.g. a phone call) takes audio focus, and has no-op Next/Previous
- Listens on HTTP port **49494**; the device description is at `http://<device-ip>:49494/desc.xml`

## Build

You need a JDK (17 works) and the Android SDK with `platforms;android-19` and `build-tools;34.0.0`. Gradle is not needed.

```powershell
.\build.ps1            # -> out\uvp-renderer.apk
.\build.ps1 -Install   # also adb install -r to the connected device
```

`build.ps1` uses `JAVA_HOME` and `%LOCALAPPDATA%\Android\Sdk`. On the first build it creates
`release.keystore` with a hard-coded password. The keystore is git-ignored. Keep it, because later versions have to be signed with the same key to install over the old one.

## Install

```sh
adb connect <device-ip>:5555
adb install -r out/uvp-renderer.apk
adb shell am start -n xyz.kerava.renderer/.MainActivity   # first start; after that it starts at boot
```

The device shows up as **UVP**. To change the name, edit `NAME` in `Renderer.java`.

## Home Assistant

1. Add the **DLNA Digital Media Renderer** integration. If the device is on another subnet (SSDP multicast does
   not cross routers), enter `http://<device-ip>:49494/desc.xml` by hand.
2. If HA runs in Docker without host networking, set `internal_url` to an address the device can reach.
   Otherwise HA hands out TTS URLs with its container IP, and the device plays nothing:
   ```yaml
   homeassistant:
     internal_url: "http://192.168.1.10:8123"
   ```
3. Test:
   ```yaml
   action: tts.speak
   target:
     entity_id: tts.piper
   data:
     media_player_entity_id: media_player.uvp
     message: Hello
   ```

The same Docker caveat applies to events. If HA's event callback address isn't reachable from the device,
playback still works, but HA updates its state by polling. To fix it, use host networking, or set the
integration's event listener port and callback URL, and publish that port.

## Test

`test/smoke.py` uses `async-upnp-client` (the library HA uses) against a running device. It runs a discovery,
a strict SCPD parse, subscribes to events, sets a URI and stops. It plays no sound.

```sh
pip install async-upnp-client
python test/smoke.py <device-ip> <this-pc-ip>
```

## Limitations

- The IP address is read once at startup. If DHCP gives the device a new address, restart the service, or better,
  reserve the address in DHCP.
- There is no seek-by-track, no playlists and no album art. It is meant for announcements and simple streams.
- Only one request per connection (no keep-alive).

## License

MIT
