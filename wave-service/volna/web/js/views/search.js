// Поиск по всей библиотеке: артисты, альбомы, треки, плейлисты.
import { get } from "../api.js";
import { h, icon } from "../dom.js";
import { empty, pageHeader, sectionTitle, shelf, trackList } from "../components.js";

let lastQuery = "";

export function renderSearch(root, { player, navigate }) {
  const input = h("input", {
    class: "search__input", type: "search", placeholder: "Трек, альбом, артист или плейлист",
    "aria-label": "Поиск", autocomplete: "off", value: lastQuery,
  });
  const results = h("div", { class: "search__results" });
  root.append(
    pageHeader("Поиск"),
    h("label", { class: "search" }, icon("search"), input),
    results,
  );
  const cleanups = [];
  let timer = null;
  let token = 0;

  async function run() {
    const q = input.value.trim();
    lastQuery = q;
    cleanups.splice(0).forEach((fn) => fn());
    if (!q) {
      results.replaceChildren(empty("Ищите по названию, артисту или альбому. С артиста можно сразу запустить волну."));
      return;
    }
    const mine = ++token;
    const data = await get(`/api/search?q=${encodeURIComponent(q)}`).catch((error) => ({ error }));
    if (mine !== token) return;
    if (data.error) {
      results.replaceChildren(empty(data.error.message));
      return;
    }
    const parts = [];
    const open = (item) => navigate(`#/${item.type}/${encodeURIComponent(item.id)}`);
    if (data.artists.length) parts.push(shelf("Артисты", data.artists, open));
    if (data.tracks.length) {
      const list = trackList(data.tracks, { player, context: { type: "search", id: q, title: `«${q}»` } });
      cleanups.push(list.cleanup);
      parts.push(sectionTitle("Треки"), list);
    }
    if (data.albums.length) parts.push(shelf("Альбомы", data.albums, open));
    if (data.playlists.length) parts.push(shelf("Плейлисты", data.playlists, open));
    results.replaceChildren(...(parts.length ? parts : [empty("Ничего не нашлось.")]));
  }

  input.addEventListener("input", () => {
    clearTimeout(timer);
    timer = setTimeout(run, 220);
  });
  run();
  requestAnimationFrame(() => input.focus());
  return () => cleanups.forEach((fn) => fn());
}
