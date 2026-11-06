"""DXF (ezdxf) and PDF (reportlab) exporters for PlanBOQ geometry JSON."""

from __future__ import annotations

import io

import ezdxf
from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas

SCALE_STEP_MM = 1000.0  # one-metre scale bar


def export_dxf(req: dict) -> bytes:
    doc = ezdxf.new("R12", setup=False)
    msp = doc.modelspace()

    for layer in req.get("layers", []):
        name = _safe_layer(layer.get("name", "0"))
        if name and name not in doc.layers:
            doc.layers.add(name, color=int(layer.get("color_index", 7)))

    for seg in req.get("segments", []):
        msp.add_line(
            (seg["x1"], seg["y1"], 0.0),
            (seg["x2"], seg["y2"], 0.0),
            dxfattribs={"layer": _safe_layer(seg.get("layer", "0"))},
        )

    for poly in req.get("polylines", []):
        pts = [(p["x"], p["y"], 0.0) for p in poly.get("points", [])]
        if len(pts) >= 2:
            # classic POLYLINE (valid in R12; LWPOLYLINE needs R2000+)
            msp.add_polyline(pts, dxfattribs={"layer": _safe_layer(poly.get("layer", "0"))})

    # annotation: scale bar + disclaimer
    if req.get("segments"):
        xs = [v for s in req["segments"] for v in (s["x1"], s["x2"])]
        ys = [v for s in req["segments"] for v in (s["y1"], s["y2"])]
        min_x, min_y = min(xs), min(ys)
        bar_y = min_y - max((max(ys) - min_y) * 0.03, 500.0)
        msp.add_line((min_x, bar_y, 0.0), (min_x + SCALE_STEP_MM, bar_y, 0.0), dxfattribs={"layer": "PLANBOQ_ANNOT"})
        note = (
            "Traced from image. Verify before construction."
            if req.get("traced_from_image")
            else "Exported by PlanBOQ - verify dimensions before construction."
        )
        msp.add_text(note, dxfattribs={"height": 100.0, "layer": "PLANBOQ_ANNOT"}).set_placement((min_x, bar_y - 250.0))

    out = io.StringIO()
    doc.write(out)
    return out.getvalue().encode("latin-1", errors="replace")


def export_pdf(req: dict) -> bytes:
    segments = req.get("segments", [])
    if not segments:
        buf = io.BytesIO()
        c = canvas.Canvas(buf, pagesize=A4)
        c.drawString(50, 800, "PlanBOQ - empty plan")
        c.save()
        return buf.getvalue()

    xs = [v for s in segments for v in (s["x1"], s["x2"])]
    ys = [v for s in segments for v in (s["y1"], s["y2"])]
    min_x, max_x = min(xs), max(xs)
    min_y, max_y = min(ys), max(ys)
    width = max(max_x - min_x, 1.0)
    height = max(max_y - min_y, 1.0)

    margin = 40.0
    page_w, page_h = A4
    scale = min((page_w - 2 * margin) / width, (page_h - 2 * margin) / height)

    buf = io.BytesIO()
    c = canvas.Canvas(buf, pagesize=A4)
    c.setLineWidth(0.35)

    def px(x: float) -> float:
        return margin + (x - min_x) * scale

    def py(y: float) -> float:
        return page_h - margin - (y - min_y) * scale

    for s in segments:
        c.line(px(s["x1"]), py(s["y1"]), px(s["x2"]), py(s["y2"]))
    for poly in req.get("polylines", []):
        pts = poly.get("points", [])
        if len(pts) >= 2:
            p = c.beginPath()
            p.moveTo(px(pts[0]["x"]), py(pts[0]["y"]))
            for q in pts[1:]:
                p.lineTo(px(q["x"]), py(q["y"]))
            c.drawPath(p, stroke=1, fill=0)

    c.setFont("Helvetica-Bold", 10)
    c.drawString(margin, page_h - margin + 14, (req.get("name") or "plan")[:60])
    note = (
        "Traced from image. Verify before construction."
        if req.get("traced_from_image")
        else "Exported by PlanBOQ - verify dimensions before construction."
    )
    c.setFont("Helvetica", 7)
    c.drawString(margin, margin - 16, note)
    c.save()
    return buf.getvalue()


def _safe_layer(name: str) -> str:
    cleaned = "".join(ch if ch.isalnum() or ch in "-_" else "_" for ch in (name or "0"))[:31]
    return cleaned or "0"
