# WatchBabyMonitor

A Wear OS watch sits on its charger next to the crib, listens for crying on-device, and makes your Android phone ring like an alarm clock. No cloud, no account; audio never leaves the watch.

```
Watch (by the crib)                               Phone (with you)
mic 16 kHz → energy gate → YAMNet → debounce ──► listener → alarm (full-screen, alarm stream)
                       heartbeat every 15 s ──► watchdog: "Monitor lost" after 60 s of silence
```

> This is a convenience alert, not a safety or medical device. Keep the baby within earshot or keep a normal monitor running until it has proven itself over many nights.

## Status

Phases 1–3 of the plan: detection on the watch, alarm on the phone, heartbeat watchdog and fault reporting. History, tile and custom cry model come later.

## Install

Every push to `main` builds both APKs in GitHub Actions and attaches them to a release (`Build N`).

**Phone:** download `BabyMonitor-phone.apk` from the latest release and open it on the phone.

**Watch** (Wear OS 3+, paired to that phone):

1. On the watch: Settings → System → About → tap *Build number* 7 times → Developer options → turn on *ADB debugging* and *Debug over Wi-Fi* (or *Wireless debugging* and pair).
2. From a computer on the same Wi-Fi:
   ```
   adb connect <watch-ip>:5555        # or adb pair <ip>:<port> for Wireless debugging
   adb -s <watch-ip>:5555 install -r BabyMonitor-watch.apk
   ```

Both APKs share one `applicationId` and one signing key (`keystore/shared-debug.keystore`). The Wearable Data Layer only delivers messages between apps that match on both, so always install the two APKs from the **same** release.

## First run

1. Phone: open the app and clear every item in **Setup** (notifications, full-screen alarm, battery optimisation; Do Not Disturb access is optional).
2. Watch: open *Baby Monitor*, tap **Start**, allow the microphone.
3. Phone: tap **Test alert**. The phone should ring within a few seconds. Tap **I'm on it**.
4. Put the watch on its charger 1–2 m from the crib (never inside it), mic facing the baby.

Sensitivity (on the watch): *Low / Medium / High* moves both the loudness gate and the cry-score threshold. Start on Medium; the watch shows the live level and cry score to help you tune it.

## How it decides

- Audio is read in 0.5 s hops into a sliding 0.975 s window (YAMNet's input).
- If the hop is quieter than the gate, the model isn't run (battery).
- Score = max of YAMNet's *Baby cry, infant cry* and *Crying, sobbing* classes.
- Crying **starts** when 3 of the last 5 windows pass the threshold (~2.5 s of crying) and **stops** after 20 s without a positive window.
- The phone alarm keeps ringing until someone taps **I'm on it**, even if the crying stops.

## What makes the phone ring

| Event | Phone shows |
| --- | --- |
| Sustained crying | Baby is crying |
| No heartbeat for 60 s while monitoring | Monitor lost |
| Watch mic failure, model failure, service killed, battery < 20% unplugged | Monitor problem |
| **Test alert** button | Test alert |

Tap **End session** on the phone if you stopped monitoring without the watch telling the phone (otherwise "Monitor lost" will ring).

## Build locally

```
mkdir -p watch/src/main/assets
curl -L -o watch/src/main/assets/yamnet.tflite \
  https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite
./gradlew :watch:assembleDebug :phone:assembleDebug
```

## Layout

- `shared/` message paths, payload format, Data Layer sender, icons
- `watch/` foreground mic service, YAMNet detector, debouncer, Wear Compose UI
- `phone/` listener service, alarm, heartbeat watchdog, setup screen
