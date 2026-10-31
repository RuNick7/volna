from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Callable, Iterable


@dataclass
class Track:
    id: str
    title: str
    artist: str = ""
    album: str = ""
    duration: float = 0.0
    artist_id: str = ""
    album_id: str = ""
    cover_id: str = ""
    play_count: int = 0
    last_played: float | None = None  # unix time
    starred: bool = False
    rating: int = 0

    def to_dict(self) -> dict:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: dict) -> "Track":
        return cls(**{k: v for k, v in data.items() if k in cls.__dataclass_fields__})


@dataclass
class Candidate:
    """Строка выдачи похожих треков: только то, что отдаёт AudioMuse."""

    id: str
    title: str = ""
    artist: str = ""


@dataclass
class Media:
    """Поток аудио или картинки, который API отдаёт клиенту как есть."""

    status: int
    media_type: str
    headers: dict[str, str]
    body: Iterable[bytes]
    close: Callable[[], None] = lambda: None
