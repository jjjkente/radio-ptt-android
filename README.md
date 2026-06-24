# radio-ptt-android

Phase 1 proof-of-concept Android client for the JJJK self-hosted PTT radio
system — replaces the stock Inrico PTT app, talks to `radio-ptt-server` for
a join token and to the self-hosted LiveKit instance (`radio-ptt`) for the
actual voice channel.

**This has not been built or run yet** — there's no Android SDK/Gradle in
the environment this was scaffolded from. Open it in Android Studio to do
the first real build.

## Before building

1. In the `radio-ptt-server` admin UI (`http://192.168.69.222:5057/admin.html`),
   create a channel and a device, copy the device's API key (shown once).
2. Edit `app/src/main/java/com/jjjk/radioptt/MainActivity.kt`: set
   `deviceApiKey` to that key, and confirm `serverUrl` still points at
   `http://192.168.69.222:5057` (or wherever `radio-ptt-server` ends up).
3. Check `app/build.gradle.kts` for a newer `io.livekit:livekit-android`
   release before building — pinned to `2.10.0` at scaffold time.

## What's deliberately not here yet

- Hardware PTT side-button wiring — this version uses an on-screen button
  only. Phase 0 (flash one radio, run `adb shell getevent` while pressing
  the side button) needs to confirm the actual keycode before wiring it up
  via `dispatchKeyEvent`/`onKeyDown` in `MainActivity`.
- GPS reporting, call recording, multi-venue/channel switching UI, kiosk
  lock-down (Screen Pinning / Device Owner) — later phases per the project
  plan.

## Testing once built

Install on two unlocked-firmware radios (or two phones, for a first sanity
check before real hardware), assign both devices to the same channel in
the admin UI, hold the button on one and confirm audio comes through the
other.
