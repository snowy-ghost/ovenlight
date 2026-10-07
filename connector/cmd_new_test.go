package main

import (
	"encoding/json"
	"image/png"
	"io/fs"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

func TestWriteStarterFillsInTheApp(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "zoes-list")
	app := starterApp{Name: `Zoë's <List> "Q"`, Slug: "zoes-list", Port: 23456}
	if err := writeStarter(dir, app); err != nil {
		t.Fatal(err)
	}
	read := func(name string) string {
		data, err := os.ReadFile(filepath.Join(dir, name))
		if err != nil {
			t.Fatal(err)
		}
		return string(data)
	}

	filepath.WalkDir(dir, func(name string, d fs.DirEntry, err error) error {
		if err != nil {
			t.Fatal(err)
		}
		if data, _ := os.ReadFile(name); filepath.Ext(name) != ".png" && strings.Contains(string(data), "{{") {
			t.Errorf("%s keeps a placeholder", name)
		}
		return nil
	})
	if got := read(".gitignore"); !strings.Contains(got, "data/") {
		t.Errorf(".gitignore = %q", got)
	}
	if got := read("public/index.html"); !strings.Contains(got, "<title>Zoë&#39;s &lt;List&gt; &#34;Q&#34;</title>") {
		t.Errorf("index.html title not escaped:\n%s", got)
	}
	var manifest struct {
		Name  string `json:"name"`
		Icons []struct {
			Src string `json:"src"`
		} `json:"icons"`
	}
	if err := json.Unmarshal([]byte(read("public/manifest.webmanifest")), &manifest); err != nil || manifest.Name != app.Name {
		t.Errorf("manifest name = %q, %v", manifest.Name, err)
	}
	var pkg struct {
		Name    string            `json:"name"`
		Scripts map[string]string `json:"scripts"`
	}
	if err := json.Unmarshal([]byte(read("package.json")), &pkg); err != nil || pkg.Name != "zoes-list" || pkg.Scripts["start"] != "node server.js" {
		t.Errorf("package.json = %+v, %v", pkg, err)
	}
	if got := read("server.js"); !strings.Contains(got, "Number(process.env.PORT) || 23456;") {
		t.Errorf("server.js lacks the port")
	}
	if got := read("README.md"); !strings.Contains(got, `ovenlight publish --port 23456 --name 'Zoë'\''s <List> "Q"' --run 'npm start'`) {
		t.Errorf("README lacks the publish command:\n%s", got)
	}

	// The manifest's icon and the apple-touch-icon exist, at their sizes.
	for name, size := range map[string]int{manifest.Icons[0].Src: 512, "apple-touch-icon.png": 180} {
		f, err := os.Open(filepath.Join(dir, "public", name))
		if err != nil {
			t.Fatal(err)
		}
		cfg, err := png.DecodeConfig(f)
		f.Close()
		if err != nil || cfg.Width != size || cfg.Height != size {
			t.Errorf("%s is %dx%d (%v), want %d", name, cfg.Width, cfg.Height, err, size)
		}
	}
}

func TestWriteStarterRefusesANonEmptyFolder(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "notes.txt"), []byte("mine"), 0o644); err != nil {
		t.Fatal(err)
	}
	err := writeStarter(dir, starterApp{Name: "List", Slug: "list", Port: 20001})
	if err == nil || !strings.Contains(err.Error(), "isn't empty") {
		t.Fatalf("err = %v", err)
	}
	if entries, _ := os.ReadDir(dir); len(entries) != 1 {
		t.Errorf("wrote into it: %d entries", len(entries))
	}
	// An empty folder is fine.
	if err := writeStarter(t.TempDir(), starterApp{Name: "List", Slug: "list", Port: 20001}); err != nil {
		t.Errorf("empty folder: %v", err)
	}
}

func TestStarterPortSkipsTakenPorts(t *testing.T) {
	port := starterPort("family-list", nil)
	if port < 20000 || port > 29999 {
		t.Fatalf("port %d is out of range", port)
	}
	if again := starterPort("family-list", map[int]bool{port: true}); again == port {
		t.Errorf("chose taken port %d", port)
	}
}

func TestShellQuote(t *testing.T) {
	for in, want := range map[string]string{
		"Family List": `"Family List"`,
		`Say "hi"`:    `'Say "hi"'`,
		"It's $5!":    `'It'\''s $5!'`,
	} {
		if got := shellQuote(in); got != want {
			t.Errorf("shellQuote(%q) = %s, want %s", in, got, want)
		}
	}
}

// A shell that leaves ~ as typed, such as Windows PowerShell 5.1, still gets the home folder.
func TestExpandHome(t *testing.T) {
	home, err := os.UserHomeDir()
	if err != nil {
		t.Skip(err)
	}
	for in, want := range map[string]string{"~": home, "~/src": filepath.Join(home, "src"), "src": "src", "~src": "~src"} {
		if got := expandHome(in); got != want {
			t.Errorf("expandHome(%q) = %q, want %q", in, got, want)
		}
	}
}

// A name after --dir's value is taken when it is clearly whole: quoted, with a space, or
// after a folder that exists.
func TestNewTakesTheNameAfterAFolder(t *testing.T) {
	t.Setenv("HOME", t.TempDir())
	dir := t.TempDir()
	paths := []string{"--config", filepath.Join(dir, "config.json"), "--state", filepath.Join(dir, "state")}
	src := filepath.Join(dir, "src")
	os.Mkdir(src, 0o700)
	for name, slug := range map[string]string{"Family Notes": "family-notes", "Todo": "todo"} {
		stdout := os.Stdout
		f, _ := os.Create(filepath.Join(dir, "out"))
		os.Stdout = f
		err := cmdNew(slices.Concat([]string{"--dir", src, name}, paths))
		os.Stdout = stdout
		f.Close()
		if err != nil {
			t.Errorf("new --dir %s %q: %v", src, name, err)
		}
		if _, err := os.Stat(filepath.Join(src, slug, "package.json")); err != nil {
			t.Errorf("%q: %v", name, err)
		}
		// The commands it prints are for the same config and state, and this binary.
		flags := " --config " + shellQuote(paths[1]) + " --state " + shellQuote(paths[3]) + " "
		if out, _ := os.ReadFile(f.Name()); !strings.Contains(string(out), ovenlightCommand()+" publish"+flags+"--port ") || !strings.Contains(string(out), ovenlightCommand()+" check"+flags+slug+"\n") {
			t.Errorf("%q printed:\n%s", name, out)
		}
	}
}
