#!/usr/bin/env python3
from __future__ import annotations

import argparse
import io
import json
import threading
import time
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any


HTML = """<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Podroid YOLO Stream</title>
  <style>
    body { margin: 0; background: #0f1115; color: #f1f5f9; font: 14px system-ui, sans-serif; }
    header { padding: 14px 18px; border-bottom: 1px solid #29313d; display: flex; justify-content: space-between; gap: 12px; }
    main { display: grid; grid-template-columns: minmax(320px, 1fr) 340px; gap: 16px; padding: 16px; }
    .panel { background: #151922; border: 1px solid #29313d; padding: 12px; }
    img { width: 100%; background: #05070a; display: block; }
    table { width: 100%; border-collapse: collapse; }
    th, td { text-align: left; padding: 7px 4px; border-bottom: 1px solid #29313d; }
    .muted { color: #94a3b8; }
    .pill { padding: 2px 7px; background: #243044; border-radius: 999px; }
    @media (max-width: 820px) { main { grid-template-columns: 1fr; } }
  </style>
</head>
<body>
  <header>
    <strong>Podroid YOLO Stream</strong>
    <span class="muted" id="meta">loading</span>
  </header>
  <main>
    <section class="panel">
      <img id="frame" src="/frame.jpg" alt="YOLO annotated frame">
    </section>
    <aside class="panel">
      <h2>Target Classes</h2>
      <div id="classes" class="muted">loading</div>
      <h2>Objects</h2>
      <table>
        <thead><tr><th>class</th><th>count</th><th>best</th></tr></thead>
        <tbody id="objects"></tbody>
      </table>
      <h2>Seen Objects</h2>
      <table>
        <thead><tr><th>class</th><th>seen</th><th>last</th></tr></thead>
        <tbody id="seen"></tbody>
      </table>
      <h2>Detections</h2>
      <table>
        <thead><tr><th>class</th><th>conf</th><th>box</th></tr></thead>
        <tbody id="detections"></tbody>
      </table>
    </aside>
  </main>
  <script>
    async function refresh() {
      const now = Date.now();
      document.getElementById("frame").src = "/frame.jpg?t=" + now;
      const r = await fetch("/detections.json?t=" + now);
      const data = await r.json();
      document.getElementById("meta").textContent =
        `${data.mode} · ${data.source} · ${data.fps_target} fps target · capture ${data.capture_age_seconds.toFixed(1)}s · detection ${data.detection_age_seconds.toFixed(1)}s · frame ${data.detection_frame_age_seconds.toFixed(1)}s`;
      document.getElementById("classes").innerHTML = (data.target_classes || [])
        .map(c => `<span class="pill">${c}</span>`)
        .join(" ");
      const objectRows = dedupeObjects(data);
      const objects = document.getElementById("objects");
      objects.innerHTML = "";
      if (objectRows.length === 0) {
        objects.insertAdjacentHTML("beforeend", `<tr><td colspan="3" class="muted">No objects detected</td></tr>`);
      }
      for (const row of objectRows) {
        objects.insertAdjacentHTML("beforeend",
          `<tr><td><span class="pill">${row.name}</span></td><td>${row.count}</td><td>${row.best_confidence.toFixed(2)}</td></tr>`);
      }
      const seen = document.getElementById("seen");
      seen.innerHTML = "";
      if ((data.seen_objects || []).length === 0) {
        seen.insertAdjacentHTML("beforeend", `<tr><td colspan="3" class="muted">Nothing seen yet</td></tr>`);
      }
      for (const row of data.seen_objects || []) {
        seen.insertAdjacentHTML("beforeend",
          `<tr><td><span class="pill">${row.name}</span></td><td>${row.total_count}</td><td>${row.last_seen_ago_seconds.toFixed(1)}s</td></tr>`);
      }
      const detections = document.getElementById("detections");
      detections.innerHTML = "";
      if (data.detections.length === 0) {
        detections.insertAdjacentHTML("beforeend", `<tr><td colspan="3" class="muted">No boxes</td></tr>`);
      }
      for (const d of data.detections) {
        detections.insertAdjacentHTML("beforeend",
          `<tr><td>${d.name}</td><td>${d.confidence.toFixed(2)}</td><td>${d.box.map(v => Math.round(v)).join(", ")}</td></tr>`);
      }
    }
    function dedupeObjects(data) {
      const byName = new Map();
      for (const item of data.objects || []) {
        byName.set(item.name, {
          name: item.name,
          count: Number(item.count) || 0,
          best_confidence: Number(item.best_confidence) || 0
        });
      }
      for (const det of data.detections || []) {
        const cur = byName.get(det.name) || { name: det.name, count: 0, best_confidence: 0 };
        cur.count += 1;
        cur.best_confidence = Math.max(cur.best_confidence, Number(det.confidence) || 0);
        byName.set(det.name, cur);
      }
      return Array.from(byName.values())
        .sort((a, b) => b.count - a.count || b.best_confidence - a.best_confidence || a.name.localeCompare(b.name));
    }
    refresh();
    setInterval(refresh, 1000);
  </script>
</body>
</html>
"""


