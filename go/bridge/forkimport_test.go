package stbridge

import (
	"archive/zip"
	"bytes"
	"compress/flate"
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/hmac"
	"crypto/pbkdf2"
	"crypto/rand"
	"crypto/sha1" //nolint:gosec // WinZip AES
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"slices"
	"strings"
	"testing"
	"testing/quick"
	"text/template"
	"time"

	"github.com/syncthing/syncthing/lib/config"
	"github.com/syncthing/syncthing/lib/protocol"
)

// The fixtures are synthetic: identities are made per test, the peer's ID
// comes from a fixed seed (the same as in testdata/fork/GenForkFixtures.java),
// and testdata/fork holds only zip4j/ObjectOutputStream output without keys.

var fixturePeer = protocol.NewDeviceID([]byte("fork-fixture-peer"))

type identity struct {
	certPEM, keyPEM []byte
	id              protocol.DeviceID
}

// newIdentity makes a Syncthing-style device certificate: Ed25519 like
// Syncthing 2.1, or ECDSA P-384 in a SEC 1 key file like older versions.
func newIdentity(t testing.TB, ed bool) identity {
	t.Helper()
	var pub, priv any
	var keyBlock *pem.Block
	if ed {
		p, k, err := ed25519.GenerateKey(rand.Reader)
		if err != nil {
			t.Fatal(err)
		}
		der, _ := x509.MarshalPKCS8PrivateKey(k)
		pub, priv, keyBlock = p, k, &pem.Block{Type: "PRIVATE KEY", Bytes: der}
	} else {
		k, err := ecdsa.GenerateKey(elliptic.P384(), rand.Reader)
		if err != nil {
			t.Fatal(err)
		}
		der, _ := x509.MarshalECPrivateKey(k)
		pub, priv, keyBlock = k.Public(), k, &pem.Block{Type: "EC PRIVATE KEY", Bytes: der}
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "syncthing", Organization: []string{"Syncthing"}},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(24 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, pub, priv)
	if err != nil {
		t.Fatal(err)
	}
	return identity{
		certPEM: pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}),
		keyPEM:  pem.EncodeToMemory(keyBlock),
		id:      protocol.NewDeviceID(der),
	}
}

type fixtureFolder struct{ ID, Label, Path, Type string }

type fixtureConfig struct {
	Version  int
	Self     protocol.DeviceID
	SelfName string
	Peer     protocol.DeviceID
	Folders  []fixtureFolder
	Paused   bool // every folder and device, as after a run condition paused syncing
}

// A Fork config.xml as the backups have it, trimmed to what matters; the core
// fills in the rest. Version 37 carries settings that later versions dropped.
var configTmpl = template.Must(template.New("config").Parse(`<configuration version="{{.Version}}">
{{- range .Folders}}
    <folder id="{{.ID}}" label="{{.Label}}" path="{{.Path}}" type="{{.Type}}" rescanIntervalS="3600" fsWatcherEnabled="false" ignorePerms="true" autoNormalize="true">
        <filesystemType>basic</filesystemType>
        <device id="{{$.Self}}" introducedBy=""></device>
        <device id="{{$.Peer}}" introducedBy=""></device>
        <versioning type="trashcan"><param key="cleanoutDays" val="14"></param></versioning>
        <markerName>.stfolder</markerName>
        {{- if $.Paused}}
        <paused>true</paused>
        {{- end}}
        {{- if eq $.Version 37}}
        <weakHashThresholdPct>25</weakHashThresholdPct>
        <disableTempIndexes>false</disableTempIndexes>
        {{- end}}
    </folder>
{{- end}}
    <device id="{{.Self}}" name="{{.SelfName}}" compression="metadata" introducer="false">
        <address>dynamic</address>
    </device>
    <device id="{{.Peer}}" name="Example peer" compression="metadata" introducer="false">
        <address>tcp://192.0.2.10:22000</address>
        <autoAcceptFolders>true</autoAcceptFolders>
        {{- if .Paused}}
        <paused>true</paused>
        {{- end}}
    </device>
    <gui enabled="true" tls="true">
        <address>127.0.0.1:8384</address>
        <user>fork-user</user>
        <password>$2a$04$abcdefghijklmnopqrstuuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ</password>
        <apikey>fork-api-key-not-for-us</apikey>
    </gui>
    <options>
        <listenAddress>tcp://127.0.0.1:0</listenAddress>
        <globalAnnounceEnabled>false</globalAnnounceEnabled>
        <localAnnounceEnabled>false</localAnnounceEnabled>
        <relaysEnabled>false</relaysEnabled>
        <natEnabled>false</natEnabled>
        <urAccepted>-1</urAccepted>
        <crashReportingEnabled>false</crashReportingEnabled>
        {{- if eq .Version 37}}
        <cacheIgnoredFiles>false</cacheIgnoredFiles>
        <databaseTuning>auto</databaseTuning>
        {{- end}}
    </options>
</configuration>
`))

