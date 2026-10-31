// Общие куски интерфейса: строки треков, карточки, полки, шапки страниц, настройки волны.
import { cover, duration, h, icon } from "./dom.js";

export function pageHeader(title, { back = false, actions = [] } = {}) {
  return h(
    "header",
    { class: "page-head" },
    back
      ? h("button", { class: "icon-btn", type: "button", "aria-label": "Назад", onClick: () => history.back() }, icon("back"))
      : null,
    h("h1", { class: "page-head__title" }, title),
    h("span", { class: "page-head__actions" }, ...actions),
  );
}

export function sectionTitle(text, action) {
  return h("div", { class: "section-title" }, h("h2", {}, text), action || null);
}

let trackActions = null;

/** Обработчик кнопки «⋯» у трека: его регистрирует main.js (меню действий). */
export function setTrackActions(handler) {
  trackActions = handler;
}

export function trackRow(track, { number, onPlay, player, showCover = true } = {}) {
  const current = player?.track?.id === track.id;
  return h(
    "div",
    { class: `track${current ? " is-current" : ""}`, dataset: { id: track.id } },
    h(
      "button",
      { class: "track__main", type: "button", onClick: onPlay },
      number !== undefined ? h("span", { class: "track__no" }, current ? icon("wave") : String(number)) : null,
      showCover ? cover(track.cover, "cover--sm") : null,
      h(
        "span",
        { class: "track__text" },
        h("span", { class: "track__title" }, track.title),
        h("span", { class: "track__sub" }, track.reason || track.artist),
      ),
      track.liked ? icon("heart", "track__liked") : null,
      h("span", { class: "track__time" }, duration(track.duration)),
    ),
    trackActions
      ? h("button", { class: "icon-btn track__more", type: "button", "aria-label": `Действия: ${track.title}`, onClick: () => trackActions(track) }, icon("more"))
      : null,
  );
}

export function trackList(tracks, { player, context, numbered = false, showCover = true } = {}) {
  const list = h("div", { class: "tracks" });
  tracks.forEach((track, i) => {
    list.append(
      trackRow(track, {
        number: numbered ? track.track_no || i + 1 : undefined,
        showCover,
        player,
        onPlay: () => player.playList(tracks, i, context),
      }),
    );
  });
  // подсветка текущего трека без перерисовки всего списка
  const sync = () => {
    for (const row of list.children) row.classList.toggle("is-current", row.dataset.id === player.track?.id);
  };
  player.addEventListener("change", sync);
  list.cleanup = () => player.removeEventListener("change", sync);
  return list;
}

export function card(item, { onClick } = {}) {
  const round = item.type === "artist";
  return h(
    "button",
    { class: `card card--${item.type}`, type: "button", onClick },
    cover(item.cover, round ? "cover--round" : ""),
    h("span", { class: "card__title" }, item.title),
    item.subtitle || item.artist ? h("span", { class: "card__sub" }, item.subtitle || item.artist) : null,
  );
}

export function grid(items, onItem) {
  return h("div", { class: "grid" }, ...items.map((item) => card(item, { onClick: () => onItem(item) })));
}

export function shelf(title, items, onItem) {
  return h(
    "section",
    { class: "shelf" },
    sectionTitle(title),
    h("div", { class: "shelf__row" }, ...items.map((item) => card(item, { onClick: () => onItem(item) }))),
  );
}

export function empty(text) {
  return h("p", { class: "empty" }, text);
}

export function loading() {
  return h("div", { class: "loading", "aria-label": "Загрузка" }, h("span"), h("span"), h("span"));
}

export function chips(options, value, onChange, { segmented = false } = {}) {
  const box = h("div", { class: segmented ? "segmented" : "chips", role: "group" });
  const render = (current) => {
    for (const button of box.children) button.setAttribute("aria-pressed", String(button.dataset.value === current));
  };
  for (const [key, label] of options) {
    box.append(
      h("button", {
        type: "button",
        dataset: { value: key },
        onClick: () => {
          render(key);
          onChange(key);
        },
      }, label),
    );
  }
  render(value);
  box.setValue = render;
  return box;
}

export function toggle(label, value, onChange) {
  const button = h("button", { class: "switch", type: "button", role: "switch", "aria-checked": String(Boolean(value)), "aria-label": label });
  button.addEventListener("click", () => {
    const next = button.getAttribute("aria-checked") !== "true";
    button.setAttribute("aria-checked", String(next));
    onChange(next);
  });
  return h("div", { class: "row" }, h("span", {}, label), button);
}

export function slider({ label, min, max, step, value, format, left, right, onInput, onChange }) {
  const output = h("output", {}, format(value));
  const input = h("input", { type: "range", min, max, step, value });
  input.addEventListener("input", () => {
    output.textContent = format(Number(input.value));
    onInput?.(Number(input.value));
  });
  input.addEventListener("change", () => onChange(Number(input.value)));
  const box = h(
    "label",
    { class: "slider" },
    h("span", { class: "slider__head" }, h("span", {}, label), output),
    input,
    h("span", { class: "slider__scale" }, h("span", {}, left), h("span", {}, right)),
  );
  box.setValue = (v, text) => {
    if (document.activeElement !== input) input.value = String(v);
    output.textContent = text ?? format(v);
  };
  return box;
}

// --- настройки волны: одни и те же контролы в шторке на главной и в разделе настроек ---