@dataclass
class DetectionState:
    lock: threading.Lock = field(default_factory=threading.Lock)
    source: str = ""
    fps_target: float = 1.0
    mode: str = "yolo"
    target_classes: list[str] = field(default_factory=list)
    latest_frame: Any | None = None
    capture_time: float = 0.0
    latest_jpeg: bytes | None = None
    detections: list[dict[str, Any]] = field(default_factory=list)
    objects: list[dict[str, Any]] = field(default_factory=list)
    seen_objects: dict[str, dict[str, Any]] = field(default_factory=dict)
    detection_time: float = 0.0
    detection_capture_time: float = 0.0
    error: str | None = None


def draw_detections(frame: Any, detections: list[dict[str, Any]]) -> Any:
    import cv2

    for det in detections:
        x1, y1, x2, y2 = [int(v) for v in det["box"]]
        name = det["name"]
        conf = det["confidence"]
        color = (54, 211, 153)
        cv2.rectangle(frame, (x1, y1), (x2, y2), color, 2)
        label = f"{name} {conf:.2f}"
        (tw, th), _ = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.55, 2)
        cv2.rectangle(frame, (x1, max(0, y1 - th - 8)), (x1 + tw + 8, y1), color, -1)
        cv2.putText(frame, label, (x1 + 4, max(14, y1 - 5)), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (12, 18, 24), 2)
    return frame


def summarize(detections: list[dict[str, Any]]) -> list[dict[str, Any]]:
    by_name: dict[str, dict[str, Any]] = {}
    for det in detections:
        item = by_name.setdefault(det["name"], {"name": det["name"], "count": 0, "best_confidence": 0.0})
        item["count"] += 1
        item["best_confidence"] = max(item["best_confidence"], det["confidence"])
    return sorted(by_name.values(), key=lambda x: (-x["count"], -x["best_confidence"], x["name"]))


def update_seen(seen: dict[str, dict[str, Any]], detections: list[dict[str, Any]], now: float) -> None:
    for row in summarize(detections):
        item = seen.setdefault(row["name"], {
            "name": row["name"],
            "total_count": 0,
            "best_confidence": 0.0,
            "first_seen": now,
            "last_seen": now,
        })
        item["total_count"] += row["count"]
        item["best_confidence"] = max(item["best_confidence"], row["best_confidence"])
        item["last_seen"] = now


def seen_snapshot(seen: dict[str, dict[str, Any]], now: float) -> list[dict[str, Any]]:
    rows = []
    for item in seen.values():
        rows.append({
            "name": item["name"],
            "total_count": item["total_count"],
            "best_confidence": item["best_confidence"],
            "first_seen_ago_seconds": max(0.0, now - item["first_seen"]),
            "last_seen_ago_seconds": max(0.0, now - item["last_seen"]),
        })
    return sorted(rows, key=lambda x: (x["last_seen_ago_seconds"], -x["total_count"], x["name"]))


def capture_worker(state: DetectionState) -> None:
    import cv2

    while True:
        capture = cv2.VideoCapture(state.source)
        capture.set(cv2.CAP_PROP_BUFFERSIZE, 1)
        if not capture.isOpened():
            with state.lock:
                state.error = f"failed to open stream: {state.source}"
            time.sleep(2)
            continue

        try:
            while True:
                ok, frame = capture.read()
                if not ok or frame is None:
                    with state.lock:
                        state.error = "stream read failed"
                    break
                with state.lock:
                    state.latest_frame = frame
                    state.capture_time = time.time()
                    if state.latest_jpeg is None:
                        ok, encoded = cv2.imencode(".jpg", frame, [int(cv2.IMWRITE_JPEG_QUALITY), 78])
                        if ok:
                            state.latest_jpeg = encoded.tobytes()
                    state.error = None
        finally:
            capture.release()
            time.sleep(0.2)


