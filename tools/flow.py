#!/usr/bin/env python3
from __future__ import annotations

import argparse
import io
import json
import os
import ssl
import threading
import time
import wave
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.error import URLError
from urllib.request import Request, urlopen


HTML = """<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Sense Flow</title>
  <script defer src="https://cdn.jsdelivr.net/npm/alpinejs@3.x.x/dist/cdn.min.js"></script>
  <style>
    :root { color-scheme: dark; }
    body { margin: 0; background: #101114; color: #f4f4f5; font: 16px system-ui, sans-serif; }
    header { height: 56px; padding: 0 18px; border-bottom: 1px solid #2a2d34; display: flex; align-items: center; justify-content: space-between; gap: 16px; }
    main { max-width: 1040px; margin: 0 auto; padding: 18px; display: grid; gap: 14px; }
    button { border: 0; background: #f4f4f5; color: #101114; padding: 12px 18px; font-weight: 800; cursor: pointer; min-width: 112px; }
    button.recording { background: #fb7185; color: #21060b; }
    .bar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
    .muted { color: #a1a1aa; }
    .pill { border: 1px solid #3f3f46; color: #d4d4d8; padding: 4px 9px; border-radius: 999px; font-size: 13px; }
    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
    .panel { border: 1px solid #2a2d34; background: #16181d; min-height: 300px; padding: 14px; display: grid; align-content: start; gap: 10px; }
    h2 { font-size: 13px; text-transform: uppercase; letter-spacing: .08em; color: #a1a1aa; margin: 0; }
    .text { white-space: pre-wrap; font-size: 24px; line-height: 1.35; }
    .clean { color: #86efac; }
    .segments { display: grid; gap: 8px; }
    .segment { border-top: 1px solid #2a2d34; padding-top: 8px; }
    .small { font-size: 13px; line-height: 1.45; word-break: break-word; }
    .error { color: #fca5a5; }
    @media (max-width: 760px) { .grid { grid-template-columns: 1fr; } .text { font-size: 20px; } }
  </style>
</head>
<body x-data="flowApp()" x-init="start()">
  <header>
    <strong>Sense Flow</strong>
    <span class="muted" x-text="meta"></span>
  </header>
  <main>
    <section class="bar">
      <button :class="{recording: state.recording}" @click="toggle()" x-text="state.recording ? 'Stop' : 'Record'"></button>
      <span class="pill" x-text="state.recording ? 'recording' : 'idle'"></span>
      <span class="pill" x-text="'rms ' + Number(state.latest_rms || 0).toFixed(3)"></span>
      <span class="pill" x-text="'whisper ' + (state.whisper_runs || 0)"></span>
      <span class="pill" x-text="'llm ' + (state.llm_calls || 0)"></span>
      <span class="pill" x-text="'skipped ' + (state.skipped_low_rms || 0)"></span>
      <span class="muted error" x-show="state.error" x-text="state.error"></span>
    </section>
    <section class="grid">
      <article class="panel">
        <h2>Flow Text</h2>
        <div class="text clean" x-text="state.cleaned_text || 'Waiting for speech...'"></div>
      </article>
      <article class="panel">
        <h2>Raw Whisper</h2>
        <div class="text" x-text="state.raw_text || 'No transcript yet'"></div>
      </article>
    </section>
    <section class="panel">
      <h2>LLM Flow</h2>
      <div class="small muted" x-text="'status: ' + (state.llm_status || 'idle')"></div>
      <div class="small muted" x-text="'last call: ' + (state.llm_last_call_at ? new Date(state.llm_last_call_at * 1000).toLocaleTimeString() : 'never')"></div>
      <div class="small" x-text="state.llm_last_response || 'No LLM response yet'"></div>
    </section>
    <section class="panel">
      <h2>Whisper Runs</h2>
      <div class="segments">
        <template x-for="segment in [...(state.segments || [])].reverse()" :key="segment.id">
          <div class="segment">
            <div class="muted" x-text="new Date(segment.time * 1000).toLocaleTimeString() + ' · rms ' + Number(segment.rms || 0).toFixed(3)"></div>
            <div x-text="segment.text"></div>
          </div>
        </template>
      </div>
    </section>
  </main>
  <script>
    function flowApp() {
      return {
        state: {},
        meta: 'loading',
        async start() {
          await this.refresh();
          setInterval(() => this.refresh(), 500);
        },
        async toggle() {
          await fetch('/record', {method: 'POST'});
          await this.refresh();
        },
        async refresh() {
          const r = await fetch('/state.json?t=' + Date.now());
          this.state = await r.json();
          this.meta = `${this.state.audio_source} · ${this.state.stt_model} · ${this.state.language} · audio ${Number(this.state.audio_age_seconds || 9999).toFixed(1)}s`;
        }
      }
    }
  </script>
</body>
</html>
"""


