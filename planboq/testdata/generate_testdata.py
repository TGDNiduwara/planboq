"""Generate PlanBOQ test assets: a small apartment plan as DXF, vector PDF,
clean PNG and a photo-style (perspective-skewed) PNG.

Run:  python generate_testdata.py
Outputs are written next to this script.

Plan: 9600 x 7200 mm apartment, wall 230 mm; vertical partition at x=5400
(thickness 115) with a 900 mm door; horizontal partition at y=3600 (thickness
115) with a 900 mm door; window on the top wall; circular column r=150.
"""

from __future__ import annotations

import math
import os

from PIL import Image, ImageDraw, ImageFilter
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))

W, H = 9600.0, 7200.0          # outer wall centreline rectangle, mm
WALL = 230.0
PART = 115.0
DOOR = 900.0

# ---------------------------------------------------------------------------
# Geometry definition (shared by all exporters)
# ---------------------------------------------------------------------------

def plan_geometry():
    """Returns (lines, arcs, circles) in mm, y-up."""
    lines: list[tuple[float, float, float, float, str]] = []
    arcs: list[tuple[float, float, float, float, float, str]] = []
    circles: list[tuple[float, float, float, str]] = []

    # outer wall: outer and inner faces
    for (x0, y0, x1, y1) in [
        (0, 0, W, 0), (W, 0, W, H), (W, H, 0, H), (0, H, 0, 0),
        (WALL, WALL, W - WALL, WALL), (W - WALL, WALL, W - WALL, H - WALL),
        (W - WALL, H - WALL, WALL, H - WALL), (WALL, H - WALL, WALL, WALL),
    ]:
        lines.append((x0, y0, x1, y1, "WALLS"))

    # vertical partition centered x=5400, door gap y 3000..3900
    xa, xb = 5400 - PART / 2, 5400 + PART / 2
    for x in (xa, xb):
        lines.append((x, WALL, x, 3000, "PARTITIONS"))
        lines.append((x, 3900, x, H - WALL, "PARTITIONS"))
    # door leaf + swing (hinge at bottom of the gap on the right face)
    lines.append((xb, 3900, xb + DOOR, 3900, "DOORS"))
    arcs.append((xb, 3900, DOOR, 180.0, 270.0, "DOORS"))

    # horizontal partition centered y=3600, x 230..5285, door gap x 1500..2400
    ya, yb = 3600 - PART / 2, 3600 + PART / 2
    for y in (ya, yb):
        lines.append((WALL, y, 1500, y, "PARTITIONS"))
        lines.append((2400, y, 5285, y, "PARTITIONS"))
    lines.append((2400, yb, 2400, yb + DOOR, "DOORS"))
    arcs.append((2400, yb, DOOR, 0.0, 90.0, "DOORS"))

    # window on top wall x 2000..3800: glazing lines inside the wall
    lines.append((2000, H - WALL * 0.66, 3800, H - WALL * 0.66, "WINDOWS"))
    lines.append((2000, H - WALL * 0.33, 3800, H - WALL * 0.33, "WINDOWS"))

    # column
    circles.append((8900, 900, 150, "COLUMNS"))

    # dimension lines (ignore in raster label drawing)
    lines.append((0, -400, W, -400, "DIM"))
    lines.append((-400, 0, -400, H, "DIM"))
    for x in (0, W):
        lines.append((x, -520, x, -280, "DIM"))
    for y in (0, H):
        lines.append((-520, y, -280, y, "DIM"))

    return lines, arcs, circles


# ---------------------------------------------------------------------------
# DXF (R12 ASCII, units = mm)
# ---------------------------------------------------------------------------

