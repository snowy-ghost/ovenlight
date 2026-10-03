package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"

	"github.com/snowy-ghost/ovenlight/connector/internal/jsonfile"
	"golang.org/x/term"
)

// App is one published local web app: a tailnet node named Slug that proxies to
// 127.0.0.1:Port. A shareable app's node is tagged tag:ovenlight-app-<slug>, so guests can
// reach it; the others belong to the owner and only the owner reaches them.
type App struct {
	Name      string `json:"name"`
	Slug      string `json:"slug"`
	Port      int    `json:"port"`
	Shareable bool   `json:"shareable,omitempty"`
	// Run is a shell command the connector keeps running in Dir, with PORT and HOST set
	// (see supervise.go). An app without one is started some other way. They are kept in
	// the commands file beside the config (see commandsPath), not in it.
	Run string `json:"-"`
	Dir string `json:"-"`

	// owner and ownerName are who check's requests through the app's tailnet name come
	// from (see withOwner). They aren't saved.
	owner, ownerName string
}

// Config is the list of published apps, stored as JSON. The daemon reads it on start
// and on every reload; publish and unpublish edit it. The first publish --shareable
// records the owner.
type Config struct {
	Apps []App `json:"apps"`
	// Owner is the login allowed to use the apps and their admin API. Tagged app nodes
	// have no user of their own, so sharing needs it written down.
	Owner string `json:"owner,omitempty"`
	// OwnerLabel is how invites and guests see the owner, for example "Sam".
	OwnerLabel string `json:"ownerLabel,omitempty"`
}

// defaultConfigPath is the app list: under ~/.config on macOS and Linux (or
// $XDG_CONFIG_HOME), and in the state directory on Windows, which stays on this computer
// when a profile roams.
func defaultConfigPath() string {
	switch runtime.GOOS {
	case "windows":
		return filepath.Join(defaultStateDir(), "config.json")
	case "linux":
		if dir := os.Getenv("XDG_CONFIG_HOME"); dir != "" {
			return filepath.Join(dir, "ovenlight", "config.json")
		}
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".config", "ovenlight", "config.json")
}

// credentialsPath keeps the API credential next to the config, so a test config gets
// its own.
func credentialsPath(configPath string) string {
	return filepath.Join(filepath.Dir(configPath), "credentials.json")
}

// commandsPath keeps the apps' commands beside the config: commands.json beside
// config.json, <name>.commands.json beside any other <name>.json. A connector from before
// --run rewrites the config without the fields it doesn't know, so a command kept in the
// config would go whenever one still running, such as an MCP server started before an
// upgrade, saved it.
func commandsPath(configPath string) string {
	name := strings.TrimSuffix(filepath.Base(configPath), ".json")
	if name == "config" {
		name = ""
	} else {
		name += "."
	}
	return filepath.Join(filepath.Dir(configPath), name+"commands.json")
}

// appCommand is an app's command and the directory it runs in, as the commands file
// keeps them by slug.
type appCommand struct {
	Run string `json:"run"`
	Dir string `json:"dir"`
}

// defaultStateDir holds node state, the control socket and the installed binary.
func defaultStateDir() string {
	home, _ := os.UserHomeDir()
	switch runtime.GOOS {
	case "darwin":
		return filepath.Join(home, "Library", "Application Support", "ovenlight")
	case "windows":
		if dir := os.Getenv("LOCALAPPDATA"); dir != "" {
			return filepath.Join(dir, "ovenlight")
		}
		return filepath.Join(home, "AppData", "Local", "ovenlight")
	}
	if dir := os.Getenv("XDG_STATE_HOME"); dir != "" {
		return filepath.Join(dir, "ovenlight")
	}
	return filepath.Join(home, ".local", "state", "ovenlight")
}

// defaultLogPath is the log the installed connector writes (install.sh and install.ps1
// pass it to run --log).
func defaultLogPath() string {
	if runtime.GOOS == "darwin" {
		home, _ := os.UserHomeDir()
		return filepath.Join(home, "Library", "Logs", "ovenlight.log")
	}
	return filepath.Join(defaultStateDir(), "ovenlight.log")
}