@dataclass
class FlowState:
    lock: threading.Lock = field(default_factory=threading.Lock)
    audio_source: str = ""
    stt_backend: str = "faster-whisper"
    stt_model: str = "tiny"
    language: str = "auto"
    chunk_seconds: float = 1.0
    min_rms: float = 0.02
    llm_url: str = ""
    llm_backend: str = "openai"
    llm_model: str = "data"
    llm_key: str = "fake-key"
    llm_insecure: bool = False
    cf_account_id: str = ""
    cf_auth_email: str = ""
    cf_auth_key: str = ""
    cf_whisper_url: str = ""
    cf_glm_url: str = ""
    audio_buffer: bytearray = field(default_factory=bytearray)
    audio_seq: int = 0
    audio_time: float = 0.0
    latest_rms: float = 0.0
    recording: bool = False
    record_started: float = 0.0
    record_offset: int = 0
    processed_offset: int = 0
    recording_pcm: bytearray = field(default_factory=bytearray)
    recording_seq: int = 0
    processed_recording_seq: int = 0
    raw_text: str = ""
    cleaned_text: str = ""
    chunks: int = 0
    whisper_runs: int = 0
    skipped_low_rms: int = 0
    segments: list[dict[str, Any]] = field(default_factory=list)
    error: str | None = None
    llm_status: str = "idle"
    llm_calls: int = 0
    llm_last_call_at: float = 0.0
    llm_last_prompt: str = ""
    llm_last_response: str = ""
    llm_error: str | None = None


def audio_capture_worker(state: FlowState, buffer_seconds: float) -> None:
    bytes_per_second = 16_000 * 2
    max_bytes = int(bytes_per_second * buffer_seconds)

    while True:
        try:
            request = Request(state.audio_source, headers={"User-Agent": "SenseFlow/1.0"})
            with urlopen(request, timeout=10) as response:
                header = response.read(44)
                if len(header) < 44 or header[:4] != b"RIFF":
                    raise RuntimeError("audio source did not return WAV")
                with state.lock:
                    state.error = None
                    state.audio_buffer.clear()
                    state.record_offset = 0
                    state.processed_offset = 0
                while True:
                    chunk = response.read(4096)
                    if not chunk:
                        raise RuntimeError("audio stream ended")
                    with state.lock:
                        state.audio_buffer.extend(chunk)
                        if state.recording:
                            state.recording_pcm.extend(chunk)
                            state.recording_seq += 1
                        if len(state.audio_buffer) > max_bytes:
                            drop = len(state.audio_buffer) - max_bytes
                            del state.audio_buffer[:drop]
                            state.record_offset = max(0, state.record_offset - drop)
                            state.processed_offset = max(0, state.processed_offset - drop)
                        state.audio_seq += 1
                        state.audio_time = time.time()
                        state.error = None
        except Exception as e:
            with state.lock:
                state.error = str(e)
            time.sleep(1.0)


def stt_worker(state: FlowState) -> None:
    model = load_stt_model(state)
    bytes_per_second = 16_000 * 2
    step_bytes = int(bytes_per_second * state.chunk_seconds)
    min_bytes = int(bytes_per_second * 0.5)

    while True:
        with state.lock:
            if not state.recording:
                pcm = None
                state.record_offset = len(state.audio_buffer)
                state.processed_offset = state.record_offset
                state.processed_recording_seq = state.recording_seq
            else:
                available = len(state.recording_pcm)
                new_bytes = available - state.processed_offset
                if available < min_bytes or new_bytes < max(min_bytes, step_bytes):
                    pcm = None
                else:
                    pcm = bytes(state.recording_pcm)
                    state.processed_offset = available
                    state.processed_recording_seq = state.recording_seq
        if pcm:
            try:
                transcribe_recording(state, model, pcm)
            except Exception as e:
                with state.lock:
                    state.error = str(e)
        time.sleep(0.15)


