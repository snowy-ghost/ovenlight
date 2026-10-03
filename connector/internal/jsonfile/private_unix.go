//go:build !windows

package jsonfile

import (
	"fmt"
	"os"
)

// MkdirPrivate makes dir and any missing parents, enterable only by the owner.
func MkdirPrivate(dir string) error { return os.MkdirAll(dir, 0o700) }

// CheckPrivate refuses a file other users can read.
func CheckPrivate(path string) error {
	info, err := os.Stat(path)
	if err != nil {
		return err
	}
	if info.Mode().Perm()&0o077 != 0 {
		return fmt.Errorf("%s is readable by other users (mode %v)", path, info.Mode().Perm())
	}
	return nil
}

// restrict leaves the file as os.CreateTemp made it, mode 0600.
func restrict(string) error { return nil }

func rename(from, to string) error { return os.Rename(from, to) }

func remove(path string) error { return os.Remove(path) }

func readFile(path string) ([]byte, error) { return os.ReadFile(path) }
