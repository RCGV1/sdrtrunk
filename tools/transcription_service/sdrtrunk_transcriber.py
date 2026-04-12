#!/usr/bin/env python3
"""
Local sdrtrunk recording transcription service.

Watches the SDRTrunk recordings directory, transcribes completed audio files with a local Whisper-compatible
engine, and writes searchable indexes grouped by talkgroup and radio/source ID.
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import hashlib
import json
import os
import re
import shutil
import signal
import sqlite3
import subprocess
import sys
import time
import traceback
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


AUDIO_EXTENSIONS = {".mp3", ".wav", ".m4a", ".flac", ".ogg", ".aac"}
DEFAULT_RECORDINGS_DIR = Path.home() / "SDRTrunk" / "recordings"
DEFAULT_OUTPUT_DIR = Path.home() / "SDRTrunk" / "transcripts"
DEFAULT_MODEL = "mlx-community/whisper-large-v3-turbo"
FILENAME_RE = re.compile(
    r"^(?P<timestamp>\d{8}_\d{6})_(?P<context>.+?)__TO_(?P<talkgroup>[^_]+)"
    r"(?:_FROM_(?P<radio>[^_]+))?(?:_V(?P<version>\d+))?$"
)
STOP = False


@dataclass
class RecordingMetadata:
    path: Path
    timestamp: str
    iso_time: str
    context: str
    system: str | None
    site: str | None
    channel: str | None
    talkgroup: str
    radio: str
    version: str | None
    extension: str
    sha256: str
    duration_seconds: float | None
    size_bytes: int


@dataclass
class TranscriptResult:
    metadata: RecordingMetadata
    text: str
    segments: list[dict[str, Any]] = field(default_factory=list)
    language: str | None = None
    engine: str = ""
    model: str = ""
    transcribed_at: str = ""


def now_iso() -> str:
    return dt.datetime.now(dt.UTC).isoformat()


def handle_signal(_signum: int, _frame: Any) -> None:
    global STOP
    STOP = True


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def is_file_stable(path: Path, stable_seconds: float) -> bool:
    try:
        first = path.stat()
        time.sleep(stable_seconds)
        second = path.stat()
    except FileNotFoundError:
        return False

    return first.st_size == second.st_size and first.st_mtime_ns == second.st_mtime_ns


def probe_duration(path: Path) -> float | None:
    if not shutil.which("ffprobe"):
        return None

    try:
        completed = subprocess.run(
            [
                "ffprobe",
                "-v",
                "error",
                "-show_entries",
                "format=duration",
                "-of",
                "default=noprint_wrappers=1:nokey=1",
                str(path),
            ],
            capture_output=True,
            text=True,
            check=True,
            timeout=20,
        )
        output = completed.stdout.strip()
        return float(output) if output else None
    except Exception:
        return None


def parse_timestamp(value: str) -> str:
    parsed = dt.datetime.strptime(value, "%Y%m%d_%H%M%S")
    return parsed.replace(tzinfo=dt.datetime.now().astimezone().tzinfo).isoformat()


def parse_context(context: str) -> tuple[str | None, str | None, str | None]:
    pieces = [piece for piece in context.split("_") if piece]

    if len(pieces) >= 3:
        return pieces[0], pieces[1], "_".join(pieces[2:])
    if len(pieces) == 2:
        return pieces[0], pieces[1], None
    if len(pieces) == 1:
        return pieces[0], None, None

    return None, None, None


def parse_recording_path(path: Path) -> RecordingMetadata | None:
    if path.suffix.lower() not in AUDIO_EXTENSIONS:
        return None

    match = FILENAME_RE.match(path.stem)
    if not match:
        return None

    context = match.group("context")
    system, site, channel = parse_context(context)
    stat = path.stat()

    return RecordingMetadata(
        path=path,
        timestamp=match.group("timestamp"),
        iso_time=parse_timestamp(match.group("timestamp")),
        context=context,
        system=system,
        site=site,
        channel=channel,
        talkgroup=match.group("talkgroup"),
        radio=match.group("radio") or "unknown",
        version=match.group("version"),
        extension=path.suffix.lower(),
        sha256=file_sha256(path),
        duration_seconds=probe_duration(path),
        size_bytes=stat.st_size,
    )


class TranscriptStore:
    def __init__(self, output_dir: Path) -> None:
        self.output_dir = output_dir
        self.output_dir.mkdir(parents=True, exist_ok=True)
        (self.output_dir / "by_talkgroup").mkdir(exist_ok=True)
        (self.output_dir / "by_radio").mkdir(exist_ok=True)
        self.database_path = self.output_dir / "transcripts.sqlite3"
        self.jsonl_path = self.output_dir / "transcripts.jsonl"
        self.csv_path = self.output_dir / "transcripts.csv"
        self.connection = sqlite3.connect(self.database_path)
        self.connection.execute(
            """
            CREATE TABLE IF NOT EXISTS transcripts (
                sha256 TEXT PRIMARY KEY,
                path TEXT NOT NULL,
                timestamp TEXT NOT NULL,
                iso_time TEXT NOT NULL,
                system TEXT,
                site TEXT,
                channel TEXT,
                talkgroup TEXT NOT NULL,
                radio TEXT NOT NULL,
                duration_seconds REAL,
                size_bytes INTEGER NOT NULL,
                engine TEXT NOT NULL,
                model TEXT NOT NULL,
                language TEXT,
                text TEXT NOT NULL,
                segments_json TEXT NOT NULL,
                transcribed_at TEXT NOT NULL
            )
            """
        )
        self.connection.execute(
            """
            CREATE TABLE IF NOT EXISTS failures (
                path TEXT PRIMARY KEY,
                error TEXT NOT NULL,
                failed_at TEXT NOT NULL
            )
            """
        )
        self.connection.commit()

    def has_transcript(self, sha256: str) -> bool:
        row = self.connection.execute("SELECT 1 FROM transcripts WHERE sha256 = ?", (sha256,)).fetchone()
        return row is not None

    def remember_failure(self, path: Path, error: str) -> None:
        self.connection.execute(
            "INSERT OR REPLACE INTO failures(path, error, failed_at) VALUES (?, ?, ?)",
            (str(path), error, now_iso()),
        )
        self.connection.commit()

    def save(self, result: TranscriptResult) -> None:
        metadata = result.metadata
        self.connection.execute(
            """
            INSERT OR REPLACE INTO transcripts (
                sha256, path, timestamp, iso_time, system, site, channel, talkgroup, radio, duration_seconds,
                size_bytes, engine, model, language, text, segments_json, transcribed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                metadata.sha256,
                str(metadata.path),
                metadata.timestamp,
                metadata.iso_time,
                metadata.system,
                metadata.site,
                metadata.channel,
                metadata.talkgroup,
                metadata.radio,
                metadata.duration_seconds,
                metadata.size_bytes,
                result.engine,
                result.model,
                result.language,
                result.text,
                json.dumps(result.segments, ensure_ascii=False),
                result.transcribed_at,
            ),
        )
        self.connection.execute("DELETE FROM failures WHERE path = ?", (str(metadata.path),))
        self.connection.commit()

        self._append_jsonl(result)
        self.rebuild_indexes()

    def _append_jsonl(self, result: TranscriptResult) -> None:
        record = result_to_dict(result)
        with self.jsonl_path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")

    def rows(self) -> list[sqlite3.Row]:
        self.connection.row_factory = sqlite3.Row
        try:
            return list(
                self.connection.execute(
                    """
                    SELECT * FROM transcripts
                    ORDER BY iso_time ASC, path ASC
                    """
                )
            )
        finally:
            self.connection.row_factory = None

    def rebuild_indexes(self) -> None:
        rows = self.rows()
        write_csv(self.csv_path, rows)
        write_grouped_markdown(self.output_dir / "by_talkgroup", "talkgroup", "TG", rows)
        write_grouped_markdown(self.output_dir / "by_radio", "radio", "RADIO", rows)
        write_summary(self.output_dir / "README.md", rows)


