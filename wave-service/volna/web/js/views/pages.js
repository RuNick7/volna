// Страницы альбома, артиста и плейлиста.
import { get, post } from "../api.js";
import { cover, h, icon, longDuration, plural, toast } from "../dom.js";
import { empty, grid, loading, pageHeader, sectionTitle, trackList } from "../components.js";
import { actionRow } from "./library.js";

export function renderEntity(root, ctx, type, id) {
  const body = h("div", {}, loading());
  root.append(pageHeader("", { back: true }), body);
  let cleanup = () => {};
  get(`/api/${type}/${encodeURIComponent(id)}`)
    .then((page) => {
      const render = { album: albumPage, artist: artistPage, playlist: playlistPage }[type];
      const { nodes, dispose } = render(page, ctx);
      cleanup = dispose;
      body.replaceChildren(...nodes);
    })
    .catch((error) => body.replaceChildren(empty(error.message)));
  return () => cleanup();
}

function hero(page, meta, { round = false } = {}) {
  return h(
    "div",
    { class: "entity" },
    cover(page.cover, `cover--xl${round ? " cover--round" : ""}`),
    h("div", { class: "entity__text" },
      h("span", { class: "entity__kind" }, { album: "Альбом", artist: "Артист", playlist: "Плейлист" }[page.type]),
      h("h1", { class: "entity__title" }, page.title),
      ...meta,
    ),
  );
}

function likeButton(page) {
  if (page.type === "playlist") return null;
  const button = h("button", { class: "btn btn--icon", type: "button", "aria-label": "Нравится", "aria-pressed": String(Boolean(page.liked)) }, icon("heart"));
  button.addEventListener("click", async () => {
    const liked = button.getAttribute("aria-pressed") !== "true";
    button.setAttribute("aria-pressed", String(liked));
    try {
      await post("/api/like", { type: page.type, id: page.id, liked });
      toast(liked ? "Отмечено в Navidrome ★" : "Отметка снята");
    } catch (error) {
      button.setAttribute("aria-pressed", String(!liked));
      toast(error.message);
    }
  });
  return button;
}

function waveButton(player, type, id, label = "Волна") {
  return h("button", { class: "btn", type: "button", onClick: () => player.startWave({ seed: { type, id } }) }, icon("wave"), label);
}

function albumPage(page, { player, navigate }) {
  const context = { type: "album", id: page.id, title: page.title };
  const list = trackList(page.tracks, { player, context, numbered: true, showCover: false });
  const meta = [
    h("button", { class: "entity__link", type: "button", onClick: () => page.artist_id && navigate(`#/artist/${encodeURIComponent(page.artist_id)}`) }, page.subtitle),
    h("span", { class: "entity__meta" }, [page.year || null, tracksLabel(page.tracks.length), longDuration(page.duration)].filter(Boolean).join(" · ")),
  ];
  return {
    nodes: [hero(page, meta), actionRow(player, page.tracks, context, [waveButton(player, "album", page.id), likeButton(page)]), list],
    dispose: list.cleanup,
  };
}

function artistPage(page, { player, navigate }) {
  const context = { type: "artist", id: page.id, title: page.title };
  const list = trackList(page.top, { player, context });
  const plays = page.plays ? `${page.plays} ${plural(page.plays, ["прослушивание", "прослушивания", "прослушиваний"])}` : "";
  const meta = [h("span", { class: "entity__meta" }, [page.subtitle, plays].filter(Boolean).join(" · "))];
  const actions = h(
    "div",
    { class: "actions" },
    h("button", { class: "btn btn--primary", type: "button", onClick: () => player.startWave({ seed: { type: "artist", id: page.id } }) },
      icon("wave"), "Волна по артисту"),
    h("button", { class: "btn", type: "button", onClick: () => player.playList(page.top, 0, context) }, icon("play"), "Популярное"),
    h("button", { class: "btn", type: "button", onClick: () => navigate(`#/mix/artist/${encodeURIComponent(page.id)}`) }, icon("mix"), "Плейлист"),
    likeButton(page),
  );
  const open = (item) => navigate(`#/album/${encodeURIComponent(item.id)}`);
  return {
    nodes: [
      hero(page, meta, { round: true }),
      actions,
      page.top.length ? sectionTitle("Популярные треки") : null,
      page.top.length ? list : null,
      page.albums.length ? sectionTitle("Альбомы") : null,
      page.albums.length ? grid(page.albums, open) : null,
    ].filter(Boolean),
    dispose: list.cleanup,
  };
}

function playlistPage(page, { player }) {
  const context = { type: "playlist", id: page.id, title: page.title };
  const list = trackList(page.tracks, { player, context });
  const meta = [h("span", { class: "entity__meta" }, [tracksLabel(page.tracks.length), longDuration(page.duration)].join(" · "))];
  return {
    nodes: [hero(page, meta), actionRow(player, page.tracks, context, [waveButton(player, "playlist", page.id)]), list],
    dispose: list.cleanup,
  };
}

function tracksLabel(n) {
  return `${n} ${plural(n, ["трек", "трека", "треков"])}`;
}
