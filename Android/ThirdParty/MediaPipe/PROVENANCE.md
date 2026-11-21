# MediaPipe Selfie Segmenter

`SelfieSegmenter.onnx` is Google MediaPipe's Selfie Segmenter (square,
256×256 input, float16 weights), Apache License 2.0 (`LICENSE`, as shipped
with the `mediapipe` Python package), converted to ONNX:

- Source: `https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_segmenter/float16/latest/selfie_segmenter.tflite`
  (SHA-256 `191ac9529ae506ee0beefa6b2c945a172dab9d07d1e802a290a4e4038226658b`).
- Converted by `Android/scripts/segmentation/convert-selfie.py`: tf2onnx 1.17
  (opset 17), then MediaPipe's custom `Convolution2DTransposeBias` (the final
  2×2, stride-2 upsampling) replaced by a standard ONNX `ConvTranspose` with the
  same kernel and bias.
- Checked against MediaPipe's own `ImageSegmenter` (mediapipe 1.2.3) on a
  portrait (`Tests/Fixtures/astronaut.png`) and two test pictures: largest
  difference in the confidence mask 0.000034.
- Result SHA-256 `7ff6a1bab263a6df7420129b96b3857880feb1ca799ff72adafe6221a0121600`.

Input: one 256×256 RGB picture, values 0…1, NHWC. Output: person confidence
0…1, 256×256. The iPhone app uses Apple Vision's person segmentation instead;
the masks are not the same.
