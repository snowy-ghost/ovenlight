// The server: the pages in public/ and a small JSON API under /api/.
// No dependencies and no build step: `npm start` runs this file as it is.
import { createServer } from "node:http";
import { readFile, writeFile, rename, mkdir } from "node:fs/promises";
import { dirname, extname, join, normalize, sep } from "node:path";
import { randomUUID } from "node:crypto";
import { userInfo } from "node:os";

// Ovenlight's connector reaches the app on 127.0.0.1, and sets PORT and HOST when it runs
// it. Never listen on 0.0.0.0 or a LAN address: anyone who reaches the port directly can
// send whatever identity headers they like. Not "localhost" either, which can mean ::1.
const HOST = process.env.HOST || "127.0.0.1";
const PORT = Number(process.env.PORT) || {{port}};

const PUBLIC = join(import.meta.dirname, "public");
const DATA = join(import.meta.dirname, "data", "data.json");

// The app's screens. Each has a real URL, so a reload, a link and the edge swipe back all
// land on the right one. They share index.html; public/app.js draws the one the URL names.
const SCREENS = new Set(["/", "/people"]);

// Everything the app keeps lives in one JSON file, read at start and rewritten whole after
// each change. For the handful of people an Ovenlight app serves, that is plenty, and the
// file is easy to read. Writing a temporary file and renaming it over the old one means a
// crash never leaves half a file.
const data = await load();

async function load() {
  try {
    return JSON.parse(await readFile(DATA, "utf8"));
  } catch (err) {
    if (err.code === "ENOENT") return { items: [], people: [] };
    throw err;
  }
}

let saving = Promise.resolve();
function save() {
  const json = JSON.stringify(data, null, 2);
  saving = saving
    .catch(() => {}) // a failed write must not block the ones after it
    .then(async () => {
      await mkdir(dirname(DATA), { recursive: true });
      await writeFile(DATA + ".tmp", json);
      await rename(DATA + ".tmp", DATA);
    });
  return saving;
}

// Who is calling. Ovenlight's connector removes any Ovenlight-* header a client sends and
// sets these from the calling device, which is why there is no login, password or session
// here, and there shouldn't be:
//   Ovenlight-User-Id  stable for each person, on all their devices: key their data by it
//   Ovenlight-User     their name, a label that can change (RFC 2047 encoded when not ASCII)
//   Ovenlight-Role     "owner" (you) or "guest" (someone you shared the app with)
//
// Requests can also come straight to this port, past the connector: your own curl, or any
// web page open in this computer's browser. A website can point its own name at 127.0.0.1
// and then send whatever headers it likes, but it can't change the Host its requests
// carry. So identity headers count only with the Host the connector sends (the app's
// .ts.net name) or a local one. A request without them gets the owner's rights only when
// it asks for 127.0.0.1 or localhost and comes from the app's own pages or from no page
// at all (curl, the address bar), so another website in this computer's browser can't act
// as you. Anything else gets null, and the API answers 403.
function caller(req) {
  const host = (req.headers.host || "").replace(/:\d+$/, "");
  const local = host === "127.0.0.1" || host === "localhost";
  if (!local && !host.endsWith(".ts.net")) return null;
  const id = req.headers["ovenlight-user-id"];
  if (id) {
    return {
      id: decodeWords(id),
      name: decodeWords(req.headers["ovenlight-user"] || id),
      owner: req.headers["ovenlight-role"] === "owner",
    };
  }
  const site = req.headers["sec-fetch-site"];
  const origin = req.headers.origin;
  const ownPage = site ? site === "same-origin" || site === "none" : !origin || origin === "http://" + req.headers.host;
  if (local && ownPage) return { id: "local", name: userInfo().username, owner: true };
  return null;
}

// Decodes RFC 2047 encoded words: "=?utf-8?q?Zo=C3=AB?=" is "Zoë".
function decodeWords(value) {
  return value
    .replace(/\?=\s+(?==\?)/g, "?=") // the space between two encoded words isn't text
    .replace(/=\?([^?]+)\?([bq])\?([^?]*)\?=/gi, (word, charset, encoding, text) => {
      const bytes =
        encoding.toLowerCase() === "b"
          ? Buffer.from(text, "base64")
          : Buffer.from(
              text.replace(/_/g, " ").replace(/=([0-9a-f]{2})/gi, (_, hex) => String.fromCharCode(parseInt(hex, 16))),
              "latin1",
            );
      try {
        return new TextDecoder(charset).decode(bytes);
      } catch {
        return word; // a charset TextDecoder doesn't know
      }
    });
}

