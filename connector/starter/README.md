# {{name}}

A small web app for Ovenlight, which opens it full screen on your iPhone and on the phones
of people you share it with. It starts as a shared list: everyone adds things and sees
who added each one, and only you, the owner, can clear it. Replace the list with your own
app and keep what's around it: the comments in the code say why each part is the way it
is, and `ovenlight guide` prints the full guide to building for Ovenlight.

## Files

- `server.js`: the server. It serves `public/` and a JSON API under `/api/`, takes who is
  calling from the headers Ovenlight sets, and keeps everything in `data/data.json`.
- `public/index.html`: the page every screen shares (`/` and `/people`).
- `public/app.js`: draws the screen the URL names and calls the API.
- `public/styles.css`: the look, with safe areas, Dynamic Type, and light and dark.
- `public/manifest.webmanifest`, `public/icon-512.png`, `public/apple-touch-icon.png`: the
  icon and color Ovenlight shows (the name comes from `ovenlight publish --name`).

## Run it on this computer

This is for a person in a terminal; a coding agent publishes the app instead (see below).
It needs Node 22.13 or later and nothing else: no `npm install`, no build step.

```sh
npm start
```

Then open http://127.0.0.1:{{port}}/. Without Ovenlight in front of it, the app takes you
for its owner. To see what a guest gets, send the headers Ovenlight would:

```sh
curl -H 'Ovenlight-User-Id: guest:sam' -H 'Ovenlight-User: Sam' -H 'Ovenlight-Role: guest' \
  http://127.0.0.1:{{port}}/api/items
```

A reload shows changes to `public/`; a change to `server.js` needs a restart.

## Put it on your phone

Stop your own `npm start` first, with Ctrl-C in its terminal (never `pkill` by name), since
they would share the port. Then run these in this folder:

```sh
{{publish}}
ovenlight check {{slug}}
```

The connector runs `npm start` in this folder, starts it again if it stops, and keeps its
output for `ovenlight logs {{slug}}`. `check` says whether the app will look and work
right in Ovenlight. After a change to `server.js`, `ovenlight restart {{slug}}` runs the
new code.
