// Package jsonfile saves state files so that a crash or power loss leaves either the
// old file or the new one, never a partial one.
package jsonfile

import (
	"encoding/json"
	"os"
	"path/filepath"
)

// Save writes v as indented JSON to path, readable only by the owner: a temporary file
// in the same directory is written, flushed to disk and renamed over path.
func Save(path string, v any) error {
	data, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return err
	}
	dir := filepath.Dir(path)
	if err := MkdirPrivate(dir); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, "."+filepath.Base(path)+"-*") // created 0600
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	_, err = tmp.Write(append(data, '\n'))
	if err == nil {
		err = tmp.Sync() // F_FULLFSYNC on macOS
	}
	if cerr := tmp.Close(); err == nil {
		err = cerr
	}
	if err == nil {
		err = restrict(tmp.Name())
	}
	if err != nil {
		return err
	}
	return rename(tmp.Name(), path)
}
