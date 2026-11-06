"""PlanBOQ processing server (FastAPI).

Runs on an Oracle Cloud ARM instance (4 OCPU / 24 GB is plenty). Serves:

  GET  /api/v1/health          - liveness probe
  POST /api/v1/process/image   - line detection on a cleaned plan image
                                 (PNG, already perspective-corrected on device)
  POST /api/v1/export/dxf      - PlanBOQ geometry JSON -> DXF file
  POST /api/v1/export/pdf      - PlanBOQ geometry JSON -> PDF file

Auth: X-API-Key header, compared against the PLANBOQ_API_KEY env var.
Leave the env var unset to disable auth (dev only!).
"""

import gzip
import hashlib
import io
import json
import os
import pathlib

from fastapi import FastAPI, File, Header, HTTPException, UploadFile
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel, Field

from exporters import export_dxf, export_pdf
from processing import detect_lines_png

API_KEY = os.environ.get("PLANBOQ_API_KEY", "")
SYNC_DIR = os.environ.get("PLANBOQ_SYNC_DIR", os.path.join(os.getcwd(), "sync_data"))
MAX_PROJECT_BYTES = 30 * 1024 * 1024

app = FastAPI(title="PlanBOQ processing server", version="0.1.0")


def check_key(x_api_key: str | None) -> None:
    if not API_KEY:
        return  # auth disabled in dev
    if x_api_key != API_KEY:
        raise HTTPException(status_code=401, detail="Invalid or missing API key")


@app.get("/api/v1/health")
def health() -> dict:
    return {"ok": True, "service": "planboq", "auth": bool(API_KEY)}


@app.post("/api/v1/process/image")
async def process_image(
    file: UploadFile = File(...),
    x_api_key: str | None = Header(default=None),
) -> dict:
    """Detect lines in a cleaned, perspective-corrected plan image.

    Returns segments in PIXEL coordinates, y-down - the same coordinate space
    the on-device detector produces, so the client can swap paths freely.
    """
    check_key(x_api_key)
    data = await file.read()
    if not data:
        raise HTTPException(status_code=400, detail="Empty upload")
    try:
        return detect_lines_png(data)
    except ValueError as exc:
        return JSONResponse(status_code=400, content={"error": str(exc)})


# --------------------------------------------------------------------------
# Export endpoints
# --------------------------------------------------------------------------

class SegmentDto(BaseModel):
    x1: float
    y1: float
    x2: float
    y2: float


class PointDto(BaseModel):
    x: float
    y: float


class PolylineDto(BaseModel):
    points: list[PointDto]
    closed: bool = False
    layer: str = "0"


class LayerDto(BaseModel):
    name: str
    color_index: int = 7


class ExportRequest(BaseModel):
    name: str = "plan"
    segments: list[SegmentDto] = Field(default_factory=list)
    polylines: list[PolylineDto] = Field(default_factory=list)
    layers: list[LayerDto] = Field(default_factory=list)
    traced_from_image: bool = False


# --------------------------------------------------------------------------
# Cloud sync (device-to-device backup through this server)
#
# The sync code is a user-held secret (>= 12 chars). Projects are stored under
# a directory named by its SHA-256, never by the code itself.
# --------------------------------------------------------------------------

class SyncProject(BaseModel):
    id: str
    name: str = ""
    updatedAt: int = 0
    planJson: str = ""
    boqJson: str = ""
    backgroundBase64: str | None = None


class SyncPushRequest(BaseModel):
    syncId: str
    projects: list[SyncProject] = Field(default_factory=list)


def _sync_root(sync_id: str) -> pathlib.Path:
    if len(sync_id) < 12:
        raise HTTPException(status_code=400, detail="Sync code must be at least 12 characters")
    digest = hashlib.sha256(sync_id.encode("utf-8")).hexdigest()
    root = pathlib.Path(SYNC_DIR) / digest
    root.mkdir(parents=True, exist_ok=True)
    return root


def _safe_project_id(project_id: str) -> str:
    cleaned = "".join(c for c in project_id if c.isalnum() or c in "-_")
    if not cleaned:
        raise HTTPException(status_code=400, detail="Invalid project id")
    return cleaned


class SyncProjectMeta(BaseModel):
    id: str
    name: str = ""
    updatedAt: int = 0


@app.post("/api/v1/sync/push")
def sync_push(req: SyncPushRequest, x_api_key: str | None = Header(default=None)) -> dict:
    check_key(x_api_key)
    root = _sync_root(req.syncId)
    written = 0
    for project in req.projects:
        pid = _safe_project_id(project.id)
        payload = project.model_dump_json().encode("utf-8")
        if len(payload) > MAX_PROJECT_BYTES:
            raise HTTPException(status_code=413, detail=f"Project {pid} is too large")
        with gzip.open(root / f"{pid}.json.gz", "wb") as fh:
            fh.write(payload)
        written += 1
    return {"ok": True, "written": written}


@app.get("/api/v1/sync/list")
def sync_list(sync_id: str, x_api_key: str | None = Header(default=None)) -> list[dict]:
    check_key(x_api_key)
    root = _sync_root(sync_id)
    out = []
    for path in sorted(root.glob("*.json.gz")):
        try:
            with gzip.open(path, "rb") as fh:
                data = json.loads(fh.read().decode("utf-8"))
            out.append(
                {
                    "id": data.get("id", path.stem),
                    "name": data.get("name", ""),
                    "updatedAt": data.get("updatedAt", 0),
                }
            )
        except Exception:
            continue
    return out


@app.get("/api/v1/sync/project/{project_id}")
def sync_project(project_id: str, sync_id: str, x_api_key: str | None = Header(default=None)) -> dict:
    check_key(x_api_key)
    root = _sync_root(sync_id)
    pid = _safe_project_id(project_id)
    path = root / f"{pid}.json.gz"
    if not path.exists():
        raise HTTPException(status_code=404, detail="Project not found")
    with gzip.open(path, "rb") as fh:
        return json.loads(fh.read().decode("utf-8"))


@app.delete("/api/v1/sync/project/{project_id}")
def sync_delete(project_id: str, sync_id: str, x_api_key: str | None = Header(default=None)) -> dict:
    check_key(x_api_key)
    root = _sync_root(sync_id)
    pid = _safe_project_id(project_id)
    path = root / f"{pid}.json.gz"
    if path.exists():
        path.unlink()
    return {"ok": True}


@app.post("/api/v1/export/dxf")
def dxf_export(
    req: ExportRequest,
    x_api_key: str | None = Header(default=None),
) -> Response:
    check_key(x_api_key)
    content = export_dxf(req.model_dump())
    return Response(
        content=content,
        media_type="application/dxf",
        headers={"Content-Disposition": f'attachment; filename="{req.name or "plan"}.dxf"'},
    )


@app.post("/api/v1/export/pdf")
def pdf_export(
    req: ExportRequest,
    x_api_key: str | None = Header(default=None),
) -> Response:
    check_key(x_api_key)
    content = export_pdf(req.model_dump())
    return Response(
        content=content,
        media_type="application/pdf",
        headers={"Content-Disposition": f'attachment; filename="{req.name or "plan"}.pdf"'},
    )
