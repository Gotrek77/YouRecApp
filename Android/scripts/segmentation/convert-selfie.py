#!/usr/bin/env python3
"""MediaPipe's Selfie Segmenter (Apache 2.0) as ONNX for the app's person mask.

    convert-selfie.py output.onnx

Downloads the pinned .tflite, converts it with tf2onnx, replaces MediaPipe's
custom Convolution2DTransposeBias (the last 2x2, stride 2 upsampling) by a
standard ConvTranspose with the same kernel and bias, and checks the result
against MediaPipe's own ImageSegmenter on test pictures.
Needs: tf2onnx, tensorflow-cpu, onnx, onnxruntime, mediapipe, numpy, pillow
(and libEGL for MediaPipe).
"""
import hashlib, os, subprocess, sys, tempfile, urllib.request
import numpy as np, onnx
from onnx import helper, numpy_helper

URL = "https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_segmenter/float16/latest/selfie_segmenter.tflite"
SHA256 = "191ac9529ae506ee0beefa6b2c945a172dab9d07d1e802a290a4e4038226658b"
OUT = sys.argv[1]
work = tempfile.mkdtemp()
tflite = os.path.join(work, "selfie_segmenter.tflite")
urllib.request.urlretrieve(URL, tflite)
digest = hashlib.sha256(open(tflite, "rb").read()).hexdigest()
assert digest == SHA256, "the downloaded model is not the pinned one: " + digest
raw = os.path.join(work, "raw.onnx")
subprocess.run([sys.executable, "-m", "tf2onnx.convert", "--tflite", tflite, "--output", raw, "--opset", "17"], check=True,
               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

model = onnx.load(raw); graph = model.graph
inits = {i.name: i for i in graph.initializer}
custom = next(n for n in graph.node if n.op_type == "TFL_Convolution2DTransposeBias")
transpose = next(n for n in graph.node if custom.input[0] in n.output)
assert transpose.op_type == "Transpose" and list(transpose.attribute[0].ints) == [0, 2, 3, 1]
kernel = numpy_helper.to_array(inits[custom.input[1]])   # TFLite OHWI: [out, kh, kw, in]
bias = numpy_helper.to_array(inits[custom.input[2]])
assert kernel.shape == (1, 2, 2, 16) and bias.shape == (1,)
weight = np.ascontiguousarray(kernel.transpose(3, 0, 1, 2)).astype(np.float32)   # ONNX: [in, out, kh, kw]
graph.initializer.extend([numpy_helper.from_array(weight, "upsample_weight"), numpy_helper.from_array(bias.astype(np.float32), "upsample_bias")])
index = list(graph.node).index(custom)
graph.node.remove(custom)
# The NCHW tensor before tf2onnx's transpose feeds the standard op; the result goes back to NHWC.
graph.node.insert(index, helper.make_node("ConvTranspose", [transpose.input[0], "upsample_weight", "upsample_bias"], ["upsample_nchw"],
                                          kernel_shape=[2, 2], strides=[2, 2], pads=[0, 0, 0, 0]))
graph.node.insert(index + 1, helper.make_node("Transpose", ["upsample_nchw"], [custom.output[0]], perm=[0, 2, 3, 1]))
onnx.checker.check_model(model)
onnx.save(model, OUT)

# Against MediaPipe's own segmenter: confidence masks on test pictures.
import onnxruntime as ort, mediapipe as mp
from mediapipe.tasks import python as tasks
from mediapipe.tasks.python import vision
segmenter = vision.ImageSegmenter.create_from_options(vision.ImageSegmenterOptions(
    base_options=tasks.BaseOptions(model_asset_path=tflite), output_confidence_masks=True))
session = ort.InferenceSession(OUT)
from PIL import Image
portrait = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "Tests", "Fixtures", "astronaut.png")
rng = np.random.default_rng(3)
y, x = np.mgrid[0:256, 0:256] / 255.0
pictures = [np.array(Image.open(portrait).convert("RGB").resize((256, 256), Image.BILINEAR)),
            np.round((np.stack([np.sin(x * 6 + y), np.cos(y * 8), x * y], -1) * 0.5 + 0.5) * 255).astype(np.uint8),
            rng.integers(0, 256, (256, 256, 3)).astype(np.uint8)]
worst = 0.0
for image in pictures:
    expected = segmenter.segment(mp.Image(image_format=mp.ImageFormat.SRGB, data=image)).confidence_masks[0].numpy_view().reshape(256, 256)
    got = session.run(None, {session.get_inputs()[0].name: image[None].astype(np.float32) / 255.0})[0][0, ..., 0]
    worst = max(worst, float(np.abs(got - expected).max()))
covered = float((session.run(None, {session.get_inputs()[0].name: pictures[0][None].astype(np.float32) / 255.0})[0] > .5).mean())
assert 0.3 < covered < 0.8, "the portrait is not found (%.2f)" % covered
print("ONNX against MediaPipe: largest difference %.6f" % worst)
assert worst < 1e-3, "the converted model differs from MediaPipe's"
segmenter.close()
print("sha256", hashlib.sha256(open(OUT, "rb").read()).hexdigest(), os.path.getsize(OUT))