func (c fixtureConfig) xml(t testing.TB) []byte {
	var b bytes.Buffer
	if err := configTmpl.Execute(&b, c); err != nil {
		t.Fatal(err)
	}
	return b.Bytes()
}

func golden(t testing.TB, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", "fork", name))
	if err != nil {
		t.Fatal(err)
	}
	return b
}

type entry struct {
	name string
	data []byte
}

// zipOf builds a backup zip. With a password every entry is WinZip AES
// encrypted (AE-2, deflated), as zip4j does it; encrypt picks entries for a
// mixed archive.
func zipOf(t testing.TB, entries []entry, password string, strength byte, encrypt func(string) bool) []byte {
	t.Helper()
	var buf bytes.Buffer
	w := zip.NewWriter(&buf)
	for _, e := range entries {
		if password == "" || (encrypt != nil && !encrypt(e.name)) {
			f, err := w.Create(e.name)
			if err != nil {
				t.Fatal(err)
			}
			f.Write(e.data)
			continue
		}
		var comp bytes.Buffer
		fw, _ := flate.NewWriter(&comp, flate.DefaultCompression)
		fw.Write(e.data)
		fw.Close()
		saltLen, keyLen := 4+4*int(strength), 8+8*int(strength)
		salt := make([]byte, saltLen)
		rand.Read(salt)
		keys, err := pbkdf2.Key(sha1.New, password, salt, 1000, 2*keyLen+2)
		if err != nil {
			t.Fatal(err)
		}
		ct, _ := aesCTRLittleEndian(keys[:keyLen], comp.Bytes())
		mac := hmac.New(sha1.New, keys[keyLen:2*keyLen])
		mac.Write(ct)
		raw := bytes.Join([][]byte{salt, keys[2*keyLen:], ct, mac.Sum(nil)[:10]}, nil)
		h := &zip.FileHeader{
			Name: e.name, Method: 99, Flags: 0x1,
			Extra:              []byte{0x01, 0x99, 7, 0, 2, 0, 'A', 'E', strength, 8, 0},
			CompressedSize64:   uint64(len(raw)),
			UncompressedSize64: uint64(len(e.data)),
		}
		f, err := w.CreateRaw(h)
		if err != nil {
			t.Fatal(err)
		}
		f.Write(raw)
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

// backupFixture is a complete backup: identity, config, prefs, and the
// entries Fork adds that the import must ignore.
type backupFixture struct {
	id      identity
	cfg     fixtureConfig
	prefs   string // golden file name, or ""
	entries []entry
}

func newBackup(t testing.TB, version int, ed bool, folders ...fixtureFolder) backupFixture {
	id := newIdentity(t, ed)
	b := backupFixture{
		id:    id,
		cfg:   fixtureConfig{Version: version, Self: id.id, SelfName: "Old phone", Peer: fixturePeer, Folders: folders},
		prefs: "prefs-full.dat",
	}
	b.entries = []entry{
		{"config.xml", b.cfg.xml(t)},
		{"key.pem", id.keyPEM},
		{"cert.pem", id.certPEM},
		{"https-cert.pem", []byte("not read")},
		{"https-key.pem", []byte("not read")},
		{"sharedpreferences.dat", golden(t, b.prefs)},
		{"index-v2/main.db", []byte("not read")},
	}
	return b
}

// setPaused pauses every folder and device in the backup's config.xml.
func (b *backupFixture) setPaused(t testing.TB) {
	b.cfg.Paused = true
	b.entries[0].data = b.cfg.xml(t)
}

func (b backupFixture) zip(t testing.TB, password string) []byte {
	return zipOf(t, b.entries, password, 3, nil)
}

func read(b []byte, password string) (*forkBackup, error) {
	return readForkBackup(bytes.NewReader(b), int64(len(b)), password)
}

func TestJavaPrefsGolden(t *testing.T) {
	m, err := readJavaPrefs(golden(t, "prefs-typed.dat"))
	if err != nil {
		t.Fatal(err)
	}
	want := map[string]any{
		"bool": true, "int": int32(-7), "long": int64(1 << 40), "float": float32(1.5),
		"string": "grüße ✓", "empty": "", "same": "string",
	}
	for k, v := range want {
		if m[k] != v {
			t.Errorf("%s = %#v, want %#v", k, m[k], v)
		}
	}
	if s, _ := m["set"].([]string); len(s) != 2 || !strings.Contains(strings.Join(s, ","), "a") {
		t.Errorf("set = %#v", m["set"])
	}

	full, err := readJavaPrefs(golden(t, "prefs-full.dat"))
	if err != nil {
		t.Fatal(err)
	}
	// The Java generator computes the peer's ID itself: both must agree.
	if full["sc_device_"+fixturePeer.String()+"_custom_sync_conditions"] != true {
		t.Error("per-device key not found under the Go-computed device ID")
	}
	if _, err := readJavaPrefs(golden(t, "prefs-minimal.dat")); err != nil {
		t.Error(err)
	}
}

func TestJavaPrefsRejects(t *testing.T) {
	typed := golden(t, "prefs-typed.dat")
	// Every truncation fails cleanly.
	for i := range typed {
		if _, err := readJavaPrefs(typed[:i]); err == nil {
			t.Fatalf("truncated at %d: accepted", i)
		}
	}
	if _, err := readJavaPrefs(append(bytes.Clone(typed), 0)); err == nil {
		t.Error("trailing byte accepted")
	}
	// A class outside the allowlist, as in a deserialization gadget.
	evil := bytes.Replace(bytes.Clone(typed), []byte("java.lang.Integer"), []byte("java.lang.Runtime"), 1)
	if _, err := readJavaPrefs(evil); err == nil || !strings.Contains(err.Error(), "not allowed") {
		t.Errorf("foreign class: %v", err)
	}
	if _, err := readJavaPrefs([]byte{0xAC, 0xED, 0, 5, tcString, 0, 1, 'x'}); err == nil {
		t.Error("a string instead of a map accepted")
	}
}

// Zips written by zip4j, the library Syncthing-Fork uses.
func TestZipAESGolden(t *testing.T) {
	text := []byte("Syncthing-Fork backup reader test\n")
	big := make([]byte, 100_000)
	for i := range big {
		big[i] = "0123456789abcdef"[i%16] ^ byte(i/997)
	}
	for _, name := range []string{"aes256-ae2.zip", "aes128-ae1.zip"} {
		b := golden(t, name)
		z, err := zip.NewReader(bytes.NewReader(b), int64(len(b)))
		if err != nil {
			t.Fatal(err)
		}
		for _, f := range z.File {
			want := map[string][]byte{"deflated.txt": text, "stored.txt": text, "big.bin": big}[f.Name]
			got, err := readZipEntry(f, "test-password", 1<<20)
			if err != nil || !bytes.Equal(got, want) {
				t.Errorf("%s/%s: %v, content ok %v", name, f.Name, err, bytes.Equal(got, want))
			}
			if _, err := readZipEntry(f, "", 1<<20); !errors.Is(err, errNeedPassword) {
				t.Errorf("%s/%s without password: %v", name, f.Name, err)
			}
			// The verifier rejects most wrong passwords; the MAC the rest.
			if _, err := readZipEntry(f, "test-passworD", 1<<20); err == nil {
				t.Errorf("%s/%s: wrong password accepted", name, f.Name)
			}
			if _, err := readZipEntry(f, "test-password", 100); err == nil && len(want) > 100 {
				t.Errorf("%s/%s: size limit ignored", name, f.Name)
			}
		}
		// A flipped ciphertext bit fails the MAC.
		bad := bytes.Clone(b)
		i := bytes.Index(bad, []byte("big.bin")) + 100
		bad[i] ^= 1
		z, _ = zip.NewReader(bytes.NewReader(bad), int64(len(bad)))
		for _, f := range z.File {
			if f.Name == "big.bin" {
				if _, err := readZipEntry(f, "test-password", 1<<20); err == nil {
					t.Errorf("%s: tampered entry accepted", name)
				}
			}
		}
	}
}

// storageIn points the folder path rule at a temporary directory, for tests
// whose folders must exist on disk.
func storageIn(t *testing.T) string {
	dir := t.TempDir()
	old := sharedStorage
	sharedStorage = dir
	t.Cleanup(func() { sharedStorage = old })
	return dir
}

func TestInspect(t *testing.T) {
	dir := storageIn(t)
	withMarker := filepath.Join(dir, "with-marker")
	os.MkdirAll(filepath.Join(withMarker, ".stfolder"), 0o700)
	noMarker := filepath.Join(dir, "no-marker")
	os.MkdirAll(noMarker, 0o700)
	folders := []fixtureFolder{
		{"photo_backup_01", "Photos", withMarker, "sendonly"},
		{"docs", "Docs", noMarker, "sendreceive"},
		{"music", "", filepath.Join(dir, "missing"), "receiveonly"},
	}
	for _, version := range []int{37, config.CurrentVersion} {
		for _, ed := range []bool{false, true} {
			for _, password := range []string{"", "pässword with spaces"} {
				name := fmt.Sprintf("v%d/ed25519=%v/password=%v", version, ed, password != "")
				t.Run(name, func(t *testing.T) {
					fx := newBackup(t, version, ed, folders...)
					b, err := read(fx.zip(t, password), password)
					if err != nil {
						t.Fatal(err)
					}
					p := b.preview(statFolder)
					if p.DeviceID != fx.id.id.String() || p.DeviceName != "Old phone" || p.ConfigVersion != version {
						t.Errorf("identity: %+v", p)
					}
					if len(p.Devices) != 1 || p.Devices[0].ID != fixturePeer.String() || !p.Devices[0].AutoAccept || p.Devices[0].Introducer {
						t.Errorf("devices: %+v", p.Devices)
					}
					want := []previewFolder{ // the core sorts by ID
						{"docs", "Docs", noMarker, "sendreceive", true, false},
						{"music", "", filepath.Join(dir, "missing"), "receiveonly", false, false},
						{"photo_backup_01", "Photos", withMarker, "sendonly", true, true},
					}
					if fmt.Sprint(p.Folders) != fmt.Sprint(want) {
						t.Errorf("folders:\n got %+v\nwant %+v", p.Folders, want)
					}
					// prefs-full: all networks and roaming, charging only, battery saver ignored, start at boot; force stop is not imported.
					wantC := runConditions{MeteredWifi: true, MobileData: true, Roaming: true, Power: "ChargingOnly", StartOnBoot: true}
					if !reflect.DeepEqual(p.Conditions, wantC) || p.Proxy != (proxySettings{Type: "None"}) || p.Source != "fork" {
						t.Errorf("conditions: %+v, proxy %+v, source %q", p.Conditions, p.Proxy, p.Source)
					}
					var keys []string
					for _, n := range p.NotImported {
						keys = append(keys, n.Key+":"+n.Subject)
					}
					wantN := "force_stop: flight_mode: master_sync: wifi_allowlist: environment: folder_conditions:Photos folder_script:Photos device_conditions:Example peer"
					if strings.Join(keys, " ") != wantN {
						t.Errorf("not imported:\n got %s\nwant %s", strings.Join(keys, " "), wantN)
					}
				})
			}
		}
	}
}

func TestInspectRejects(t *testing.T) {
	fx := newBackup(t, config.CurrentVersion, true, fixtureFolder{"f", "F", t.TempDir(), "sendreceive"})
	other := newIdentity(t, true)
	// with returns the backup's entries with some replaced (nil removes).
	with := func(changes map[string][]byte) []entry {
		var es []entry
		for _, e := range fx.entries {
			if data, ok := changes[e.name]; ok {
				if data != nil {
					es = append(es, entry{e.name, data})
				}
				continue
			}
			es = append(es, e)
		}
		return es
	}
	plain := func(changes map[string][]byte) []byte { return zipOf(t, with(changes), "", 0, nil) }
	newer := fx.cfg
	newer.Version = config.CurrentVersion + 1
	xxe := bytes.Replace(fx.cfg.xml(t), []byte("<configuration"),
		[]byte(`<!DOCTYPE configuration [<!ENTITY e SYSTEM "file:///etc/hostname">]><configuration`), 1)
	xxe = bytes.Replace(xxe, []byte(`name="Old phone"`), []byte(`name="&e;"`), 1)

	cases := []struct {
		name     string
		zip      []byte
		password string
		want     string
	}{
		{"not a zip", []byte("PK nothing"), "", "not a zip"},
		{"no config", plain(map[string][]byte{"config.xml": nil}), "", "config.xml is missing"},
		{"no key", plain(map[string][]byte{"key.pem": nil}), "", "key.pem is missing"},
		{"zip slip name", zipOf(t, append(with(map[string][]byte{"config.xml": nil}), entry{"../config.xml", fx.cfg.xml(t)}), "", 0, nil), "", "config.xml is missing"},
		{"entry twice", zipOf(t, append(with(nil), entry{"config.xml", fx.cfg.xml(t)}), "", 0, nil), "", "twice"},
		{"mixed encryption", zipOf(t, fx.entries, "pw", 3, func(n string) bool { return n != "config.xml" }), "pw", "mixes"},
		{"need password", fx.zip(t, "pw"), "", errNeedPassword.Error()},
		{"wrong password", fx.zip(t, "pw"), "wrong", ""},
		{"key of another device", plain(map[string][]byte{"key.pem": other.keyPEM}), "", "device certificate"},
		{"identity not in config", plain(map[string][]byte{"key.pem": other.keyPEM, "cert.pem": other.certPEM}), "", "does not belong"},
		{"newer config", plain(map[string][]byte{"config.xml": newer.xml(t)}), "", "newer Syncthing"},
		{"external entity", plain(map[string][]byte{"config.xml": xxe}), "", "config.xml"},
		{"bad prefs", plain(map[string][]byte{"sharedpreferences.dat": []byte("junk")}), "", "sharedpreferences.dat"},
		{"oversized config", plain(map[string][]byte{"config.xml": bytes.Repeat([]byte(" "), 5<<20)}), "", "too large"},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			_, err := read(c.zip, c.password)
			if err == nil || !strings.Contains(err.Error(), c.want) {
				t.Errorf("got %v, want an error with %q", err, c.want)
			}
		})
	}
}

