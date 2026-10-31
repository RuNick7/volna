"""Настройки волны в духе Яндекса: характер, настроение, занятие, «без слов» и тонкая настройка.

Пользователь видит понятные переключатели, а движок получает из них Tuning: числа,
которыми управляется подбор (температура, тяга к новому, сила разнообразия и т. д.).
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, field

# Характер волны. «Популярное» из Яндекса для одной домашней библиотеки почти
# бессмысленно, поэтому вместо него «Забытое»: любимое, что давно не звучало
# (аналог яндексовского «Тайника»).
CHARACTERS = {
    "favorite": dict(temperature=0.35, novelty=-0.3, forgotten=0.0, explore=0.05, repeat_days=2, favorites=12),
    "balanced": dict(temperature=0.6, novelty=0.15, forgotten=0.1, explore=0.15, repeat_days=3, favorites=4),
    "discover": dict(temperature=1.0, novelty=0.7, forgotten=0.0, explore=0.3, repeat_days=30, favorites=0),
    "forgotten": dict(temperature=0.5, novelty=-0.2, forgotten=0.9, explore=0.1, repeat_days=60, favorites=12),
}

# Цели по признакам AudioMuse: вес > 0 значит «больше этого», < 0 — «меньше»
MOODS = {
    "any": {},
    "energetic": {"energy": 1.0, "tempo": 0.6, "danceable": 0.4, "relaxed": -0.6},
    "happy": {"happy": 1.0, "party": 0.5, "sad": -0.8},
    "calm": {"relaxed": 1.0, "energy": -0.8, "aggressive": -0.6},
    "sad": {"sad": 1.0, "happy": -0.6, "energy": -0.3},
}
ACTIVITIES = {
    "any": {},
    "wake": {"happy": 0.6, "energy": 0.4, "aggressive": -0.5},
    "commute": {"energy": 0.4, "danceable": 0.4, "happy": 0.3},
    "work": {"relaxed": 0.6, "instrumental": 0.6, "aggressive": -0.6, "party": -0.4},
    "workout": {"energy": 1.0, "tempo": 0.8, "danceable": 0.5, "relaxed": -0.6},
    "sleep": {"relaxed": 1.0, "energy": -1.0, "tempo": -0.6, "aggressive": -0.8},
}
TRAITS = ("danceable", "aggressive", "happy", "party", "relaxed", "sad")


def _clip(value: float, lo: float, hi: float) -> float:
    return max(lo, min(hi, float(value)))


@dataclass
class WaveSettings:
    character: str = "balanced"
    mood: str = "any"
    activity: str = "any"
    no_vocals: bool = False
    temperature: float | None = None  # «смелость подбора», None — по характеру
    diversity: float = 0.5  # 0 — держаться одного звучания, 1 — максимально разнообразно
    repeat_days: float | None = None  # не повторять треки, игравшие за N дней; None — по характеру
    context_aware: bool = True  # учитывать время суток и будни/выходные
    steer: float = 0.65  # волна по треку или плейлисту: доля похожего на источник (остальное — ваш вкус)

    @classmethod
    def from_dict(cls, data: dict | None) -> "WaveSettings":
        return cls().merged(data or {})

    def merged(self, patch: dict) -> "WaveSettings":
        """Новые настройки поверх текущих; неизвестные значения отбрасываются."""
        data = {**asdict(self), **{k: v for k, v in patch.items() if k in self.__dataclass_fields__}}
        s = WaveSettings(**data)
        s.character = s.character if s.character in CHARACTERS else "balanced"
        s.mood = s.mood if s.mood in MOODS else "any"
        s.activity = s.activity if s.activity in ACTIVITIES else "any"
        s.no_vocals = bool(s.no_vocals)
        s.temperature = None if s.temperature is None else _clip(s.temperature, 0.1, 1.5)
        s.diversity = _clip(s.diversity, 0.0, 1.0)
        s.repeat_days = None if s.repeat_days is None else _clip(s.repeat_days, 0.0, 90.0)
        s.context_aware = bool(s.context_aware)
        s.steer = _clip(s.steer, 0.1, 0.95)
        return s

    def to_dict(self) -> dict:
        return asdict(self)


@dataclass(frozen=True)
class Tuning:
    character: str
    temperature: float
    novelty: float
    forgotten: float
    explore: float
    repeat_days: float
    favorites: int
    redundancy: float  # штраф MMR за похожесть внутри порции
    satiety: float  # как быстро кластер «приедается» за сессию
    artist_window: int  # сколько последних артистов не повторять
    context_aware: bool
    target: dict[str, float] = field(default_factory=dict)

    @property
    def has_target(self) -> bool:
        return bool(self.target)


def tune(s: WaveSettings) -> Tuning:
    base = CHARACTERS[s.character]
    temperature = s.temperature if s.temperature is not None else base["temperature"]
    # смелее температура — чаще разведка в другие кластеры вкуса
    explore = _clip(base["explore"] * (temperature / base["temperature"]) ** 0.8, 0.02, 0.5)
    target: dict[str, float] = {}
    for part in (MOODS[s.mood], ACTIVITIES[s.activity]):
        for key, weight in part.items():
            target[key] = target.get(key, 0.0) + weight
    if s.no_vocals:
        target["instrumental"] = target.get("instrumental", 0.0) + 2.0
    return Tuning(
        character=s.character,
        temperature=temperature,
        novelty=base["novelty"],
        forgotten=base["forgotten"],
        explore=explore,
        repeat_days=s.repeat_days if s.repeat_days is not None else base["repeat_days"],
        favorites=base["favorites"],
        redundancy=0.2 + 1.0 * s.diversity,
        satiety=0.02 + 0.1 * s.diversity,
        artist_window=2 + round(4 * s.diversity),
        context_aware=s.context_aware,
        target=target,
    )


def track_traits(features: dict | None) -> dict[str, float]:
    """Признаки AudioMuse, приведённые к шкале 0..1."""
    if not features:
        return {}
    traits: dict[str, float] = {}
    if features.get("energy"):
        traits["energy"] = _clip((features["energy"] - 0.01) / 0.14, 0.0, 1.0)
    if features.get("tempo"):
        traits["tempo"] = _clip((features["tempo"] - 60.0) / 120.0, 0.0, 1.0)
    other = features.get("other") or {}
    for key in TRAITS:
        if key in other:
            traits[key] = _clip(other[key], 0.0, 1.0)
    moods = {k.lower(): v for k, v in (features.get("moods") or {}).items()}
    if "instrumental" in moods:
        traits["instrumental"] = _clip(moods["instrumental"], 0.0, 1.0)
    return traits


def match(traits: dict[str, float], target: dict[str, float]) -> float:
    """Насколько трек подходит под настроение и занятие: от -1 до 1 (0 — неизвестно)."""
    if not target or not traits:
        return 0.0
    total = sum(abs(w) for w in target.values())
    return sum(w * (traits.get(k, 0.5) - 0.5) * 2 for k, w in target.items()) / total
