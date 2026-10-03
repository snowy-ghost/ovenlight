// Draws the screen the URL names, and talks to the API in server.js.

const screen = document.getElementById("screen");
const screens = { "/": listScreen, "/people": peopleScreen };
let refresh = () => {};

// Moving between screens changes the real URL with history.pushState, so Ovenlight's edge
// swipe back and a reload work as they do on any website. Don't add a pull-to-refresh of
// your own: pulling down at the top of the page is how people bring back Ovenlight's menu.
async function show() {
  refresh = () => {};
  const draw = screens[location.pathname];
  if (!draw) return render(h("h1", {}, "Not found"), h("a", { href: "/", class: "row link" }, "Back to the list"));
  try {
    refresh = await draw();
  } catch (err) {
    render(h("h1", {}, document.title), problem(err));
  }
}

document.addEventListener("click", (event) => {
  const link = event.target.closest("a[href^='/']");
  if (!link || event.metaKey || event.ctrlKey || event.shiftKey) return;
  event.preventDefault();
  // A back link goes back, like the swipe, when the previous screen is this app's.
  if ("back" in link.dataset && history.state?.pushed) return history.back();
  if (link.getAttribute("href") === location.pathname) return;
  history.pushState({ pushed: true }, "", link.getAttribute("href"));
  scrollTo(0, 0);
  show();
});
addEventListener("popstate", show);

// Others change the list too: catch up when the app comes back to the screen.
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) refresh();
});

// iOS applies :active styles, the press feedback in styles.css, only with a touch listener.
document.addEventListener("touchstart", () => {}, { passive: true });

show();

async function listScreen() {
  const greeting = h("p", { class: "subtitle" });
  const list = h("ul", { class: "list" });
  const footer = h("div", { class: "footer" });
  const input = h("input", {
    name: "text",
    "aria-label": "New item",
    placeholder: "Add something",
    autocomplete: "off",
    enterkeyhint: "send",
    maxlength: "200",
    required: true,
  });
  const form = h("form", { class: "add", onsubmit: add }, input, h("button", { type: "submit", class: "button primary" }, "Add"));

  function fill({ me, items }) {
    greeting.textContent = `Hi, ${me.name}`;
    list.replaceChildren(
      ...items.map((item) =>
        h(
          "li",
          { class: "row" },
          h("div", { class: "text" }, h("span", {}, item.text), h("small", {}, item.by)),
          item.removable &&
            h("button", { class: "icon", "aria-label": `Remove ${item.text}`, onclick: () => change("DELETE", `/api/items/${item.id}`) }, "×"),
        ),
      ),
    );
    if (!items.length) list.append(h("li", { class: "row empty" }, "Nothing here yet."));
    footer.replaceChildren(
      h("a", { href: "/people", class: "row link" }, "People"),
      me.owner
        ? items.length > 0 && h("button", { class: "button danger", onclick: clear }, "Clear the List")
        : h("p", { class: "note" }, "Only the owner can clear the list."),
    );
  }

  async function change(method, path, body) {
    try {
      fill(await api(method, path, body));
      return true;
    } catch (err) {
      alert(err.message);
      return false;
    }
  }

  async function add(event) {
    event.preventDefault();
    if (await change("POST", "/api/items", { text: input.value })) input.value = "";
  }

  async function clear() {
    if (confirm("Clear the list for everyone?")) await change("DELETE", "/api/items");
  }

  fill(await api("GET", "/api/items"));
  render(h("header", {}, h("h1", {}, document.title), greeting), form, list, footer);
  return async () => fill(await api("GET", "/api/items"));
}

async function peopleScreen() {
  const list = h("ul", { class: "list" });

  function fill({ people }) {
    list.replaceChildren(
      ...people.map((p) =>
        h(
          "li",
          { class: "row" },
          h("div", { class: "text" }, h("span", {}, p.you ? `${p.name} (you)` : p.name), h("small", {}, p.owner ? "Owner" : "Guest")),
          h("span", { class: "count" }, String(p.items)),
        ),
      ),
    );
    if (!people.length) list.append(h("li", { class: "row empty" }, "Nobody has added anything yet."));
  }

  fill(await api("GET", "/api/people"));
  render(
    h("a", { href: "/", class: "back", "data-back": true }, "‹ List"),
    h("header", {}, h("h1", {}, "People"), h("p", { class: "subtitle" }, "How many items each person has on the list")),
    list,
  );
  return async () => fill(await api("GET", "/api/people"));
}

async function api(method, path, body) {
  const res = await fetch(path, {
    method,
    headers: body ? { "content-type": "application/json" } : {},
    body: body && JSON.stringify(body),
  });
  const out = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(out.error || `The server answered ${res.status}`);
  return out;
}

function render(...children) {
  screen.replaceChildren(...children.filter(Boolean));
}

function problem(err) {
  return h("p", { class: "note" }, `Couldn't load this: ${err.message}`);
}

// Builds elements from text, never from HTML, so whatever people type stays text.
function h(tag, props, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (key.startsWith("on")) el.addEventListener(key.slice(2), value);
    else if (value === true) el.setAttribute(key, "");
    else if (value !== false && value != null) el.setAttribute(key, value);
  }
  el.append(...children.filter((c) => c != null && c !== false));
  return el;
}
