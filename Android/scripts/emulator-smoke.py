#!/usr/bin/env python3
"""Drive the app once through its main path on an emulator or a test phone:
record, switch camera, bookmark, pause, resume, stop, edit, split, export.
Controls are found by the names TalkBack reads, so this also checks that they
have them. Everything observed is printed; screenshots, the recorded takes and
the exported movie are kept in the output directory.

    emulator-smoke.py YouRec-debug.apk output-directory

An emulator's camera is a synthetic scene: this establishes that the pipelines
run and produce valid files. It is not evidence of correct framing on a real
sensor. Use a device with no projects you care about: the app is reinstalled.
"""
import os, re, subprocess, sys, time
import xml.etree.ElementTree as ET

APK, OUT = sys.argv[1], sys.argv[2]
PACKAGE = "com.antirez.yourec"
FILES = "/sdcard/Android/data/" + PACKAGE + "/files"
os.makedirs(OUT, exist_ok=True)
failures = []

def adb(*args, check=False, timeout=120):
    result = subprocess.run(("adb",) + args, capture_output=True, timeout=timeout)
    if check and result.returncode != 0:
        raise RuntimeError("adb " + " ".join(args) + ": " + result.stderr.decode(errors="replace"))
    return result.stdout.decode(errors="replace")

notes = []
applog = None
def note(message):
    notes.append(message)
    print(message, flush=True)

def fail(message):
    failures.append(message)
    note("FAIL: " + message)

