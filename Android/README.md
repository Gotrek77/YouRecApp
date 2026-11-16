# YouRec for Android

A port of [YouRec](../README.md) to Android, in Kotlin. Everything here lives
under `Android/`; the iPhone app and its documents are untouched. The
[specification](../SPEC.md) is shared: this port implements its *Confirmed*
behaviour, and where the two platforms differ the difference is written down
here rather than left to be discovered.

**Status: first milestone, awaiting hardware acceptance.** The code compiles
against the Android 15 framework classes and the platform-independent core
passes its checks. It has **not yet been built with Gradle, installed, or run
on a phone**: the environment it was written in had no Android SDK and no
device. Following the project's own [camera rule](../docs/CAMERA-QA.md), nothing
that touches a camera, an encoder or a screen is described as working until
[the device checks](docs/DEVICE-QA.md) have been carried out. Expect the first
build on real hardware to need fixes.

## What is ported

The scope follows the specification's own implementation sequence: durable
capture, recording controls, the cut editor, export.

- **Projects** with the iPhone app's document format: a small atomic
  `project.json`, immutable edit snapshots named by their SHA-256 under
  `History/`, a capture ledger separate from Undo, rotating index backups.
  Undo and redo survive closing the app, including closing after an undo.
  Recording adds footage to every retained edit state without becoming an undo
  step. Rename, reversible **Recently Deleted** with a 30-day confirmation,
  video import into managed storage, recovery of a finished take that was not
  yet indexed when the app died.
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
- **Export** to a new MP4 (H.264 or H.265, project size/720p/1080p/4K, 24–60
  fps, AAC), with a receipt naming the edit revision and source checksums. An
  unchanged edit offers its existing movie. Share, or save to the gallery.
  Sources and earlier exports are never rewritten.

## What is not ported yet

Each of these exists on iPhone and is absent here. A project that uses one of
them is **refused by name** on Android ("This project uses Transitions…")
instead of being opened and quietly losing that work on the next save, the same
policy older iPhone builds apply to newer projects.

Subtitles and the Qwen3-ASR model · Text and title design, imported fonts ·
Color LUTs, Log, HDR capture and export · Video effects and their Metal
shaders · Transitions · Speed · Crop, Mirror, Placement of overlapping videos
(an overlap currently shows only the front picture) · Image overlays · Photos
as clips · Stabilization · Enhance audio (RNNoise, DeepFilterNet3, LavaSR,
VoiceFixer) · Censor audio · Pause cuts · Copy/paste · Cover picker · Original
recordings browser · Duplicate, Zip project and OpenTimelineIO · Dual-camera
layouts, live effects, face follow, front light, horizon level, focus peaking ·
Record-here insertion · Recovery of a segment interrupted while being written ·
Landscape editor layout.

Two of these deserve a note. **Interrupted takes:** Android's `MediaMuxer`
writes the MP4 index when a segment is finalized, so a crash or battery loss
during a take loses the segment in progress (everything up to the last Pause is
safe). Bounding that loss needs a different container strategy and is the first
reliability item to do. **Open formats:** the Kotlin `ProjectCoding.kt` reads
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
