package stbridge

import (
	"archive/zip"
	"bytes"
	"testing"

	"github.com/syncthing/syncthing/lib/config"
)

// Malformed or hostile preferences never panic, and what is accepted holds
// only the value types the reader promises.
func FuzzReadJavaPrefs(f *testing.F) {
	for _, name := range []string{"prefs-typed.dat", "prefs-full.dat", "prefs-minimal.dat"} {
		f.Add(golden(f, name))
	}
	f.Fuzz(func(t *testing.T, b []byte) {
		m, err := readJavaPrefs(b)
		if err != nil {
			return
		}
		for k, v := range m {
			switch v.(type) {
			case nil, string, bool, int32, int64, float32, []string:
			default:
				t.Fatalf("%q has type %T", k, v)
			}
		}
	})
}

// Any zip, encrypted or not, is read without panics or runaway memory, and
// what is read passes the import policy.
func FuzzReadForkBackup(f *testing.F) {
	fx := newBackup(f, 37, false, fixtureFolder{"f", "F", "/storage/emulated/0/fuzz", "sendreceive"})
	f.Add(fx.zip(f, ""), "")
	f.Add(fx.zip(f, "pw"), "pw")
	hostile := hostileBackup(f, "/storage/emulated/0")
	f.Add(hostile.zip(f, ""), "")
	f.Add(golden(f, "aes256-ae2.zip"), "test-password")
	f.Add(golden(f, "aes128-ae1.zip"), "test-password")
	f.Fuzz(func(t *testing.T, b []byte, password string) {
		if bk, err := read(b, password); err == nil {
			bk.preview(func(c config.FolderConfiguration) (bool, bool) { return false, false })
			if err := checkPolicy(bk.cfg); err != nil {
				t.Fatal(err)
			}
		}
		// Every entry, not only the four the import reads.
		if z, err := zip.NewReader(bytes.NewReader(b), int64(len(b))); err == nil {
			for _, f := range z.File {
				readZipEntry(f, password, 1<<20)
			}
		}
	})
}
