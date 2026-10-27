// Command sitedocs renders the Markdown docs in docs/ as pages of ovenlight.app, and is
// their link checker: it fails on any relative link or #anchor in them that doesn't
// resolve. wrangler.jsonc runs it before each deploy, and CI on each push. It also fails a
// month before site/.well-known/security.txt expires.
//
// Run it from this directory. It writes site/docs.html, site/docs/ with each doc's page
// and its Markdown, and site/sitemap.xml, all of which git ignores.
package main

import (
	"bytes"
	_ "embed"
	"errors"
	"fmt"
	"html/template"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"strings"
	"time"
	"unicode"

	"github.com/yuin/goldmark"
	"github.com/yuin/goldmark/ast"
	"github.com/yuin/goldmark/extension"
	"github.com/yuin/goldmark/text"
	"github.com/yuin/goldmark/util"
)

const (
	root = "../.."
	repo = "https://github.com/snowy-ghost/ovenlight"
)

// The docs, in the order the index lists them, each with its line there.
var docs = []struct{ name, summary string }{
	{"connector", "Installing and updating the connector, publishing apps, connecting a coding agent, sharing apps, moving to a new computer, uninstalling, troubleshooting and every command."},
	{"always-on", "Your own server for your apps: at home (a Mac mini, a Raspberry Pi or a spare PC) or rented in the cloud."},
	{"apps-you-run", "Apps you already self-host, such as Grafana or Home Assistant, behind Ovenlight, and when guests can sign in by header."},
	{"building-apps", "Building an app that works and feels native in Ovenlight, for coding agents and people."},
	{"app-contract", "What an app behind Ovenlight must do and may rely on."},
	{"security", "The threat model, the tailnet policy change, logging and privacy."},
	{"protocol", "How Ovenlight and the connector talk."},
	{"development", "Building the iPhone and Android apps, tests, local development, shipping and forking."},
}

// The index's shortcuts to sections of the docs, checked like the docs' own links.
var shortcuts = []shortcut{
	{"Connect a coding agent", "connector", "connect-a-coding-agent"},
	{"Share an app", "connector", "share-an-app"},
	{"Who is calling", "building-apps", "who-is-calling"},
	{"Commands", "connector", "commands"},
	{"Troubleshooting", "connector", "troubleshooting"},
}

type shortcut struct{ Text, Doc, Heading string }

var md = goldmark.New(goldmark.WithExtensions(extension.GFM))

type doc struct {
	Name, Summary, Title, ID string
	TOC                      []heading
	Body                     template.HTML
	src                      []byte
	tree                     ast.Node
	ids                      map[string]bool
	// mdFixes points the Markdown copy's links to repository files at GitHub, as the page's
	// are, since the site has only the docs.
	mdFixes []string
}

type heading struct{ ID, Text string }

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "sitedocs:", err)
		os.Exit(1)
	}
}