// Imports into a home the app already used, starts the core on it, and checks
// what the user gets: the folders (created where missing), the identity, no
// old database, and only the app's own API key working.
func TestImportAndStart(t *testing.T) {
	for _, keep := range []bool{true, false} {
		t.Run(fmt.Sprintf("keepIdentity=%v", keep), func(t *testing.T) {
			dir := storageIn(t)
			home := filepath.Join(t.TempDir(), "syncthing")
			if err := Start(home, "app-key", "android", "secret"); err != nil {
				t.Fatal(err)
			}
			own := DeviceID()
			Stop()
			os.WriteFile(filepath.Join(home, "index-v2", "stale"), []byte("x"), 0o600)

			missing := filepath.Join(dir, "storage", "Camera")
			fx := newBackup(t, 37, false, fixtureFolder{"photo_backup_01", "Photos", missing, "sendreceive"})
			fx.setPaused(t) // taken while Fork paused everything
			f := filepath.Join(dir, "backup.zip")
			os.WriteFile(f, fx.zip(t, "pw"), 0o600)
			fd, _ := os.Open(f)
			defer fd.Close()
			preview, err := InspectForkBackup(int(fd.Fd()), "pw")
			if err != nil {
				t.Fatal(err)
			}
			var p forkPreview
			json.Unmarshal([]byte(preview), &p)
			if err := ImportForkBackup(int(fd.Fd()), "pw", home, keep, p.Digest); err != nil {
				t.Fatal(err)
			}
			if _, err := os.Stat(filepath.Join(home, "index-v2", "stale")); !errors.Is(err, os.ErrNotExist) {
				t.Error("old database kept")
			}
			if _, err := os.Stat(home + ".import"); !errors.Is(err, os.ErrNotExist) {
				t.Error("staging directory left behind")
			}
			if fi, err := os.Stat(filepath.Join(home, "key.pem")); err != nil || fi.Mode().Perm() != 0o600 {
				t.Errorf("key file: %v", err)
			}

			if err := Start(home, "app-key", "android", "secret"); err != nil {
				t.Fatal(err)
			}
			defer Stop()
			wantID := own
			if keep {
				wantID = fx.id.id.String()
			}
			if DeviceID() != wantID {
				t.Errorf("device ID %s, want %s", DeviceID(), wantID)
			}
			status := func(key string) int {
				req, _ := http.NewRequest(http.MethodGet, "http://"+GuiAddress()+"/rest/system/status", nil)
				req.Header.Set("X-API-Key", key)
				resp, err := http.DefaultClient.Do(req)
				if err != nil {
					t.Fatal(err)
				}
				resp.Body.Close()
				return resp.StatusCode
			}
			if status("app-key") != http.StatusOK {
				t.Error("app key refused")
			}
			if status("fork-api-key-not-for-us") == http.StatusOK {
				t.Error("the old install's API key still works")
			}

			var folders []struct {
				Path    string
				Paused  bool
				Devices []struct{ DeviceID string }
			}
			var devices []struct {
				DeviceID string
				Paused   bool
			}
			getJSON(t, "/rest/config/folders", &folders)
			getJSON(t, "/rest/config/devices", &devices)
			if len(folders) != 1 || folders[0].Path != missing || folders[0].Paused {
				t.Fatalf("folders: %+v", folders)
			}
			var ids []string
			for _, d := range devices {
				ids = append(ids, d.DeviceID)
				if d.Paused {
					t.Errorf("device %s still paused", d.DeviceID)
				}
			}
			var shared []string
			for _, d := range folders[0].Devices {
				shared = append(shared, d.DeviceID)
			}
			wantIDs := strings.Join(sorted(wantID, fixturePeer.String()), " ")
			if strings.Join(sorted(ids...), " ") != wantIDs || strings.Join(sorted(shared...), " ") != wantIDs {
				t.Errorf("devices %v, folder shared with %v, want %s", ids, shared, wantIDs)
			}

			// The empty database makes the core treat the folder as new: it creates
			// the missing directory and its marker (design doc, section 7).
			for deadline := time.Now().Add(20 * time.Second); ; time.Sleep(100 * time.Millisecond) {
				if _, err := os.Stat(filepath.Join(missing, ".stfolder")); err == nil {
					break
				}
				if time.Now().After(deadline) {
					t.Fatal("folder and marker not created")
				}
			}
		})
	}
}

