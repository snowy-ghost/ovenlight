# ovenlight.app

The Ovenlight website: the home, support, privacy, invite (`/join`) and docs (`/docs`)
pages, as a static site served by Cloudflare (Workers static assets, configured in the
repo root's `wrangler.jsonc`, which also attaches the ovenlight.app domain).

`.assetsignore` keeps this README off the site. `llms.txt` tells coding agents what
Ovenlight is and how to use it, served as `text/plain`; it points to `ovenlight guide`
and the docs rather than repeating docs/building-apps.md.

The docs pages aren't committed: `scripts/sitedocs` renders them from the repo's `docs/`
into `docs.html` and `docs/`, which git ignores, with each doc's Markdown beside its page
for coding agents. Wrangler runs it before each deploy and `wrangler dev` (`build` in
`wrangler.jsonc`), so it needs Go. It also fails on a link or `#anchor` in the docs that
goes nowhere, and CI runs it on every push. `docs.js` adds the Copy buttons on code.

The home, support, privacy and docs pages share `style.css`; the join page keeps its styles
inline, since its Content-Security-Policy loads nothing but `join.js` and same-origin
images. Screenshots in `images/` come in WebP with a JPEG fallback, at 480 and 960 px wide. The link
preview image, `og-invite.jpg`, is written by `design/og/generate.py`, and the icons,
`icon.svg` and `icon-180.png`, by `design/icon/generate.py`.

`.well-known/apple-app-site-association` makes invite links open the app. iOS
accepts it only when it is served directly, with no redirect, as
`application/json`; `_headers` sets the content type.

## Deploying

Deploy origin/master from a worktree of its own, not from a checkout someone may be
editing. Use the pinned wrangler (signed in with `npx wrangler@4.147.0 login`), and put
the commit in the version's message:

    git fetch origin
    git worktree add --detach /tmp/ovenlight-site origin/master
    cd /tmp/ovenlight-site
    npx wrangler@4.147.0 deploy --message "$(git rev-parse HEAD)"

Then fetch every file back and compare it with the commit and the docs pages the deploy
rendered from it, asking as a browser does (`Accept: text/html`), since Cloudflare can
inject scripts into the pages browsers get. Nothing should print:

    cd site
    { git ls-files; find docs.html docs -type f; } | grep -vxF -e README.md -e _headers -e .assetsignore | while read -r f; do
      curl -fsSL -H 'Accept: text/html' "https://ovenlight.app/$f" | cmp -s - "$f" || echo "differs: $f"
    done

A page that differs only by an added script means a Cloudflare feature, such as Web
Analytics, is injecting it: turn that off for the zone. Then, back in your own
checkout, run `git worktree remove /tmp/ovenlight-site`. `scripts/publish-connector.sh`
pins the same wrangler; change both together.

## Rolling back

Each deploy is a version of the Worker, with its own copy of the files. From the repo
root, list the recent versions, each with its commit as the message, and roll back to
the one you want:

    npx wrangler@4.147.0 versions list
    npx wrangler@4.147.0 rollback <version-id> --message "<why>"

Without a version ID, `rollback` goes back to the version deployed before the current
one. Check the files as above, from a worktree of the commit now live, once `go run .` in its
`scripts/sitedocs` has rendered the docs.
