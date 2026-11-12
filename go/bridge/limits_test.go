// Copyright (C) 2026 mysyncthing contributors.
// SPDX-License-Identifier: AGPL-3.0-or-later

package stbridge

import (
	"encoding/json"
	"os"
	"regexp"
	"strings"
	"testing"

	"github.com/syncthing/syncthing/lib/config"
	"github.com/syncthing/syncthing/lib/protocol"
)

// maxText is the sync protocol's limit for device names, folder IDs and
// labels: a Syncthing update that changes it fails here.
func TestMaxTextIsTheProtocolLimit(t *testing.T) {
	proto, err := os.ReadFile("../syncthing/proto/bep/bep.proto")
	if err != nil {
		t.Fatal(err)
	}
	for _, field := range []string{"device_name", "id", "label", "name"} {
		re := regexp.MustCompile(`string ` + field + ` = \d+ \[\(buf\.validate\.field\)\.string\.max_len = (\d+)\]`)
		found := false
		for _, m := range re.FindAllStringSubmatch(string(proto), -1) {
			// File names (32767) are not text a person types.
			if m[1] == "32767" {
				continue
			}
			found = true
			if m[1] != "1024" || maxText != 1024 {
				t.Errorf("%s: protocol max_len %s, maxText %d", field, m[1], maxText)
			}
		}
		if !found {
			t.Errorf("%s: no max_len in bep.proto", field)
		}
	}
}

// longText is n characters of 1 to 4 UTF-8 bytes each: limits count characters, not bytes or UTF-16 units.
func longText(n int) string {
	return strings.Repeat("aé€😀", n/4) + strings.Repeat("a", n%4)
}

func lengthTestConfig() config.Configuration {
	self := protocol.NewDeviceID([]byte("limits-self"))
	peer := protocol.NewDeviceID([]byte("limits-peer"))
	cfg := config.New(self)
	cfg.Devices = append(cfg.Devices, config.DeviceConfiguration{DeviceID: peer, Name: "peer", Addresses: []string{"dynamic"}})
	cfg.Folders = []config.FolderConfiguration{{
		ID: "abcde-fghij", Label: "Photos", Path: "/storage/emulated/0/Photos",
		Devices: []config.FolderDeviceConfiguration{{DeviceID: self}, {DeviceID: peer}},
	}}
	return cfg
}

