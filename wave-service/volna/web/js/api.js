// Обёртка над HTTP API сервиса волны.

// Сервис может жить не в корне сайта (за Caddy это /volna/), поэтому все адреса,
// и запросы, и ссылки на звук и обложки из ответов API, считаем от текущей страницы.
export const rel = (path) => (path && path.startsWith("/") ? path.slice(1) : path);

export async function api(path, body, method) {
  const res = await fetch(rel(path), {
    method: method || (body === undefined ? "GET" : "POST"),
    headers: body === undefined ? {} : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (res.status === 401) window.dispatchEvent(new Event("volna:login"));
  if (!res.ok) {
    let detail = res.statusText;
    try {
      detail = (await res.json()).detail || detail;
    } catch {
      /* не JSON */
    }
    const error = new Error(typeof detail === "string" ? detail : "Запрос не выполнен");
    error.status = res.status;
    throw error;
  }
  return res.json();
}

export const get = (path) => api(path);
export const post = (path, body = {}) => api(path, body, "POST");
export const put = (path, body = {}) => api(path, body, "PUT");
export const del = (path) => api(path, undefined, "DELETE");