func run() error {
	if err := checkSecurityTxt(time.Now()); err != nil {
		return err
	}
	byName := map[string]*doc{}
	var all []*doc
	for _, d := range docs {
		src, err := os.ReadFile(filepath.Join(root, "docs", d.name+".md"))
		if err != nil {
			return err
		}
		p := &doc{Name: d.name, Summary: d.summary, src: src, tree: md.Parser().Parse(text.NewReader(src))}
		p.ids = setIDs(p.tree, src)
		// The page shows the title above the doc, and lists its sections beside it.
		title, ok := p.tree.FirstChild().(*ast.Heading)
		if !ok || title.Level != 1 {
			return fmt.Errorf("docs/%s.md doesn't start with a # title", d.name)
		}
		p.Title, p.ID = plainText(title, src), id(title)
		p.tree.RemoveChild(p.tree, title)
		for n := p.tree.FirstChild(); n != nil; n = n.NextSibling() {
			if h, ok := n.(*ast.Heading); ok && h.Level == 2 {
				p.TOC = append(p.TOC, heading{id(h), plainText(h, src)})
			}
		}
		byName[d.name] = p
		all = append(all, p)
	}
	found, err := filepath.Glob(filepath.Join(root, "docs", "*.md"))
	if err != nil {
		return err
	}
	for _, f := range found {
		if byName[strings.TrimSuffix(filepath.Base(f), ".md")] == nil {
			return fmt.Errorf("docs/%s isn't in the list in scripts/sitedocs/main.go", filepath.Base(f))
		}
	}

	var problems []string
	for _, p := range all {
		problems = append(problems, p.links(byName)...)
	}
	for _, s := range shortcuts {
		if d := byName[s.Doc]; d == nil || !d.ids[s.Heading] {
			problems = append(problems, fmt.Sprintf("scripts/sitedocs/main.go: the index's shortcut to %s#%s goes nowhere", s.Doc, s.Heading))
		}
	}
	if len(problems) > 0 {
		for _, s := range problems {
			fmt.Fprintln(os.Stderr, s)
		}
		return fmt.Errorf("%d broken links", len(problems))
	}

	out := filepath.Join(root, "site", "docs")
	if err := os.MkdirAll(out, 0o755); err != nil {
		return err
	}
	for _, p := range all {
		p.anchorHeadings()
		var b bytes.Buffer
		if err := md.Renderer().Render(&b, p.src, p.tree); err != nil {
			return err
		}
		// The tables scroll sideways in a wrapper. Markdown escapes "<" in text and code, and
		// links() refuses raw HTML, so these tags come only from tables. email_off stops
		// Cloudflare from obfuscating the addresses in commands and examples, which would
		// need a script the pages don't allow.
		body := strings.NewReplacer("<table>", `<div class="table"><table>`, "</table>", "</table></div>").Replace(b.String())
		p.Body = template.HTML("<!--email_off-->\n" + body + "<!--/email_off-->")
		if err := write(filepath.Join(out, p.Name+".html"), page{p.Title + " · Ovenlight docs", p.Summary, "/docs/" + p.Name, p, nil, nil}); err != nil {
			return err
		}
		if err := os.WriteFile(filepath.Join(out, p.Name+".md"), []byte(strings.NewReplacer(p.mdFixes...).Replace(string(p.src))), 0o644); err != nil {
			return err
		}
	}
	if err := write(filepath.Join(root, "site", "docs.html"), page{
		"Documentation · Ovenlight",
		"How the Ovenlight connector works, how to build apps for it, and how Ovenlight keeps them private.",
		"/docs", nil, all, shortcuts,
	}); err != nil {
		return err
	}
	// Every page for search engines, the docs included. The join page isn't one: it's an invite's.
	var sitemap strings.Builder
	sitemap.WriteString("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n")
	pages := []string{"/", "/docs", "/support", "/privacy", "/privacy/android"}
	for _, p := range all {
		pages = append(pages, "/docs/"+p.Name)
	}
	for _, p := range pages {
		fmt.Fprintf(&sitemap, "  <url><loc>https://ovenlight.app%s</loc></url>\n", p)
	}
	sitemap.WriteString("</urlset>\n")
	return os.WriteFile(filepath.Join(root, "site", "sitemap.xml"), []byte(sitemap.String()), 0o644)
}

// checkSecurityTxt fails within 30 days of security.txt's Expires, so a deploy or CI's weekly
// run asks for a new date before the file goes stale. RFC 9116 wants it under a year away.
func checkSecurityTxt(now time.Time) error {
	src, err := os.ReadFile(filepath.Join(root, "site", ".well-known", "security.txt"))
	if err != nil {
		return err
	}
	for _, line := range strings.Split(string(src), "\n") {
		if v, ok := strings.CutPrefix(line, "Expires: "); ok {
			t, err := time.Parse(time.RFC3339, v)
			if err != nil {
				return fmt.Errorf("security.txt: %w", err)
			}
			if t.Sub(now) < 30*24*time.Hour {
				return fmt.Errorf("site/.well-known/security.txt expires at %s: move Expires to under a year from now", v)
			}
			return nil
		}
	}
	return errors.New("site/.well-known/security.txt has no Expires line")
}

// setIDs gives each heading the ID GitHub gives it, so that links into the docs work the
// same on the site, and returns the IDs.
func setIDs(tree ast.Node, src []byte) map[string]bool {
	ids := map[string]bool{}
	seen := map[string]int{}
	ast.Walk(tree, func(n ast.Node, entering bool) (ast.WalkStatus, error) {
		if h, ok := n.(*ast.Heading); ok && entering {
			v := slug(plainText(h, src), seen)
			h.SetAttributeString("id", v)
			ids[v] = true
		}
		return ast.WalkContinue, nil
	})
	return ids
}

func id(h *ast.Heading) string {
	v, _ := h.AttributeString("id")
	return v.(string)
}

// anchorHeadings links each heading to itself, as on the support and privacy pages.
func (p *doc) anchorHeadings() {
	ast.Walk(p.tree, func(n ast.Node, entering bool) (ast.WalkStatus, error) {
		h, ok := n.(*ast.Heading)
		if !entering || !ok {
			return ast.WalkContinue, nil
		}
		a := ast.NewLink()
		a.Destination = []byte("#" + id(h))
		for c := h.FirstChild(); c != nil; {
			next := c.NextSibling()
			a.AppendChild(a, c)
			c = next
		}
		h.AppendChild(h, a)
		return ast.WalkSkipChildren, nil
	})
}

// plainText is a heading's text as a browser shows it, which GitHub makes its ID from.
func plainText(n ast.Node, src []byte) string {
	var b strings.Builder
	ast.Walk(n, func(n ast.Node, entering bool) (ast.WalkStatus, error) {
		if !entering {
			return ast.WalkContinue, nil
		}
		switch n := n.(type) {
		case *ast.Text:
			v := n.Value(src)
			if _, code := n.Parent().(*ast.CodeSpan); !code {
				v = util.ResolveEntityNames(util.ResolveNumericReferences(util.UnescapePunctuations(v)))
			}
			b.Write(v)
		case *ast.String:
			b.Write(n.Value)
		}
		return ast.WalkContinue, nil
	})
	return b.String()
}