func TestLengthGuard(t *testing.T) {
	base := lengthTestConfig()
	if p := lengthProblems(base); len(p) != 0 {
		t.Fatalf("base config: %v", p)
	}
	part := func(n int) string { return strings.Repeat("é", n/2) + strings.Repeat("a", n%2) }
	pathOf := func(n int) string { // n bytes in all, parts of at most 255
		p := "/storage/emulated/0"
		for len(p) < n {
			p += "/" + strings.Repeat("a", min(255, n-len(p)-1))
		}
		return p
	}
	listOf := func(n int) []string {
		l := make([]string, n)
		for i := range l {
			l[i] = "tcp://:" + string(rune('0'+i%10))
		}
		return l
	}
	cases := []struct {
		name   string
		change func(n int, c *config.Configuration)
		limit  int
	}{
		{"folder label", func(n int, c *config.Configuration) { c.Folders[0].Label = longText(n) }, maxText},
		{"folder ID", func(n int, c *config.Configuration) { c.Folders[0].ID = longText(n) }, maxText},
		{"folder group", func(n int, c *config.Configuration) { c.Folders[0].Group = longText(n) }, maxText},
		{"own name", func(n int, c *config.Configuration) { c.Devices[0].Name = longText(n) }, maxText},
		{"device name", func(n int, c *config.Configuration) { c.Devices[1].Name = longText(n) }, maxText},
		{"device group", func(n int, c *config.Configuration) { c.Devices[1].Group = longText(n) }, maxText},
		{"default device address", func(n int, c *config.Configuration) {
			c.Defaults.Device.Addresses = []string{"tcp://" + strings.Repeat("a", n-len("tcp://"))}
		}, maxText},
		{"encryption password", func(n int, c *config.Configuration) { c.Folders[0].Devices[1].EncryptionPassword = longText(n) }, maxText},
		{"versioning parameter", func(n int, c *config.Configuration) {
			c.Folders[0].Versioning = config.VersioningConfiguration{Type: "simple", Params: map[string]string{"keep": longText(n)}}
		}, maxText},
		{"a field the app doesn't show", func(n int, c *config.Configuration) { c.Options.URUniqueID = longText(n) }, maxText},
		{"path part", func(n int, c *config.Configuration) { c.Folders[0].Path = "/storage/emulated/0/" + part(n) }, maxFileName},
		{"path", func(n int, c *config.Configuration) { c.Folders[0].Path = pathOf(n) }, maxPath},
		{"default path", func(n int, c *config.Configuration) { c.Defaults.Folder.Path = pathOf(n) }, maxPath},
		{"listen addresses", func(n int, c *config.Configuration) { c.Options.RawListenAddresses = listOf(n) }, maxEntries},
		{"listen addresses together", func(n int, c *config.Configuration) {
			c.Options.RawListenAddresses = []string{"tcp://" + strings.Repeat("a", 1000), "tcp://" + strings.Repeat("b", 1000),
				"tcp://" + strings.Repeat("c", 1000), "tcp://" + strings.Repeat("d", 1000), "tcp://" + strings.Repeat("e", n-4*1006-len("tcp://"))}
		}, maxListenTotal},
		{"discovery servers", func(n int, c *config.Configuration) { c.Options.RawGlobalAnnServers = listOf(n) }, maxEntries},
		{"default device addresses", func(n int, c *config.Configuration) { c.Defaults.Device.Addresses = listOf(n) }, maxEntries},
		{"default ignores", func(n int, c *config.Configuration) {
			c.Defaults.Ignores.Lines = []string{strings.Repeat("a", 60_000), strings.Repeat("b", 60_000), strings.Repeat("c", n-120_002)}
		}, maxIgnores},
	}
	// A device's addresses are not held to limits here: the core copies an
	// introducer's list as it adds the device, and the protocol sets none.
	introduced := base.Copy()
	introduced.Devices[1].Addresses = append(listOf(maxEntries+1), "tcp://"+strings.Repeat("a", maxText))
	if err := (lengthGuard{}).VerifyConfiguration(base, introduced); err != nil {
		t.Errorf("introduced device's addresses: %v", err)
	}
	for _, c := range cases {
		at, over := base.Copy(), base.Copy()
		c.change(c.limit, &at)
		c.change(c.limit+1, &over)
		if err := (lengthGuard{}).VerifyConfiguration(base, at); err != nil {
			t.Errorf("%s at the limit (%d): %v", c.name, c.limit, err)
		}
		if err := (lengthGuard{}).VerifyConfiguration(base, over); err == nil {
			t.Errorf("%s over the limit (%d): taken", c.name, c.limit+1)
		}
		// A value over the limit from an older version doesn't block other changes.
		other := over.Copy()
		other.Options.MaxSendKbps = 7
		if err := (lengthGuard{}).VerifyConfiguration(over, other); err != nil {
			t.Errorf("%s already over: another change refused: %v", c.name, err)
		}
	}
}

