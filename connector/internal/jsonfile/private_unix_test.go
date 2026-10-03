//go:build !windows

package jsonfile

import (
	"os"
	"testing"
)

func shareWithEveryone(t *testing.T, path string) {
	if err := os.Chmod(path, 0o644); err != nil {
		t.Fatal(err)
	}
}
