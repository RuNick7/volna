// Коллекция: «Мне нравится», плейлисты, альбомы, артисты, недавно слушали.
import { get } from "../api.js";
import { h, icon, plural } from "../dom.js";
import { chips, empty, grid, loading, pageHeader, sectionTitle, trackList } from "../components.js";

const SECTIONS = [
  ["liked", "Мне нравится", "heart"],
  ["playlists", "Плейлисты", "playlist"],
  ["albums", "Альбомы", "album"],
  ["artists", "Артисты", "artist"],
  ["recent", "Недавно слушали", "clock"],
];

export function renderLibrary(root, ctx, section) {
  if (!section) return renderRoot(root, ctx);
  const renderer = { liked: renderTracks, recent: renderTracks, albums: renderAlbums, artists: renderArtists, playlists: renderPlaylists }[section];
  if (!renderer) return renderRoot(root, ctx);
  return renderer(root, ctx, section);
}

function renderRoot(root, { navigate }) {
  const counts = {};
  const tiles = h("div", { class: "tiles" });
  const recent = h("div", {}, loading());
  root.append(pageHeader("Коллекция"), tiles, recent);

  const renderTiles = () => {
    tiles.replaceChildren(
      ...SECTIONS.map(([key, label, ic]) =>
        h("button", { class: "tile", type: "button", onClick: () => navigate(`#/library/${key}`) },
          icon(ic, "tile__icon"),
          h("span", { class: "tile__title" }, label),
          counts[key] !== undefined ? h("span", { class: "tile__count" }, String(counts[key])) : null,
          icon("chevron", "tile__chevron"),
        ),
      ),
    );
  };
  renderTiles();

  get("/api/library")
    .then((stats) => {
      Object.assign(counts, { liked: stats.liked, playlists: stats.playlists, albums: stats.albums, artists: stats.artists });
      renderTiles();
    })
    .catch(() => {});
  get("/api/library/albums?sort=newest&limit=12")
    .then(({ items }) => {
      const open = (item) => navigate(`#/album/${encodeURIComponent(item.id)}`);
      recent.replaceChildren(sectionTitle("Недавно добавлены"), items.length ? grid(items, open) : empty("Пока пусто."));
    })
    .catch((error) => recent.replaceChildren(empty(`Библиотека недоступна: ${error.message}`)));
}

function renderTracks(root, { player }, section) {
  const title = section === "liked" ? "Мне нравится" : "Недавно слушали";
  const body = h("div", {}, loading());
  root.append(pageHeader(title, { back: true }), body);
  let cleanup = () => {};
  get(`/api/library/${section}`)
    .then(({ tracks }) => {
      if (!tracks.length) {
        body.replaceChildren(empty(section === "liked" ? "Отмечайте треки сердцем — они соберутся здесь." : "История пока пуста."));
        return;
      }
      const context = { type: "library", id: section, title };
      const list = trackList(tracks, { player, context });
      cleanup = list.cleanup;
      body.replaceChildren(
        h("p", { class: "page-meta" }, `${tracks.length} ${plural(tracks.length, ["трек", "трека", "треков"])}`),
        actionRow(player, tracks, context),
        list,
      );
    })
    .catch((error) => body.replaceChildren(empty(error.message)));
  return () => cleanup();
}

export function actionRow(player, tracks, context, extra = []) {
  return h(
    "div",
    { class: "actions" },
    h("button", { class: "btn btn--primary", type: "button", onClick: () => player.playList(tracks, 0, context) }, icon("play"), "Слушать"),
    h("button", { class: "btn", type: "button", onClick: () => player.shuffle(tracks, context) }, icon("shuffle"), "Перемешать"),
    ...extra,
  );
}

const ALBUM_SORTS = [["alpha", "А–Я"], ["newest", "Новые"], ["recent", "Недавно слушали"], ["frequent", "Часто"], ["year", "По году"], ["liked", "Любимые"]];
const ARTIST_SORTS = [["alpha", "А–Я"], ["frequent", "Часто слушаете"], ["liked", "Любимые"]];
const sortMemory = { albums: "alpha", artists: "alpha" };

function renderAlbums(root, ctx) {
  return renderSortedGrid(root, ctx, "albums", "Альбомы", ALBUM_SORTS, "album");
}

function renderArtists(root, ctx) {
  return renderSortedGrid(root, ctx, "artists", "Артисты", ARTIST_SORTS, "artist");
}

function renderSortedGrid(root, { navigate }, kind, title, sorts, type) {
  const body = h("div", {}, loading());
  const load = (sort) => {
    sortMemory[kind] = sort;
    body.replaceChildren(loading());
    get(`/api/library/${kind}?sort=${sort}&limit=300`)
      .then(({ items }) => {
        const open = (item) => navigate(`#/${type}/${encodeURIComponent(item.id)}`);
        body.replaceChildren(items.length ? grid(items, open) : empty("Здесь пока пусто."));
      })
      .catch((error) => body.replaceChildren(empty(error.message)));
  };
  root.append(pageHeader(title, { back: true }), chips(sorts, sortMemory[kind], load), body);
  load(sortMemory[kind]);
}

function renderPlaylists(root, { navigate }) {
  const body = h("div", {}, loading());
  root.append(pageHeader("Плейлисты", { back: true }), body);
  get("/api/library/playlists")
    .then(({ items }) => {
      const open = (item) => navigate(`#/playlist/${encodeURIComponent(item.id)}`);
      body.replaceChildren(items.length ? grid(items, open) : empty("Плейлистов пока нет: их можно создать в Navidrome."));
    })
    .catch((error) => body.replaceChildren(empty(error.message)));
}
