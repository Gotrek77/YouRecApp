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
  cut navigation, **Record** from the editor (inside the edit, the new takes go
  in at the playhead, one Undo for the whole session, as on iPhone), **Add video**, **Add photo** (as on iPhone, a three-second
  silent clip), **Add audio**, persistent Undo/Redo,
  **Copy**, **Cut** and **Paste** of selected parts or a marked range (at a
  cut where picture and sound sequences meet, Paste asks which one).
  **Speed** from 0.25× to 4× with the pitch of the sound preserved, **Crop**
  with zoom, position, rotation and the project shapes (Original, 16:9, 9:16,
  1:1, 4:5), and **Mirror**, on the selected clips or the whole project.
  **Text** puts timed titles on the picture in the four plain styles and six
  colours, or designed: the iPhone app's 24 looks, its ten bundled typefaces
  with weight, italic and alignment, or a TTF, OTF, TTC or OTC font imported
  into the project (kept with it as on iPhone), a border outside the letters, a drop
  shadow, and a box behind the title or each line. Label + headline puts a
  small label and a headline on a card, in the iPhone's six card looks, each
  line with its own font and size. Titles animate in and out (fade, drift,
  scale or wipe, with direction and duration), once across the cuts they
  span, as on iPhone. Size, position and duration as well; a
  title stays with its footage through cuts, moves and speed changes, and
  designed, carded and animated titles from iPhone projects open with them.
  **Color** grades the selected clips or the whole project: the fourteen
  built-in looks or an imported `.cube` LUT (3D or 1D, Rec.709), with strength,
  after exposure, warmth and tint. Exposure and white balance are per-channel
  gains on display values: close to the iPhone result, not identical to it.
  **Effects** (video): all 24 of the iPhone app's effects, on the selected
  clips or the whole project, with the iPhone app's shader arithmetic ported
  to GLSL (checked in WebGL against a NumPy transcription of the Metal
  kernels): Color Correction, Silent Tint, Three-Strip, Bleach Bypass, Day for
  Night, Noir, Expressionism, RGB Offset, Negative Strobe, Night fix (Fast),
  Color Isolation (by colour or on the subject, with the temporal matte),
  Quantization (palette tracks, Pop and saved palettes, dithering, face
  detail), Kaleidoscope, Crash zoom, Dolly zoom, Widescreen, Halation, 16mm,
  Super 8, Undercranking, Step printing, Freeze frame, Chroma Key and
  Background (Solid, Studio, Window, Blur original, a picture or a looping
  movie). Where the iPhone app asks Apple Vision, Android uses MediaPipe's
  Selfie Segmenter (ThirdParty/MediaPipe) for the person mask and
  android.media.FaceDetector for Follow face: the masks and tracks differ.
  Pictures between passes are 8-bit display values (the iPhone app keeps
  16-bit linear light); grain repeats a fixed random tile.
  **Transition** at the start, at a cut or at the end, in every iPhone style:
  Dissolve, Fade through a colour, Wipe (direction, soft or hard edge), Split
  screen (axis, divider, framing, whose sound), Hitchcock (shadows or highlights
  first), Whip pan, Flash Cut (count, acceleration), Still (holds the last
  frame) and Defocus, with a hold at the middle and the sound changing before
  or after the picture (J and L cuts). Each partner is drawn whole, titles
  included, and the two are mixed by one shader per style.
  **Image** places a picture from the phone over the video, with size,
  position, opacity and duration, timed with its footage like a title.
  **Cover** offers nine frames of the finished edit from the marked range,
  the selected clips or the whole project; swipe through frames and share
  one as a full-size JPEG.
  **Censor** replaces the sound in a marked range with a tone (sine, triangle
  or square; short, soft or pulsed; pitch and level), on the selected sound or
  all sound there; overlapping tracks share one tone.
  **Enhance** builds a filter chain over the selected sound, a marked range
  or all sound: Noise reduction (RNNoise, as on iPhone, or DeepFilterNet3,
  with Amount), Low-cut, Voice EQ and Output gain, in any order, each with
  bypass. As on iPhone each source is processed once per chain, before speed
  changes, and preview and export play the same samples. DeepFilterNet3 runs
  upstream's ONNX graphs in ONNX Runtime, in 15-second chunks: close to, not
  identical with, the iPhone's per-hop stream
  ([details](ThirdParty/DeepFilterNet3/PROVENANCE.md)). Speech detail
  (LavaSR) and Studio restoration (VoiceFixer) run the iPhone app's DSP with
  the same networks converted to ONNX in CI
  ([details](scripts/neural/README.md)); a build without them does not offer
  them, and a project that uses them is reported as unavailable there.
  Not ported from the iPhone inspector: level-matched compare, auditioning a
  short window, and resumable per-stage checkpoints.
  **Subtitles** transcribe the marked range or the whole project from its own
  sound with the iPhone app's speech model, Qwen3-ASR-0.6B, through antirez's
  qwen-asr in C ([details](ThirdParty/qwen-asr/PROVENANCE.md)), downloaded
  once on request (about 1.9 GB, verified) and run on the phone. Language
  automatic or chosen; captions regrouped by word count, styled (style,
  colour, size, position), corrected one by one keeping the other words'
  times, or removed. Projects with iPhone subtitles open and keep them.
  Captions take the Text panel's designs and entrance/exit animations, each
  caption animating over its own time; an iPhone subtitle font (Classic, Mono…)
  is kept and drawn with an Android font of the same kind.
  As on iPhone, an outline over the preview fits the largest caption: drag
  it to move all subtitles, pinch to resize them; sliders do the same, and
  the largest caption always stays inside the frame.
  Not ported yet: resuming an interrupted transcription.
  **Pauses** finds silence in the selected clips, at a measured or chosen
  level; as on iPhone, RNNoise tells a quiet syllable from silence in spoken
  takes. Each pause can be kept or cut; picture and sound shorten together;
  Remove restores the media.
  **Placement** arranges videos that overlap: Full, Side by side, Stacked and
  Inset presets, front/back order, Fit or Fill, and each picture's window and
  framing.