def load_stt_model(state: FlowState) -> Any:
    if state.stt_backend == "mlx":
        import mlx_whisper

        return mlx_whisper
    if state.stt_backend == "cloudflare-whisper":
        return None

    from faster_whisper import WhisperModel

    return WhisperModel(state.stt_model, device="auto", compute_type="int8")


def transcribe_recording(state: FlowState, model: Any, pcm: bytes) -> None:
    import numpy as np

    audio = np.frombuffer(pcm, dtype=np.int16).astype(np.float32) / 32768.0
    rms = float(np.sqrt(np.mean(np.square(audio)))) if audio.size else 0.0
    with state.lock:
        state.latest_rms = rms
    if rms < state.min_rms:
        with state.lock:
            state.skipped_low_rms += 1
        return

    text = transcribe_with_backend(state, model, audio)
    if not text:
        return

    now = time.time()
    with state.lock:
        state.whisper_runs += 1
        state.chunks = state.whisper_runs
        state.segments.append({"id": state.whisper_runs, "time": now, "text": text, "rms": rms})
        state.segments = state.segments[-80:]
        state.raw_text = text
        raw_text = state.raw_text

    cleaned = clean_with_llm(state, raw_text)
    if cleaned:
        with state.lock:
            state.cleaned_text = cleaned


def transcribe_with_backend(state: FlowState, model: Any, audio: Any) -> str:
    language = None if state.language == "auto" else state.language
    if state.stt_backend == "mlx":
        result = model.transcribe(
            audio,
            path_or_hf_repo=state.stt_model,
            language=language,
            verbose=False,
            temperature=0.0,
            no_speech_threshold=0.7,
            logprob_threshold=-1.0,
            condition_on_previous_text=False,
        )
        return str(result.get("text") or "").strip()
    if state.stt_backend == "cloudflare-whisper":
        return transcribe_with_cloudflare(state, audio)

    segments, _info = model.transcribe(
        audio,
        language=language,
        task="transcribe",
        beam_size=1,
        vad_filter=False,
        temperature=0.0,
        no_speech_threshold=0.7,
        condition_on_previous_text=False,
    )
    return " ".join(
        segment.text.strip()
        for segment in segments
        if segment.text.strip()
        and getattr(segment, "no_speech_prob", 0.0) < 0.95
        and getattr(segment, "avg_logprob", 0.0) > -2.0
    ).strip()


def transcribe_with_cloudflare(state: FlowState, audio: Any) -> str:
    wav_bytes = pcm_float_to_wav(audio)
    request = Request(
        state.cf_whisper_url,
        data=wav_bytes,
        headers=cloudflare_headers(state, content_type=""),
        method="POST",
    )
    with urlopen(request, timeout=60) as response:
        data = json.loads(response.read().decode("utf-8"))
    text = cloudflare_text(data)
    if not text and data.get("errors"):
        raise RuntimeError(data.get("errors"))
    return text


