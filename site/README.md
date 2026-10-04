# ovenlight.app

The Ovenlight website: the home, support, privacy and invite (`/join`) pages, as a
static site served by Cloudflare (Workers static assets, configured in the repo root's
`wrangler.jsonc`, which also attaches the ovenlight.app domain).

`.assetsignore` keeps this README off the site. `llms.txt` tells coding agents what
Ovenlight is and how to use it, served as `text/plain`; it points to `ovenlight guide`
rather than repeating docs/building-apps.md.

The home, support and privacy pages share `style.css`; the join page keeps its styles
inline, since its Content-Security-Policy loads nothing but `join.js` and same-origin
images. Screenshots in `images/` come in WebP with a JPEG fallback, at 480 and 960 px wide. The link
preview image, `og-invite.jpg`, is written by `design/og/generate.py`.

`.well-known/apple-app-site-association` makes invite links open the app. iOS
accepts it only when it is served directly, with no redirect, as
`application/json`; `_headers` sets the content type.

`.well-known/security.txt` says where to report a security problem (RFC 9116);
`_headers` adds the charset the RFC requires. Renew its `Expires` date before it passes.

## Deploying

Deploy origin/master from a worktree of its own, not from a checkout someone may be
editing. Use the pinned wrangler (signed in with `npx wrangler@4.147.0 login`), and put
the commit in the version's message:

    git fetch origin
    git worktree add --detach /tmp/ovenlight-site origin/master
    cd /tmp/ovenlight-site
    npx wrangler@4.147.0 deploy --message "$(git rev-parse HEAD)"

Then fetch every file back and compare it with the commit, asking as a browser does
(`Accept: text/html`), since Cloudflare can inject scripts into the pages browsers get.
Nothing should print:

    cd site
    git ls-files | grep -vxF -e README.md -e _headers -e .assetsignore | while read -r f; do
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
one. Check the files as above, from a worktree of the commit now live.
