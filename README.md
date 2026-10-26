# GRNLR v0.4 — Stereo Granular Groovebox for Android

A landscape Android starter project with four granular sample tracks, stereo WAV import/playback, master echo, reverb, saturation, mixer and a gamepad-oriented UI. Inspired by the compact LCD UI language of hardware grooveboxes; not affiliated with Elektron.

## Stereo implementation
- The audio engine outputs stereo PCM 16-bit at 44.1 kHz.
- Stereo PCM 16-bit WAV samples preserve their left/right channels from import through playback.
- Mono PCM 16-bit WAV files are duplicated to both channels.
- Echo has separate left/right delay buffers; reverb uses separate cross-coupled stereo states.
- Track pan adjusts the stereo balance without converting the sample to mono.

## Screens
- Tracks: four sample tracks with granular controls and load actions
- FX: echo, reverb and saturation
- Mixer: track level, pan, mute, solo and master level
- Samples: loaded sample list and controller mapping

## Build an APK using GitHub Actions
1. Download and extract this ZIP.
2. Create a repository at https://github.com/new.
3. Upload all extracted files to the repository root, including `.github/workflows/android.yml`.
4. Commit the files.
5. Open **Actions** and select **Build Android APK**.
6. When the workflow completes, open the run and download `GRNLR-v0.4-stereo-apk`.

You do not need Android Studio to build via GitHub Actions.

## Controller intent
Designed for touchscreen-free use after connecting a DualShock 4 to Android:
- D-pad left/right: select track on Tracks screen; otherwise change page
- Cross (Android `BUTTON_A`): load selected track on Samples screen; play/stop elsewhere
- Circle (`BUTTON_B`): previous page
- L1/R1: previous/next page
- L2/R2: density down/up
- Triangle (`BUTTON_Y`): solo selected track
- Square (`BUTTON_X`): mute selected track
- Options/Start: play/stop

Controller button codes can vary by Android device. Full analog-stick axis mapping and true focus-driven file browsing are still prototype tasks and should be tested/finished on the target device.

## Current limitations
This is an early prototype, not a finished low-latency instrument. The WAV loader currently accepts PCM 16-bit WAV. The Android system picker is used to choose files, so the exact picker UI depends on the device. Test audio latency, stability and controller behavior before using it live.
