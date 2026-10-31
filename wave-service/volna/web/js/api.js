// Обёртка над HTTP API сервиса волны.

export async function api(path, body, method) {
  const res = await fetch(path, {
    method: method || (body === undefined ? "GET" : "POST"),
    headers: body === undefined ? {} : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
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
