# YouRec for Android

A port of [YouRec](../README.md) to Android, in Kotlin. Everything here lives
under `Android/`; the iPhone app and its documents are untouched. The
[specification](../SPEC.md) is shared: this port implements its *Confirmed*
behaviour, and where the two platforms differ the difference is written down
here rather than left to be discovered.

**Status: first milestone, awaiting hardware acceptance.** What has been
observed so far, and where:

- The platform-independent core passes its checks (`scripts/test-core.sh`).
- The Gradle build produces a debug APK on GitHub Actions.
- On an Android 14 emulator in the same workflow, `scripts/emulator-smoke.py`
  drives the app through record, camera switch while recording, bookmark,
  pause, resume, stop, edit, play, split, undo, redo and export, and checks the
  files: two upright 1080×1920 H.264/AAC segments without a rotation flag, the
  capture events beside them, a project document, and an exported movie whose
  length matches the takes to within a frame.

It has **not been run on a phone**. An emulator's camera is a synthetic scene
and its encoder is far slower than real time, so none of this is evidence of
correct framing, mirroring, focus, audio/video sync or performance on a real
sensor. Following the project's own [camera rule](../docs/CAMERA-QA.md), those
stay open until [the device checks](docs/DEVICE-QA.md) have been carried out.

## What is ported

The scope follows the specification's own implementation sequence: durable
capture, recording controls, the cut editor, export.

- **Projects** with the iPhone app's document format: a small atomic
  `project.json`, immutable edit snapshots named by their SHA-256 under
  `History/`, a capture ledger separate from Undo, rotating index backups.
  Undo and redo survive closing the app, including closing after an undo.
  Recording adds footage to every retained edit state without becoming an undo
  step. Rename, reversible **Recently Deleted** with a 30-day confirmation,
  video import into managed storage. A take that was not yet indexed when the
  app died is indexed at the next launch; a take that was still being recorded
  is rebuilt, up to the last second that reached the disk, from a journal of
  its encoded samples kept beside the movie until it closes (`TakeJournal.kt`).
- **Recorder**: full-screen preview with overlaid controls. Record, Pause,
  Resume and Stop into immutable source segments; continue a project later.
  Switch cameras *while recording*: the last frame is held in preview and movie
  while the destination camera starts, and the independent microphone keeps
  running. Zoom presets with a timed, eased ramp and per-camera zoom memory.
  Focus lock, manual lens position and return to autofocus, remembered per
  camera. Microphone name, level meter, selection and fallback warning.
  Bookmarks while recording. 1080p or 4K at 30 fps, SDR H.264.
