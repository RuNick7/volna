// Плейлист по песне (или по артисту): смещённая волна, собранная заранее.
// Можно сразу слушать, пересобрать или сохранить плейлистом в Navidrome.
import { post } from "../api.js";
import { cover, h, icon, longDuration, plural, toast } from "../dom.js";
import { chips, empty, loading, pageHeader, trackList } from "../components.js";

const cache = new Map(); // одна и та же подборка при возврате «назад», пока не попросили пересобрать
const KIND = { track: "Плейлист по треку", artist: "Плейлист по артисту", album: "Плейлист по альбому", playlist: "Плейлист по плейлисту" };

export function renderMix(root, { player, navigate }, type, id) {
  let size = 25;
  let cleanup = () => {};
  const body = h("div", {}, loading());
  root.append(pageHeader("", { back: true }), body);

  async function load(force = false) {
    const key = `${type}:${id}:${size}`;
    cleanup();
    body.replaceChildren(loading());
    try {
      let data = force ? null : cache.get(key);
      if (!data) {
        data = await post("/api/mix", { seed: { type, id }, size });
        cache.set(key, data);
      }
      render(data);
    } catch (error) {
      body.replaceChildren(empty(error.message));
    }
  }

  function render(data) {
    const tracks = data.tracks;
    const context = { type: "other", id: `mix:${type}:${id}`, title: data.title };
    const list = trackList(tracks, { player, context });
    cleanup = list.cleanup;
    const total = tracks.reduce((sum, t) => sum + (t.duration || 0), 0);

    const name = h("input", { class: "field__input", value: data.title, "aria-label": "Название плейлиста", maxlength: "200" });
    const saveForm = h(
      "form",
      { class: "save-form", hidden: true },
      name,
      h("button", { class: "btn btn--primary", type: "submit" }, "Сохранить"),
    );
    saveForm.addEventListener("submit", async (event) => {
      event.preventDefault();
      if (!name.value.trim()) return;
      try {
        const saved = await post("/api/playlists", { name: name.value.trim(), track_ids: tracks.map((t) => t.id) });
        toast(`Плейлист «${saved.name}» сохранён в Navidrome`);
        if (saved.id) navigate(`#/playlist/${encodeURIComponent(saved.id)}`);
      } catch (error) {
        toast(error.message);
      }
    });

    body.replaceChildren(
      h(
        "div",
        { class: "entity" },
        h("span", { class: "mix-cover" }, cover(tracks[0]?.cover, "cover--xl"), icon("mix", "mix-cover__badge")),
        h("div", { class: "entity__text" },
          h("span", { class: "entity__kind" }, KIND[type] || "Плейлист"),
          h("h1", { class: "entity__title" }, data.title),
          h("span", { class: "entity__meta" }, `${tracks.length} ${plural(tracks.length, ["трек", "трека", "треков"])} · ${longDuration(total)}`),
          h("span", { class: "entity__meta" }, "Похожее на источник с поправкой на ваш вкус: без нелюбимого и пропускаемого."),
        ),
      ),
      h(
        "div",
        { class: "actions" },
        h("button", { class: "btn btn--primary", type: "button", onClick: () => player.playList(tracks, 0, context) }, icon("play"), "Слушать"),
        h("button", { class: "btn", type: "button", onClick: () => player.shuffle(tracks, context) }, icon("shuffle"), "Перемешать"),
        h("button", { class: "btn", type: "button", onClick: () => { saveForm.hidden = !saveForm.hidden; if (!saveForm.hidden) name.focus(); } }, icon("mix"), "Сохранить"),
        h("button", { class: "btn", type: "button", onClick: () => load(true) }, icon("sync"), "Пересобрать"),
      ),
      saveForm,
      chips([["15", "15 треков"], ["25", "25"], ["50", "50"]], String(size), (v) => {
        size = Number(v);
        load();
      }),
      list,
    );
  }

  load();
  return () => cleanup();
}
