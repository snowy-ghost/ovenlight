//go:build !windows

package main

import (
	"os"
	"time"
)

// sinceBoot is only read on Windows, and set by the tests of it.
var sinceBoot func() time.Duration

// renamer is a file to rename. Only Windows keeps a file that something holds from being
// renamed, so here it's just the path, for the tests of Windows' renames.
type renamer struct{ path string }

func holdForRename(path string, _ time.Duration) (*renamer, error) {
	if _, err := os.Lstat(path); err != nil {
		return nil, err
	}
	return &renamer{path}, nil
}

func (r *renamer) rename(to string) error {
	if err := os.Rename(r.path, to); err != nil {
		return err
	}
	r.path = to
	return nil
}

func (r *renamer) Close() error { return nil }