func getJSON(t *testing.T, path string, v any) {
	t.Helper()
	req, _ := http.NewRequest(http.MethodGet, "http://"+GuiAddress()+path, nil)
	req.Header.Set("X-API-Key", "app-key")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if err := json.NewDecoder(resp.Body).Decode(v); err != nil {
		t.Fatal(err)
	}
}

func sorted(s ...string) []string { return slices.Sorted(slices.Values(s)) }

// The networks the app may use never exceed Fork's, and match them exactly
// unless Fork avoided Wi-Fi altogether, which the app can't (and reports).
func TestNetworkMappingProperty(t *testing.T) {
	type nets struct{ unmeteredWifi, meteredWifi, mobile, roaming bool }
	prop := func(wifi, metered, mobile, roaming bool, set uint8) bool {
		prefs := map[string]any{}
		def := []bool{true, false, false, false}
		for i, v := range []struct {
			key string
			v   *bool
		}{{"run_on_wifi", &wifi}, {"run_on_metered_wifi", &metered}, {"run_on_mobile_data", &mobile}, {"run_on_roaming", &roaming}} {
			if set&(1<<i) != 0 {
				prefs[v.key] = *v.v
			} else {
				*v.v = def[i]
			}
		}
		fork := nets{wifi, wifi && metered, mobile, mobile && roaming}
		c, _, notes := mapForkPrefs(prefs, config.Configuration{})
		got := nets{true, c.MeteredWifi, c.MobileData, c.MobileData && c.Roaming}
		noted := false
		for _, n := range notes {
			noted = noted || n.Key == "networks"
		}
		if !wifi {
			return got == nets{true, false, fork.mobile, fork.roaming} && noted
		}
		return got == fork && !noted
	}
	if err := quick.Check(prop, &quick.Config{MaxCount: 2000}); err != nil {
		t.Error(err)
	}
}

