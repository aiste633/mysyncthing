package stbridge

import (
	"archive/zip"
	"bytes"
	"testing"
	"time"

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

// Any zip, encrypted or not, or sealed file, is read without panics or
// runaway memory, and what is read passes the import policy.
func FuzzReadForkBackup(f *testing.F) {
	fx := newBackup(f, 37, false, fixtureFolder{"f", "F", "/storage/emulated/0/fuzz", "sendreceive"})
	f.Add(fx.zip(f, ""), "")
	f.Add(fx.zip(f, "pw"), "pw")
	hostile := hostileBackup(f, "/storage/emulated/0")
	f.Add(hostile.zip(f, ""), "")
	f.Add(golden(f, "aes256-ae2.zip"), "test-password")
	f.Add(golden(f, "aes128-ae1.zip"), "test-password")
	// The app's own backup, with devices' own sync windows.
	home, _ := exportHome(f)
	var own bytes.Buffer
	if err := writeBackup(&own, home, ExportPlain, "", settingsJSON(f, someSettings), time.Date(2026, 11, 2, 18, 0, 0, 0, time.UTC)); err != nil {
		f.Fatal(err)
	}
	f.Add(own.Bytes(), "")
	// The strong format, sealed with the cheap stand-in key derivation.
	cheapSealKey(f)
	own.Reset()
	if err := writeBackup(&own, home, ExportStrong, "fuzz-password", settingsJSON(f, someSettings), time.Date(2026, 11, 5, 18, 0, 0, 0, time.UTC)); err != nil {
		f.Fatal(err)
	}
	f.Add(own.Bytes(), "fuzz-password")
	f.Fuzz(func(t *testing.T, b []byte, password string) {
		if bk, err := read(b, password); err == nil {
			p := bk.preview(func(c config.FolderConfiguration) (bool, bool) { return false, false })
			// Own windows only for devices the import pairs with.
			paired := map[string]bool{}
			for _, d := range p.Devices {
				paired[d.ID] = true
			}
			for id := range p.Conditions.DeviceWindows {
				if !paired[id] {
					t.Fatalf("own windows for %s, which the backup does not pair with", id)
				}
			}
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