def write_dxf(path: str) -> None:
    lines, arcs, circles = plan_geometry()
    out: list[str] = []

    def p(code: int, value) -> None:
        out.append(str(code))
        out.append(str(value))

    p(0, "SECTION"); p(2, "HEADER")
    p(9, "$ACADVER"); p(1, "AC1009")
    p(9, "$INSUNITS"); p(70, 4)   # millimetres
    p(9, "$EXTMIN"); p(10, -600); p(20, -600); p(30, 0)
    p(9, "$EXTMAX"); p(10, 9900); p(20, 7400); p(30, 0)
    p(0, "ENDSEC")

    p(0, "SECTION"); p(2, "TABLES")
    p(0, "TABLE"); p(2, "LAYER"); p(70, 6)
    for name, color in [("WALLS", 7), ("PARTITIONS", 8), ("DOORS", 3),
                        ("WINDOWS", 5), ("COLUMNS", 6), ("DIM", 2)]:
        p(0, "LAYER"); p(2, name); p(70, 0); p(62, color); p(6, "CONTINUOUS")
    p(0, "ENDTAB"); p(0, "ENDSEC")

    p(0, "SECTION"); p(2, "ENTITIES")
    for x0, y0, x1, y1, layer in lines:
        p(0, "LINE"); p(8, layer)
        p(10, f"{x0:.4f}"); p(20, f"{y0:.4f}"); p(30, 0)
        p(11, f"{x1:.4f}"); p(21, f"{y1:.4f}"); p(31, 0)
    for cx, cy, r, a0, a1, layer in arcs:
        p(0, "ARC"); p(8, layer)
        p(10, f"{cx:.4f}"); p(20, f"{cy:.4f}"); p(30, 0); p(40, f"{r:.4f}")
        p(50, f"{a0:.4f}"); p(51, f"{a1:.4f}")
    for cx, cy, r, layer in circles:
        p(0, "CIRCLE"); p(8, layer)
        p(10, f"{cx:.4f}"); p(20, f"{cy:.4f}"); p(30, 0); p(40, f"{r:.4f}")
    p(0, "ENDSEC"); p(0, "EOF")

    with open(path, "w", encoding="ascii", newline="\r\n") as fh:
        fh.write("\n".join(out) + "\n")
    print("wrote", path)


# ---------------------------------------------------------------------------
# Vector PDF
# ---------------------------------------------------------------------------

def write_pdf(path: str) -> None:
    from reportlab.lib.pagesizes import A3, landscape
    from reportlab.pdfgen import canvas

    lines, arcs, circles = plan_geometry()
    page_w, page_h = landscape(A3)
    margin = 50.0
    scale = min((page_w - 2 * margin) / (W + 800), (page_h - 2 * margin) / (H + 800))

    def X(x): return margin + (x + 400) * scale
    def Y(y): return margin + (y + 400) * scale

    c = canvas.Canvas(path, pagesize=(page_w, page_h))
    c.setTitle("PlanBOQ sample plan")
    c.setLineWidth(0.7)
    for x0, y0, x1, y1, _layer in lines:
        c.line(X(x0), Y(y0), X(x1), Y(y1))
    c.setLineWidth(0.45)
    for cx, cy, r, a0, a1, _layer in arcs:
        steps = 24
        pts = [
            (X(cx + r * math.cos(math.radians(a0 + (a1 - a0) * i / steps))),
             Y(cy + r * math.sin(math.radians(a0 + (a1 - a0) * i / steps))))
            for i in range(steps + 1)
        ]
        p = c.beginPath()
        p.moveTo(*pts[0])
        for q in pts[1:]:
            p.lineTo(*q)
        c.drawPath(p, stroke=1, fill=0)
    for cx, cy, r, _layer in circles:
        c.circle(X(cx), Y(cy), r * scale, stroke=1, fill=0)
    c.setFont("Helvetica-Bold", 14)
    c.drawString(margin, page_h - margin + 10, "PlanBOQ sample plan - 9600 x 7200 mm")
    c.setFont("Helvetica", 8)
    c.drawString(margin, margin - 30, "Vector test sheet. Import via PlanBOQ -> Vector PDF.")
    c.save()
    print("wrote", path)


# ---------------------------------------------------------------------------
# Clean PNG (white sheet, black lines)
# ---------------------------------------------------------------------------

