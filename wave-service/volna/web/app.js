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
    like: $("like"), dislike: $("dislike"), skip: $("skip"), modes: $("modes"),
    next: $("next"), status: $("status"),
  };
  const ICON_PLAY = "M8 5.5v13l10.5-6.5z";
  const ICON_PAUSE = "M8 5h3v14H8zM13 5h3v14h-3z";

  const state = {
    session: null,
    track: null,
    upcoming: [],
    mode: readMode(),
    busy: false,
  };

  function readMode() {
    try { return localStorage.getItem("volna.mode") || "balanced"; } catch { return "balanced"; }
  }

  function saveMode(mode) {
    try { localStorage.setItem("volna.mode", mode); } catch { /* приватный режим */ }
  }

  async function api(path, body) {
    const res = await fetch(path, {
      method: body === undefined ? "GET" : "POST",
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
      apply(await api("/api/wave/start", { mode: state.mode }));
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

  ui.modes.addEventListener("click", async (event) => {
    const button = event.target.closest("button[data-mode]");
    if (!button || button.dataset.mode === state.mode) return;
    state.mode = button.dataset.mode;
    saveMode(state.mode);
    renderModes();
    if (!state.session) return;
    try {
      apply(await api(`/api/wave/${state.session}/mode`, { mode: state.mode }));
    } catch (error) {
      showError(error);
    }
  });

  function renderModes() {
    for (const button of ui.modes.querySelectorAll("button[data-mode]")) {
      button.setAttribute("aria-checked", String(button.dataset.mode === state.mode));
    }
  }

  document.addEventListener("keydown", (event) => {
    if (event.target.closest?.("input, textarea") || event.metaKey || event.ctrlKey || event.altKey) return;
    if (event.code === "Space") {
      event.preventDefault();
      ui.wave.click();
    } else if (event.code === "ArrowRight") {
      advance("skip");
    } else if (event.code === "KeyL") {
      toggleLike();
    } else if (event.code === "KeyD") {
      advance("dislike");
    }
  });

  if ("mediaSession" in navigator) {
    navigator.mediaSession.setActionHandler("play", () => play());
    navigator.mediaSession.setActionHandler("pause", () => audio.pause());
    navigator.mediaSession.setActionHandler("nexttrack", () => advance("skip"));
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
    ui.status.textContent = error.message || "Что-то пошло не так";
    clearTimeout(statusTimer);
    statusTimer = setTimeout(renderHealth, 5000);
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

  renderModes();
  renderHealth();
})();