export const WAVE_LABELS = {
  character: [["favorite", "Любимое"], ["balanced", "Баланс"], ["discover", "Незнакомое"], ["forgotten", "Забытое"]],
  mood: [["any", "Любое"], ["energetic", "Бодрое"], ["happy", "Весёлое"], ["calm", "Спокойное"], ["sad", "Грустное"]],
  activity: [["any", "Любое"], ["wake", "Просыпаюсь"], ["commute", "В дороге"], ["work", "Работаю"], ["workout", "Тренируюсь"], ["sleep", "Засыпаю"]],
};
const CHARACTER_HINTS = {
  favorite: "Больше того, что вы уже любите и часто дослушиваете.",
  balanced: "Любимое вперемешку с новым, похожим на ваш вкус.",
  discover: "В основном треки, которые вы ещё не слышали.",
  forgotten: "Отмеченное и любимое, что давно не звучало.",
};
const CHARACTER_DEFAULTS = {
  favorite: { temperature: 0.35, repeat_days: 2 },
  balanced: { temperature: 0.6, repeat_days: 3 },
  discover: { temperature: 1.0, repeat_days: 30 },
  forgotten: { temperature: 0.5, repeat_days: 60 },
};

export function waveSummary(s) {
  if (!s) return "Баланс";
  const label = (group, key) => (WAVE_LABELS[group].find(([k]) => k === key) || [])[1];
  const parts = [label("character", s.character)];
  if (s.mood !== "any") parts.push(label("mood", s.mood));
  if (s.activity !== "any") parts.push(label("activity", s.activity));
  if (s.no_vocals) parts.push("Без слов");
  return parts.filter(Boolean).join(" · ");
}

const DEFAULT_SETTINGS = {
  character: "balanced", mood: "any", activity: "any", no_vocals: false,
  temperature: null, diversity: 0.5, repeat_days: null, context_aware: true, steer: 0.65,
};

export function waveSettingsPanel(player, { onShake } = {}) {
  let s = { ...DEFAULT_SETTINGS, ...(player.settings || {}) };
  const change = async (patch) => {
    s = { ...s, ...patch };
    render();
    try {
      s = await player.changeWaveSettings(patch);
      render();
    } catch (error) {
      hint.textContent = error.message;
    }
  };
  const defaults = () => CHARACTER_DEFAULTS[s.character] || CHARACTER_DEFAULTS.balanced;
  const hint = h("p", { class: "hint" });
  const character = chips(WAVE_LABELS.character, s.character, (v) => change({ character: v }), { segmented: true });
  const mood = chips(WAVE_LABELS.mood, s.mood, (v) => change({ mood: v }));
  const activity = chips(WAVE_LABELS.activity, s.activity, (v) => change({ activity: v }));
  const noVocals = toggle("Без слов", s.no_vocals, (v) => change({ no_vocals: v }));
  const context = toggle("Учитывать время суток и выходные", s.context_aware, (v) => change({ context_aware: v }));
  const temperature = slider({
    label: "Смелость подбора", min: 0.1, max: 1.5, step: 0.05, value: s.temperature ?? defaults().temperature,
    format: (v) => v.toFixed(2), left: "предсказуемо", right: "смело", onChange: (v) => change({ temperature: v }),
  });
  const diversity = slider({
    label: "Разнообразие", min: 0, max: 1, step: 0.05, value: s.diversity,
    format: (v) => `${Math.round(v * 100)}%`, left: "одно звучание", right: "всё разное", onChange: (v) => change({ diversity: v }),
  });
  const repeat = slider({
    label: "Не повторять треки", min: 0, max: 60, step: 1, value: s.repeat_days ?? defaults().repeat_days,
    format: (v) => (v === 0 ? "можно сразу" : `${Math.round(v)} дн.`), left: "можно сразу", right: "2 месяца",
    onChange: (v) => change({ repeat_days: v }),
  });

  const steer = slider({
    label: "Волна по треку и плейлисту", min: 0.1, max: 0.95, step: 0.05, value: s.steer,
    format: (v) => `${Math.round(v * 100)}% похожего`, left: "больше своего вкуса", right: "ближе к источнику",
    onChange: (v) => change({ steer: v }),
  });

  function render() {
    character.setValue(s.character);
    mood.setValue(s.mood);
    activity.setValue(s.activity);
    hint.textContent = CHARACTER_HINTS[s.character] || "";
    const t = s.temperature ?? defaults().temperature;
    const r = s.repeat_days ?? defaults().repeat_days;
    temperature.setValue(t, `${t.toFixed(2)}${s.temperature == null ? " · авто" : ""}`);
    diversity.setValue(s.diversity);
    repeat.setValue(r, `${r === 0 ? "можно сразу" : `${Math.round(r)} дн.`}${s.repeat_days == null ? " · авто" : ""}`);
    steer.setValue(s.steer);
  }

  const panel = h(
    "div",
    { class: "wave-settings" },
    group("Характер", character, hint),
    group("Настроение", mood),
    group("Занятие", activity),
    group(null, noVocals),
    group(
      "Тонкая настройка",
      temperature,
      diversity,
      repeat,
      steer,
      h("p", { class: "hint" }, "Сколько в волне по треку, альбому или плейлисту — и в продолжении после них — похожего на источник. Остальное — ваш вкус, подтянутый к нему. Пропуски похожего ослабляют смещение на ходу."),
      context,
      h("button", { class: "link-btn", type: "button", onClick: () => change({ temperature: null, repeat_days: null, diversity: 0.5 }) },
        "Вернуть значения характера"),
    ),
    onShake ? h("button", { class: "wide-btn", type: "button", onClick: onShake }, "Встряхнуть волну") : null,
  );
  render();
  return panel;
}

export function group(label, ...children) {
  return h("div", { class: "group" }, label ? h("div", { class: "group__label" }, label) : null, ...children);
}