// LoadConfig reads the config. A missing file is an empty config, not an error.
func LoadConfig(path string) (*Config, error) {
	c, _, err := loadConfigFile(path)
	return c, err
}

// loadConfigFile is LoadConfig, also reporting whether the file exists. It drops the
// commands of apps that aren't in the config: a connector from before --run unpublishes
// without touching the commands file, and a command left there would start again, in its
// old folder, whenever any connector published the slug again. It writes only the commands
// file: a read never rewrites the config. The cleanup is best effort, since what it read
// leaves those commands out already: a read works where it can't write, such as a folder
// that isn't writable.
func loadConfigFile(path string) (*Config, bool, error) {
	c, found, stale, err := readConfig(path)
	if err == nil && stale {
		_ = lockConfig(path, func() error {
			c, _, stale, err := readConfig(path)
			if err == nil && stale {
				err = c.saveCommands(path)
			}
			return err
		})
	}
	return c, found, err
}

// readConfig reads the config and its apps' commands, also reporting whether the config
// exists and whether the commands file holds a command for an app the config doesn't.
func readConfig(path string) (c *Config, found, stale bool, err error) {
	data, err := os.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		return &Config{}, false, false, nil
	}
	if err != nil {
		return nil, false, false, err
	}
	c = &Config{}
	if err := json.Unmarshal(data, c); err != nil {
		return nil, true, false, fmt.Errorf("%s: %w", path, err)
	}
	commands := map[string]appCommand{}
	if data, err := os.ReadFile(commandsPath(path)); err == nil {
		if err := json.Unmarshal(data, &commands); err != nil {
			return nil, true, false, fmt.Errorf("%s: %w", commandsPath(path), err)
		}
	} else if !errors.Is(err, fs.ErrNotExist) {
		return nil, true, false, err
	}
	for i := range c.Apps {
		cmd := commands[c.Apps[i].Slug]
		delete(commands, c.Apps[i].Slug)
		c.Apps[i].Run, c.Apps[i].Dir = cmd.Run, cmd.Dir
		if err := c.Apps[i].Validate(); err != nil {
			return nil, true, false, fmt.Errorf("%s: %w", path, err)
		}
	}
	return c, true, len(commands) > 0, nil
}

// Save writes the config and the commands file atomically, readable only by the owner.
// The commands go first, so a save that fails halfway loses none.
func (c *Config) Save(path string) error {
	if c.Apps == nil {
		c.Apps = []App{}
	}
	if err := c.saveCommands(path); err != nil {
		return err
	}
	return jsonfile.Save(path, c)
}

// saveCommands writes the apps' commands to the commands file, or removes it when no app
// has one.
func (c *Config) saveCommands(path string) error {
	commands := map[string]appCommand{}
	for _, app := range c.Apps {
		if app.Run != "" {
			commands[app.Slug] = appCommand{app.Run, app.Dir}
		}
	}
	if len(commands) > 0 {
		return jsonfile.Save(commandsPath(path), commands)
	}
	if err := os.Remove(commandsPath(path)); err != nil && !errors.Is(err, fs.ErrNotExist) {
		return err
	}
	return nil
}

// UpdateConfig loads the config, changes it and saves it under a lock, so two commands
// at once (a terminal and an agent, say) can't save over each other's change.
func UpdateConfig(path string, change func(*Config) error) error {
	return lockConfig(path, func() error {
		c, _, _, err := readConfig(path)
		if err != nil {
			return err
		}
		if err := change(c); err != nil {
			return err
		}
		return c.Save(path)
	})
}

// lockConfig runs f holding the config's lock.
func lockConfig(path string, f func() error) error {
	if err := jsonfile.MkdirPrivate(filepath.Dir(path)); err != nil {
		return err
	}
	lock, err := os.OpenFile(path+".lock", os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return err
	}
	defer lock.Close() // releases the lock
	if err := lockFile(lock, true); err != nil {
		return err
	}
	return f()
}

// fromTerminal reports whether a person runs this command: an agent's shell has no
// terminal on standard input.
func fromTerminal() bool { return term.IsTerminal(int(os.Stdin.Fd())) }

