package jsonfile

import (
	"errors"
	"fmt"
	"os"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

// Windows ignores Unix modes. A private file or directory here has a protected access
// list (none inherited from its parent) that grants the current user alone.

// fileAllAccess is FILE_ALL_ACCESS.
const fileAllAccess = 0x1f01ff

// readRights are the access bits that let someone read a file's contents, or give
// themselves that.
const readRights = windows.FILE_READ_DATA | windows.GENERIC_READ | windows.GENERIC_ALL | windows.WRITE_DAC | windows.WRITE_OWNER

func currentUser() (*windows.SID, error) {
	u, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		return nil, err
	}
	return u.User.Sid.Copy()
}

// ownerOnly replaces path's access list with one entry for the current user, inherited
// as given (a directory passes it on to what it holds).
func ownerOnly(path string, inheritance uint32) error {
	me, err := currentUser()
	if err != nil {
		return err
	}
	acl, err := windows.ACLFromEntries([]windows.EXPLICIT_ACCESS{{
		AccessPermissions: fileAllAccess,
		AccessMode:        windows.GRANT_ACCESS,
		Inheritance:       inheritance,
		Trustee: windows.TRUSTEE{
			TrusteeForm:  windows.TRUSTEE_IS_SID,
			TrusteeType:  windows.TRUSTEE_IS_USER,
			TrusteeValue: windows.TrusteeValueFromSID(me),
		},
	}}, nil)
	if err != nil {
		return err
	}
	return windows.SetNamedSecurityInfo(path, windows.SE_FILE_OBJECT,
		windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION, nil, nil, acl, nil)
}

// MkdirPrivate makes dir and any missing parents. A directory it makes grants only the
// current user, and passes that on to what it holds; one that exists is left alone.
func MkdirPrivate(dir string) error {
	if _, err := os.Stat(dir); err == nil {
		return nil
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	return ownerOnly(dir, windows.SUB_CONTAINERS_AND_OBJECTS_INHERIT)
}

// CheckPrivate refuses a file anyone but the current user, SYSTEM and the
// Administrators group can read, the Windows counterparts of the owner and root.
func CheckPrivate(path string) error {
	sd, err := windows.GetNamedSecurityInfo(path, windows.SE_FILE_OBJECT, windows.DACL_SECURITY_INFORMATION)
	if err != nil {
		return err
	}
	dacl, _, err := sd.DACL()
	if errors.Is(err, windows.ERROR_OBJECT_NOT_FOUND) || (err == nil && dacl == nil) {
		return fmt.Errorf("%s has no access list, so anyone can read it", path)
	}
	if err != nil {
		return err
	}
	me, err := currentUser()
	if err != nil {
		return err
	}
	for i := range uint32(dacl.AceCount) {
		var ace *windows.ACCESS_ALLOWED_ACE
		if err := windows.GetAce(dacl, i, &ace); err != nil {
			return err
		}
		if ace.Header.AceType != windows.ACCESS_ALLOWED_ACE_TYPE || ace.Header.AceFlags&windows.INHERIT_ONLY_ACE != 0 || ace.Mask&readRights == 0 {
			continue
		}
		sid := (*windows.SID)(unsafe.Pointer(&ace.SidStart))
		if sid.Equals(me) || sid.IsWellKnown(windows.WinLocalSystemSid) || sid.IsWellKnown(windows.WinBuiltinAdministratorsSid) {
			continue
		}
		who := sid.String()
		if account, domain, _, err := sid.LookupAccount(""); err == nil {
			who = account
			if domain != "" {
				who = domain + `\` + account
			}
		}
		return fmt.Errorf("%s is readable by %s", path, who)
	}
	return nil
}

// restrict makes a file just written readable only by the current user, in place of
// the access list it inherited from its directory.
func restrict(path string) error { return ownerOnly(path, windows.NO_INHERITANCE) }

// rename retries for a moment, since Windows refuses to replace a file another process
// has open, as a reader or a virus scanner may for an instant.
func rename(from, to string) error {
	var err error
	for range 20 {
		err = os.Rename(from, to)
		if !errors.Is(err, windows.ERROR_ACCESS_DENIED) && !errors.Is(err, windows.ERROR_SHARING_VIOLATION) {
			return err
		}
		time.Sleep(50 * time.Millisecond)
	}
	return err
}