def worker(state: DetectionState, model_name: str, imgsz: int, confidence: float, world: bool) -> None:
    import cv2
    from ultralytics import YOLO, YOLOWorld

    model = YOLOWorld(model_name) if world else YOLO(model_name)
    if world and state.target_classes:
        model.set_classes(state.target_classes)
    period = 1.0 / max(0.1, state.fps_target)

    while True:
        start = time.monotonic()
        with state.lock:
            frame = None if state.latest_frame is None else state.latest_frame.copy()
            capture_time = state.capture_time

        if frame is None:
            time.sleep(0.05)
            continue

        results = model.predict(frame, imgsz=imgsz, conf=confidence, verbose=False)
        names = results[0].names
        detections: list[dict[str, Any]] = []
        for box in results[0].boxes:
            cls = int(box.cls[0].item())
            conf = float(box.conf[0].item())
            xyxy = [float(v) for v in box.xyxy[0].tolist()]
            detections.append({"name": str(names[cls]), "confidence": conf, "box": xyxy})

        annotated = draw_detections(frame, detections)
        ok, encoded = cv2.imencode(".jpg", annotated, [int(cv2.IMWRITE_JPEG_QUALITY), 78])
        if ok:
            now = time.time()
            with state.lock:
                state.latest_jpeg = encoded.tobytes()
                state.detections = detections
                state.objects = summarize(detections)
                update_seen(state.seen_objects, detections, now)
                state.detection_time = now
                state.detection_capture_time = capture_time
                state.error = None

        elapsed = time.monotonic() - start
        if elapsed < period:
            time.sleep(period - elapsed)


class Handler(BaseHTTPRequestHandler):
    state: DetectionState

    def do_GET(self) -> None:
        if self.path.startswith("/detections.json"):
            self.write_json(self.snapshot())
            return
        if self.path.startswith("/frame.jpg"):
            with self.state.lock:
                jpeg = self.state.latest_jpeg
            if jpeg is None:
                jpeg = placeholder_jpeg()
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(jpeg)))
            self.end_headers()
            self.wfile.write(jpeg)
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(HTML.encode("utf-8"))

    def log_message(self, fmt: str, *args: Any) -> None:
        return

    def snapshot(self) -> dict[str, Any]:
        with self.state.lock:
            now = time.time()
            return {
                "source": self.state.source,
                "fps_target": self.state.fps_target,
                "mode": self.state.mode,
                "target_classes": list(self.state.target_classes),
                "detections": list(self.state.detections),
                "objects": list(self.state.objects),
                "seen_objects": seen_snapshot(self.state.seen_objects, now),
                "capture_age_seconds": max(0.0, now - self.state.capture_time) if self.state.capture_time else 9999.0,
                "detection_age_seconds": max(0.0, now - self.state.detection_time) if self.state.detection_time else 9999.0,
                "detection_frame_age_seconds": max(0.0, now - self.state.detection_capture_time)
                if self.state.detection_capture_time else 9999.0,
                "error": self.state.error,
            }

    def write_json(self, payload: dict[str, Any]) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def placeholder_jpeg() -> bytes:
    from PIL import Image, ImageDraw

    image = Image.new("RGB", (960, 540), (15, 17, 21))
    draw = ImageDraw.Draw(image)
    draw.text((32, 32), "Waiting for YOLO frame...", fill=(241, 245, 249))
    out = io.BytesIO()
    image.save(out, format="JPEG", quality=80)
    return out.getvalue()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run YOLO on the Podroid Android camera MJPEG stream.")
    parser.add_argument("--source", default="http://192.168.1.33:18080/stream.mjpg")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18888)
    parser.add_argument("--fps", type=float, default=1.0)
    parser.add_argument("--model", default="yolov8n.pt")
    parser.add_argument("--world", action="store_true", help="Use YOLO-World with the supplied text classes.")
    parser.add_argument(
        "--classes",
        default="person,face,hand,phone,laptop,keyboard,mouse,monitor,bottle,cup,book,backpack,chair,table,door,window",
        help="Comma-separated classes for YOLO-World.",
    )
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--conf", type=float, default=0.25)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    target_classes = [c.strip() for c in args.classes.split(",") if c.strip()]
    state = DetectionState(
        source=args.source,
        fps_target=args.fps,
        mode="yolo-world" if args.world else "yolo",
        target_classes=target_classes if args.world else [],
    )
    Handler.state = state
    threading.Thread(target=capture_worker, args=(state,), daemon=True).start()
    threading.Thread(target=worker, args=(state, args.model, args.imgsz, args.conf, args.world), daemon=True).start()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"YOLO viewer: http://{args.host}:{args.port}")
    print(f"Source: {args.source}")
    if args.world:
        print(f"YOLO-World classes: {', '.join(target_classes)}")
    server.serve_forever()


if __name__ == "__main__":
    main()
