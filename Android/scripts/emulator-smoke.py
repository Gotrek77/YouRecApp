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
def note(message):
    notes.append(message)
    print(message, flush=True)

def fail(message):
    failures.append(message)
    note("FAIL: " + message)

def nodes():
    """Every on-screen control with a name: (name, text, enabled, centre)."""
    for attempt in range(6):
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

def find(label, last=False):
    matches = [n for n in nodes() if n[0].startswith(label) or n[1].startswith(label)]
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
            if name.endswith(".mp4"):
                movies.append(os.path.join(root, name))
    return movies

def crashed():
    log = adb("logcat", "-d", "-b", "crash")
    if PACKAGE in log or "FATAL EXCEPTION" in log:
        fail("the app crashed:\n" + log[-6000:])
        return True
    return False

def main():
    adb("wait-for-device")
    adb("uninstall", PACKAGE)
    adb("install", "-r", APK, check=True, timeout=300)
    for permission in ("CAMERA", "RECORD_AUDIO"):
        adb("shell", "pm", "grant", PACKAGE, "android.permission." + permission)
    adb("logcat", "-c")
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/yourec.YouRecActivity")
    time.sleep(4)

    # Recorder.
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
    wait("Resume recording", 15)
    shot("paused")
    time.sleep(2)   # paused time must not appear in the movie
    tap("Resume recording")
    time.sleep(3)
    wait("Stop recording", 10)
    tap("Stop recording")
    wait("Record into this project", 20)
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
    tap("Play")
    # The time readout changes every frame while playing, so the screen is read by its clock.
    clocks = []
    for _ in range(4):
        time.sleep(1)
        clocks.append(next((n[1] for n in nodes() if " / " in n[1]), "unreadable"))
    note("playhead while playing: " + ", ".join(clocks))
    shot("editor-playing")
    if find("Pause"):
        tap("Pause")
    if len({c for c in clocks if c != "unreadable"}) < 2:
        fail("the playhead did not advance during playback")
    time.sleep(1)
    note("after playing: " + describe())
    tap("Split")
    time.sleep(1)
    if not find("Split · Undo available"):
        fail("Split did not report an undoable edit. On screen: " + describe())
    tap("Undo"); time.sleep(1)
    tap("Redo"); time.sleep(1)
    shot("editor-after-split")
    if crashed(): return

    # Export.
    tap("Export")
    wait("Export movie", 10)
    tap("Export", last=True)
    if not wait("Export finished", 240):
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
        if abs(duration - recorded) > 0.25:
            fail("the export lasts %.2f s but the takes total %.2f s" % (duration, recorded))
    crashed()

try:
    main()
except Exception as error:
    fail("the script stopped: %r" % (error,))
finally:
    with open(os.path.join(OUT, "logcat.txt"), "w") as f:
        f.write(adb("logcat", "-d", timeout=300))
    errors = [l for l in open(os.path.join(OUT, "logcat.txt"), errors="replace") if (" E " in l or " W " in l) and ("yourec" in l.lower() or "MediaCodec" in l or "Camera" in l or "EGL" in l or "AndroidRuntime" in l)]
    note("log lines worth reading (%d, last 60):\n%s" % (len(errors), "".join(errors[-60:])))
    # The emulator prints its own noise between our lines: repeat ours together.
    print("==== SUMMARY ====\n" + "\n".join(n for n in notes if not n.startswith("log lines")), flush=True)
    note("RESULT: " + ("PASS" if not failures else "%d failure(s)\n- " % len(failures) + "\n- ".join(failures)))
    sys.exit(1 if failures else 0)
