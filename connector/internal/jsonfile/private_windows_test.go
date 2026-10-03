package jsonfile

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"

	"golang.org/x/sys/windows"
)

// shareWithEveryone adds an entry that lets Everyone read path.
func shareWithEveryone(t *testing.T, path string) {
	everyone, err := windows.CreateWellKnownSid(windows.WinWorldSid)
	if err != nil {
		t.Fatal(err)
	}
	sd, err := windows.GetNamedSecurityInfo(path, windows.SE_FILE_OBJECT, windows.DACL_SECURITY_INFORMATION)
	if err != nil {
		t.Fatal(err)
	}
	old, _, err := sd.DACL()
	if err != nil {
		t.Fatal(err)
	}
	acl, err := windows.ACLFromEntries([]windows.EXPLICIT_ACCESS{{
		AccessPermissions: windows.GENERIC_READ,
		AccessMode:        windows.GRANT_ACCESS,
		Trustee: windows.TRUSTEE{
			TrusteeForm:  windows.TRUSTEE_IS_SID,
			TrusteeType:  windows.TRUSTEE_IS_WELL_KNOWN_GROUP,
			TrusteeValue: windows.TrusteeValueFromSID(everyone),
		},
	}}, old)
	if err != nil {
		t.Fatal(err)
	}
	if err := windows.SetNamedSecurityInfo(path, windows.SE_FILE_OBJECT, windows.DACL_SECURITY_INFORMATION, nil, nil, acl, nil); err != nil {
		t.Fatal(err)
	}
}

// While another process replaces a file, Windows refuses to open it; ReadFile waits.
// A replace holds the file with delete access, which a plain open doesn't share.
func TestReadFileWaitsForAReplace(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	if err := Save(path, map[string]string{"k": "v"}); err != nil {
		t.Fatal(err)
	}
	name, err := windows.UTF16PtrFromString(path)
	if err != nil {
		t.Fatal(err)
	}
	h, err := windows.CreateFile(name, windows.DELETE, windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE|windows.FILE_SHARE_DELETE, nil, windows.OPEN_EXISTING, windows.FILE_ATTRIBUTE_NORMAL, 0)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.ReadFile(path); !errors.Is(err, windows.ERROR_SHARING_VIOLATION) {
		windows.CloseHandle(h)
		t.Fatalf("a plain read of a file being replaced: %v", err)
	}
	time.AfterFunc(200*time.Millisecond, func() { windows.CloseHandle(h) })
	if data, err := ReadFile(path); err != nil || string(data) != "{\n  \"k\": \"v\"\n}\n" {
		t.Fatalf("ReadFile: %q, %v", data, err)
	}
}

// While another process reads a file, Windows refuses to delete it; Remove waits.
func TestRemoveWaitsForAReader(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	if err := Save(path, map[string]string{"k": "v"}); err != nil {
		t.Fatal(err)
	}
	f, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(path); !errors.Is(err, windows.ERROR_SHARING_VIOLATION) {
		f.Close()
		t.Fatalf("a plain remove of an open file: %v", err)
	}
	time.AfterFunc(200*time.Millisecond, func() { f.Close() })
	if err := Remove(path); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(path); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("after Remove: %v", err)
	}
}
