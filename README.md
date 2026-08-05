<img width="1280" height="640" alt="Hyperion Grabber Reborn" src="https://github.com/user-attachments/assets/80f2cdb0-f79a-47b9-ac09-efedaf90931a" />


# Hyperion Android Reborn

A screen grabber for [Hyperion](https://hyperion-project.org/) that turns your Android TV or phone into an ambilight source. It grabs whatever's on screen and sends it to your Hyperion/HyperHDR box so your LEDs match what you're watching.

Works on Android 5.0+ (API 21+), including Android 12, 13, 14 and anything newer. One APK covers both the TV (Leanback) and phone interfaces.

---

## What it does

- Captures the screen and streams it to Hyperion using the FlatBuffers protocol (lighter than the old Protobuf setup)
- **Two capture methods**:
  - *Standard* - the default, simplest path
  - *Codec (compatibility)* - sends the screen through an H.264 encoder/decoder instead. This fixes the black-screen problem a lot of TVs (TCL, Amlogic, Hisense...) have with the normal path
- **Capture resolution** setting (Small / Medium / High) so low-RAM devices don't struggle
- Adjustable capture rate (10-60 fps)
- Handy extras: send average screen color, audio visualization mode, RGB sync animation
- Start on boot, auto-reconnect, and a network scanner that finds your Hyperion for you
- Three ways to toggle it on/off: a home-screen **widget**, a **quick-settings tile** (phones), and a "Hyperion Grabber (Toggle)" shortcut

---

## Requirements

- Android 5.0+ (API 21+)
- A Hyperion or HyperHDR instance your device can reach on the network

---

## Download

Grab the latest APK from the [Releases page](https://github.com/evanwhitt/hyperion-android-reborn/releases).

---

## Setting it up

1. Install the APK and open the app.
2. On TV, it'll offer to **scan your network** for a running Hyperion server - or hit "Manual setup" and type in the host and port (FlatBuffers port, usually **19400**).
3. On a phone, open **Settings** and enter the host and port.
4. Tap the power button and accept the **screen recording** prompt.
5. That's it - your LEDs should follow the screen.

To turn it off, tap the power button again, use the widget/tile, or use the Toggle shortcut.

---

## Settings

- **Hyperion Host / Port** - where your Hyperion server lives (usually port 19400)
- **Message Priority** - the priority channel to use (default 100)
- **Capture Rate** - how many frames per second get sent (10-60)
- **Capture Method** - Standard, or *Codec (compatibility)* if the screen comes through black
- **Capture Resolution** - Small / Medium / High. Small is the friendliest for weaker hardware
- **Send Average Screen Color** - sends one flat color instead of the whole image
- **Reconnect** - automatically reconnect if the connection drops
- **Grab on Boot** - start capturing when the device boots up

---

## TCL TVs

TCL's watchdog really doesn't like this app and will kill it in the background. To stop that:

1. Go to **Settings > Apps > Hyperion Grabber**
2. Turn on **"Auto-start"** or **"Allow background activity"**
3. Some TCL TVs hide this under **Settings > Privacy > Special app access > Auto-start**

If video plays but your LEDs stay black, switch **Capture Method** to *Codec (compatibility)*.

---

## Disabling the Screen Recording Indicator (Android TV)

On newer Android/Google TV releases (14+), a persistent status bar chip or screen recording icon whenever screen capture is active. You can attempt to disable this indicator via ADB:

```sh
adb shell device_config put privacy media_projection_indicators_enabled false default
```

If that woks, you can optionally, prevent system device config updates from automatically re-enabling it as that can occur after system updates with `adb shell device_config set_sync_disabled_for_tests persistent`. However this is only needed if the command above gets reverted regularly, **do not use this** unless you are sure you need it.

> [!WARNING]
> Modifying `device_config` flags or disabling config sync may cause unexpected side effects, system instability, or break on future system updates. Use at your own risk. Review the resources below for more info.

More info:
- [droidVNC-NG Issue #324](https://github.com/bk138/droidVNC-NG/issues/324)
- [Android Developer: Media projection](https://developer.android.com/media/grow/media-projection)

---

## Troubleshooting

- **Black screen while something's playing** - try **Capture Method → Codec (compatibility)**. Some TVs draw video on a separate hardware layer that the normal path can't see. (Heads up: DRM stuff like Netflix will always show up black, that's not something the app can get around.)
- **"Screen recording permission denied"** - some TCL builds refuse to show the permission prompt at all (that's a TV firmware thing). You can sometimes force it over ADB:
  `adb shell appops set com.hyperion.grabber PROJECT_MEDIA allow`
- **Stuttering on 4K** - cheaper TVs can't decode 4K and capture at the same time. Try **Capture Resolution: Small**, drop the **Capture Rate** to 10-15, or use **Send Average Screen Color**.
- **Lights stay on after stopping** - clear the priority on the Hyperion side, or just force-stop the app.

---

## Support

- [Hyperion project](https://hyperion-project.org/)
- Found a bug or want a feature? [Open an issue](https://github.com/evanwhitt/hyperion-android-reborn/issues)