func TestForkPowerAndProxy(t *testing.T) {
	cases := []struct {
		prefs map[string]any
		power string
		proxy proxySettings
		notes string
	}{
		{map[string]any{}, "Any", proxySettings{Type: "None"}, ""},
		{map[string]any{"power_source": "ac_power"}, "ChargingOnly", proxySettings{Type: "None"}, ""},
		{map[string]any{"power_source": "battery_power"}, "BatteryOnly", proxySettings{Type: "None"}, ""},
		{map[string]any{"power_source": "nuclear"}, "Any", proxySettings{Type: "None"}, ""},
		{map[string]any{"use_tor": true}, "Any", torProxy, ""},
		{map[string]any{"use_tor": true, "socks_proxy_address": "socks5://192.0.2.2:1080"}, "Any", torProxy, "socks_proxy"},
		{map[string]any{"socks_proxy_address": " socks5://192.0.2.2:1080 "}, "Any", proxySettings{Type: "Socks5", Host: "192.0.2.2", Port: 1080}, ""},
		{map[string]any{"socks_proxy_address": "socks5://me:pw@example.org:1080"}, "Any", proxySettings{Type: "Socks5", Host: "example.org", Port: 1080, User: "me", Password: "pw"}, ""},
		{map[string]any{"http_proxy_address": "http://proxy.example.org:3128"}, "Any", proxySettings{Type: "Http", Host: "proxy.example.org", Port: 3128}, ""},
		{map[string]any{"socks_proxy_address": "socks5://a.example.org:1", "http_proxy_address": "http://b.example.org:2"}, "Any", proxySettings{Type: "Socks5", Host: "a.example.org", Port: 1}, "http_proxy"},
		{map[string]any{"socks_proxy_address": "192.0.2.2:1080"}, "Any", proxySettings{Type: "None"}, "socks_proxy"},
		{map[string]any{"socks_proxy_address": "socks5://proxy.example.org"}, "Any", proxySettings{Type: "None"}, "socks_proxy"},
		{map[string]any{"socks_proxy_address": "socks5://proxy.example.org:99999"}, "Any", proxySettings{Type: "None"}, "socks_proxy"},
		{map[string]any{"http_proxy_address": "https://proxy.example.org:443"}, "Any", proxySettings{Type: "None"}, "http_proxy"},
		{map[string]any{"http_proxy_address": "http://proxy.example.org:80/path"}, "Any", proxySettings{Type: "None"}, "http_proxy"},
	}
	for _, c := range cases {
		got, proxy, notes := mapForkPrefs(c.prefs, config.Configuration{})
		var keys []string
		for _, n := range notes {
			keys = append(keys, n.Key)
		}
		if got.Power != c.power || proxy != c.proxy || strings.Join(keys, " ") != c.notes {
			t.Errorf("%v: power %s, proxy %+v, notes %v", c.prefs, got.Power, proxy, keys)
		}
	}
}

