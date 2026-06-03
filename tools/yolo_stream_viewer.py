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
from urllib.request import Request, urlopen


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
    .class-form { display: grid; gap: 8px; margin: 8px 0 12px; }
    textarea { width: 100%; box-sizing: border-box; min-height: 72px; resize: vertical; background: #0f1115; color: #f1f5f9; border: 1px solid #29313d; padding: 8px; font: inherit; }
    button { justify-self: start; background: #38d399; color: #07110d; border: 0; padding: 7px 12px; font-weight: 700; cursor: pointer; }
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
      <div class="class-form">
        <textarea id="classInput" spellcheck="false" placeholder="person, phone, bottle"></textarea>
        <button id="applyClasses" type="button">Apply Classes</button>
        <div id="classStatus" class="muted"></div>
      </div>
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
      <h2>Speech</h2>
      <div id="speechMeta" class="muted">loading</div>
      <table>
        <thead><tr><th>time</th><th>text</th></tr></thead>
        <tbody id="transcript"></tbody>
      </table>
    </aside>
  </main>
  <script>
    async function refresh() {
      const now = Date.now();
      document.getElementById("frame").src = "/frame.jpg?t=" + now;
      const r = await fetch("/detections.json?t=" + now);
      const data = await r.json();
      if (!window.classInputDirty) {
        document.getElementById("classInput").value = (data.target_classes || []).join(", ");
      }
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
      await refreshTranscript();
    }
    async function refreshTranscript() {
      const r = await fetch("/transcript.json?t=" + Date.now());
      const data = await r.json();
      document.getElementById("speechMeta").textContent =
        data.enabled
          ? `${data.source} · model ${data.model} · ${data.language} · audio ${data.audio_age_seconds.toFixed(1)}s · transcript ${data.transcript_age_seconds.toFixed(1)}s${data.error ? " · " + data.error : ""}`
          : "disabled";
      const transcript = document.getElementById("transcript");
      transcript.innerHTML = "";
      if ((data.segments || []).length === 0) {
        transcript.insertAdjacentHTML("beforeend", `<tr><td colspan="2" class="muted">No speech yet</td></tr>`);
      }
      for (const row of data.segments || []) {
        transcript.insertAdjacentHTML("beforeend",
          `<tr><td class="muted">${new Date(row.time * 1000).toLocaleTimeString()}</td><td>${escapeHtml(row.text)}</td></tr>`);
      }
    }
    function escapeHtml(value) {
      return String(value).replace(/[&<>"']/g, ch => ({
        "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
      }[ch]));
    }
    async function applyClasses() {
      const raw = document.getElementById("classInput").value;
      const status = document.getElementById("classStatus");
      status.textContent = "applying";
      const r = await fetch("/classes", {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({classes: raw})
      });
      const data = await r.json();
      status.textContent = data.ok ? `applied ${data.target_classes.length} classes` : `error: ${data.error}`;
      window.classInputDirty = false;
      await refresh();
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
    document.getElementById("classInput").addEventListener("input", () => { window.classInputDirty = true; });
    document.getElementById("applyClasses").addEventListener("click", applyClasses);
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
    classes_version: int = 0
    latest_frame: Any | None = None
    capture_time: float = 0.0
    latest_jpeg: bytes | None = None
    detections: list[dict[str, Any]] = field(default_factory=list)
    objects: list[dict[str, Any]] = field(default_factory=list)
    seen_objects: dict[str, dict[str, Any]] = field(default_factory=dict)
    detection_time: float = 0.0
    detection_capture_time: float = 0.0
    error: str | None = None
    stt_enabled: bool = False
    audio_source: str = ""
    stt_model: str = ""
    stt_language: str = "auto"
    stt_min_rms: float = 0.05
    stt_segments: list[dict[str, Any]] = field(default_factory=list)
    stt_audio_time: float = 0.0
    stt_time: float = 0.0
    stt_error: str | None = None


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


def parse_classes(value: str | list[Any]) -> list[str]:
    if isinstance(value, list):
        raw = ",".join(str(v) for v in value)
    else:
        raw = value
    seen = set()
    classes = []
    for item in raw.replace("\n", ",").split(","):
        name = " ".join(item.strip().split())
        if not name or name in seen:
            continue
        seen.add(name)
        classes.append(name)
    return classes


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
    applied_classes_version = -1
    period = 1.0 / max(0.1, state.fps_target)

    while True:
        start = time.monotonic()
        with state.lock:
            frame = None if state.latest_frame is None else state.latest_frame.copy()
            capture_time = state.capture_time

        if frame is None:
            time.sleep(0.05)
            continue

        if world:
            with state.lock:
                classes = list(state.target_classes)
                classes_version = state.classes_version
            if classes and classes_version != applied_classes_version:
                model.set_classes(classes)
                applied_classes_version = classes_version

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


def stt_worker(state: DetectionState, chunk_seconds: float) -> None:
    from faster_whisper import WhisperModel

    model = WhisperModel(state.stt_model, device="auto", compute_type="int8")
    bytes_per_second = 16_000 * 2
    target_bytes = int(bytes_per_second * chunk_seconds)
    min_bytes = int(bytes_per_second * 1.0)

    while True:
        try:
            request = Request(state.audio_source, headers={"User-Agent": "GreedSenseSTT/1.0"})
            with urlopen(request, timeout=10) as response:
                wav_header = response.read(44)
                if len(wav_header) < 44 or wav_header[:4] != b"RIFF":
                    raise RuntimeError("audio source did not return WAV")
                pending = bytearray()
                with state.lock:
                    state.stt_error = None
                while True:
                    chunk = response.read(4096)
                    if not chunk:
                        raise RuntimeError("audio stream ended")
                    pending.extend(chunk)
                    with state.lock:
                        state.stt_audio_time = time.time()
                        state.stt_error = None
                    if len(pending) >= target_bytes:
                        pcm = bytes(pending[:target_bytes])
                        del pending[:target_bytes]
                        transcribe_pcm16_chunk(state, model, pcm, min_bytes)
        except Exception as e:
            with state.lock:
                state.stt_error = str(e)
            time.sleep(1.5)


def transcribe_pcm16_chunk(state: DetectionState, model: Any, pcm: bytes, min_bytes: int) -> None:
    if len(pcm) < min_bytes:
        return
    import numpy as np

    audio = np.frombuffer(pcm, dtype=np.int16).astype(np.float32) / 32768.0
    rms = float(np.sqrt(np.mean(np.square(audio)))) if audio.size else 0.0
    if rms < state.stt_min_rms:
        return

    language = None if state.stt_language == "auto" else state.stt_language
    segments, _info = model.transcribe(
        audio,
        language=language,
        beam_size=1,
        vad_filter=True,
        temperature=0.0,
        no_speech_threshold=0.55,
        log_prob_threshold=-1.0,
        condition_on_previous_text=False,
    )
    accepted = [
        segment.text.strip()
        for segment in segments
        if segment.text.strip()
        and getattr(segment, "no_speech_prob", 0.0) < 0.75
        and getattr(segment, "avg_logprob", 0.0) > -1.2
    ]
    text = " ".join(accepted).strip()
    if not text:
        return
    now = time.time()
    with state.lock:
        state.stt_segments.append({"time": now, "text": text, "rms": rms})
        state.stt_segments = state.stt_segments[-100:]
        state.stt_time = now
        state.stt_error = None


class Handler(BaseHTTPRequestHandler):
    state: DetectionState

    def do_POST(self) -> None:
        if self.path.startswith("/classes"):
            length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(length).decode("utf-8")
            try:
                payload = json.loads(body) if body else {}
                classes = parse_classes(payload.get("classes", ""))
                if not classes:
                    self.write_json({"ok": False, "error": "class list is empty"})
                    return
                with self.state.lock:
                    self.state.target_classes = classes
                    self.state.classes_version += 1
                    self.state.detections = []
                    self.state.objects = []
                    self.state.seen_objects = {}
                    self.state.detection_time = 0.0
                    self.state.detection_capture_time = 0.0
                self.write_json({"ok": True, "target_classes": classes})
            except Exception as e:
                self.write_json({"ok": False, "error": str(e)})
            return
        self.send_response(404)
        self.end_headers()

    def do_GET(self) -> None:
        if self.path.startswith("/transcript.json"):
            self.write_json(self.transcript_snapshot())
            return
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

    def transcript_snapshot(self) -> dict[str, Any]:
        with self.state.lock:
            now = time.time()
            return {
                "enabled": self.state.stt_enabled,
                "source": self.state.audio_source,
                "model": self.state.stt_model,
                "language": self.state.stt_language,
                "min_rms": self.state.stt_min_rms,
                "segments": list(self.state.stt_segments[-20:]),
                "audio_age_seconds": max(0.0, now - self.state.stt_audio_time) if self.state.stt_audio_time else 9999.0,
                "transcript_age_seconds": max(0.0, now - self.state.stt_time) if self.state.stt_time else 9999.0,
                "error": self.state.stt_error,
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
    parser.add_argument("--stt", action="store_true", help="Transcribe the Podroid microphone WAV stream locally.")
    parser.add_argument("--audio-source", default="http://192.168.1.33:18082/stream.wav")
    parser.add_argument("--stt-model", default="base", help="faster-whisper model name, for example tiny, base, small.")
    parser.add_argument("--stt-language", default="auto", help="Language code such as en/zh, or auto.")
    parser.add_argument("--stt-chunk-seconds", type=float, default=4.0)
    parser.add_argument("--stt-min-rms", type=float, default=0.05, help="Skip low-energy audio chunks below this RMS.")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    target_classes = parse_classes(args.classes)
    state = DetectionState(
        source=args.source,
        fps_target=args.fps,
        mode="yolo-world" if args.world else "yolo",
        target_classes=target_classes if args.world else [],
        stt_enabled=args.stt,
        audio_source=args.audio_source,
        stt_model=args.stt_model,
        stt_language=args.stt_language,
        stt_min_rms=args.stt_min_rms,
    )
    Handler.state = state
    threading.Thread(target=capture_worker, args=(state,), daemon=True).start()
    threading.Thread(target=worker, args=(state, args.model, args.imgsz, args.conf, args.world), daemon=True).start()
    if args.stt:
        threading.Thread(target=stt_worker, args=(state, args.stt_chunk_seconds), daemon=True).start()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"YOLO viewer: http://{args.host}:{args.port}")
    print(f"Source: {args.source}")
    if args.world:
        print(f"YOLO-World classes: {', '.join(target_classes)}")
    if args.stt:
        print(f"STT source: {args.audio_source}")
        print(f"STT model: {args.stt_model} language={args.stt_language} min_rms={args.stt_min_rms}")
    server.serve_forever()


if __name__ == "__main__":
    main()