// slug makes a heading's ID as GitHub does (github-slugger): lowercase, with everything but
// letters, marks, numbers, connector punctuation, spaces and hyphens dropped, spaces turned
// into hyphens, and -1, -2 and so on added to repeats.
func slug(s string, seen map[string]int) string {
	base := strings.Map(func(r rune) rune {
		switch {
		case r == ' ':
			return '-'
		case r == '-' || unicode.In(r, unicode.L, unicode.M, unicode.N, unicode.Pc):
			return r
		}
		return -1
	}, strings.ToLower(s))
	id := base
	for {
		if _, taken := seen[id]; !taken {
			break
		}
		seen[base]++
		id = fmt.Sprintf("%s-%d", base, seen[base])
	}
	seen[id] = 0
	return id
}

// links points each link at the page it goes to on the site, or at the file on GitHub,
// and lists those that go nowhere, and anything else the pages would leave out.
func (p *doc) links(byName map[string]*doc) []string {
	var problems []string
	report := func(n ast.Node, format string, a ...any) {
		line := bytes.Count(p.src[:max(n.Pos(), 0)], []byte("\n")) + 1
		problems = append(problems, fmt.Sprintf("docs/%s.md:%d: %s", p.Name, line, fmt.Sprintf(format, a...)))
	}
	ast.Walk(p.tree, func(n ast.Node, entering bool) (ast.WalkStatus, error) {
		if !entering {
			return ast.WalkContinue, nil
		}
		switch n := n.(type) {
		case *ast.Link:
			href, err := p.resolve(string(n.Destination), byName)
			if err != nil {
				report(n, "%s: %v", n.Destination, err)
			}
			if strings.HasPrefix(href, repo+"/") {
				p.mdFixes = append(p.mdFixes, "]("+string(n.Destination)+")", "]("+href+")")
			}
			n.Destination = []byte(href)
		case *ast.Image:
			report(n, "%s: images aren't published with the docs", n.Destination)
		case *ast.RawHTML, *ast.HTMLBlock:
			report(n, "raw HTML, which the site leaves out")
		}
		return ast.WalkContinue, nil
	})
	return problems
}

func (p *doc) resolve(dest string, byName map[string]*doc) (string, error) {
	u, err := url.Parse(dest)
	if err != nil {
		return "", err
	}
	if u.Scheme != "" || u.Host != "" {
		return dest, nil
	}
	if u.Path == "" {
		if u.Fragment != "" && !p.ids[u.Fragment] {
			return "", errors.New("no such heading")
		}
		return dest, nil
	}
	target := path.Join("docs", u.Path)
	if target == ".." || strings.HasPrefix(target, "../") {
		return "", errors.New("outside the repository")
	}
	info, err := os.Stat(filepath.Join(root, filepath.FromSlash(target)))
	if err != nil {
		return "", errors.New("no such file")
	}
	fragment := ""
	if u.Fragment != "" {
		fragment = "#" + u.EscapedFragment()
	}
	if name, ok := strings.CutSuffix(strings.TrimPrefix(target, "docs/"), ".md"); ok && byName[name] != nil {
		if u.Fragment != "" && !byName[name].ids[u.Fragment] {
			return "", errors.New("no such heading")
		}
		return "/docs/" + name + fragment, nil
	}
	if u.Fragment != "" && strings.HasSuffix(target, ".md") {
		ids, err := headingIDs(target)
		if err != nil {
			return "", err
		}
		if !ids[u.Fragment] {
			return "", errors.New("no such heading")
		}
	}
	kind := "blob"
	if info.IsDir() {
		kind = "tree"
	}
	return repo + "/" + kind + "/master/" + (&url.URL{Path: target}).EscapedPath() + fragment, nil
}

// headingIDs reads the heading IDs of another Markdown file in the repository, such as
// README.md.
func headingIDs(file string) (map[string]bool, error) {
	src, err := os.ReadFile(filepath.Join(root, filepath.FromSlash(file)))
	if err != nil {
		return nil, err
	}
	return setIDs(md.Parser().Parse(text.NewReader(src)), src), nil
}

// Source is the doc on GitHub.
func (p *doc) Source() string { return repo + "/blob/master/docs/" + p.Name + ".md" }

// page is a doc's page, or the index when Doc is nil.
type page struct {
	Title, Description, Path string
	Doc                      *doc
	Docs                     []*doc
	Shortcuts                []shortcut
}

func write(file string, p page) error {
	var b bytes.Buffer
	if err := layout.Execute(&b, p); err != nil {
		return err
	}
	return os.WriteFile(file, b.Bytes(), 0o644)
}

//go:embed layout.html
var layoutHTML string

var layout = template.Must(template.New("").Parse(layoutHTML))
