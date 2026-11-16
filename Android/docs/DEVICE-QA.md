# Android: first-device acceptance

Nothing in this port has run on hardware. This is the list of what must be
observed on a phone before any of it is called working, in the order that finds
the most with the least effort. It applies the iPhone app's
[camera acceptance process](../../docs/CAMERA-QA.md): requested settings and
successful callbacks are not evidence; the visible preview and the saved file
are. Keep hypothesis, observation and remaining doubt separate in any report.

Record the phone model, Android version and commit for every run. Use a test
project, never a project you care about, until section 2 has passed.

## 0. Build

- `./gradlew assembleDebug` completes. The Gradle files were written without
  being executed (plugin versions, the custom source directories and the
  manifest location are all unproven).
- The app installs, launches into the recorder, and asks for camera and
  microphone access.

## 1. Recorder

Use a scene with identifiable objects at the left, right, top and bottom, and
readable text. Compare the on-screen preview with the saved movie each time.

| Check | Pass when |
| --- | --- |
| Framing, portrait and both landscape orientations | The saved movie shows what the preview showed: upright, not stretched, no missing edges, correct shape (1080×1920 or 1920×1080, and the 4K variants) |
| Front camera | Preview is mirrored, the saved movie is not; text reads correctly in the movie. Then with each mirroring setting changed |
| Every listed camera | Each opens and shows the lens it names; no depth or duplicate entries; labels match the framing |
| Record → Pause → Resume → Stop | One playable file per segment; paused time is absent; elapsed time is right |
| Switch camera while recording | Preview and movie hold the last frame, then continue; sound is continuous through the switch; the timeline shows one take |
| Rejected or failed switch | The previous camera keeps recording and an error is shown |
| Zoom presets, tap and double-tap | The ramp takes the chosen time, eases at both ends and never jumps; each camera restores its own zoom after switching and after relaunch |
| Focus lock, manual, back to auto | No hunting while locked, in the saved movie; exposure still adapts |
| Microphone meter and selection | The level follows speech; a wired or Bluetooth input can be chosen; unplugging it mid-take falls back with a visible warning |
| Audio/video sync | A clap is aligned within one frame at the start and at the end of a 10-minute take, and across a camera switch |
| Leaving mid-take | Home button, a call, or locking the screen finalizes the segment; returning never resumes recording by itself |
| Long take | 60 minutes at 1080p: file plays to the end, device temperature and dropped frames noted |
| Low storage | Recording stops cleanly with a message and a playable file |

## 2. Projects and durability

| Check | Pass when |
| --- | --- |
| Continue after relaunch | Record A, pause, B, stop, force-quit, reopen, record C: one project plays A+B+C |
| Undo across relaunch | Edits A, B, C; undo C; force-quit; reopen; Redo is offered and restores C. Undo C, make D: Redo is gone |
| Recording never undone | After edits, Undo to the beginning: every take is still present |
| Kill during a take | The segment in progress is lost (known limitation); all earlier segments and the project open normally |
| Kill right after Stop | A take finished but not yet indexed appears on the next launch |
| Recently Deleted | Delete, restore, delete again; nothing is erased without confirmation |
| iPhone project | A project folder made by the iPhone app using only cuts opens here with the same timeline; one using an unported tool is refused by name and its files are unchanged (compare checksums before and after) |

## 3. Editor

| Check | Pass when |
| --- | --- |
| Preview | The first frame appears; play/pause works; sound and picture stay together for several minutes; the frame shown while paused is the frame under the playhead |
| Scrubbing | The picture follows the finger; releasing stays paused |
| Imported phone video | A portrait clip recorded by the stock camera (rotation flag) appears upright and unstretched in preview and export |
| Split, range Delete, trims | The cut heard and seen in preview is at the marked frame; Undo restores it |
| Trim handles | Both edges of a short clip can be grabbed; dragging inward shortens, outward reveals; one Undo per gesture |
| Move, Unlink, Link, Close gap | Parts snap to cuts; detached sound stays put when its picture moves; Cancel leaves no history entry |
| Volume and fades | Audible change matches the waveform; a fade survives a split through it |
| Timeline | Thumbnails and waveforms load without stalling the interface on a long project; pinch zooms from whole-project fit to frame level |
| TalkBack | Every control announces a name; tools remain reachable |

## 4. Export

| Check | Pass when |
| --- | --- |
| Basic export | The movie plays in the gallery and on a computer; duration equals the edited duration within one frame (`ffprobe`) |
| Same cuts as preview | Every cut is at the same frame as in the editor |
| Sync | Clap alignment within one frame at start and end of a long export |
| Settings | H.264 and H.265; project size, 720p, 1080p, 4K; 24/25/30/50/60 fps each produce the stated size and rate without duplicated or dropped frames |
| Mixed shapes | A landscape clip in a portrait project is letterboxed whole, not cropped or stretched |
| Cancel and failure | Cancel leaves no partial file in Exports; filling the storage fails with a message and leaves the project usable |
| Sources untouched | Checksums of every source before and after editing and exporting are identical |
| Share and gallery | Share hands the file to another app; Save places it under Movies/YouRec |

## Device artifact cleanup

The app's storage holds the only copy of a user's projects. Do not clear app
data, uninstall, or `adb shell rm` inside `Projects/` or `RecentlyDeleted/` to
tidy up after a test. Install in place (`adb install -r`). Keep diagnostic
recordings in a test project and remove them through the app's own Delete.
Before any manual cleanup on a device that has real projects, copy the whole
`Projects` folder off the device and compare it afterwards.
