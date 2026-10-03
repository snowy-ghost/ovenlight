package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"golang.org/x/net/html"
)

// SiteManifest is what the connector serves at /.well-known/ovenlight.json so Ovenlight
// can list the app with its name, icon and color.
type SiteManifest struct {
	Name       string  `json:"name"`
	Slug       string  `json:"slug"`
	Icon       *string `json:"icon"`       // path on the app's own origin, or null
	ThemeColor *string `json:"themeColor"` // CSS color, or null
	Version    int     `json:"version"`
}

const siteManifestPath = "/.well-known/ovenlight.json"

// pageInfo is what the app's home page says about itself.
type pageInfo struct {
	Manifest   string // href of <link rel="manifest">
	TouchIcon  string // href of <link rel="apple-touch-icon">
	ThemeColor string // first <meta name="theme-color">
}

func parsePage(r io.Reader) pageInfo {
	var info pageInfo
	z := html.NewTokenizer(r)
	for {
		switch z.Next() {
		case html.ErrorToken:
			return info
		case html.StartTagToken, html.SelfClosingTagToken:
			name, hasAttr := z.TagName()
			if !hasAttr {
				continue
			}
			attrs := tagAttrs(z)
			switch string(name) {
			case "link":
				rels := strings.Fields(strings.ToLower(attrs["rel"]))
				href := strings.TrimSpace(attrs["href"])
				if href == "" {
					continue
				}
				for _, rel := range rels {
					if rel == "manifest" && info.Manifest == "" {
						info.Manifest = href
					}
					if rel == "apple-touch-icon" && info.TouchIcon == "" {
						info.TouchIcon = href
					}
				}
			case "meta":
				if strings.EqualFold(attrs["name"], "theme-color") && info.ThemeColor == "" {
					info.ThemeColor = strings.TrimSpace(attrs["content"])
				}
			}
		}
	}
}

// tagAttrs reads the current tag's attributes; of a repeated one, the first counts.
func tagAttrs(z *html.Tokenizer) map[string]string {
	attrs := map[string]string{}
	for {
		k, v, more := z.TagAttr()
		key := string(k)
		if _, seen := attrs[key]; !seen {
			attrs[key] = string(v)
		}
		if !more {
			return attrs
		}
	}
}

type webManifest struct {
	ThemeColor string         `json:"theme_color"`
	Icons      []manifestIcon `json:"icons"`
}

type manifestIcon struct {
	Src   string `json:"src"`
	Sizes string `json:"sizes"`
	Type  string `json:"type"`
}

// bestIcon picks the raster icon closest to 512 px, the larger of two as close (a
// smaller one looks blurry, a bigger one only costs download), and skips SVG, which iOS
// can't use as an image.
func (m webManifest) bestIcon() string {
	best, bestScore := "", math.MinInt
	for _, icon := range m.Icons {
		src := strings.TrimSpace(icon.Src)
		if src == "" || strings.Contains(icon.Type, "svg") || strings.HasSuffix(strings.ToLower(strings.SplitN(src, "?", 2)[0]), ".svg") {
			continue
		}
		size := largestSize(icon.Sizes)
		score := -2 * max(size-512, 512-size)
		if size >= 512 {
			score++
		}
		if score > bestScore {
			best, bestScore = src, score
		}
	}
	return best
}

func largestSize(sizes string) int {
	largest := 0
	for _, token := range strings.Fields(strings.ToLower(sizes)) {
		w, _, ok := strings.Cut(token, "x")
		if !ok {
			continue
		}
		if n, err := strconv.Atoi(w); err == nil && n > largest {
			largest = n
		}
	}
	return largest
}

// sameOriginPath resolves ref against base and returns its path and query, but only
// when it stays on base's origin: Ovenlight fetches the icon from the app's tailnet host,
// so an icon hosted elsewhere can't be proxied.
func sameOriginPath(base *url.URL, ref string) (string, bool) {
	u, err := base.Parse(ref)
	if err != nil || u.Scheme != base.Scheme || u.Host != base.Host {
		return "", false
	}
	p := u.EscapedPath()
	if p == "" {
		p = "/"
	}
	if u.RawQuery != "" {
		p += "?" + u.RawQuery
	}
	return p, true
}

// buildSiteManifest combines the page and its web manifest (nil when there is none).
func buildSiteManifest(app App, pageURL *url.URL, page pageInfo, manifestURL *url.URL, manifest *webManifest) SiteManifest {
	out := SiteManifest{Name: app.Name, Slug: app.Slug, Version: 1}
	theme := page.ThemeColor
	if manifest != nil {
		if manifest.ThemeColor != "" {
			theme = manifest.ThemeColor
		}
		if src := manifest.bestIcon(); src != "" {
			if p, ok := sameOriginPath(manifestURL, src); ok {
				out.Icon = &p
			}
		}
	}
	if out.Icon == nil && page.TouchIcon != "" {
		if p, ok := sameOriginPath(pageURL, page.TouchIcon); ok {
			out.Icon = &p
		}
	}
	if theme != "" {
		out.ThemeColor = &theme
	}
	return out
}

const maxMetaBody = 1 << 20

// fetchSiteManifest reads the app's home page and web manifest from upstream. It
// returns an error only when the home page itself can't be read.
func fetchSiteManifest(ctx context.Context, client *http.Client, upstream *url.URL, app App) (SiteManifest, error) {
	d, err := discover(ctx, client, upstream)
	if err != nil {
		return SiteManifest{Name: app.Name, Slug: app.Slug, Version: 1}, err
	}
	return buildSiteManifest(app, d.pageURL, d.page, d.manifestURL, d.manifest), nil
}

// discovery is what the connector reads about an app: its home page, and the web
// manifest that page links when it can be used.
type discovery struct {
	pageURL     *url.URL
	page        pageInfo
	manifestURL *url.URL // nil without a usable manifest
	manifest    *webManifest
	manifestErr error // why a linked manifest went unused
}

// discover reads the home page and its web manifest from upstream. It returns an error
// only when the home page itself can't be read.
func discover(ctx context.Context, client *http.Client, upstream *url.URL) (*discovery, error) {
	d := &discovery{pageURL: upstream.ResolveReference(&url.URL{Path: "/"})}
	body, err := fetch(ctx, client, d.pageURL)
	if err != nil {
		return nil, err
	}
	d.page = parsePage(bytes.NewReader(body))
	if d.page.Manifest == "" {
		return d, nil
	}
	u, err := d.pageURL.Parse(d.page.Manifest)
	if err != nil || u.Host != d.pageURL.Host {
		d.manifestErr = fmt.Errorf("its link, %s, isn't a relative URL", d.page.Manifest)
		return d, nil
	}
	data, err := fetch(ctx, client, u)
	if err != nil {
		d.manifestErr = err
		return d, nil
	}
	var m webManifest
	if err := json.Unmarshal(data, &m); err != nil {
		d.manifestErr = fmt.Errorf("%s isn't JSON: %v", u.Path, err)
		return d, nil
	}
	d.manifest, d.manifestURL = &m, u
	return d, nil
}

func fetch(ctx context.Context, client *http.Client, u *url.URL) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return nil, err
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return nil, fmt.Errorf("GET %s: %s", u.Path, resp.Status)
	}
	return io.ReadAll(io.LimitReader(resp.Body, maxMetaBody))
}