def safe_name(value: str) -> str:
    value = value.strip() or "unknown"
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", value)


def write_csv(path: Path, rows: list[sqlite3.Row]) -> None:
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=[
                "iso_time",
                "talkgroup",
                "radio",
                "system",
                "site",
                "channel",
                "duration_seconds",
                "text",
                "path",
                "engine",
                "model",
            ],
        )
        writer.writeheader()
        for row in rows:
            writer.writerow({field: row[field] for field in writer.fieldnames})


def write_grouped_markdown(directory: Path, key: str, prefix: str, rows: list[sqlite3.Row]) -> None:
    for old_file in directory.glob("*.md"):
        old_file.unlink()

    groups: dict[str, list[sqlite3.Row]] = {}
    for row in rows:
        groups.setdefault(row[key] or "unknown", []).append(row)

    for value, group_rows in sorted(groups.items()):
        output = directory / f"{prefix}_{safe_name(value)}.md"
        with output.open("w", encoding="utf-8") as handle:
            handle.write(f"# {prefix} {value}\n\n")
            for row in group_rows:
                duration = f"{row['duration_seconds']:.1f}s" if row["duration_seconds"] is not None else "unknown duration"
                handle.write(
                    f"## {row['iso_time']} | TG {row['talkgroup']} | Radio {row['radio']} | {duration}\n\n"
                )
                handle.write(f"- File: `{row['path']}`\n")
                handle.write(f"- Engine: `{row['engine']}` model `{row['model']}`\n\n")
                handle.write((row["text"] or "").strip() + "\n\n")