- **Projects**: Duplicate (an independent copy with the same history), Back up
  (a `.zip` in the iPhone app's archive format, shared from the phone) and Open
  project backup, in the iPhone app's archive format (not yet tried with a
  backup made on a real iPhone). The OpenTimelineIO file of an iPhone backup
  is ignored.
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
them is **refused by name** on Android ("This project uses Video effects…")
instead of being opened and quietly losing that work on the next save, the same
policy older iPhone builds apply to newer projects.

In titles: choosing among the iPhone's other system fonts (a title or subtitle
in one opens, keeps it and is drawn with an Android font of the same kind), a
font library shared between projects (Android keeps each font in its project;
import it again elsewhere), favourite fonts, saved card looks, the system
colour wheel and the eyedropper (colours are picked from the app's palette,
recent colours or a hex value), the animation's replay button and its duration
slider (presets instead) ·
Log LUTs (Apple Log input), highlight recovery in Color, HDR capture and export · Inside video effects:
Night fix Medium and Precise (motion-aligned fusion), a Chroma Key clean
plate, a background movie's own sound, a freeze's Continue and Fade sound,
Widescreen's oval bokeh and Dolly zoom's depth layer (iPhone depth data),
analysis recorded live on iPhone, animated Color Correction · Dragging and pinching a placed picture in the preview,
and its edge-crop handles (edge crops made on iPhone are kept and drawn) ·
animated Crop and Placement · Stabilization · Original
recordings browser · OpenTimelineIO export · Dual-camera
layouts, live effects, face follow, horizon level ·
Landscape editor layout · Color, Crop,
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

## Open decisions

- **A photo does not ripple, unless asked.** A photo is picture without sound,
  so it is in no link group. When a clip before it gets shorter (Pauses, Speed,
  a transition's overlap), the linked clips move up and the photo stays where it
  was, leaving a gap before it. The iPhone app does exactly the same
  (`TrackTimeline.init` gives picture-only sources no link; `replaceContents`
  moves only linked items), and that is the default here. **Editor settings →
  Photos follow the edit** makes pictures without sound ripple with the clips
  before them instead (`EditingPreferences.photosFollow`, used by
  `TrackTimeline.replaceContents` and `TransitionLayout.shiftTargets`). It is an
  app setting, not part of the project, so documents stay the iPhone app's.

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

Requires JDK 17 or later and the Android SDK (platform 35), with the NDK and
CMake from the SDK manager: the iPhone app's RNNoise (`../ThirdParty/RNNoise`,
unchanged) is built by `native/CMakeLists.txt` for Noise reduction and the voice check in Pauses. Android Studio can
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

The video effect shader is checked against an independent NumPy transcription
of the iPhone app's Metal kernels, running the same GLSL ES 1.0 in WebGL 1
through headless Chromium (needs Node with Playwright, and NumPy):

```sh
NODE_PATH=$(npm root -g) python3 scripts/effects/check-shaders.py
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

The same [MIT License](../LICENSE) as YouRec. Third-party parts: the iPhone
app's RNNoise and DeepFilterNet3 DSP data (`../ThirdParty`, used in place);
upstream DeepFilterNet3's ONNX graphs (`ThirdParty/DeepFilterNet3`, MIT or
Apache-2.0, model MIT, Hendrik Schröter); the iPhone app's LavaSR and
VoiceFixer DSP (`../ThirdParty`, used in place) and, in builds that carry
them, those networks converted to ONNX (LavaSR Apache-2.0; VoiceFixer code MIT,
weights CC BY 4.0, Haohe Liu et al.); ONNX Runtime (MIT, from Maven Central).
