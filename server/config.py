"""Configuracao lida de variaveis de ambiente / ficheiro .env."""

from __future__ import annotations

import os
from pathlib import Path

from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent

load_dotenv(BASE_DIR / ".env")


def _str(name: str, default: str) -> str:
    value = os.getenv(name)
    return default if value is None or value.strip() == "" else value.strip()


def _int(name: str, default: int) -> int:
    try:
        return int(_str(name, str(default)))
    except ValueError:
        return default


def _float(name: str, default: float) -> float:
    try:
        return float(_str(name, str(default)))
    except ValueError:
        return default


def _bool(name: str, default: bool) -> bool:
    return _str(name, "1" if default else "0").lower() in ("1", "true", "yes", "sim", "on")


API_KEY: str = os.getenv("API_KEY", "").strip()

DATA_DIR: Path = Path(_str("DATA_DIR", str(BASE_DIR / "data")))
if not DATA_DIR.is_absolute():
    DATA_DIR = (BASE_DIR / DATA_DIR).resolve()

FILES_DIR: Path = DATA_DIR / "files"
THUMBS_DIR: Path = DATA_DIR / "thumbs"
FACES_DIR: Path = DATA_DIR / "faces"
DB_PATH: Path = DATA_DIR / "leiturabi.db"

HOST: str = _str("HOST", "0.0.0.0")
PORT: int = _int("PORT", 8000)

FACE_MODEL: str = _str("FACE_MODEL", "buffalo_l")
FACE_DET_SIZE: int = _int("FACE_DET_SIZE", 640)
FACE_DET_THRESHOLD: float = _float("FACE_DET_THRESHOLD", 0.5)
FACE_MATCH_THRESHOLD: float = _float("FACE_MATCH_THRESHOLD", 0.42)
FACE_AUTO_CLUSTER: bool = _bool("FACE_AUTO_CLUSTER", True)
FACE_DISABLED: bool = _bool("FACE_DISABLED", False)

MAX_UPLOAD_BYTES: int = _int("MAX_UPLOAD_MB", 40) * 1024 * 1024
THUMB_SIZE: int = _int("THUMB_SIZE", 512)

# PDF
PDF_EXTRACT_TEXT: bool = _bool("PDF_EXTRACT_TEXT", True)
PDF_TEXT_CHARS: int = _int("PDF_TEXT_CHARS", 20000)
PDF_DETECT_FACES: bool = _bool("PDF_DETECT_FACES", True)
PDF_FACE_PAGES: int = _int("PDF_FACE_PAGES", 3)

IMAGE_MIME = {
    "image/jpeg": ".jpg",
    "image/jpg": ".jpg",
    "image/png": ".png",
    "image/webp": ".webp",
    "image/heic": ".heic",
    "image/heif": ".heif",
}

PDF_MIME = {
    "application/pdf": ".pdf",
    "application/x-pdf": ".pdf",
}

ALLOWED_MIME = {**IMAGE_MIME, **PDF_MIME}


def kind_for_mime(mime: str) -> str | None:
    mime = (mime or "").lower().split(";")[0].strip()
    if mime in IMAGE_MIME:
        return "photo"
    if mime in PDF_MIME:
        return "pdf"
    return None


def ensure_dirs() -> None:
    for directory in (DATA_DIR, FILES_DIR, THUMBS_DIR, FACES_DIR):
        directory.mkdir(parents=True, exist_ok=True)
