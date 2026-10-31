// Веб-плеер прототипа: одна кнопка запускает волну, дальше всё происходит само.
// Каждое окончание трека (дослушал / пропустил на N-й секунде / «не нравится»)
// отправляется на сервер, и в ответ приходит следующий трек.
(() => {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const audio = $("audio");
  const ui = {
    wave: $("wave"), icon: $("icon"), cover: $("cover"), title: $("title"), sub: $("sub"),
    reason: $("reason"), progress: $("progress"), bar: $("bar"), controls: $("controls"),
    like: $("like"), dislike: $("dislike"), skip: $("skip"), next: $("next"), status: $("status"),
    sheet: $("sheet"), backdrop: $("sheet-backdrop"), summary: $("tune-summary"),
  };
  const ICON_PLAY = "M8 5.5v13l10.5-6.5z";
  const ICON_PAUSE = "M8 5h3v14H8zM13 5h3v14h-3z";

  const LABELS = {
    character: { favorite: "Любимое", balanced: "Баланс", discover: "Незнакомое", forgotten: "Забытое" },
    mood: { any: "", energetic: "Бодрое", happy: "Весёлое", calm: "Спокойное", sad: "Грустное" },
    activity: { any: "", wake: "Просыпаюсь", commute: "В дороге", work: "Работаю", workout: "Тренируюсь", sleep: "Засыпаю" },
  };
  const CHARACTER_HINTS = {
    favorite: "Больше того, что вы уже любите и часто дослушиваете.",
    balanced: "Любимое вперемешку с новым, похожим на ваш вкус.",
    discover: "В основном треки, которые вы ещё не слышали.",
    forgotten: "Отмеченное и любимое, что давно не звучало.",
  };
  // значения характера по умолчанию: показываем, когда ползунок «авто»
  const CHARACTER_DEFAULTS = {
    favorite: { temperature: 0.35, repeat_days: 2 },
    balanced: { temperature: 0.6, repeat_days: 3 },
    discover: { temperature: 1.0, repeat_days: 30 },
    forgotten: { temperature: 0.5, repeat_days: 60 },
  };

  const state = {
    session: null,
    track: null,
    upcoming: [],
    settings: { character: "balanced", mood: "any", activity: "any", no_vocals: false,
      temperature: null, diversity: 0.5, repeat_days: null, context_aware: true },
    busy: false,
  };

  async function api(path, body, method) {
    const res = await fetch(path, {
      method: method || (body === undefined ? "GET" : "POST"),
      headers: body === undefined ? {} : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!res.ok) {
      let detail = res.statusText;
      try { detail = (await res.json()).detail || detail; } catch { /* не JSON */ }
      const error = new Error(detail);
      error.status = res.status;
      throw error;
    }
    return res.json();
  }

  // --- поток ---

  async function startWave() {
    setLoading(true);
    try {
      apply(await api("/api/wave/start", { device: "web" }));
      await play();
    } catch (error) {
      showError(error);
    } finally {
      setLoading(false);
    }
  }

  async function advance(outcome) {
    if (!state.track || state.busy) return;
    state.busy = true;
    const duration = Number.isFinite(audio.duration) ? audio.duration : state.track.duration;
    const body = {
      track_id: state.track.id,
      outcome,
      position: audio.currentTime || 0,
      duration: duration || 0,
    };
    audio.pause();
    try {
      apply(await api(`/api/wave/${state.session}/advance`, body));
      await play();
    } catch (error) {
      if (error.status === 404) {
        state.session = null;
        await startWave();
      } else {
        showError(error);
      }
    } finally {
      state.busy = false;
    }
  }

  function apply(data) {
    state.session = data.session;
    state.upcoming = data.upcoming || [];
    if (data.settings) setSettings(data.settings);
    if (!state.track || !data.track || state.track.id !== data.track.id) setTrack(data.track);
    else renderTrack(data.track);
    renderNext();
  }

  function setTrack(track) {
    state.track = track;
    if (!track) {
      audio.removeAttribute("src");
      ui.title.textContent = "Волна стихла";
      ui.sub.textContent = "Не нашлось подходящих треков";
      ui.reason.textContent = "";
      return;
    }
    audio.src = track.stream;
    renderTrack(track);
    ui.bar.style.width = "0%";
    ui.cover.style.backgroundImage = `url("${track.cover}")`;
    ui.wave.classList.add("has-cover");
    ui.progress.hidden = false;
    ui.controls.hidden = false;
    if ("mediaSession" in navigator) {
      navigator.mediaSession.metadata = new MediaMetadata({
        title: track.title,
        artist: track.artist,
        album: track.album,
        artwork: [{ src: track.cover, sizes: "600x600" }],
      });
    }
  }

  function renderTrack(track) {
    state.track = track;
    ui.title.textContent = track.title;
    ui.sub.textContent = track.artist;
    ui.reason.textContent = track.reason || "";
    ui.like.setAttribute("aria-pressed", String(Boolean(track.liked)));
    ui.wave.setAttribute("aria-label", "Пауза или продолжить");
  }

  function renderNext() {
    const next = state.upcoming[0];
    ui.next.textContent = next ? `Дальше: ${next.title} · ${next.artist}` : "";
  }

  async function play() {
    if (!state.track) return;
    ensureAnalyser();
    try {
      await audio.play();
    } catch (error) {
      if (error.name !== "AbortError") showError(new Error("Нажмите на волну, чтобы включить звук"));
    }
  }

  // --- реакция на аудио ---

  audio.addEventListener("ended", () => advance("complete"));
  audio.addEventListener("error", () => {
    if (!state.track || !audio.getAttribute("src")) return;
    showError(new Error("Трек не воспроизводится, беру следующий"));
    setTimeout(() => advance("error"), 1200);
  });
  audio.addEventListener("timeupdate", () => {
    const duration = Number.isFinite(audio.duration) ? audio.duration : state.track?.duration;
    if (duration) ui.bar.style.width = `${Math.min(100, (audio.currentTime / duration) * 100)}%`;
  });
  audio.addEventListener("play", () => setIcon(true));
  audio.addEventListener("pause", () => setIcon(false));

  function setIcon(playing) {
    ui.icon.setAttribute("d", playing ? ICON_PAUSE : ICON_PLAY);
    if ("mediaSession" in navigator) navigator.mediaSession.playbackState = playing ? "playing" : "paused";
  }

  // --- управление ---

  ui.wave.addEventListener("click", () => {
    if (!state.session || !state.track) startWave();
    else if (audio.paused) play();
    else audio.pause();
  });
  ui.skip.addEventListener("click", () => advance("skip"));
  ui.dislike.addEventListener("click", () => advance("dislike"));
  ui.like.addEventListener("click", toggleLike);
  ui.progress.addEventListener("click", (event) => {
    if (!Number.isFinite(audio.duration)) return;
    const rect = ui.progress.getBoundingClientRect();
    audio.currentTime = ((event.clientX - rect.left) / rect.width) * audio.duration;
  });

  async function toggleLike() {
    if (!state.track || !state.session) return;
    const liked = ui.like.getAttribute("aria-pressed") !== "true";
    ui.like.setAttribute("aria-pressed", String(liked));
    try {
      apply(await api(`/api/wave/${state.session}/like`, { track_id: state.track.id, liked }));
    } catch (error) {
      ui.like.setAttribute("aria-pressed", String(!liked));
      showError(error);
    }
  }

  async function shake() {
    if (!state.session) return startWave();
    try {
      apply(await api(`/api/wave/${state.session}/shake`, {}));
      showNotice("Волна встряхнута: дальше будет иначе");
    } catch (error) {
      showError(error);
    }
  }

  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && !ui.sheet.hidden) return closeSheet();
    if (!ui.sheet.hidden || event.metaKey || event.ctrlKey || event.altKey) return;
    if (event.code === "Space") {
      event.preventDefault();
      ui.wave.click();
    } else if (event.code === "ArrowRight") {
      advance("skip");
    } else if (event.code === "KeyL") {
      toggleLike();
    } else if (event.code === "KeyD") {
      advance("dislike");
    } else if (event.code === "KeyS") {
      shake();
    }
  });

  if ("mediaSession" in navigator) {
    navigator.mediaSession.setActionHandler("play", () => play());
    navigator.mediaSession.setActionHandler("pause", () => audio.pause());
    navigator.mediaSession.setActionHandler("nexttrack", () => advance("skip"));
  }

  // --- настройки волны ---

  let lastFocus = null;

  function openSheet() {
    lastFocus = document.activeElement;
    ui.sheet.hidden = false;
    ui.backdrop.hidden = false;
    $("close-settings").focus();
    loadTaste();
  }

  function closeSheet() {
    ui.sheet.hidden = true;
    ui.backdrop.hidden = true;
    lastFocus?.focus();
  }

  $("open-settings").addEventListener("click", openSheet);
  $("tune-pill").addEventListener("click", openSheet);
  $("close-settings").addEventListener("click", closeSheet);
  ui.backdrop.addEventListener("click", closeSheet);
  $("shake").addEventListener("click", () => { closeSheet(); shake(); });
  $("reset-auto").addEventListener("click", () => changeSettings({ temperature: null, repeat_days: null, diversity: 0.5 }));

  for (const group of ui.sheet.querySelectorAll(".segmented, .chips")) {
    group.addEventListener("click", (event) => {
      const button = event.target.closest("button[data-value]");
      if (button) changeSettings({ [group.dataset.setting]: button.dataset.value });
    });
  }
  for (const toggle of ui.sheet.querySelectorAll(".switch")) {
    toggle.addEventListener("click", () => {
      changeSettings({ [toggle.dataset.setting]: toggle.getAttribute("aria-checked") !== "true" });
    });
  }
  for (const range of ui.sheet.querySelectorAll('input[type="range"]')) {
    range.addEventListener("input", () => {
      state.settings[range.dataset.setting] = Number(range.value);
      renderSettings();
      changeSettingsLater({ [range.dataset.setting]: Number(range.value) });
    });
  }

  let pending = {};
  let pendingTimer = null;

  function changeSettingsLater(patch) {
    pending = { ...pending, ...patch };
    clearTimeout(pendingTimer);
    pendingTimer = setTimeout(() => {
      const patchToSend = pending;
      pending = {};
      changeSettings(patchToSend);
    }, 300);
  }

  async function changeSettings(patch) {
    setSettings({ ...state.settings, ...patch });
    try {
      if (state.session) {
        apply(await api(`/api/wave/${state.session}/settings`, patch));
      } else {
        setSettings((await api("/api/settings", patch, "PUT")).settings);
      }
    } catch (error) {
      if (error.status === 404) {
        state.session = null;
        setSettings((await api("/api/settings", patch, "PUT")).settings);
      } else {
        showError(error);
      }
    }
  }

  function setSettings(settings) {
    state.settings = settings;
    renderSettings();
  }

  function renderSettings() {
    const s = state.settings;
    const defaults = CHARACTER_DEFAULTS[s.character] || CHARACTER_DEFAULTS.balanced;
    for (const group of ui.sheet.querySelectorAll(".segmented, .chips")) {
      for (const button of group.querySelectorAll("button[data-value]")) {
        button.setAttribute("aria-pressed", String(s[group.dataset.setting] === button.dataset.value));
      }
    }
    for (const toggle of ui.sheet.querySelectorAll(".switch")) {
      toggle.setAttribute("aria-checked", String(Boolean(s[toggle.dataset.setting])));
    }
    const temperature = s.temperature ?? defaults.temperature;
    const repeat = s.repeat_days ?? defaults.repeat_days;
    setRange("temperature", temperature);
    setRange("diversity", s.diversity);
    setRange("repeat_days", repeat);
    $("temperature-value").textContent = `${temperature.toFixed(2)}${s.temperature == null ? " · авто" : ""}`;
    $("diversity-value").textContent = `${Math.round(s.diversity * 100)}%`;
    $("repeat-value").textContent = `${repeat === 0 ? "можно сразу" : `${Math.round(repeat)} дн.`}${s.repeat_days == null ? " · авто" : ""}`;
    $("character-hint").textContent = CHARACTER_HINTS[s.character] || "";

    const parts = [LABELS.character[s.character], LABELS.mood[s.mood], LABELS.activity[s.activity]];
    if (s.no_vocals) parts.push("Без слов");
    ui.summary.textContent = parts.filter(Boolean).join(" · ");
  }

  function setRange(name, value) {
    const input = ui.sheet.querySelector(`input[data-setting="${name}"]`);
    if (document.activeElement !== input) input.value = String(value);
  }

  async function loadTaste() {
    try {
      const taste = await api("/api/taste");
      const s = taste.signals || {};
      const count = (n, forms) => `${n ?? 0} ${plural(n ?? 0, forms)}`;
      const TRACKS = ["трек", "трека", "треков"];
      $("taste-signals").textContent =
        `Учтено: ${count(s.played_tracks, TRACKS)} с прослушиваниями, ★ у ${count(s.starred_tracks, ["трека", "треков", "треков"])}, ` +
        `${count(s.starred_albums, ["альбома", "альбомов", "альбомов"])} и ` +
        `${count(s.starred_artists, ["артиста", "артистов", "артистов"])}, ` +
        `${count(s.playlists, ["плейлист", "плейлиста", "плейлистов"])} и ` +
        `${count(s.events, ["реакция", "реакции", "реакций"])} в Volna.`;
      fillList($("taste-loved"), taste.loved_artists, (a) => `${a.artist} · ${Math.round(a.rate * 100)}%`);
      fillList($("taste-skipped"), taste.skipped_artists, (a) => `${a.artist} · ${Math.round(a.skips)} проп.`);
      const sources = Object.entries(taste.sources || {})
        .map(([name, v]) => `${SOURCE_NAMES[name] || name}: ${Math.round(v.rate * 100)}% дослушано`);
      $("taste-sources").textContent = sources.length ? `Откуда треки заходят лучше — ${sources.join(", ")}.` : "";
    } catch (error) {
      $("taste-signals").textContent = `Не удалось загрузить: ${error.message}`;
    }
  }

  function plural(n, [one, few, many]) {
    const mod10 = n % 10;
    const mod100 = n % 100;
    if (mod10 === 1 && mod100 !== 11) return one;
    if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) return few;
    return many;
  }

  const SOURCE_NAMES = {
    audiomuse: "похожие по звучанию", favorites: "из любимого", similar: "похожие на трек",
    random: "случайные", manual: "вне волны",
  };

  function fillList(list, items, format) {
    list.replaceChildren(...(items || []).slice(0, 6).map((item) => {
      const li = document.createElement("li");
      li.textContent = format(item);
      return li;
    }));
    if (!list.children.length) {
      const li = document.createElement("li");
      li.textContent = "пока мало данных";
      list.append(li);
    }
  }

  // --- громкость для фона и пульса кнопки ---

  let analyser = null;
  let samples = null;

  function ensureAnalyser() {
    if (analyser) return;
    try {
      const context = new (window.AudioContext || window.webkitAudioContext)();
      const source = context.createMediaElementSource(audio);
      analyser = context.createAnalyser();
      analyser.fftSize = 512;
      samples = new Uint8Array(analyser.fftSize);
      source.connect(analyser);
      analyser.connect(context.destination);
      audio.addEventListener("play", () => context.resume());
    } catch {
      analyser = null;
    }
  }

  function loudness() {
    if (!analyser || audio.paused) return 0;
    analyser.getByteTimeDomainData(samples);
    let sum = 0;
    for (let i = 0; i < samples.length; i++) {
      const v = (samples[i] - 128) / 128;
      sum += v * v;
    }
    return Math.min(1, Math.sqrt(sum / samples.length) * 3.2);
  }

  window.Topo?.setEnergySource(loudness);
  (function pulse() {
    const energy = window.Topo ? window.Topo.getEnergy() : 0;
    document.documentElement.style.setProperty("--pulse", (1 + energy * 0.08).toFixed(3));
    requestAnimationFrame(pulse);
  })();

  // --- служебное ---

  function setLoading(loading) {
    ui.wave.classList.toggle("is-loading", loading);
    if (loading && !state.track) ui.sub.textContent = "Настраиваю волну под ваш вкус…";
  }

  let statusTimer = null;
  function showError(error) {
    showNotice(error.message || "Что-то пошло не так");
  }

  function showNotice(text) {
    ui.status.textContent = text;
    clearTimeout(statusTimer);
    statusTimer = setTimeout(renderHealth, 4000);
  }

  let health = null;
  async function renderHealth() {
    try {
      health = health || (await api("/api/health"));
      ui.status.textContent = health.mode === "demo"
        ? "демо-библиотека"
        : health.navidrome && health.audiomuse ? "" : "сервер недоступен";
    } catch {
      ui.status.textContent = "сервис волны недоступен";
    }
  }

  api("/api/settings").then((data) => setSettings(data.settings)).catch(() => renderSettings());
  renderHealth();
})();
