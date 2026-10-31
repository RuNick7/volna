// Вход аккаунтом Navidrome: нужен, когда сервис открыт в интернет.
import { post } from "../api.js";
import { h } from "../dom.js";

export function renderLogin(root) {
  const error = h("p", { class: "field__hint login__error", role: "alert" });
  const button = h("button", { class: "btn btn--primary", type: "submit" }, "Войти");
  const field = (name, label, attrs) =>
    h("label", { class: "field" },
      h("span", { class: "field__label" }, label),
      h("input", { name, class: "field__input", required: true, autocapitalize: "off", spellcheck: "false", ...attrs }),
    );
  const form = h("form", { class: "form login", onSubmit: submit },
    h("h1", { class: "login__title" }, "Вход"),
    h("p", { class: "hint" }, "Логин и пароль от Navidrome."),
    field("user", "Логин", { autocomplete: "username" }),
    field("password", "Пароль", { type: "password", autocomplete: "current-password" }),
    error,
    button,
  );
  root.append(form);
  form.querySelector("input").focus();

  async function submit(event) {
    event.preventDefault();
    const { user, password } = Object.fromEntries(new FormData(form).entries());
    button.disabled = true;
    error.textContent = "";
    try {
      await post("/api/auth/login", { user, password, device: "web" });
      location.reload();
    } catch (e) {
      error.textContent = e.message;
      button.disabled = false;
    }
  }
}