// Upsert adds the app, or replaces the one with the same slug. It reports whether an
// existing app was replaced.
func (c *Config) Upsert(app App) bool {
	for i := range c.Apps {
		if c.Apps[i].Slug == app.Slug {
			c.Apps[i] = app
			return true
		}
	}
	c.Apps = append(c.Apps, app)
	return false
}

// Remove deletes the app with this slug and reports whether it was there.
func (c *Config) Remove(slug string) bool {
	for i := range c.Apps {
		if c.Apps[i].Slug == slug {
			c.Apps = append(c.Apps[:i], c.Apps[i+1:]...)
			return true
		}
	}
	return false
}

// appInfo is an app as outputs show it, so a field added to the config record never
// reaches them unasked.
type appInfo struct {
	Name      string `json:"name"`
	Slug      string `json:"slug"`
	Port      int    `json:"port"`
	Shareable bool   `json:"shareable,omitempty"`
	Run       string `json:"run,omitempty"`
	Dir       string `json:"dir,omitempty"`
}

func (a App) view() appInfo {
	return appInfo{Name: a.Name, Slug: a.Slug, Port: a.Port, Shareable: a.Shareable, Run: a.Run, Dir: a.Dir}
}

func (c *Config) Find(slug string) (App, bool) {
	for _, app := range c.Apps {
		if app.Slug == slug {
			return app, true
		}
	}
	return App{}, false
}

// Published finds the app with the slug, or else returns an error that names the app the
// slug probably meant: one whose name it matches, ignoring case, as after a rename.
func (c *Config) Published(slug string) (App, error) {
	if app, ok := c.Find(slug); ok {
		return app, nil
	}
	err := fmt.Sprintf("no published app has the slug %q", slug)
	if hint := c.publishedAs(slug); hint != "" {
		err += "; " + hint
	}
	return App{}, errors.New(err)
}

// publishedAs says which slug the app s names is published under, such as
// `"Dinner Poll" is published as dinner-vote`, when s is its name or its slug in other
// case, and "" otherwise.
func (c *Config) publishedAs(s string) string {
	want := Slugify(s)
	if want == "" {
		return ""
	}
	for _, app := range c.Apps {
		if app.Slug != s && (Slugify(app.Name) == want || app.Slug == want) {
			return fmt.Sprintf("%q is published as %s", app.Name, app.Slug)
		}
	}
	return ""
}

var slugPattern = regexp.MustCompile(`^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$`)

// Validate checks the slug is a usable DNS label (it becomes the node's MagicDNS name),
// the port is a real TCP port, and a command comes with an absolute directory.
func (a App) Validate() error {
	if strings.TrimSpace(a.Name) == "" {
		return errors.New("app name is empty")
	}
	if !slugPattern.MatchString(a.Slug) {
		return fmt.Errorf("slug %q must be lowercase letters, digits and dashes (a DNS label, at most 63 characters)", a.Slug)
	}
	if a.Port < 1 || a.Port > 65535 {
		return fmt.Errorf("port %d is out of range", a.Port)
	}
	if a.Run != "" && !filepath.IsAbs(a.Dir) {
		return fmt.Errorf("%s runs a command, so it needs an absolute directory to run it in, not %q", a.Slug, a.Dir)
	}
	if a.Run == "" && a.Dir != "" {
		return fmt.Errorf("%s has a directory but no command to run in it", a.Slug)
	}
	return nil
}

// Slugify turns an app name into a hostname: "Interview Coach" becomes "interview-coach".
func Slugify(name string) string {
	var b strings.Builder
	dash := false
	for _, r := range strings.ToLower(name) {
		switch {
		case r >= 'a' && r <= 'z', r >= '0' && r <= '9':
			b.WriteRune(r)
			dash = false
		default:
			if b.Len() > 0 && !dash {
				b.WriteByte('-')
				dash = true
			}
		}
	}
	s := strings.TrimRight(b.String(), "-")
	if len(s) > 63 {
		s = strings.TrimRight(s[:63], "-")
	}
	return s
}
