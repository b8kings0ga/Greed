#!/usr/bin/env python3
from __future__ import annotations

import argparse
import io
import json
import threading
import time
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
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
      <h2>Objects</h2>
      <table>
        <thead><tr><th>class</th><th>count</th><th>best</th></tr></thead>
        <tbody id="objects"></tbody>
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
        `${data.source} · ${data.fps_target} fps target · frame ${data.frame_age_seconds.toFixed(1)}s old`;
      const objects = document.getElementById("objects");
      objects.innerHTML = "";
      for (const row of data.objects) {
        objects.insertAdjacentHTML("beforeend",
          `<tr><td><span class="pill">${row.name}</span></td><td>${row.count}</td><td>${row.best_confidence.toFixed(2)}</td></tr>`);
      }
      const detections = document.getElementById("detections");
      detections.innerHTML = "";
      for (const d of data.detections) {
        detections.insertAdjacentHTML("beforeend",
          `<tr><td>${d.name}</td><td>${d.confidence.toFixed(2)}</td><td>${d.box.map(v => Math.round(v)).join(", ")}</td></tr>`);
      }
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
    latest_jpeg: bytes | None = None
    detections: list[dict[str, Any]] = field(default_factory=list)
    objects: list[dict[str, Any]] = field(default_factory=list)
    frame_time: float = 0.0
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


def worker(state: DetectionState, model_name: str, imgsz: int, confidence: float) -> None:
    import cv2
    from ultralytics import YOLO

    model = YOLO(model_name)
    period = 1.0 / max(0.1, state.fps_target)

    while True:
        capture = cv2.VideoCapture(state.source)
        if not capture.isOpened():
            with state.lock:
                state.error = f"failed to open stream: {state.source}"
            time.sleep(2)
            continue

        try:
            while True:
                start = time.monotonic()
                ok, frame = capture.read()
                if not ok or frame is None:
                    with state.lock:
                        state.error = "stream read failed"
                    break

                results = model.predict(frame, imgsz=imgsz, conf=confidence, verbose=False)
                names = results[0].names
                detections: list[dict[str, Any]] = []
                for box in results[0].boxes:
                    cls = int(box.cls[0].item())
                    conf = float(box.conf[0].item())
                    xyxy = [float(v) for v in box.xyxy[0].tolist()]
                    detections.append({"name": str(names[cls]), "confidence": conf, "box": xyxy})

                annotated = draw_detections(frame.copy(), detections)
                ok, encoded = cv2.imencode(".jpg", annotated, [int(cv2.IMWRITE_JPEG_QUALITY), 78])
                if ok:
                    with state.lock:
                        state.latest_jpeg = encoded.tobytes()
                        state.detections = detections
                        state.objects = summarize(detections)
                        state.frame_time = time.time()
                        state.error = None

                elapsed = time.monotonic() - start
                if elapsed < period:
                    time.sleep(period - elapsed)
        finally:
            capture.release()
            time.sleep(0.5)


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
            return {
                "source": self.state.source,
                "fps_target": self.state.fps_target,
                "detections": list(self.state.detections),
                "objects": list(self.state.objects),
                "frame_age_seconds": max(0.0, time.time() - self.state.frame_time) if self.state.frame_time else 9999.0,
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
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--conf", type=float, default=0.25)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    state = DetectionState(source=args.source, fps_target=args.fps)
    Handler.state = state
    thread = threading.Thread(target=worker, args=(state, args.model, args.imgsz, args.conf), daemon=True)
    thread.start()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"YOLO viewer: http://{args.host}:{args.port}")
    print(f"Source: {args.source}")
    server.serve_forever()


if __name__ == "__main__":
    main()