// Preferences of the wrong type (a crafted or corrupt backup) fall back to
// Fork's defaults instead of failing or panicking.
func TestPrefsWrongTypesProperty(t *testing.T) {
	keys := []string{"run_on_wifi", "run_on_metered_wifi", "run_on_mobile_data", "power_source",
		"btnStateForceStartStop", "respect_battery_saving", "always_run_in_background", "use_tor",
		"run_on_roaming", "socks_proxy_address", "http_proxy_address",
		"environment_variables", "sc_folder_a_b_custom_sync_conditions"}
	values := []any{nil, "true", int32(1), int64(2), float32(1), []string{"x"}, true, false, ""}
	prop := func(picks []uint8) bool {
		prefs := map[string]any{}
		for i, p := range picks {
			prefs[keys[i%len(keys)]] = values[int(p)%len(values)]
		}
		cfg := config.Configuration{Folders: []config.FolderConfiguration{{ID: "a_b"}}}
		mapForkPrefs(prefs, cfg)
		return true
	}
	if err := quick.Check(prop, &quick.Config{MaxCount: 3000}); err != nil {
		t.Error(err)
	}
}

// Writes complete dummy backups for emulator tests (testdata/fork/gen.sh dummy).
// Paths are in the test area of shared storage.
func TestWriteDummyForkBackups(t *testing.T) {
	out := os.Getenv("DUMMY_FORK_DIR")
	if out == "" {
		t.Skip("only for testdata/fork/gen.sh dummy")
	}
	root := "/storage/emulated/0/mysyncthing-test-import"
	for _, d := range []struct {
		name, prefs, password string
		version               int
		ed                    bool
	}{
		{"fork-1.27-like", "full", "", 37, false},
		{"fork-2.1-like", "minimal", "", config.CurrentVersion, true},
		{"fork-2.1-encrypted", "full", "test-password", config.CurrentVersion, false},
		{"fork-2.1-proxy", "proxy", "", config.CurrentVersion, true},
		// Every setting the import policy takes out (importpolicy_test.go):
		// the preview lists them, and the core runs none of them.
		{"fork-2.1-hostile", "minimal", "", config.CurrentVersion, true},
	} {
		dir := filepath.Join(out, d.name)
		os.MkdirAll(filepath.Join(dir, "index-v2"), 0o700)
		fx := newBackup(t, d.version, d.ed,
			fixtureFolder{"photo_backup_01", "Photos", root + "/" + d.name + "/photos", "sendonly"},
			fixtureFolder{"docs", "Docs", root + "/" + d.name + "/docs", "sendreceive"})
		fx.cfg.Paused = d.prefs == "full" // and Fork's pause button on: the import still starts syncing
		configXML := fx.cfg.xml(t)
		if d.name == "fork-2.1-hostile" {
			configXML = hostileXML(t, root+"/"+d.name, fx.id.id)
		}
		for name, data := range map[string][]byte{
			"config.xml": configXML, "cert.pem": fx.id.certPEM, "key.pem": fx.id.keyPEM,
			"https-cert.pem": fx.id.certPEM, "https-key.pem": fx.id.keyPEM,
			"index-v2/main.db": []byte("stand-in database, must not be imported"),
			"prefs-variant":    []byte(d.prefs), "password": []byte(d.password),
		} {
			if err := os.WriteFile(filepath.Join(dir, name), data, 0o600); err != nil {
				t.Fatal(err)
			}
		}
	}
}

// The preview's JSON is what the app decodes (core ForkImport.kt); its test
// reads the same file.
func TestPreviewJSONContract(t *testing.T) {
	p := forkPreview{
		Source:   "fork",
		DeviceID: "SELF-ID", DeviceName: "Old phone", ConfigVersion: 37,
		Folders:     []previewFolder{{"photo_backup_01", "Photos", "/storage/emulated/0/DCIM/Camera", "sendonly", true, false}},
		Devices:     []previewDevice{{"PEER-ID", "Example peer", true, false}},
		Conditions:  runConditions{MobileData: true, Power: "BatteryOnly", RespectBatterySaver: true},
		Proxy:       torProxy,
		NotImported: []notImported{{"networks", ""}, {"folder_conditions", "Photos"}, {"folder_versioning", "Photos"}},
		Digest:      "0123abcd",
	}
	got, err := json.MarshalIndent(p, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	if want := golden(t, "preview.json"); !bytes.Equal(bytes.TrimSpace(want), got) {
		t.Errorf("preview JSON changed; update testdata/fork/preview.json and ForkImport.kt:\n%s", got)
	}
}
