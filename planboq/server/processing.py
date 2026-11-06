"""Image cleanup + line detection, mirroring the on-device OpenCV pipeline.

Input: a PNG of a plan that the client has already perspective-corrected.
Steps: grayscale -> adaptive threshold -> LSD (or HoughLinesP fallback).
Output segments are in input-image pixel coordinates, y-down.
"""

from __future__ import annotations

import cv2
import numpy as np

MAX_LINES = 6000


def detect_lines_png(png_bytes: bytes) -> dict:
    buffer = np.frombuffer(png_bytes, dtype=np.uint8)
    image = cv2.imdecode(buffer, cv2.IMREAD_GRAYSCALE)
    if image is None:
        raise ValueError("Could not decode image (PNG expected)")

    binary = _binarize(image)
    segments, detector = _detect(binary)

    return {
        "segments": [{"x1": x1, "y1": y1, "x2": x2, "y2": y2} for (x1, y1, x2, y2) in segments],
        "width": int(image.shape[1]),
        "height": int(image.shape[0]),
        "detector": detector,
    }


def _binarize(gray: np.ndarray) -> np.ndarray:
    # Flatten illumination like the device pipeline: divide by a blurred estimate.
    blur = cv2.GaussianBlur(gray, (0, 0), max(gray.shape) / 120.0)
    flattened = cv2.divide(gray, blur, scale=255)
    block = max(7, (max(gray.shape) // 40) | 1)
    return cv2.adaptiveThreshold(
        flattened, 255.0, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY_INV, block, 10.0,
    )


def _detect(binary: np.ndarray) -> tuple[list[tuple[float, float, float, float]], str]:
    lines: np.ndarray | None = None
    detector = "Hough"
    try:
        lsd = cv2.createLineSegmentDetector()
        lines = lsd.detect(binary)[0]
        detector = "LSD"
    except Exception:
        lines = None

    if lines is None or len(lines) == 0:
        min_len = 0.02 * min(binary.shape)
        found = cv2.HoughLinesP(binary, 1.0, np.pi / 720.0, 60, minLineLength=min_len, maxLineGap=5)
        detector = "Hough"
        if found is None:
            return [], detector
        lines = found

    segs = [
        (float(l[0]), float(l[1]), float(l[2]), float(l[3]))
        for l in lines.reshape(-1, 4)
    ]
    segs.sort(key=lambda s: -((s[2] - s[0]) ** 2 + (s[3] - s[1]) ** 2))
    return segs[:MAX_LINES], detector