def write_summary(path: Path, rows: list[sqlite3.Row]) -> None:
    talkgroups = sorted({row["talkgroup"] for row in rows})
    radios = sorted({row["radio"] for row in rows})
    with path.open("w", encoding="utf-8") as handle:
        handle.write("# SDRTrunk Transcripts\n\n")
        handle.write(f"Generated: {now_iso()}\n\n")
        handle.write(f"- Total transcripts: {len(rows)}\n")
        handle.write(f"- Talkgroups: {', '.join(talkgroups) if talkgroups else 'none'}\n")
        handle.write(f"- Radios: {', '.join(radios) if radios else 'none'}\n\n")
        handle.write("Indexes:\n\n")
        handle.write("- `transcripts.jsonl`: append-only JSONL transcript stream\n")
        handle.write("- `transcripts.csv`: spreadsheet-friendly flat index\n")
        handle.write("- `by_talkgroup/`: Markdown files sorted by talkgroup\n")
        handle.write("- `by_radio/`: Markdown files sorted by radio/source ID\n")


def result_to_dict(result: TranscriptResult) -> dict[str, Any]:
    metadata = result.metadata
    return {
        "sha256": metadata.sha256,
        "path": str(metadata.path),
        "timestamp": metadata.timestamp,
        "iso_time": metadata.iso_time,
        "system": metadata.system,
        "site": metadata.site,
        "channel": metadata.channel,
        "talkgroup": metadata.talkgroup,
        "radio": metadata.radio,
        "duration_seconds": metadata.duration_seconds,
        "size_bytes": metadata.size_bytes,
        "engine": result.engine,
        "model": result.model,
        "language": result.language,
        "text": result.text,
        "segments": result.segments,
        "transcribed_at": result.transcribed_at,
    }


class TranscriptionEngine:
    def __init__(self, engine_name: str, model: str, language: str | None) -> None:
        self.engine_name = engine_name
        self.model = model
        self.language = language
        self._engine: Any | None = None

    def transcribe(self, path: Path) -> tuple[str, list[dict[str, Any]], str | None, str]:
        if self.engine_name == "auto":
            for candidate in ("lightning-whisper-mlx", "mlx-whisper", "faster-whisper", "openai-whisper"):
                try:
                    return self._transcribe_with(candidate, path)
                except ModuleNotFoundError:
                    continue
            raise RuntimeError(
                "No local transcription engine is installed. Run "
                "`python3.12 -m pip install -r tools/transcription_service/requirements.txt`."
            )

        return self._transcribe_with(self.engine_name, path)

    def _transcribe_with(self, engine_name: str, path: Path) -> tuple[str, list[dict[str, Any]], str | None, str]:
        if engine_name == "mlx-whisper":
            return self._transcribe_mlx_whisper(path)
        if engine_name == "lightning-whisper-mlx":
            return self._transcribe_lightning_whisper_mlx(path)
        if engine_name == "faster-whisper":
            return self._transcribe_faster_whisper(path)
        if engine_name == "openai-whisper":
            return self._transcribe_openai_whisper(path)

        raise ValueError(f"Unsupported engine: {engine_name}")

    def _transcribe_mlx_whisper(self, path: Path) -> tuple[str, list[dict[str, Any]], str | None, str]:
        import mlx_whisper

        kwargs: dict[str, Any] = {"path_or_hf_repo": self.model}
        if self.language:
            kwargs["language"] = self.language
        result = mlx_whisper.transcribe(str(path), **kwargs)
        return result.get("text", "").strip(), normalize_segments(result.get("segments", [])), result.get("language"), "mlx-whisper"

    def _transcribe_lightning_whisper_mlx(self, path: Path) -> tuple[str, list[dict[str, Any]], str | None, str]:
        from lightning_whisper_mlx import LightningWhisperMLX

        model, quant = split_lightning_model(self.model)
        if self._engine is None:
            self._engine = LightningWhisperMLX(model=model, batch_size=12, quant=quant)

        result = self._engine.transcribe(audio_path=str(path))
        return result.get("text", "").strip(), normalize_segments(result.get("segments", [])), result.get("language"), "lightning-whisper-mlx"

    def _transcribe_faster_whisper(self, path: Path) -> tuple[str, list[dict[str, Any]], str | None, str]:
        from faster_whisper import WhisperModel

        if self._engine is None:
            self._engine = WhisperModel(self.model, device="auto", compute_type="auto")

        segments, info = self._engine.transcribe(str(path), language=self.language, vad_filter=True)
        normalized = [{"start": segment.start, "end": segment.end, "text": segment.text.strip()} for segment in segments]
        text = " ".join(segment["text"] for segment in normalized).strip()
        return text, normalized, getattr(info, "language", None), "faster-whisper"

    def _transcribe_openai_whisper(self, path: Path) -> tuple[str, list[dict[str, Any]], str | None, str]:
        import whisper

        if self._engine is None:
            self._engine = whisper.load_model(self.model)

        kwargs: dict[str, Any] = {}
        if self.language:
            kwargs["language"] = self.language
        result = self._engine.transcribe(str(path), **kwargs)
        return result.get("text", "").strip(), normalize_segments(result.get("segments", [])), result.get("language"), "openai-whisper"


