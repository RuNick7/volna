from __future__ import annotations

from dataclasses import asdict, dataclass, field
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
    starred_at: float | None = None
    track_no: int = 0
    disc: int = 0
    year: int = 0
    genre: str = ""

    def to_dict(self) -> dict:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: dict) -> "Track":
        return cls(**{k: v for k, v in data.items() if k in cls.__dataclass_fields__})


@dataclass
class Entity:
    """Альбом или артист вместе с отметками пользователя."""

    id: str
    name: str
    artist: str = ""
    starred: bool = False
    rating: int = 0
    play_count: int = 0
    artist_id: str = ""
    cover_id: str = ""
    year: int = 0
    song_count: int = 0
    album_count: int = 0
    created: float | None = None  # когда появился в библиотеке


@dataclass
class Playlist:
    id: str
    name: str
    track_ids: list[str] = field(default_factory=list)
    changed: float | None = None  # unix time последнего изменения


@dataclass
class LibrarySnapshot:
    """Вся библиотека с отметками пользователя: из неё строится профиль вкуса."""

    songs: list[Track]
    albums: list[Entity]
    artists: list[Entity]
    playlists: list[Playlist]
    synced_at: float


@dataclass
class Candidate:
    """Строка выдачи похожих треков: только то, что отдаёт AudioMuse."""

    id: str
    title: str = ""
    artist: str = ""
    source: str = "audiomuse"  # откуда кандидат: audiomuse | similar | favorites | random


@dataclass
class Media:
    """Поток аудио или картинки, который API отдаёт клиенту как есть."""

    status: int
    media_type: str
    headers: dict[str, str]
    body: Iterable[bytes]
    close: Callable[[], None] = lambda: None