def nodes(tries=6):
    """Every on-screen control with a name: (name, text, enabled, centre)."""
    for attempt in range(tries):
        # Remove the last dump first: a failed dump must not be mistaken for the screen.
        adb("shell", "rm", "-f", "/sdcard/yourec-ui.xml")
        adb("shell", "uiautomator", "dump", "/sdcard/yourec-ui.xml")
        xml = adb("shell", "cat", "/sdcard/yourec-ui.xml")
        if xml.startswith("<?xml"):
            break
        time.sleep(0.7)   # the window was not idle: a meter or timer was changing
    else:
        return []
    found = []
    for node in ET.fromstring(xml).iter("node"):
        name = node.get("content-desc") or ""
        text = node.get("text") or ""
        box = re.findall(r"\d+", node.get("bounds") or "")
        if (name or text) and len(box) == 4:
            x1, y1, x2, y2 = map(int, box)
            found.append((name, text, node.get("enabled") == "true", ((x1 + x2) // 2, (y1 + y2) // 2)))
    return found

def find(label, last=False, exact=False):
    matches = [n for n in nodes() if (n[0] == label or n[1] == label if exact else n[0].startswith(label) or n[1].startswith(label))]
    return (matches[-1] if last else matches[0]) if matches else None

def wait(label, seconds=20, enabled=True):
    deadline = time.time() + seconds
    while time.time() < deadline:
        node = find(label)
        if node and (node[2] or not enabled):
            return node
        time.sleep(1)
    fail("'%s' did not appear within %d s. On screen: %s" % (label, seconds, describe()))
    return None

def tap(label, seconds=20, last=False):
    node = wait(label, seconds)
    if last:
        node = find(label, last=True) or node
    if not node:
        return False
    adb("shell", "input", "tap", str(node[3][0]), str(node[3][1]))
    note("tap  " + label)
    time.sleep(0.8)
    return True

TOOL_ROW = None   # the y of the editor's tool row, learnt from the first tool seen

def tool(label):
    """Tap a tool in the editor's row, which scrolls sideways when it does not fit.
    The row is swept back to its start, then forward, so its position does not matter."""
    global TOOL_ROW
    for attempt in range(10):
        node = find(label, exact=True)
        if node and node[2]:
            TOOL_ROW = TOOL_ROW or node[3][1]
            adb("shell", "input", "tap", str(node[3][0]), str(node[3][1]))
            note("tap  " + label); time.sleep(1); return True
        if TOOL_ROW is None:
            anchor = next((n for n in (find(t, exact=True) for t in ("Split", "Volume", "Speed", "Record", "Add video")) if n), None)
            if not anchor:
                break
            TOOL_ROW = anchor[3][1]
        y = str(TOOL_ROW)
        if attempt < 3:
            adb("shell", "input", "swipe", "200", y, "1000", y, "200")   # towards the start
        else:
            adb("shell", "input", "swipe", "900", y, "300", y, "300")    # then forward, one step at a time
        time.sleep(0.8)
    fail("the '%s' tool was not found. On screen: %s" % (label, describe()))
    return False

def readout():
    """The editor's 'position / duration' text as two numbers of seconds."""
    text = next((n[1] for n in nodes() if " / " in n[1]), "")
    def seconds(part):
        # Editor time is minutes, seconds and frames at 30 per second: 0:07.12.
        minutes, rest = part.strip().split(":")
        whole, frames = rest.split(".")
        return int(minutes) * 60 + int(whole) + int(frames) / 30.0
    try:
        position, total = text.split(" / ")
        return seconds(position), seconds(total)
    except ValueError:
        return None

def option(label, family):
    """Tap a choice in a panel's row of choices, which scrolls sideways like the tools."""
    for attempt in range(5):
        node = find(label)
        if node and node[2]:
            adb("shell", "input", "tap", str(node[3][0]), str(node[3][1]))
            note("tap  " + label); time.sleep(0.8); return True
        anchor = find(family)
        if not anchor:
            break
        adb("shell", "input", "swipe", "900", str(anchor[3][1]), "300", str(anchor[3][1]), "300")
        time.sleep(1)
    fail("the choice '%s' was not found. On screen: %s" % (label, describe()))
    return False

def describe():
    return " | ".join(sorted({(n[0] or n[1]).replace("\n", " ") for n in nodes()}))

def shot(name):
    path = os.path.join(OUT, name + ".png")
    with open(path, "wb") as f:
        f.write(subprocess.run(("adb", "exec-out", "screencap", "-p"), capture_output=True, timeout=60).stdout)
    note("shot %s  %s" % (name, brightness(path)))

def brightness(path, at=None):
    """Mean and spread of luma, to tell a picture from a black or flat frame."""
    command = ["ffmpeg", "-v", "error"] + (["-ss", str(at)] if at is not None else []) + ["-i", path, "-frames:v", "1", "-vf", "scale=64:64,format=gray", "-f", "rawvideo", "-"]
    data = subprocess.run(command, capture_output=True, timeout=120).stdout
    if not data:
        return "unreadable"
    mean = sum(data) / len(data)
    spread = (sum((v - mean) ** 2 for v in data) / len(data)) ** 0.5
    return "luma mean %.0f spread %.0f" % (mean, spread)

def probe(path):
    out = subprocess.run(("ffprobe", "-v", "error", "-show_entries", "stream=codec_type,codec_name,width,height,r_frame_rate,nb_frames,start_time,duration,sample_rate,channels:stream_side_data=rotation:format=duration",
                          "-of", "compact=p=1:nk=0", path), capture_output=True, timeout=120).stdout.decode()
    note("     " + os.path.basename(path) + "\n       " + out.strip().replace("\n", "\n       "))
    duration = re.search(r"format\|duration=([\d.]+)", out)
    return out, float(duration.group(1)) if duration else 0.0

def pull():
    local = os.path.join(OUT, "files")
    subprocess.run(("rm", "-rf", local))
    adb("pull", FILES, local, timeout=600)
    movies = []
    for root, _, names in os.walk(local):
        for name in sorted(names):
            if name.endswith(".mp4") and not name.startswith("."):
                movies.append(os.path.join(root, name))
    return movies

def crashed():
    """Only a crash of the app itself counts; the test tools have their own."""
    log = adb("logcat", "-d", "-b", "crash")
    blocks = [b for b in log.split("FATAL EXCEPTION") if "Process: " + PACKAGE in b]
    if blocks:
        with open(os.path.join(OUT, "crash.txt"), "w") as f:
            f.write("FATAL EXCEPTION".join(blocks))
        fail("the app crashed: " + " / ".join(blocks[-1].strip().splitlines()[2:8]))
        return True
    return False

def main():
    adb("wait-for-device")
    adb("uninstall", PACKAGE)
    adb("install", "-r", APK, check=True, timeout=300)
    for permission in ("CAMERA", "RECORD_AUDIO"):
        adb("shell", "pm", "grant", PACKAGE, "android.permission." + permission)
    adb("logcat", "-c")
    # The app's own lines, kept from the start: the device log forgets them within minutes.
    global applog
    applog = subprocess.Popen(("adb", "logcat", "-s", "YouRec:V"), stdout=open(os.path.join(OUT, "app.log"), "w"), stderr=subprocess.DEVNULL)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/yourec.YouRecActivity")
    time.sleep(4)

    # Recorder.
    # A freshly booted emulator can show a system "isn't responding" dialog over the app.
    for attempt in range(3):
        if any("isn't responding" in (n[0] or n[1]) for n in nodes(tries=2)):
            note("system dialog: " + describe())
            if find("Wait", exact=True):
                tap("Wait", 5)
            time.sleep(3)
    if not wait("Record a new project", 40):
        shot("recorder-not-ready"); return
    note("recorder: " + describe())
    shot("recorder-idle")
    tap("Record a new project")
    time.sleep(4)
    wait("Stop recording", 10)
    shot("recording")
    flip = find("Switch to")
    if flip and flip[2]:
        tap("Switch to"); time.sleep(4)
        wait("Stop recording", 10); shot("recording-after-switch")
    else:
        note("no second camera to switch to")
    tap("Add recording bookmark")
    time.sleep(1)
    tap("Pause recording")
    wait("Resume recording", 60)
    shot("paused")
    time.sleep(2)   # paused time must not appear in the movie
    tap("Resume recording")
    time.sleep(3)
    wait("Stop recording", 10)
    tap("Stop recording")
    wait("Record into this project", 60)
    note("after stop: " + describe())
    if crashed(): return

    takes = [m for m in pull() if "/Exports/" not in m]
    note("takes: %d" % len(takes))
    if len(takes) != 2:
        fail("expected two segments (before and after the pause), found %d" % len(takes))
    recorded = 0.0
    for take in takes:
        out, duration = probe(take)
        recorded += duration
        if "codec_type=video" not in out or "codec_type=audio" not in out:
            fail(os.path.basename(take) + " lacks a video or an audio track")
        if "rotation" in out:
            fail(os.path.basename(take) + " carries a rotation flag; pixels should be stored upright")
        note("     middle frame: " + brightness(take, duration / 2))
        sidecar = take[:-4] + ".events.json"
        note("     events: " + (open(sidecar).read() if os.path.exists(sidecar) else "none"))
    manifests = [os.path.join(r, n) for r, _, ns in os.walk(os.path.join(OUT, "files")) for n in ns if n == "project.json"]
    for manifest in manifests:
        note("project.json: " + open(manifest).read()[:1500])
    if not manifests:
        fail("no project document was written")

    # Editor.
    tap("Edit")
    if not wait("Play", 20):
        shot("editor-not-ready"); return
    time.sleep(2)
    note("editor: " + describe())
    shot("editor")
    button = find("Play")
    tap("Play")
    # While the movie plays its time readout changes on every frame, so the
    # screen cannot be read: that itself is the sign that it is playing.
    time.sleep(2)
    busy = sum(1 for _ in range(3) if not nodes(tries=1))
    note("screen changing during playback: %d of 3 readings" % busy)
    shot("editor-playing")
    if button:
        adb("shell", "input", "tap", str(button[3][0]), str(button[3][1])); note("tap  Pause (same button)")
    time.sleep(1.5)
    readout = next((n[1] for n in nodes() if " / " in n[1]), "unreadable")
    note("paused at: " + readout)
    if readout.startswith("0:00.00") or readout == "unreadable":
        fail("the playhead did not advance during playback (%s)" % readout)
    elif readout.split(" / ")[0] == readout.split(" / ")[1]:
        note("playback had already reached the end; the tap restarted nothing")
    note("after playing: " + describe())
    tap("Split")
    time.sleep(1)
    if not find("Split · Undo available"):
        fail("Split did not report an undoable edit. On screen: " + describe())
    tap("Undo"); time.sleep(1)
    tap("Redo"); time.sleep(1)
    shot("editor-after-split")
    if crashed(): return

    # Clip tools: the whole project at double speed, flipped, in a square frame.
    expected, shape = recorded, None
    if tool("Speed") and option("Speed 2 times", "Speed 1 times"):
        time.sleep(1); tap("Done", 10)
        note("after Speed: " + describe())
        expected = recorded / 2
    if tool("Mirror") and tap("Flip left", 10):
        time.sleep(1); tap("Done", 10)
    if tool("Crop") and tap("Video shape 1:1", 10):
        time.sleep(1); tap("Done", 10)
        shape = "width=1080|height=1080"
    if tool("Color") and option("Look Cinema", "Look "):
        time.sleep(1); tap("Done", 10)
        note("after Color: " + describe())
    # Text needs a picture under the playhead; at the very end there is none.
    readout = next((n[1] for n in nodes() if " / " in n[1]), "")
    if readout and readout.split(" / ")[0] == readout.split(" / ")[-1]:
        tap("Previous cut", 10); time.sleep(1)
    if tool("Text") and wait("Title text", 10):
        option("Style Box", "Style "); option("Colour Yellow", "Colour "); tap("Done", 10)
        note("after Text: " + describe())
    # Before the photo: a photo has no sound and so no link, and does not ripple
    # when a transition pulls the clips before it closer (as on iPhone).
    dissolved = transition()
    if dissolved:
        expected -= dissolved[1]
    expected += photo()
    image()
    enhanced = enhance()
    censored = censor()
    shot("editor-after-tools")
    if crashed(): return

    # Export.
    tap("Export")
    wait("Export movie", 10)
    tap("Export", last=True)
    if not wait("Export finished", 900):
        shot("export-not-finished")
    else:
        note("export: " + describe())
    shot("export")
    exports = [m for m in pull() if "/Exports/" in m]
    if len(exports) != 1:
        fail("expected one exported movie, found %d" % len(exports))
    for movie in exports:
        out, duration = probe(movie)
        note("     middle frame: " + brightness(movie, duration / 2))
        if "codec_type=video" not in out or "codec_type=audio" not in out:
            fail("the export lacks a video or an audio track")
        if abs(duration - expected) > 0.25:
            fail("the export lasts %.2f s but the edit should last %.2f s" % (duration, expected))
        if shape and shape not in out:
            fail("the export is not in the chosen 1:1 shape")
        if dissolved:
            level = brightness(movie, dissolved[0])
            note("dissolve at %.2f s (overlap %.2f s): %s" % (dissolved[0], dissolved[1], level))
            spread = re.search(r"spread ([0-9]+)", level)
            if dissolved[1] <= 0.1 or not spread or int(spread.group(1)) < 10:
                fail("the dissolve took no overlap or its middle frame is blank (%s)" % level)
        if censored:
            # A -18 dBFS sine has an RMS of about -21 dBFS; nothing else in the range.
            a, b = censored[0] + 0.05, censored[1] - 0.05
            stats = subprocess.run(("ffmpeg", "-hide_banner", "-nostats", "-ss", "%.3f" % a, "-t", "%.3f" % (b - a), "-i", movie, "-map", "0:a", "-af", "astats=measure_overall=RMS_level:measure_perchannel=none", "-f", "null", "-"),
                                   capture_output=True, text=True).stderr
            level = re.findall(r"RMS level dB: (-?[0-9.]+|-inf)", stats)
            note("censored range %.2f-%.2f s: RMS %s dB" % (a, b, level))
            if not level or level[-1] == "-inf" or abs(float(level[-1]) + 21) > 3:
                fail("the censored range does not carry the -18 dBFS tone (RMS %s)" % level)
        if enhanced:
            # RNNoise ran through JNI over each source: one processed file per take, no partial left.
            listing = adb("shell", "run-as", PACKAGE, "ls", "-l", "cache/Enhanced audio")
            note("enhanced audio cache:\n" + listing)
            sizes = [int(f[4]) for f in (line.split() for line in listing.splitlines()) if len(f) > 7 and f[-1].endswith(".f32")]
            if not sizes or min(sizes) == 0 or ".partial" in listing:
                fail("Enhance audio left no processed sound (%s)" % listing.strip())
    crashed()
    cover()
    if crashed(): return
    clipboard()
    if crashed(): return
    pauses()
    if crashed(): return
    interrupted(len(takes))

def transition():
    """Dissolve at a cut inside the edit, where both pictures overlap. Returns
    (time of the cut, seconds the overlap took out), or None."""
    tap("Previous cut", 10); time.sleep(0.8)
    if not tool("Transition"):
        return None
    if find("Transition · Start") or find("Transition · End"):
        tap("Next join", 5) if find("Transition · Start") else tap("Previous join", 5)
        time.sleep(1)
    if not find("Transition · Cut"):
        note("transition: no interior cut. On screen: " + describe()); tap("Done with transition", 5); return None
    before = readout()
    if not tap("Transition style Dissolve", 10) or not wait("Transition Dissolve", 10):
        return None
    after = readout()
    note("transition: dissolve, before %s after %s" % (before, after))
    shot("editor-transition")
    tap("Done with transition", 10)
    if not before or not after:
        return None
    return (after[0], before[1] - after[1])

def enhance():
    """Noise reduction (RNNoise) on all sound. True once applied."""
    if not tool("Enhance") or not tap("Add Noise reduction", 10):
        return False
    shot("editor-enhance")
    if not tap("Apply enhance audio", 10):
        return False
    time.sleep(2)
    note("after Enhance: " + describe())
    return True

def censor():
    """Mark a range over sound and censor it. Returns the range in seconds, or None.
    Two cuts back keeps the range off a photo added at the end, which has no sound."""
    tap("Previous cut", 10); time.sleep(0.8)
    tap("Previous cut", 10); time.sleep(0.8)
    first = readout()
    if not first or not tool("Start"):
        return None
    tap("Next cut", 10); time.sleep(0.8)
    second = readout()
    if not second or abs(second[0] - first[0]) < 0.5 or not tool("End"):
        note("censor: no range to mark (%s, %s)" % (first, second))
        if find("Clear", exact=True): tool("Clear")
        return None
    if not tool("Censor") or not tap("Apply censor", 10):
        return None
    if not wait("Censored", 10):
        return None
    note("censored %.2f-%.2f" % (min(first[0], second[0]), max(first[0], second[0])))
    return (min(first[0], second[0]), max(first[0], second[0]))

def pick_photo():
    """In the system picker, choose the test picture pushed to Downloads. False if the picker could not be driven."""
    found = None
    for attempt in range(15):
        found = find("yourec-photo")
        if found:
            break
        if attempt == 5:
            # The picker may open on Recent; look in Downloads.
            menu = find("Show roots") or find("Open navigation drawer")
            if menu:
                adb("shell", "input", "tap", str(menu[3][0]), str(menu[3][1])); time.sleep(1.5)
                if find("Downloads"):
                    tap("Downloads", 5)
        time.sleep(1)
    if not found:
        note("photo picker could not be driven. On screen: " + describe())
        shot("photo-picker")
        adb("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(2)
        return False
    adb("shell", "input", "tap", str(found[3][0]), str(found[3][1]))
    note("tap  yourec-photo.jpg")
    return True

def photo():
    """Add a photo through the system picker. Returns the seconds it adds to the
    edit: 3 when it was added, 0 when the picker could not be driven."""
    picture = os.path.join(OUT, "yourec-photo.jpg")
    subprocess.run(("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=1600x1200", "-frames:v", "1", picture))
    adb("push", picture, "/sdcard/Download/yourec-photo.jpg")
    adb("shell", "content", "call", "--method", "scan_volume", "--uri", "content://media", "--arg", "external_primary")
    if not tool("Add photo") or not pick_photo():
        return 0
    if not wait("Photo added", 60):
        fail("the chosen photo was not added. On screen: " + describe())
        return 0
    note("after Add photo: " + describe())
    return 3.0

def image():
    """Put the test picture over the video at the playhead and close its panel."""
    tap("Previous cut", 10); time.sleep(0.8)
    if not tool("Image") or not pick_photo():
        return
    if not wait("Done with image", 30):
        fail("the chosen image was not placed. On screen: " + describe()); return
    option("Image on screen for 2 seconds", "Image on screen for ")
    note("after Image: " + describe())
    shot("editor-image")
    tap("Done with image", 10)

def cover():
    """Open Select cover, pick a frame, and share it: a JPEG of the project size appears."""
    if find("Close"):
        tap("Close")
    if not tool("Cover") or not tap("Frame at ", 20):
        return
    deadline = time.time() + 30
    while time.time() < deadline and not find("Cover image, "):
        time.sleep(1)
    image = find("Cover image, ")
    note("cover: " + (image[0] if image else "no frame"))
    if not image:
        fail("the cover frame did not load. On screen: " + describe()); return
    shot("cover")
    tap("Share JPEG", 10); time.sleep(4)
    adb("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(1)
    pull()
    local = os.path.join(OUT, "files")
    jpegs = [os.path.join(r, n) for r, _, ns in os.walk(local) for n in ns if n.endswith(".jpg")]
    if len(jpegs) != 1:
        fail("expected one cover JPEG, found %d" % len(jpegs)); return
    out = subprocess.run(("ffprobe", "-v", "error", "-show_entries", "stream=width,height", "-of", "csv=p=0", jpegs[0]), capture_output=True, text=True).stdout.strip()
    note("cover JPEG: %s %s, %s" % (os.path.basename(jpegs[0]), out, brightness(jpegs[0])))
    if out != "1080,1080":
        fail("the cover JPEG is %s, not the 1080 x 1080 project picture" % out)
    if find("‹ Frames"):
        tap("‹ Frames"); time.sleep(1)
    if find("Done"):
        tap("Done")

def pauses():
    """Find pauses in the whole project, apply them, and check the edit shrinks
    by the reported amount; then undo."""
    if find("Close"):
        tap("Close")
    before = readout()
    if not before or not tool("Pauses") or not tap("Find pauses", 10):
        return
    deadline = time.time() + 120
    summary = None
    while time.time() < deadline:
        summary = next((n[1] for n in nodes() if " pauses cut · " in n[1] or n[1].startswith("No pauses found")), None)
        if summary:
            break
        time.sleep(2)
    note("pauses: %s" % summary)
    if not summary:
        fail("pause detection did not finish. On screen: " + describe()); return
    if summary.startswith("No pauses"):
        tap("Cancel", 5); return
    saved = float(re.search(r"· ([0-9.]+) s shorter", summary).group(1))
    tap("Apply pause cuts", 10); time.sleep(2)
    after = readout()
    note("pauses: before %s, after %s, saved %.1f s | %s" % (before, after, saved, describe()))
    if after and abs(after[1] - before[1]) < 0.05 and find("Pauses cut"):
        # As on iPhone, a photo has no sound and so no link: it does not ripple
        # when an earlier clip gets shorter. With one at the end the length stays.
        note("pauses: cut applied; the photo at the end kept its place, as on iPhone")
    elif not after or abs((before[1] - after[1]) - saved) > 0.15:
        fail("pause cuts changed the edit by %s s, not the %.1f s reported" % (None if not after else round(before[1] - after[1], 2), saved))
    shot("editor-after-pauses")
    tap("Undo", 10); time.sleep(1)

def clipboard():
    """Mark a range between two cuts, copy it and paste it: the edit grows by its length."""
    if find("Close"):
        tap("Close")
    tap("Previous cut", 10); time.sleep(0.8)
    before = readout()
    if not before or not tool("Start"):
        return
    tap("Previous cut", 10); time.sleep(0.8)
    marked = readout()
    if marked and before and abs(marked[0] - before[0]) < 0.1:
        # Already at the first cut: mark forward instead.
        tap("Next cut", 10); time.sleep(0.8)
        marked = readout()
    if not tool("End") or not tool("Copy"):
        return
    after_copy = readout()
    if not tool("Paste"):
        return
    time.sleep(1)
    choice = find("Paste at ")
    if choice:
        tap("Paste at ")
    time.sleep(1)
    after = readout()
    note("clipboard: before %s, marked to %s, after paste %s | %s" % (before, marked, after, describe()))
    if not before or not marked or not after:
        fail("the timeline readout could not be read around Copy and Paste")
        return
    length = abs(before[0] - marked[0])
    if after_copy and after_copy[1] != before[1]:
        fail("Copy changed the edit's length")
    if abs(after[1] - (before[1] + length)) > 0.1:
        fail("after pasting %.2f s the edit lasts %.2f s instead of %.2f s" % (length, after[1], before[1] + length))

    shot("editor-after-paste")

def interrupted(before):
    """Kill the app in the middle of a take, as a crash or a dead battery would,
    and expect the take back, playable and in the project, after relaunch."""
    if find("Close"):
        tap("Close")
    tool("Record")
    if not wait("Record into this project", 40):
        shot("recorder-not-ready-again"); return
    tap("Record into this project")
    time.sleep(8)
    adb("shell", "am", "force-stop", PACKAGE)
    note("killed while recording")
    local = os.path.join(OUT, "files")
    pull()
    journals = [n for _, _, ns in os.walk(local) for n in ns if n.endswith(".journal")]
    note("journals left by the kill: %s" % journals)
    if len(journals) != 1:
        fail("expected one journal beside the interrupted take, found %d" % len(journals))
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/yourec.YouRecActivity")
    deadline = time.time() + 90
    takes = []
    while time.time() < deadline:
        time.sleep(5)
        takes = [m for m in pull() if "/Exports/" not in m and "/Recovery/" not in m and not os.path.basename(m).startswith("photos-import-")]
        manifests = [os.path.join(r, n) for r, _, ns in os.walk(local) for n in ns if n == "project.json"]
        if manifests and all(os.path.basename(t) in open(manifests[0]).read() for t in takes) and len(takes) == before + 1:
            break
    else:
        fail("the interrupted take was not back in the project 90 s after relaunch (takes: %d, expected %d)" % (len(takes), before + 1))
    recovered = sorted(takes)[-1] if takes else None
    if recovered:
        out, duration = probe(recovered)
        if "codec_type=video" not in out or "codec_type=audio" not in out or duration < 2:
            fail("the recovered take is not a playable movie with picture and sound")
        note("     middle frame: " + brightness(recovered, duration / 2))
    left = [n for _, _, ns in os.walk(local) for n in ns if n.endswith(".journal")]
    if left:
        fail("a journal remained after recovery: %s" % left)
    note("after recovery: " + describe())
    crashed()
    if not crashed():
        projects()

def projects():
    """Duplicate the project, back the copy up, and check the archive."""
    import zipfile, json
    tap("Projects", 15)
    if not tap("More actions for", 20):
        return
    tap("Duplicate project", 10)
    time.sleep(10)
    local = os.path.join(OUT, "files")
    pull()
    manifests = [os.path.join(r, n) for r, _, ns in os.walk(local) for n in ns if n == "project.json" and "RecentlyDeleted" not in r]
    note("projects after Duplicate: %d" % len(manifests))
    if len(manifests) != 2:
        fail("Duplicate project should leave two projects, found %d" % len(manifests)); return
    titles = sorted(json.load(open(m))["title"] for m in manifests)
    if not any(t.endswith(" copy") for t in titles):
        fail("the duplicate is not titled as a copy: %s" % titles)
    if not tap("More actions for", 10) or not tap("Back up project", 10):
        return
    time.sleep(15)
    shot("backup-share")
    adb("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(1)
    pull()
    archives = [os.path.join(r, n) for r, _, ns in os.walk(local) for n in ns if n.endswith(".zip")]
    if len(archives) != 1:
        fail("expected one project backup, found %d" % len(archives)); return
    with zipfile.ZipFile(archives[0]) as archive:
        bad = archive.testzip()
        names = archive.namelist()
        integrity = json.loads(archive.read("integrity.json"))
        movies = [n for n in names if n.endswith(".mp4")]
        note("backup: %d entries, %d movies, damaged: %s" % (len(names), len(movies), bad))
        if bad or "project.json" not in names or not movies or sorted(integrity["sha256"]) != sorted(n for n in names if n not in ("project.json", "integrity.json") and not n.startswith("History/")):
            fail("the project backup is incomplete or damaged")
        if any(i.compress_type != zipfile.ZIP_STORED for i in archive.infolist()):
            fail("the project backup is compressed; the iPhone format stores entries")
    crashed()

try:
    main()
except Exception as error:
    fail("the script stopped: %r" % (error,))
finally:
    log = adb("logcat", "-d", timeout=300)
    with open(os.path.join(OUT, "logcat.txt"), "w") as f:
        f.write(log)
    # The emulator prints its own noise between our lines: repeat ours together, last.
    print("==== SUMMARY ====\n" + "\n".join(notes), flush=True)
    if applog:
        applog.terminate()
    own = open(os.path.join(OUT, "app.log"), errors="replace").read() if os.path.exists(os.path.join(OUT, "app.log")) else ""
    print("==== APP LOG ====\n" + own[:2500] + ("\n...\n" + own[-2500:] if len(own) > 5000 else own[2500:]), flush=True)
    print("RESULT: " + ("PASS" if not failures else "%d failure(s)\n- " % len(failures) + "\n- ".join(f[:600] for f in failures)), flush=True)
    sys.exit(1 if failures else 0)