def split_lightning_model(model: str) -> tuple[str, str | None]:
    if ":" in model:
        name, quant = model.split(":", 1)
        return name, quant or None
    return model, None


def normalize_segments(segments: Any) -> list[dict[str, Any]]:
    normalized: list[dict[str, Any]] = []
    for segment in segments or []:
        if isinstance(segment, dict):
            normalized.append(
                {
                    "start": segment.get("start"),
                    "end": segment.get("end"),
                    "text": str(segment.get("text", "")).strip(),
                }
            )
    return normalized


def iter_recordings(recordings_dir: Path, newest_first: bool) -> list[Path]:
    return sorted(
        [
            path
            for path in recordings_dir.iterdir()
            if path.is_file() and path.suffix.lower() in AUDIO_EXTENSIONS and not path.name.startswith(".")
        ],
        key=lambda path: (path.stat().st_mtime, path.name),
        reverse=newest_first,
    )


def process_once(
    recordings_dir: Path,
    store: TranscriptStore,
    engine: TranscriptionEngine,
    stable_seconds: float,
    limit: int | None,
    newest_first: bool,
    min_duration_seconds: float,
) -> int:
    processed = 0

    for path in iter_recordings(recordings_dir, newest_first):
        if limit is not None and processed >= limit:
            break

        if not is_file_stable(path, stable_seconds):
            continue

        metadata = parse_recording_path(path)
        if metadata is None:
            continue

        if metadata.duration_seconds is not None and metadata.duration_seconds < min_duration_seconds:
            continue

        if store.has_transcript(metadata.sha256):
            continue

        try:
            print(f"Transcribing TG {metadata.talkgroup} radio {metadata.radio}: {path.name}", flush=True)
            text, segments, language, actual_engine = engine.transcribe(path)
            result = TranscriptResult(
                metadata=metadata,
                text=text,
                segments=segments,
                language=language,
                engine=actual_engine,
                model=engine.model,
                transcribed_at=now_iso(),
            )
            store.save(result)
            processed += 1
        except Exception as exc:
            error = "".join(traceback.format_exception_only(type(exc), exc)).strip()
            print(f"Failed to transcribe {path}: {error}", file=sys.stderr, flush=True)
            store.remember_failure(path, error)

    return processed


def main() -> int:
    parser = argparse.ArgumentParser(description="Monitor SDRTrunk recordings and transcribe them locally.")
    parser.add_argument("--recordings-dir", type=Path, default=DEFAULT_RECORDINGS_DIR)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--engine", default="auto", choices=["auto", "mlx-whisper", "lightning-whisper-mlx", "faster-whisper", "openai-whisper"])
    parser.add_argument("--model", default=os.environ.get("SDRTRUNK_TRANSCRIBER_MODEL", DEFAULT_MODEL))
    parser.add_argument("--language", default=os.environ.get("SDRTRUNK_TRANSCRIBER_LANGUAGE", "en"))
    parser.add_argument("--poll-seconds", type=float, default=10.0)
    parser.add_argument("--stable-seconds", type=float, default=1.0)
    parser.add_argument("--min-duration-seconds", type=float, default=0.75)
    parser.add_argument("--oldest-first", action="store_true")
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--limit", type=int, default=None)
    args = parser.parse_args()

    if not args.recordings_dir.exists():
        print(f"Recordings directory does not exist: {args.recordings_dir}", file=sys.stderr)
        return 2

    signal.signal(signal.SIGINT, handle_signal)
    signal.signal(signal.SIGTERM, handle_signal)

    store = TranscriptStore(args.output_dir)
    engine = TranscriptionEngine(args.engine, args.model, args.language or None)

    print(f"Watching recordings: {args.recordings_dir}", flush=True)
    print(f"Writing transcripts: {args.output_dir}", flush=True)
    print(f"Engine: {args.engine}; model: {args.model}", flush=True)
    print(f"Minimum duration: {args.min_duration_seconds}s; newest first: {not args.oldest_first}", flush=True)

    while not STOP:
        count = process_once(
            args.recordings_dir,
            store,
            engine,
            args.stable_seconds,
            args.limit,
            not args.oldest_first,
            args.min_duration_seconds,
        )
        if args.once:
            print(f"Processed {count} recording(s).", flush=True)
            return 0
        time.sleep(args.poll_seconds)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