// The API. A GET only reads: a link on another website still reaches the app as a GET, so
// a GET that changed something could be triggered by anyone's page. Changes are POST and
// DELETE, sent with fetch by the app's own pages.
async function api(req, res, path) {
  const me = caller(req);
  if (!me) throw httpError(403, "Open this app in Ovenlight");
  const itemPath = path.match(/^\/api\/items\/([\w-]+)$/);

  if (req.method === "GET" && path === "/api/items") return send(res, 200, listView(me));

  if (req.method === "POST" && path === "/api/items") {
    const text = String((await readJSON(req)).text ?? "").trim();
    if (!text || text.length > 200) throw httpError(400, "Write between 1 and 200 characters");
    remember(me);
    data.items.push({ id: randomUUID(), text, by: me.id, at: new Date().toISOString() });
    await save();
    return send(res, 200, listView(me));
  }

  if (req.method === "DELETE" && itemPath) {
    const item = data.items.find((i) => i.id === itemPath[1]);
    if (!item) throw httpError(404, "That item is already gone");
    if (item.by !== me.id && !me.owner) throw httpError(403, "Only whoever added it, or the owner, can remove it");
    remember(me);
    data.items = data.items.filter((i) => i !== item);
    await save();
    return send(res, 200, listView(me));
  }

  if (req.method === "DELETE" && path === "/api/items") {
    // The owner/guest difference is enforced here. Hiding the button for guests is only
    // for looks.
    if (!me.owner) throw httpError(403, "Only the owner can clear the list");
    remember(me);
    data.items = [];
    await save();
    return send(res, 200, listView(me));
  }

  if (req.method === "GET" && path === "/api/people") return send(res, 200, peopleView(me));

  throw httpError(404, "Not found");
}

// Keeps each person's latest name, by their id. Names are labels: a guest's is whatever the
// owner called them when inviting, and either can change, so items store the id.
function remember(me) {
  const person = data.people.find((p) => p.id === me.id);
  if (person) Object.assign(person, { name: me.name, owner: me.owner });
  else data.people.push({ id: me.id, name: me.name, owner: me.owner });
}

function nameOf(id) {
  return data.people.find((p) => p.id === id)?.name ?? "Someone";
}

function listView(me) {
  return {
    me: { name: me.name, owner: me.owner },
    items: data.items.map((item) => ({
      id: item.id,
      text: item.text,
      by: item.by === me.id ? "You" : nameOf(item.by),
      removable: item.by === me.id || me.owner,
    })),
  };
}

function peopleView(me) {
  return {
    people: data.people.map((p) => ({
      name: p.name,
      you: p.id === me.id,
      owner: p.owner,
      items: data.items.filter((i) => i.by === p.id).length,
    })),
  };
}

async function readJSON(req) {
  if (!(req.headers["content-type"] || "").startsWith("application/json")) throw httpError(415, "Send JSON");
  let raw = "";
  for await (const chunk of req) {
    raw += chunk;
    if (raw.length > 10_000) throw httpError(413, "Too much");
  }
  try {
    return JSON.parse(raw) ?? {};
  } catch {
    throw httpError(400, "Not valid JSON");
  }
}

const TYPES = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".webmanifest": "application/manifest+json",
  ".png": "image/png",
  ".svg": "image/svg+xml",
};

async function file(res, path) {
  const full = normalize(join(PUBLIC, path));
  if (!full.startsWith(PUBLIC + sep)) throw httpError(404, "Not found");
  let body;
  try {
    body = await readFile(full);
  } catch {
    throw httpError(404, "Not found");
  }
  // no-cache: the phone checks for a newer copy every time, so an edit shows on the next
  // reload in Ovenlight.
  res.writeHead(200, { "content-type": TYPES[extname(full)] || "application/octet-stream", "cache-control": "no-cache" });
  res.end(body);
}

function send(res, status, body) {
  res.writeHead(status, { "content-type": "application/json", "cache-control": "no-store" });
  res.end(JSON.stringify(body));
}

function httpError(status, message) {
  return Object.assign(new Error(message), { status });
}

const server = createServer(async (req, res) => {
  try {
    const path = new URL(req.url, "http://localhost").pathname;
    if (path.startsWith("/api/")) return await api(req, res, path);
    if (req.method !== "GET" && req.method !== "HEAD") throw httpError(405, "Method not allowed");
    await file(res, SCREENS.has(path) ? "/index.html" : path);
  } catch (err) {
    const status = err.status || 500;
    if (status === 500) console.error(err);
    if (!res.headersSent) send(res, status, { error: status === 500 ? "Something went wrong" : err.message });
    else res.end();
  }
});

server.on("error", (err) => {
  if (err.code !== "EADDRINUSE") throw err;
  console.error(`Port ${PORT} is in use. Stop whatever uses it, or start this with another PORT.`);
  process.exit(1);
});

server.listen(PORT, HOST, () => console.log(`Listening on http://${HOST}:${PORT}/`));

// Ctrl-C, or the connector stopping the app: let the last write finish first.
for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => saving.finally(() => process.exit(0)));
}