- **Editor**: preview rendered directly from the sources, a shared
  thumbnail/waveform timeline with pinch zoom and a centred playhead, **Split**,
  **Start/End/Clear** range marking and **Delete** with gap closure, trim
  handles (shorten, or reveal trimmed material), **Move** with snapping and
  Done/Cancel, **Unlink**/**Link** for independent picture and sound lanes,
  **Close gap**, **Volume** with mute, **Fade in** and **Fade out**, bookmarks,
  cut navigation, **Add video**, **Add audio**, persistent Undo/Redo.
  **Speed** from 0.25× to 4× with the pitch of the sound preserved, **Crop**
  with zoom, position, rotation and the project shapes (Original, 16:9, 9:16,
  1:1, 4:5), and **Mirror**, on the selected clips or the whole project.
  **Text** puts timed titles on the picture in the four plain styles and six
  colours, with size, position and duration; a title stays with its footage
  through cuts, moves and speed changes.
  **Placement** arranges videos that overlap: Full, Side by side, Stacked and
  Inset presets, front/back order, Fit or Fill, and each picture's window and
  framing.
- **Export** to a new MP4 (H.264 or H.265, project size/720p/1080p/4K, 24–60
  fps, AAC), with a receipt naming the edit revision and source checksums. An
  unchanged edit offers its existing movie. Share, or save to the gallery.
  Sources and earlier exports are never rewritten.

The port follows the iPhone app as of upstream commit `dadc258` (project
schema 16). When the iPhone app moves on, compare `YouRec/Project.swift`,
`TrackTimeline.swift`, `TimelineEditing.swift` and `TimelineNavigation.swift`
with their `.kt` counterparts first: they define the shared document.

## What is not ported yet

Each of these exists on iPhone and is absent here. A project that uses one of
them is **refused by name** on Android ("This project uses Transitions…")
instead of being opened and quietly losing that work on the next save, the same
policy older iPhone builds apply to newer projects.

Subtitles and the Qwen3-ASR model · Title designs, looks and outlines, imported fonts,
animated titles ·
Color LUTs, Log, HDR capture and export · Video effects and their Metal
shaders · Transitions · Dragging and pinching a placed picture in the preview,
and its edge-crop handles (edge crops made on iPhone are kept and drawn) ·
animated Crop and Placement · Image overlays · Photos
as clips · Stabilization · Enhance audio (RNNoise, DeepFilterNet3, LavaSR,
VoiceFixer) · Censor audio · Pause cuts · Copy/paste · Cover picker · Original
recordings browser · Duplicate, Zip project and OpenTimelineIO · Dual-camera
layouts, live effects, face follow, front light, horizon level, focus peaking ·
Record-here insertion ·
Landscape editor layout · Label and headline cards in Text · Color, Crop,
Mirror, Stabilization and effects limited to a range of a clip or spanning
several clips · Variable aperture (the control exists on iPhone only for lenses
that have one) · The Mac and iPad editions (desktop recorder, picture-in-picture,
project library).

Smaller gaps inside what *is* ported, all in the recorder: lenses hidden behind
a logical multi-camera are reached through zoom rather than listed separately;
the ultra-wide's **1× Main** preset does not switch lens; there is no
reorderable microphone preference list (Automatic is Android's own choice);
Bluetooth microphones are listed but no SCO link is opened for them; if every
microphone disappears the take continues with picture only and a warning.

One of the larger items deserves a note. **Open formats:** the Kotlin `ProjectCoding.kt` reads
and writes the same JSON as the Swift `Codable` types, so the unported tools
are a matter of adding fields and renderers, not of converting documents.

## Choices

- **The Android framework and nothing else.** No AndroidX, CameraX, Compose,
  Media3 or other library; no layout XML. Camera2, MediaCodec, MediaMuxer,
  AudioRecord/AudioTrack and OpenGL ES are used directly, and views are built
  in code. The iPhone app is written the same way against AVFoundation and
  Metal, with its few dependencies vendored. The only resource is the icon.
- **Flat sources named after their Swift counterparts.** `YouRec/TrackTimeline.kt`
  is `YouRec/TrackTimeline.swift`, and so on; read them side by side.
- **Value semantics kept explicit.** Swift structs became immutable Kotlin data
  classes; a timeline is copied before it is edited, and an edit is a pure
  function from one state to the next (`TimelineEditing.kt`).
- **One clock.** `EditTime` is the same 240 000-tick clock, rounded the same
  way, so both apps agree on every boundary of a shared project.
- **One render plan.** Preview and export ask `RenderPlan` and `AudioMix` what
  to show and play at each instant; neither has a second opinion.
- **Pixels are stored upright.** A take's shape is fixed when it starts and the
  camera picture is rotated in OpenGL, so recordings carry no rotation flag.

| iPhone | Android |
| --- | --- |
| `Project.swift`, `ProjectRecovery.swift` | `Project.kt`, `ProjectCoding.kt`, `JSON.kt` |
| `TrackTimeline.swift`, `TimelineEditing.swift`, `TimelineNavigation.swift` | `TrackTimeline.kt`, `TimelineEditing.kt` |
| `ProjectStore.swift` | `ProjectStore.kt` |
| `CaptureController.swift`, `CaptureSegmentWriter.swift`, `CaptureAudio.swift`, `RecorderView.swift`, `ZoomTransition.swift` | the same names, `.kt` |
| `TrackRenderer.swift`, `MediaRenderer.swift`, `SourceMediaCache.swift`, `WaveformDetail.swift` | `RenderPlan.kt`, `MediaRenderer.kt`, `SourceMediaCache.kt`, `GLFrame.kt` |
| `EditorController.swift`, `EditorView.swift`, `EditorTimeline.swift`, `MovieExportPanel.swift`, `ContentView.swift` | the same names, `.kt` |
| `YouRecApp.swift` | `YouRecApp.kt`, `Navigation.kt`, `Design.kt` |

## Build and run

Requires JDK 17 or later and the Android SDK (platform 35). Android Studio can
open the `Android` folder directly. From a terminal:

```sh
cd Android
./gradlew assembleDebug
adb install -r build/outputs/apk/debug/YouRec-debug.apk
```

The application identifier is `com.antirez.yourec`, matching the iPhone bundle.
For your own device build or a fork, use an identifier you own:
`./gradlew assembleDebug -Pyourec.applicationId=com.example.yourec`. As the main
README asks, please do not publish copies to a store; the YouRec name is reserved.

Minimum Android 10 (API 29). Allow camera and microphone access when prompted.
Projects are stored in the app's own storage under `Projects/`, one folder per
project, with the same layout as on iPhone.

## Verification

```sh
./scripts/test-core.sh
```

The repository's `Android` workflow on GitHub Actions runs these checks,
`./gradlew assembleDebug`, and the emulator run on every push that touches
`Android/`. It attaches the debug APK, and the screenshots, takes, export and
log of the emulator run. The same script works against a connected test phone:

```sh
python3 scripts/emulator-smoke.py build/outputs/apk/debug/YouRec-debug.apk out
```

It reinstalls the app, so use a phone with no projects you care about.

Requires only a JDK and `kotlinc`. It compiles the core with its checks into one
program, as the iPhone `scripts/test-core.sh` does with `swiftc`. The checks are
ports of the Swift ones where the feature exists here (`Tests/TrackTimelineTests.kt`,
`Tests/RecordingHistoryTests.kt`, `Tests/ZoomTransitionTests.kt`) plus Android
additions: split, ripple and detached deletion, trims and reveals, link/unlink,
move companions and snapping, lanes, fades, persistent undo/redo and redo-branch
replacement, recording during an open draft, snapshot integrity, refusal of
unported tools and newer schemas, reading a document written by the iPhone app,
30-day retention, sample-exact audio mixing across cuts, and the picture plan.

What that does **not** cover is everything that needs a phone: camera framing,
orientation and mirroring, encoder timestamps and audio/video sync, the hold
during a camera switch, decoder behaviour in preview and export, touch handling,
and layout. Those are listed, with how to check each, in
[docs/DEVICE-QA.md](docs/DEVICE-QA.md). Until they have been checked on
hardware, treat this port as unverified beyond its core.

## License

The same [MIT License](../LICENSE) as YouRec. No third-party code is included.