def _draw_sheet(px_w: int = 1600) -> Image.Image:
    from PIL import ImageFont

    lines, arcs, circles = plan_geometry()
    margin_mm = 700.0
    scale = px_w / (W + 2 * margin_mm)
    px_h = int((H + 2 * margin_mm) * scale)

    img = Image.new("RGB", (px_w, px_h), (252, 251, 248))
    d = ImageDraw.Draw(img)

    def X(x): return (x + margin_mm) * scale
    def Y(y): return px_h - (y + margin_mm) * scale   # y-up -> image y-down

    for x0, y0, x1, y1, layer in lines:
        width = {"WALLS": 9, "PARTITIONS": 5, "WINDOWS": 3, "DOORS": 2, "DIM": 1}.get(layer, 2)
        color = {"WINDOWS": (60, 90, 170), "DOORS": (30, 120, 60), "DIM": (140, 140, 140)}.get(layer, (25, 25, 25))
        d.line([X(x0), Y(y0), X(x1), Y(y1)], fill=color, width=width)

    def arc_points(cx, cy, r, a0, a1, steps=40):
        return [
            (X(cx + r * math.cos(math.radians(a0 + (a1 - a0) * i / steps))),
             Y(cy + r * math.sin(math.radians(a0 + (a1 - a0) * i / steps))))
            for i in range(steps + 1)
        ]

    for cx, cy, r, a0, a1, _layer in arcs:
        d.line(arc_points(cx, cy, r, a0, a1), fill=(30, 120, 60), width=2)
    for cx, cy, r, _layer in circles:
        d.ellipse([X(cx - r), Y(cy + r), X(cx + r), Y(cy - r)], outline=(25, 25, 25), width=6)

    try:
        font = ImageFont.truetype("arial.ttf", max(14, int(22 * scale / 0.18)))
        small = ImageFont.truetype("arial.ttf", max(10, int(14 * scale / 0.18)))
    except OSError:
        font = ImageFont.load_default()
        small = font

    d.text((X(2600), Y(6200)), "LIVING", fill=(60, 60, 60), font=font)
    d.text((X(2600), Y(3000)), "BEDROOM", fill=(60, 60, 60), font=font)
    d.text((X(6500), Y(5400)), "KITCHEN", fill=(60, 60, 60), font=font)
    d.text((X(6500), Y(4700)), "BATH", fill=(60, 60, 60), font=font)
    d.text((X(4200), Y(-650)), "9600 mm", fill=(120, 120, 120), font=small)
    d.text((X(-680), Y(3700)), "7200", fill=(120, 120, 120), font=small)
    return img


def write_png(path: str) -> None:
    img = _draw_sheet()
    img.save(path)
    print("wrote", path)


def write_photo_png(path: str) -> None:
    """The clean sheet perspective-warped onto a dark background, simulating a
    phone photo of a printed plan (for the 4-corner trace flow)."""
    sheet = _draw_sheet(px_w=1500)
    sw, sh = sheet.size

    canvas = Image.new("RGB", (2000, 1500), (70, 68, 64))
    # perspective destination quad (TL, TR, BR, BL) on the canvas
    dst = np.array([
        [260.0, 150.0],
        [1760.0, 260.0],
        [1620.0, 1380.0],
        [330.0, 1300.0],
    ], dtype=np.float64)
    src = np.array([
        [0.0, 0.0],
        [sw - 1.0, 0.0],
        [sw - 1.0, sh - 1.0],
        [0.0, sh - 1.0],
    ], dtype=np.float64)

    # PIL wants the OUTPUT -> INPUT homography (inverse of src -> dst):
    # solve for the map taking each destination corner back to the source corner.
    A, b = [], []
    for (ix, iy), (ox, oy) in zip(src, dst):
        A.append([ox, oy, 1, 0, 0, 0, -ix * ox, -ix * oy])
        b.append(ix)
        A.append([0, 0, 0, ox, oy, 1, -iy * ox, -iy * oy])
        b.append(iy)
    h = np.linalg.solve(np.array(A, dtype=np.float64), np.array(b, dtype=np.float64))
    coeffs = h.tolist()
    warped = sheet.transform(canvas.size, Image.PERSPECTIVE, coeffs, Image.BICUBIC, fillcolor=(70, 68, 64))
    canvas = warped

    # soft shadow vignette + slight blur + grain
    canvas = canvas.filter(ImageFilter.GaussianBlur(0.6))
    arr = np.asarray(canvas).astype(np.int16)
    rng = np.random.default_rng(7)
    noise = rng.integers(-6, 7, arr.shape, dtype=np.int16)
    arr = np.clip(arr + noise, 0, 255).astype(np.uint8)
    # illumination gradient: darker toward the right edge
    hgt, wid = arr.shape[:2]
    grad = np.linspace(1.06, 0.82, wid, dtype=np.float32)[None, :, None]
    arr = np.clip(arr.astype(np.float32) * grad, 0, 255).astype(np.uint8)
    Image.fromarray(arr).save(path)
    print("wrote", path)


def main() -> None:
    write_dxf(os.path.join(HERE, "sample_plan.dxf"))
    write_pdf(os.path.join(HERE, "sample_plan.pdf"))
    write_png(os.path.join(HERE, "sample_plan.png"))
    write_photo_png(os.path.join(HERE, "sample_plan_photo.png"))


if __name__ == "__main__":
    main()
