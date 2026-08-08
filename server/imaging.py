"""Utilitarios de imagem: miniaturas, EXIF, recortes de rosto."""

from __future__ import annotations

import io
import logging
from datetime import datetime
from typing import Optional, Tuple

import numpy as np
from PIL import Image, ImageOps

import config

log = logging.getLogger("leiturabi.imaging")

Image.MAX_IMAGE_PIXELS = 200_000_000

_EXIF_DATETIME_TAGS = (36867, 36868, 306)  # DateTimeOriginal, DateTimeDigitized, DateTime
_EXIF_GPS_TAG = 34853


def open_image(data: bytes) -> Image.Image:
    """Abre a imagem, corrige a orientacao EXIF e converte para RGB."""
    image = Image.open(io.BytesIO(data))
    image = ImageOps.exif_transpose(image)
    if image.mode not in ("RGB", "L"):
        image = image.convert("RGB")
    elif image.mode == "L":
        image = image.convert("RGB")
    return image


def to_bgr(image: Image.Image) -> np.ndarray:
    """Converte PIL RGB para array BGR (esperado pelo OpenCV/InsightFace)."""
    return np.asarray(image, dtype=np.uint8)[:, :, ::-1].copy()


def make_thumbnail(image: Image.Image, destination) -> Tuple[int, int]:
    thumb = image.copy()
    thumb.thumbnail((config.THUMB_SIZE, config.THUMB_SIZE), Image.LANCZOS)
    thumb.save(destination, format="JPEG", quality=82, optimize=True)
    return thumb.size


def save_face_crop(image: Image.Image, box: Tuple[int, int, int, int], destination, margin: float = 0.35) -> None:
    """Guarda o recorte de um rosto com margem para enquadrar melhor."""
    x, y, w, h = box
    pad_x = int(w * margin)
    pad_y = int(h * margin)
    left = max(0, x - pad_x)
    top = max(0, y - pad_y)
    right = min(image.width, x + w + pad_x)
    bottom = min(image.height, y + h + pad_y)

    crop = image.crop((left, top, right, bottom))
    crop.thumbnail((256, 256), Image.LANCZOS)
    crop.save(destination, format="JPEG", quality=85, optimize=True)


def read_exif(image: Image.Image) -> dict:
    """Extrai data de captura e coordenadas GPS, quando existem."""
    info: dict = {}
    try:
        exif = image.getexif()
    except Exception:  # noqa: BLE001
        return info
    if not exif:
        return info

    for tag in _EXIF_DATETIME_TAGS:
        raw = exif.get(tag)
        if not raw:
            continue
        try:
            parsed = datetime.strptime(str(raw).strip(), "%Y:%m:%d %H:%M:%S")
            info["taken_at"] = parsed.isoformat(sep=" ", timespec="seconds")
            break
        except ValueError:
            continue

    try:
        gps = exif.get_ifd(_EXIF_GPS_TAG)
    except Exception:  # noqa: BLE001
        gps = None
    if gps:
        latitude = _gps_to_degrees(gps.get(2), gps.get(1))
        longitude = _gps_to_degrees(gps.get(4), gps.get(3))
        if latitude is not None and longitude is not None:
            info["latitude"] = latitude
            info["longitude"] = longitude

    return info


def _gps_to_degrees(values, reference) -> Optional[float]:
    if not values or len(values) != 3:
        return None
    try:
        degrees, minutes, seconds = (float(v) for v in values)
    except (TypeError, ValueError, ZeroDivisionError):
        return None
    result = degrees + minutes / 60.0 + seconds / 3600.0
    if reference and str(reference).upper().strip() in ("S", "W"):
        result = -result
    return round(result, 7)
