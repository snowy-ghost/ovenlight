package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// A path from the home folder written with ~ and slashes runs in PowerShell and Git Bash
// alike.
func TestOvenlightCommandFromHome(t *testing.T) {
	exe, _ := os.Executable()
	home, _ := os.UserHomeDir()
	if rel, err := filepath.Rel(home, exe); err != nil || strings.HasPrefix(rel, "..") || !plainPath.MatchString(rel) {
		t.Skip("the test binary isn't under the home folder on a plain path")
	}
	if got := ovenlightCommand(); !strings.HasPrefix(got, "~/") || strings.Contains(got, `\`) {
		t.Errorf("ovenlightCommand() = %q", got)
	}
}