def pcm_float_to_wav(audio: Any) -> bytes:
    import numpy as np

    pcm = np.clip(audio, -1.0, 1.0)
    pcm16 = (pcm * 32767.0).astype(np.int16)
    out = io.BytesIO()
    with wave.open(out, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(16_000)
        wav.writeframes(pcm16.tobytes())
    return out.getvalue()


def cloudflare_headers(state: FlowState, content_type: str = "application/json") -> dict[str, str]:
    headers = {
        "X-Auth-Email": state.cf_auth_email,
        "X-Auth-Key": state.cf_auth_key,
    }
    if content_type:
        headers["Content-Type"] = content_type
    return headers


def cloudflare_text(data: dict[str, Any]) -> str:
    result = data.get("result")
    if isinstance(result, dict):
        for key in ("text", "response", "content"):
            value = result.get(key)
            if value:
                return str(value).strip()
        choices = result.get("choices")
        if isinstance(choices, list) and choices:
            content = ((choices[0] or {}).get("message") or {}).get("content")
            if content:
                return str(content).strip()
    if isinstance(result, str):
        return result.strip()
    choices = data.get("choices")
    if isinstance(choices, list) and choices:
        content = ((choices[0] or {}).get("message") or {}).get("content")
        if content:
            return str(content).strip()
    return ""


def clean_with_llm(state: FlowState, raw_text: str) -> str:
    prompt = (
        "You are a Wispr Flow style dictation editor. Clean the transcript below. "
        "Remove filler sounds, repeated starts, and obvious speech recognition noise. "
        "Preserve the speaker's meaning, language, names, and technical terms. "
        "Return only the cleaned dictated text, no explanation.\n\n"
        f"Transcript:\n{raw_text}"
    )
    payload = llm_payload(state, prompt)
    body = json.dumps(payload).encode("utf-8")
    request = Request(
        llm_url(state),
        data=body,
        headers=llm_headers(state),
        method="POST",
    )
    try:
        context = ssl._create_unverified_context() if state.llm_insecure else None
        with state.lock:
            state.llm_status = "calling"
            state.llm_calls += 1
            state.llm_last_call_at = time.time()
            state.llm_last_prompt = prompt[-2000:]
            state.llm_error = None
        with urlopen(request, timeout=20, context=context) as response:
            data = json.loads(response.read().decode("utf-8"))
        content = llm_content(state, data)
        if not content:
            raise RuntimeError(data.get("error") or "LLM returned no content")
        with state.lock:
            state.llm_status = "ok"
            state.llm_last_response = str(content).strip()
            state.llm_error = None
        return str(content).strip()
    except (URLError, TimeoutError, RuntimeError, json.JSONDecodeError) as e:
        with state.lock:
            state.llm_status = "error"
            state.llm_error = str(e)
        return raw_text


def llm_url(state: FlowState) -> str:
    return state.cf_glm_url if state.llm_backend == "cloudflare-glm" else state.llm_url


def llm_headers(state: FlowState) -> dict[str, str]:
    if state.llm_backend == "cloudflare-glm":
        return cloudflare_headers(state)
    return {
        "Authorization": f"Bearer {state.llm_key}",
        "Content-Type": "application/json",
    }


def llm_payload(state: FlowState, prompt: str) -> dict[str, Any]:
    if state.llm_backend == "cloudflare-glm":
        return {
            "messages": [{"role": "user", "content": prompt}],
            "max_tokens": 512,
        }
    return {
        "model": state.llm_model,
        "messages": [{"role": "user", "content": prompt}],
        "stream": False,
        "max_tokens": 512,
    }


def llm_content(state: FlowState, data: dict[str, Any]) -> str:
    if state.llm_backend == "cloudflare-glm":
        return cloudflare_text(data)
    return str(((data.get("choices") or [{}])[0].get("message") or {}).get("content") or "").strip()


class Handler(BaseHTTPRequestHandler):
    state: FlowState

    def do_POST(self) -> None:
        if self.path.startswith("/record"):
            self.write_json(self.toggle_record())
            return
        self.send_response(404)
        self.end_headers()

    def do_GET(self) -> None:
        if self.path.startswith("/state.json"):
            self.write_json(self.snapshot())
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(HTML.encode("utf-8"))

    def toggle_record(self) -> dict[str, Any]:
        with self.state.lock:
            if self.state.recording:
                self.state.recording = False
            else:
                now = time.time()
                offset = len(self.state.audio_buffer)
                self.state.recording = True
                self.state.record_started = now
                self.state.record_offset = offset
                self.state.processed_offset = 0
                self.state.raw_text = ""
                self.state.cleaned_text = ""
                self.state.chunks = 0
                self.state.whisper_runs = 0
                self.state.skipped_low_rms = 0
                self.state.segments = []
                self.state.recording_pcm.clear()
                self.state.recording_seq = 0
                self.state.processed_recording_seq = 0
                self.state.latest_rms = 0.0
                self.state.llm_status = "idle"
                self.state.llm_calls = 0
                self.state.llm_last_call_at = 0.0
                self.state.llm_last_prompt = ""
                self.state.llm_last_response = ""
                self.state.llm_error = None
            return {"ok": True, "recording": self.state.recording}

    def snapshot(self) -> dict[str, Any]:
        with self.state.lock:
            now = time.time()
            return {
                "audio_source": self.state.audio_source,
                "stt_backend": self.state.stt_backend,
                "stt_model": self.state.stt_model,
                "language": self.state.language,
                "chunk_seconds": self.state.chunk_seconds,
                "min_rms": self.state.min_rms,
                "llm_model": self.state.llm_model,
                "llm_backend": self.state.llm_backend,
                "llm_insecure": self.state.llm_insecure,
                "recording": self.state.recording,
                "record_seconds": max(0.0, now - self.state.record_started) if self.state.recording else 0.0,
                "latest_rms": self.state.latest_rms,
                "chunks": self.state.chunks,
                "whisper_runs": self.state.whisper_runs,
                "skipped_low_rms": self.state.skipped_low_rms,
                "raw_text": self.state.raw_text,
                "cleaned_text": self.state.cleaned_text,
                "segments": list(self.state.segments),
                "recording_bytes": len(self.state.recording_pcm),
                "llm_status": self.state.llm_status,
                "llm_calls": self.state.llm_calls,
                "llm_last_call_at": self.state.llm_last_call_at,
                "llm_last_prompt": self.state.llm_last_prompt,
                "llm_last_response": self.state.llm_last_response,
                "audio_age_seconds": max(0.0, now - self.state.audio_time) if self.state.audio_time else 9999.0,
                "error": self.state.llm_error or self.state.error,
            }

    def write_json(self, payload: dict[str, Any]) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt: str, *args: Any) -> None:
        return


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run a local Wispr Flow style speech cleanup UI.")
    parser.add_argument("--audio-source", default="http://192.168.1.33:18082/stream.wav")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18889)
    parser.add_argument("--stt-backend", choices=["faster-whisper", "mlx", "cloudflare-whisper"], default="faster-whisper")
    parser.add_argument("--stt-model", default="tiny")
    parser.add_argument("--language", default="auto")
    parser.add_argument("--chunk-seconds", type=float, default=1.0)
    parser.add_argument("--min-rms", type=float, default=0.005)
    parser.add_argument("--buffer-seconds", type=float, default=30.0)
    parser.add_argument("--llm-url", default="https://gjallarhorn.gjallarhorngit.orb.local/v1/chat/completions")
    parser.add_argument("--llm-backend", choices=["openai", "cloudflare-glm"], default="openai")
    parser.add_argument("--llm-model", default="data")
    parser.add_argument("--llm-key", default="fake-key")
    parser.add_argument(
        "--llm-insecure",
        action=argparse.BooleanOptionalAction,
        default=False,
        help="Disable TLS certificate verification for the LLM endpoint.",
    )
    parser.add_argument("--cf-account-id", default="493511f38053d97a5c71d021374bb67a")
    parser.add_argument("--cf-auth-email", default="Tiancailishen111@gmail.com")
    parser.add_argument("--cf-auth-key", default=os.environ.get("CF_GLOBAL_KEY", ""))
    parser.add_argument("--cf-whisper-model", default="@cf/openai/whisper")
    parser.add_argument("--cf-glm-model", default="@cf/zai-org/glm-4.7-flash")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    state = FlowState(
        audio_source=args.audio_source,
        stt_backend=args.stt_backend,
        stt_model=args.stt_model,
        language=args.language,
        chunk_seconds=args.chunk_seconds,
        min_rms=args.min_rms,
        llm_url=args.llm_url,
        llm_backend=args.llm_backend,
        llm_model=args.llm_model,
        llm_key=args.llm_key,
        llm_insecure=args.llm_insecure,
        cf_account_id=args.cf_account_id,
        cf_auth_email=args.cf_auth_email,
        cf_auth_key=args.cf_auth_key,
        cf_whisper_url=f"https://api.cloudflare.com/client/v4/accounts/{args.cf_account_id}/ai/run/{args.cf_whisper_model}",
        cf_glm_url=f"https://api.cloudflare.com/client/v4/accounts/{args.cf_account_id}/ai/run/{args.cf_glm_model}",
    )
    if (args.stt_backend == "cloudflare-whisper" or args.llm_backend == "cloudflare-glm") and not args.cf_auth_key:
        raise SystemExit("CF_GLOBAL_KEY is required for Cloudflare Workers AI backends.")
    Handler.state = state
    threading.Thread(target=audio_capture_worker, args=(state, args.buffer_seconds), daemon=True).start()
    threading.Thread(target=stt_worker, args=(state,), daemon=True).start()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"Sense Flow: http://{args.host}:{args.port}")
    print(f"Audio source: {args.audio_source}")
    print(f"STT backend: {args.stt_backend} model={args.stt_model} language={args.language} chunk={args.chunk_seconds}s min_rms={args.min_rms}")
    print(f"LLM backend: {args.llm_backend} model={args.llm_model} url={llm_url(state)} insecure={args.llm_insecure}")
    server.serve_forever()


if __name__ == "__main__":
    main()