// What the import keeps is within every limit; names over it are cleared
// and listed, folders that can't do without the long value are left out.
func TestImportPolicyLengths(t *testing.T) {
	cfg := lengthTestConfig()
	long := longText(maxText + 1)
	cfg.Folders[0].Label = long
	cfg.Folders = append(cfg.Folders,
		config.FolderConfiguration{ID: long, Label: "Long ID", Path: "/storage/emulated/0/LongID"},
		config.FolderConfiguration{ID: "pw", Label: "Password", Path: "/storage/emulated/0/Password",
			Devices: []config.FolderDeviceConfiguration{{DeviceID: cfg.Devices[1].DeviceID, EncryptionPassword: long}}},
		config.FolderConfiguration{ID: "ver", Label: "Versions", Path: "/storage/emulated/0/Versions",
			Versioning: config.VersioningConfiguration{Type: "simple", Params: map[string]string{"keep": long}}},
		config.FolderConfiguration{ID: "part", Label: "Part", Path: "/storage/emulated/0/" + strings.Repeat("a", 256)},
	)
	cfg.Devices[1].Name = long
	cfg.Devices[1].Addresses = []string{"tcp://" + strings.Repeat("a", maxText), "tcp://192.0.2.1:22000"}
	for range maxEntries {
		cfg.Devices[1].Addresses = append(cfg.Devices[1].Addresses, "tcp://192.0.2.2:22000")
	}
	cfg.Options.RawListenAddresses = []string{"default", "tcp://" + strings.Repeat("a", maxText)}
	cfg.Options.UnackedNotificationIDs = []string{long}
	cfg.Defaults.Ignores.Lines = []string{strings.Repeat("a", maxIgnores+1)}

	for i := range cfg.Folders {
		cfg.Folders[i].FilesystemType = config.FilesystemTypeBasic
	}
	notes := importPolicy(&cfg)
	if err := checkPolicy(cfg); err != nil {
		t.Fatal(err)
	}
	got := noteKeys(notes)
	for _, want := range []string{"text_too_long", "folder_id", "folder_password_long", "folder_versioning", "folder_path", "device_addresses", "listen_addresses", "default_ignores", "other_too_long"} {
		if !strings.Contains(got, want) {
			t.Errorf("not listed: %s (got %s)", want, got)
		}
	}
	var ids []string
	for _, f := range cfg.Folders {
		ids = append(ids, f.ID)
	}
	if strings.Join(ids, ",") != "abcde-fghij,ver" {
		t.Errorf("folders kept: %q", ids)
	}
	if cfg.Folders[0].Label != "" || cfg.Devices[1].Name != "" || len(cfg.Devices[1].Addresses) != maxEntries {
		t.Errorf("label %q, name %q, %d addresses", brief(cfg.Folders[0].Label), brief(cfg.Devices[1].Name), len(cfg.Devices[1].Addresses))
	}
}

// The subjects listed stay short however long the name.
func TestBrief(t *testing.T) {
	if b := brief(longText(maxText)); len([]rune(b)) != 64 || !strings.HasSuffix(b, "…") {
		t.Errorf("brief: %q", b)
	}
	if brief("Photos") != "Photos" {
		t.Error("short name changed")
	}
}

// A patterns text over maxIgnores is refused before the core's parser runs.
func TestCheckIgnoresLength(t *testing.T) {
	line := strings.Repeat("a", 999) + "\n"
	ok := strings.Repeat(line, maxIgnores/len(line)) + strings.Repeat("b", maxIgnores%len(line))
	if msg := CheckIgnores("", ok); msg != "" {
		t.Errorf("at the limit: %s", msg)
	}
	if msg := CheckIgnores("", ok+"c"); msg == "" {
		t.Error("over the limit: taken")
	}
}

// Text limits count characters as the app's forms do (ui/Validation.kt
// lengthOf): ImportContractTest reads the same cases.
func TestTextLengthContract(t *testing.T) {
	var c struct {
		Text []struct {
			Text string
			OK   bool
		}
	}
	if err := json.Unmarshal(golden(t, "../validation-contract.json"), &c); err != nil {
		t.Fatal(err)
	}
	if len(c.Text) == 0 {
		t.Fatal("no cases")
	}
	for _, x := range c.Text {
		if textTooLong(x.Text) == x.OK {
			t.Errorf("%d characters: ok=%v wanted", len([]rune(x.Text)), x.OK)
		}
	}
}
