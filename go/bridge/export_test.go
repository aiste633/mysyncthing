// Copyright (C) 2026 mysyncthing contributors.
// SPDX-License-Identifier: AGPL-3.0-or-later

package stbridge

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"errors"
	"math/rand"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"testing/quick"
	"time"

	"github.com/syncthing/syncthing/lib/config"
	"github.com/syncthing/syncthing/lib/protocol"
)

// exportHome is a home dir as the core leaves it, from a synthetic identity.
func exportHome(t testing.TB) (string, backupFixture) {
	t.Helper()
	fx := newBackup(t, config.CurrentVersion, true,
		fixtureFolder{"photo_backup_01", "Photos", "/storage/emulated/0/mysyncthing-test/photos", "sendonly"})
	fx.setPaused(t) // exported while a run condition pauses syncing
	home := t.TempDir()
	for name, data := range map[string][]byte{"config.xml": fx.cfg.xml(t), "cert.pem": fx.id.certPEM, "key.pem": fx.id.keyPEM} {
		if err := os.WriteFile(filepath.Join(home, name), data, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	return home, fx
}

func settingsJSON(t testing.TB, s ownSettings) []byte {
	t.Helper()
	b, err := json.Marshal(s)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

var someSettings = ownSettings{
	Version: ownSettingsVersion,
	Conditions: runConditions{
		MeteredWifi: true, MobileData: true, Power: "BatteryOnly", StartOnBoot: true,
		TimeSchedule: true, Windows: []syncWindow{{Start: 22 * 60, End: 7 * 60, Days: []int{1, 2, 3, 4, 5}}, {Start: 0, End: 1439, Days: []int{6, 7}}},
		// The fixture's peer's own windows, and kept ones of a device the backup does not pair with.
		DeviceWindows: map[string]deviceWindows{
			fixturePeer.String(): {On: true, Windows: []syncWindow{{Start: 23 * 60, End: 5 * 60, Days: []int{6, 7}}}},
			unpairedDevice:       {Windows: []syncWindow{{Start: 60, End: 120, Days: []int{3}}}},
		},
	},
	Proxy: proxySettings{Type: "Socks5", Host: "proxy.example.org", Port: 1080, User: "me", Password: "p@1:w/ord", Only: true},
}

// unpairedDevice is a device the backup fixture does not pair with.
var unpairedDevice = protocol.NewDeviceID([]byte("unpaired-device")).String()

func TestExportRoundTrip(t *testing.T) {
	home, fx := exportHome(t)
	if c, _ := os.ReadFile(filepath.Join(home, "config.xml")); bytes.Count(c, []byte("<paused>true</paused>")) != 2 {
		t.Fatalf("the home is not paused:\n%s", c)
	}
	for _, c := range []struct{ format, password string }{
		{ExportPlain, ""},
		{ExportCompatible, "correct horse battery"}, {ExportCompatible, "пароль-пароль-✓"},
		{ExportStrong, "correct horse battery"}, {ExportStrong, "пароль-пароль-✓"},
	} {
		password := c.password
		var buf bytes.Buffer
		if err := writeBackup(&buf, home, c.format, password, settingsJSON(t, someSettings), time.Date(2026, 10, 26, 23, 0, 0, 0, time.UTC)); err != nil {
			t.Fatalf("%s %q: %v", c.format, password, err)
		}
		zipBytes := buf.Bytes()
		if c.format == ExportStrong {
			// Sealed as a whole: nothing of the zip shows, not even its names.
			if !isSealed(zipBytes) {
				t.Fatalf("strong: no sealed header: % x", zipBytes[:16])
			}
			for _, leak := range []string{"PK\x03\x04", "config.xml", "key.pem", ownSettingsFile, "PRIVATE KEY"} {
				if bytes.Contains(zipBytes, []byte(leak)) {
					t.Errorf("strong: %q readable in the file", leak)
				}
			}
			var err error
			if zipBytes, err = unseal(bytes.NewReader(zipBytes), int64(len(zipBytes)), password); err != nil {
				t.Fatal(err)
			}
		}
		z, err := zip.NewReader(bytes.NewReader(zipBytes), int64(len(zipBytes)))
		if err != nil {
			t.Fatal(err)
		}
		var names []string
		for _, f := range z.File {
			names = append(names, f.Name)
			if encrypted := f.Flags&0x1 != 0; encrypted != (c.format == ExportCompatible) || encrypted && f.Method != 99 {
				t.Errorf("%s: %s flags %x method %d", c.format, f.Name, f.Flags, f.Method)
			}
			if !f.Modified.Equal(time.Date(2026, 10, 26, 23, 0, 0, 0, time.UTC)) {
				t.Errorf("%s: modified %v", f.Name, f.Modified)
			}
		}
		if got := strings.Join(names, " "); got != "config.xml cert.pem key.pem mysyncthing.json" {
			t.Errorf("entries: %s", got)
		}

		b, err := read(buf.Bytes(), password)
		if err != nil {
			t.Fatalf("password %q: import: %v", password, err)
		}
		// The core reading the config makes up an API key, so the file is checked.
		if b.id != fx.id.id || bytes.Contains(b.configXML, []byte("fork-user")) || bytes.Contains(b.configXML, []byte("fork-api-key")) ||
			bytes.Contains(b.configXML, []byte("$2a$")) {
			t.Errorf("identity %v, GUI login or API key in config.xml:\n%s", b.id, b.configXML)
		}
		for _, d := range b.cfg.Devices {
			if d.Paused {
				t.Errorf("device %s exported paused", d.DeviceID)
			}
		}
		for _, f := range b.cfg.Folders {
			if f.Paused {
				t.Errorf("folder %s exported paused", f.ID)
			}
		}
		p := b.preview(statFolder)
		// Own windows only for the devices the backup pairs with.
		want := someSettings.Conditions
		want.DeviceWindows = map[string]deviceWindows{fixturePeer.String(): want.DeviceWindows[fixturePeer.String()]}
		if p.Source != "own" || !reflect.DeepEqual(p.Conditions, want) || p.Proxy != someSettings.Proxy || len(p.NotImported) != 0 {
			t.Errorf("preview %+v", p)
		}
		if len(p.Folders) != 1 || p.Folders[0].ID != "photo_backup_01" || len(p.Devices) != 1 || p.Devices[0].ID != fixturePeer.String() {
			t.Errorf("folders %+v, devices %+v", p.Folders, p.Devices)
		}
		if password != "" {
			if _, err := read(buf.Bytes(), ""); !errors.Is(err, errNeedPassword) {
				t.Errorf("no password: %v", err)
			}
			if _, err := read(buf.Bytes(), password+"x"); !errors.Is(err, errWrongPassword) {
				t.Errorf("wrong password: %v", err)
			}
		}
	}
}

// Every entry carries the same time in both zip formats: the MS-DOS fields
// as the phone's wall clock (what unzip and file managers show) and the
// exact instant in the extended timestamp.
func TestExportEntryTimes(t *testing.T) {
	home, _ := exportHome(t)
	at := time.Date(2026, 11, 5, 18, 30, 4, 0, time.FixedZone("CET", 3600))
	for _, c := range []struct{ format, password string }{{ExportPlain, ""}, {ExportCompatible, "correct horse battery"}} {
		var buf bytes.Buffer
		if err := writeBackup(&buf, home, c.format, c.password, settingsJSON(t, someSettings), at); err != nil {
			t.Fatal(err)
		}
		z, err := zip.NewReader(bytes.NewReader(buf.Bytes()), int64(buf.Len()))
		if err != nil {
			t.Fatal(err)
		}
		for _, f := range z.File {
			wall := f.ModTime() //nolint:staticcheck // the MS-DOS fields themselves are under test
			if wall.Hour() != 18 || wall.Minute() != 30 || wall.Second() != 4 || !f.Modified.Equal(at) {
				t.Errorf("%s %s: MS-DOS time %v, modified %v, want 18:30:04 wall clock and %v", c.format, f.Name, wall, f.Modified, at)
			}
		}
	}
}

func TestExportRefuses(t *testing.T) {
	home, _ := exportHome(t)
	good := settingsJSON(t, someSettings)
	for name, c := range map[string]struct {
		password string
		settings string
	}{
		"short password":            {"elevenchars", string(good)},
		"password line break":       {"twelve chars\nlong", string(good)},
		"password control":          {"twelve chars\x00long", string(good)},
		"password not UTF-8":        {"twelve chars \xff long", string(good)},
		"password too long":         {strings.Repeat("p", maxExportPassword+1), string(good)},
		"settings not JSON":         {"", "{"},
		"settings newer":            {"", `{"version":2}`},
		"settings unknown field":    {"", strings.Replace(string(good), `"version":1`, `"version":1,"extra":true`, 1)},
		"settings trailing data":    {"", string(good) + `{}`},
		"settings pause":            {"", strings.Replace(string(good), `"startOnBoot":true`, `"startOnBoot":true,"pausedByUser":false`, 1)},
		"settings bad power":        {"", strings.Replace(string(good), "BatteryOnly", "Solar", 1)},
		"settings bad proxy type":   {"", strings.Replace(string(good), `"Socks5"`, `"Socks4"`, 1)},
		"settings bad proxy port":   {"", strings.Replace(string(good), `"port":1080`, `"port":0`, 1)},
		"settings bad proxy host":   {"", strings.Replace(string(good), "proxy.example.org", "proxy example.org", 1)},
		"settings window no days":   {"", strings.Replace(string(good), `"days":[6,7]`, `"days":[]`, 1)},
		"settings window bad day":   {"", strings.Replace(string(good), `"days":[6,7]`, `"days":[6,8]`, 1)},
		"settings window twice day": {"", strings.Replace(string(good), `"days":[6,7]`, `"days":[6,6]`, 1)},
		"settings window same ends": {"", strings.Replace(string(good), `"end":1439`, `"end":0`, 1)},
		"settings window past day":  {"", strings.Replace(string(good), `"end":1439`, `"end":1440`, 1)},
		"settings schedule empty":   {"", settingsString(t, ownSettings{Version: 1, Conditions: runConditions{Power: "Any", TimeSchedule: true}, Proxy: proxySettings{Type: "None"}})},
		"settings proxy leftover":   {"", settingsString(t, ownSettings{Version: 1, Conditions: runConditions{Power: "Any"}, Proxy: proxySettings{Type: "None", Host: "x"}})},
	} {
		formats := []string{ExportPlain}
		if c.password != "" {
			formats = []string{ExportCompatible, ExportStrong}
		}
		for _, f := range formats {
			if err := writeBackup(&bytes.Buffer{}, home, f, c.password, []byte(c.settings), time.Now()); err == nil {
				t.Errorf("%s, %s: exported", name, f)
			}
		}
	}
	for name, c := range map[string]struct{ format, password string }{
		"unknown format":       {"aes", "correct horse battery"},
		"empty format":         {"", ""},
		"plain with password":  {ExportPlain, "correct horse battery"},
		"strong no password":   {ExportStrong, ""},
		"compat no password":   {ExportCompatible, ""},
		"format case mismatch": {"Strong", "correct horse battery"},
	} {
		if err := writeBackup(&bytes.Buffer{}, home, c.format, c.password, good, time.Now()); err == nil {
			t.Errorf("%s: exported", name)
		}
	}
	// A key that is not the certificate's.
	other := newIdentity(t, true)
	os.WriteFile(filepath.Join(home, "key.pem"), other.keyPEM, 0o600)
	if err := writeBackup(&bytes.Buffer{}, home, ExportPlain, "", good, time.Now()); err == nil {
		t.Error("exported a key that does not match the certificate")
	}
}

func settingsString(t testing.TB, s ownSettings) string { return string(settingsJSON(t, s)) }

func TestExportPasswordError(t *testing.T) {
	for pw, ok := range map[string]bool{
		"": true, "123456789012": true, "ünïcödé-pässwörd": true, "twelve chars": true, "密码密码密码密码密码密码": true,
		"12345678901": false, "密码密码密码密码密码": false, "tab\tinside long": false, "zero-width ​ is format, ok": true,
	} {
		if got := ExportPasswordError(pw) == ""; got != ok {
			t.Errorf("%q: accepted %v, want %v (%s)", pw, got, ok, ExportPasswordError(pw))
		}
	}
}

// The longest password the app's import form allows still reaches the
// backup; one past the bridge's bound is refused before any key derivation.
func TestImportPasswordBound(t *testing.T) {
	f, err := os.CreateTemp(t.TempDir(), "backup")
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	longest := strings.Repeat("€", 1024) // 1024 characters, 3 bytes each
	if _, err := InspectForkBackup(int(f.Fd()), longest); err == nil || strings.Contains(err.Error(), "password longer") {
		t.Errorf("1024 characters: %v", err)
	}
	if _, err := InspectForkBackup(int(f.Fd()), longest+"x"); err == nil || !strings.Contains(err.Error(), "password longer") {
		t.Errorf("past the bound: %v", err)
	}
}

// Property: the window check agrees with a plain reading of its rules, for
// any numbers, in range or not.
func TestSyncWindowCheckProperty(t *testing.T) {
	r := rand.New(rand.NewSource(31))
	pick := func(lo, hi int) int { return lo + r.Intn(hi-lo+1) }
	for range 20000 {
		w := syncWindow{Start: pick(-3, minutesPerDay+2), End: pick(-3, minutesPerDay+2)}
		for range r.Intn(9) {
			w.Days = append(w.Days, pick(-1, 9))
		}
		days := map[int]bool{}
		ok := w.Start >= 0 && w.Start < minutesPerDay && w.End >= 0 && w.End < minutesPerDay && w.Start != w.End && len(w.Days) > 0
		for _, d := range w.Days {
			ok = ok && d >= 1 && d <= 7 && !days[d]
			days[d] = true
		}
		if err := w.check(); (err == nil) != ok {
			t.Fatalf("%+v: %v, want ok=%v", w, err, ok)
		}
	}
}

// Property: any settings the app can store, and any password it accepts,
// come back from an export unchanged, in every format.
func TestExportRoundTripProperty(t *testing.T) {
	cheapSealKey(t) // 300 real Argon2id runs would take minutes; TestSealRealKey covers it
	home, _ := exportHome(t)
	r := rand.New(rand.NewSource(26))
	powers := []string{"Any", "ChargingOnly", "BatteryOnly"}
	pwRunes := []rune("aZ09 -_.!äß€п中😀​")
	prop := func(c runConditions, proxy proxyInput, pwLen uint8) bool {
		c.Power = powers[r.Intn(len(powers))]
		c.Windows = randomWindows(r)
		c.TimeSchedule = c.TimeSchedule && len(c.Windows) > 0
		c.DeviceWindows = randomDeviceWindows(r)
		p := proxySettings{Type: "None"}
		if u, err := proxyFrom(proxy.Scheme, proxy.Host, proxy.Port, proxy.User, proxy.Password); err == nil && u != nil {
			p = proxySettings{map[string]string{"socks5": "Socks5", "http": "Http"}[proxy.Scheme], proxy.Host, proxy.Port, proxy.User, proxy.Password, r.Intn(2) == 0}
		}
		var pw strings.Builder
		format := []string{ExportPlain, ExportCompatible, ExportStrong}[pwLen%3]
		if format != ExportPlain {
			for range minExportPassword + int(pwLen)%40 {
				pw.WriteRune(pwRunes[r.Intn(len(pwRunes))])
			}
		}
		s := ownSettings{Version: ownSettingsVersion, Conditions: c, Proxy: p}
		var buf bytes.Buffer
		if err := writeBackup(&buf, home, format, pw.String(), settingsJSON(t, s), time.Now()); err != nil {
			t.Logf("%+v: %v", s, err)
			return false
		}
		b, err := read(buf.Bytes(), pw.String())
		if err != nil || !reflect.DeepEqual(*b.own, s) {
			t.Logf("%+v came back as %+v: %v", s, b, err)
			return false
		}
		return true
	}
	if err := quick.Check(prop, &quick.Config{MaxCount: 300, Rand: r}); err != nil {
		t.Fatal(err)
	}
}

// randomWindows is what the app can store: up to maxSyncWindows valid windows, nil for none.
func randomWindows(r *rand.Rand) []syncWindow {
	var ws []syncWindow
	for range r.Intn(maxSyncWindows + 1) {
		w := syncWindow{Start: r.Intn(minutesPerDay), End: r.Intn(minutesPerDay)}
		if w.End == w.Start {
			w.End = (w.Start + 1) % minutesPerDay
		}
		for _, d := range r.Perm(7)[:1+r.Intn(7)] {
			w.Days = append(w.Days, d+1)
		}
		ws = append(ws, w)
	}
	return ws
}

// randomDeviceWindows is what the app can store: some devices' own windows, on or kept
// while off, nil for none.
func randomDeviceWindows(r *rand.Rand) map[string]deviceWindows {
	var all map[string]deviceWindows
	for range r.Intn(4) {
		id := make([]byte, 8)
		r.Read(id)
		w := deviceWindows{Windows: randomWindows(r)}
		w.On = len(w.Windows) > 0 && r.Intn(2) == 0
		if all == nil {
			all = map[string]deviceWindows{}
		}
		all[protocol.NewDeviceID(id).String()] = w
	}
	return all
}

// Own windows the app could not store, or for what is not a device ID as the core
// writes it, make the whole settings file unreadable, as for the global windows.
func TestDeviceWindowsCheck(t *testing.T) {
	peer := fixturePeer.String()
	night := []syncWindow{{Start: 22 * 60, End: 7 * 60, Days: []int{1}}}
	devices := func(n int) map[string]deviceWindows {
		all := map[string]deviceWindows{}
		for i := range n {
			all[protocol.NewDeviceID([]byte{byte(i), byte(i >> 8)}).String()] = deviceWindows{Windows: night}
		}
		return all
	}
	tooMany := make([]syncWindow, maxSyncWindows+1)
	for i := range tooMany {
		tooMany[i] = night[0]
	}
	for name, c := range map[string]struct {
		windows map[string]deviceWindows
		ok      bool
	}{
		"none":               {nil, true},
		"on":                 {map[string]deviceWindows{peer: {On: true, Windows: night}}, true},
		"kept while off":     {map[string]deviceWindows{peer: {Windows: night}}, true},
		"off and empty":      {map[string]deviceWindows{peer: {}}, true},
		"as many as allowed": {devices(maxDeviceWindows), true},
		"on without windows": {map[string]deviceWindows{peer: {On: true}}, false},
		"bad window":         {map[string]deviceWindows{peer: {Windows: []syncWindow{{Start: 60, End: 60, Days: []int{1}}}}}, false},
		"too many windows":   {map[string]deviceWindows{peer: {Windows: tooMany}}, false},
		"lower case ID":      {map[string]deviceWindows{strings.ToLower(peer): {Windows: night}}, false},
		"ID without dashes":  {map[string]deviceWindows{strings.ReplaceAll(peer, "-", ""): {Windows: night}}, false},
		"wrong check":        {map[string]deviceWindows{peer[:len(peer)-1] + "A": {Windows: night}}, false},
		"not an ID":          {map[string]deviceWindows{"laptop": {Windows: night}}, false},
		"empty ID":           {map[string]deviceWindows{"": {Windows: night}}, false},
		"too many devices":   {devices(maxDeviceWindows + 1), false},
	} {
		s := someSettings
		s.Conditions.DeviceWindows = c.windows
		if _, err := readOwnSettings(settingsJSON(t, s)); (err == nil) != c.ok {
			t.Errorf("%s: %v, want ok=%v", name, err, c.ok)
		}
	}
}

// A backup's own windows for a device it does not pair with are left out of the
// preview, so they don't wait in the app for a device paired later.
func TestOnlyForPairedDevices(t *testing.T) {
	c := someSettings.Conditions
	peer := fixturePeer.String()
	if got := c.onlyFor(map[string]bool{peer: true}).DeviceWindows; len(got) != 1 || !reflect.DeepEqual(got[peer], c.DeviceWindows[peer]) {
		t.Errorf("paired: %+v", got)
	}
	if got := c.onlyFor(map[string]bool{}).DeviceWindows; got != nil {
		t.Errorf("none paired: %+v", got)
	}
	if len(c.DeviceWindows) != 2 {
		t.Errorf("onlyFor changed its input: %+v", c.DeviceWindows)
	}
}

func FuzzReadOwnSettings(f *testing.F) {
	f.Add(settingsJSON(f, someSettings))
	f.Add([]byte(`{"version":1,"conditions":{"power":"Any"},"proxy":{"type":"None"}}`))
	f.Fuzz(func(t *testing.T, js []byte) {
		s, err := readOwnSettings(js)
		if err != nil {
			return
		}
		again, err := readOwnSettings(settingsJSON(t, *s))
		if err != nil || !reflect.DeepEqual(again, s) {
			t.Fatalf("%+v does not round-trip: %v", s, err)
		}
	})
}

// The app's form checks (ui/Validation.kt) and the bridge agree on every case
// in this file; its Kotlin test reads the same file.
func TestValidationContract(t *testing.T) {
	var c struct {
		Proxy []struct {
			Scheme, Host   string
			Port           int
			User, Password string
			OK             bool
		}
		ExportPassword []struct {
			Password string
			OK       bool
		}
		SyncWindow []struct {
			Start, End int
			Days       []int
			OK         bool
		}
	}
	if err := json.Unmarshal(golden(t, "../validation-contract.json"), &c); err != nil {
		t.Fatal(err)
	}
	for _, p := range c.Proxy {
		if _, err := proxyFrom(p.Scheme, p.Host, p.Port, p.User, p.Password); (err == nil) != p.OK {
			t.Errorf("proxy %+v: %v, want ok=%v", p, err, p.OK)
		}
	}
	for _, w := range c.SyncWindow {
		if err := (syncWindow{w.Start, w.End, w.Days}).check(); (err == nil) != w.OK {
			t.Errorf("window %+v: %v, want ok=%v", w, err, w.OK)
		}
	}
	for _, p := range c.ExportPassword {
		if msg := ExportPasswordError(p.Password); (msg == "") != p.OK {
			t.Errorf("password %q: %q, want ok=%v", p.Password, msg, p.OK)
		}
	}
}

// The app writes ownSettingsFile like this (core ForkPreviewTest writes and
// compares the same file); the bridge must read it as written.
func TestOwnSettingsContract(t *testing.T) {
	// The file names its devices by placeholder: the leak check refuses device IDs in the repository.
	js := strings.NewReplacer("$PEER", fixturePeer.String(), "$UNPAIRED", unpairedDevice).Replace(string(golden(t, "../own-settings.json")))
	s, err := readOwnSettings([]byte(js))
	if err != nil {
		t.Fatal(err)
	}
	want := someSettings
	want.Conditions.RespectBatterySaver = true
	if !reflect.DeepEqual(*s, want) {
		t.Errorf("got %+v\nwant %+v", *s, want)
	}
}
