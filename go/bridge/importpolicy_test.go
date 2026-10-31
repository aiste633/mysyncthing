// Copyright (C) 2026 mysyncthing contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package stbridge

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"slices"
	"strings"
	"testing"
	"text/template"

	"github.com/syncthing/syncthing/lib/config"
	"github.com/syncthing/syncthing/lib/protocol"
)

// hostileTmpl is a config.xml with every setting importPolicy takes out, as a
// tampered backup could carry it. Hosts are documentation addresses.
var hostileTmpl = template.Must(template.New("hostile").Parse(`<configuration version="{{.Version}}">
    <folder id="photos" label="Pho&#x202E;gpj.sotos" path="{{.Root}}/DCIM" type="sendreceive">
        <filesystemType>basic</filesystemType>
        <device id="{{.Self}}"></device>
        <device id="{{.Peer}}"></device>
        <versioning type="external">
            <param key="command" val="/system/bin/sh -c id"></param>
            <fsPath>{{.Root}}</fsPath>
        </versioning>
        <markerName>.</markerName>
    </folder>
    <folder id="keys" label="Keys" path="/data/user/0/eu.stedb.mysyncthing/files/syncthing" type="sendonly">
        <device id="{{.Self}}"></device>
        <device id="{{.Peer}}"></device>
    </folder>
    <folder id="music" label="Music" path="{{.Root}}/Music" type="sendreceive">
        <device id="{{.Self}}"></device>
    </folder>
    <folder id="nested" label="Nested" path="{{.Root}}/Music/Sub" type="sendreceive">
        <device id="{{.Self}}"></device>
    </folder>
    <folder id="cleaned" label="Cleaned" path="{{.Root}}/Documents" type="sendreceive">
        <device id="{{.Self}}"></device>
        <versioning type="trashcan">
            <param key="cleanoutDays" val="1"></param>
            <fsPath>../..</fsPath>
        </versioning>
    </folder>
    <folder id="fake" label="Fake" path="{{.Root}}/Fake" type="sendreceive">
        <filesystemType>fake</filesystemType>
        <device id="{{.Self}}"></device>
    </folder>
    <folder id="bad&#x9b;id" label="Bell" path="{{.Root}}/Bell" type="sendreceive">
        <device id="{{.Self}}"></device>
    </folder>
    <device id="{{.Self}}" name="Old phone"></device>
    <device id="{{.Peer}}" name="Peer&#x202E;" introducer="true">
        <address>relay://192.0.2.20:22067/?id={{.Peer}}</address>
        <address>tcp://192.0.2.10:22000</address>
        <address>http://192.0.2.10/</address>
        <allowedNetwork>192.0.2.0/24</allowedNetwork>
        <allowedNetwork>nonsense</allowedNetwork>
        <autoAcceptFolders>true</autoAcceptFolders>
    </device>
    <gui enabled="false" tls="true" debugging="true">
        <address>0.0.0.0:8384</address>
        <unixSocketPermissions>777</unixSocketPermissions>
        <user>fork-user</user>
        <password>$2a$04$abcdefghijklmnopqrstuuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ</password>
        <apikey>fork-api-key-not-for-us</apikey>
        <insecureAdminAccess>true</insecureAdminAccess>
        <insecureSkipHostCheck>true</insecureSkipHostCheck>
        <insecureAllowFrameLoading>true</insecureAllowFrameLoading>
        <metricsWithoutAuth>true</metricsWithoutAuth>
        <theme>../../../files</theme>
    </gui>
    <ldap>
        <address>ldap.example.com:389</address>
    </ldap>
    <options>
        <listenAddress>tcp://127.0.0.1:0</listenAddress>
        <listenAddress>relay://192.0.2.20:22067</listenAddress>
        <listenAddress>dynamic+https://relays.example.com/endpoint</listenAddress>
        <globalAnnounceServer>https://discovery.example.com/</globalAnnounceServer>
        <globalAnnounceEnabled>false</globalAnnounceEnabled>
        <localAnnounceEnabled>false</localAnnounceEnabled>
        <localAnnounceMCAddr>[2001:db8::8384]:21027</localAnnounceMCAddr>
        <relaysEnabled>false</relaysEnabled>
        <natEnabled>false</natEnabled>
        <startBrowser>true</startBrowser>
        <urAccepted>3</urAccepted>
        <urSeen>3</urSeen>
        <urUniqueID>abcdefgh</urUniqueID>
        <urURL>https://reports.example.com/newdata</urURL>
        <releasesURL>https://releases.example.com/meta.json</releasesURL>
        <crashReportingURL>https://crash.example.com/newcrash</crashReportingURL>
        <crashReportingEnabled>false</crashReportingEnabled>
        <alwaysLocalNet>192.0.2.0/24</alwaysLocalNet>
        <alwaysLocalNet>not-a-net</alwaysLocalNet>
        <stunServer>stun.example.com:3478</stunServer>
        <featureFlag>experimental</featureFlag>
        <auditEnabled>true</auditEnabled>
        <auditFile>{{.Root}}/audit.log</auditFile>
    </options>
    <defaults>
        <folder id="" label="" path="/data/user/0/eu.stedb.mysyncthing/files" type="sendreceive">
            <versioning type="external">
                <param key="command" val="/system/bin/sh -c id"></param>
                <fsPath>/data/user/0/eu.stedb.mysyncthing/files</fsPath>
            </versioning>
            <markerName>.</markerName>
        </folder>
        <device id="" introducer="false">
            <address>relay://192.0.2.20:22067</address>
        </device>
    </defaults>
</configuration>
`))

func hostileXML(t testing.TB, root string, self protocol.DeviceID) []byte {
	var b bytes.Buffer
	err := hostileTmpl.Execute(&b, struct {
		Version    int
		Root       string
		Self, Peer protocol.DeviceID
	}{config.CurrentVersion, root, self, fixturePeer})
	if err != nil {
		t.Fatal(err)
	}
	return b.Bytes()
}

// hostileBackup is a backup with the hostile config, its folders under root.
func hostileBackup(t testing.TB, root string) backupFixture {
	fx := newBackup(t, config.CurrentVersion, true)
	fx.entries[0].data = hostileXML(t, root, fx.id.id)
	return fx
}

// checkPolicy is what importPolicy promises about a config; nil if it holds.
func checkPolicy(cfg config.Configuration) error {
	def := config.New(protocol.EmptyDeviceID)
	var paths []string
	for _, f := range cfg.Folders {
		if hasUnsafeText(f.ID) || hasUnsafeText(f.Label) {
			return fmt.Errorf("folder %q: unsafe text", f.ID)
		}
		if f.FilesystemType != config.FilesystemTypeBasic {
			return fmt.Errorf("folder %q: filesystem %q", f.ID, f.FilesystemType)
		}
		if err := folderPathError(f.Path, paths); err != nil || folderPath(f.Path) != f.Path {
			return fmt.Errorf("folder %q: path %q: %v", f.ID, f.Path, err)
		}
		paths = append(paths, f.Path)
		if err := checkFolderOptions(f); err != nil {
			return fmt.Errorf("folder %q: %w", f.ID, err)
		}
	}
	df := cfg.Defaults.Folder
	if df.Path != "" && df.Path != "~" && folderPathError(folderPath(df.Path), nil) != nil || df.FilesystemType != config.FilesystemTypeBasic {
		return fmt.Errorf("default folder path %q (%s)", df.Path, df.FilesystemType)
	}
	if err := checkFolderOptions(df); err != nil {
		return fmt.Errorf("default folder: %w", err)
	}
	for _, d := range append(slices.Clone(cfg.Devices), cfg.Defaults.Device) {
		if hasUnsafeText(d.Name) {
			return fmt.Errorf("device %s: unsafe name", d.DeviceID)
		}
		if len(d.Addresses) == 0 {
			return fmt.Errorf("device %s: no address", d.DeviceID)
		}
		for _, a := range d.Addresses {
			if a != "dynamic" && !isImportDeviceAddress(a) {
				return fmt.Errorf("device %s: address %q", d.DeviceID, a)
			}
		}
		for _, n := range d.AllowedNetworks {
			if !isNetwork(n) {
				return fmt.Errorf("device %s: network %q", d.DeviceID, n)
			}
		}
	}
	o := cfg.Options
	switch {
	case o.URURL != def.Options.URURL || o.CRURL != def.Options.CRURL || o.ReleasesURL != def.Options.ReleasesURL:
		return errors.New("report or release URL")
	case o.URAccepted > 0 || o.URUniqueID != "":
		return fmt.Errorf("usage reporting consent %d", o.URAccepted)
	case !onlyDefault(o.RawGlobalAnnServers) || !onlyDefault(o.RawStunServers):
		return errors.New("discovery or STUN servers")
	case o.LocalAnnMCAddr != def.Options.LocalAnnMCAddr || o.LocalAnnPort != def.Options.LocalAnnPort:
		return errors.New("local discovery")
	case len(o.FeatureFlags) > 0 || o.AuditEnabled || o.AuditFile != "" || o.StartBrowser:
		return errors.New("feature flags, audit or browser")
	}
	for _, a := range o.RawListenAddresses {
		if a != "default" && !isImportListenAddress(a) {
			return fmt.Errorf("listen address %q", a)
		}
	}
	for _, n := range o.AlwaysLocalNets {
		if !isNetwork(n) {
			return fmt.Errorf("local network %q", n)
		}
	}
	g := cfg.GUI
	if lockOf(g) != lockOf(locked(config.GUIConfiguration{})) || g.RawAddress != def.GUI.RawAddress || g.RawUnixSocketPermissions != "" || !themeName.MatchString(g.Theme) {
		return fmt.Errorf("GUI %+v", g)
	}
	if cfg.LDAP != (config.LDAPConfiguration{}) {
		return errors.New("LDAP")
	}
	return nil
}

func checkFolderOptions(f config.FolderConfiguration) error {
	v := f.Versioning
	if !slices.Contains([]string{"", "simple", "staggered", "trashcan"}, v.Type) || v.FSPath != "" || v.FSType != config.FilesystemTypeBasic {
		return fmt.Errorf("versioning %+v", v)
	}
	if f.MarkerName != config.DefaultMarkerName {
		return fmt.Errorf("marker %q", f.MarkerName)
	}
	return nil
}

func noteKeys(ns []notImported) string {
	var keys []string
	for _, n := range ns {
		keys = append(keys, n.Key+":"+n.Subject)
	}
	return strings.Join(keys, " ")
}

// Everything hostile is taken out, listed, and nothing more; the policy then
// holds, and running it again changes nothing.
func TestImportPolicyHostile(t *testing.T) {
	root := "/storage/emulated/0"
	self := newIdentity(t, true).id
	cfg, _, err := config.ReadXML(bytes.NewReader(hostileXML(t, root, self)), self)
	if err != nil {
		t.Fatal(err)
	}
	notes := importPolicy(&cfg)
	// The core sorts folders by ID.
	want := "folder_id:Bell folder_filesystem:Fake folder_path:Keys folder_versions_path:Cleaned " +
		"folder_overlap:Music folder_overlap:Nested " +
		"folder_versioning:Pho\uFFFDgpj.sotos folder_versions_path:Pho\uFFFDgpj.sotos folder_marker:Pho\uFFFDgpj.sotos " +
		"default_folder_path: default_folder_versioning: default_folder_versions_path: default_folder_marker: " +
		"device_addresses:Peer\uFFFD default_device_addresses: " +
		"discovery_servers: stun_servers: listen_addresses: local_networks: local_discovery: feature_flags:"
	if got := noteKeys(notes); got != want {
		t.Errorf("not imported:\n got %q\nwant %q", got, want)
	}
	if err := checkPolicy(cfg); err != nil {
		t.Error(err)
	}
	var ids []string
	for _, f := range cfg.Folders {
		ids = append(ids, f.ID)
	}
	if strings.Join(ids, " ") != "cleaned photos" {
		t.Errorf("folders %v", ids)
	}
	peer, _, _ := cfg.Device(fixturePeer)
	if !reflect.DeepEqual(peer.Addresses, []string{"tcp://192.0.2.10:22000"}) || !reflect.DeepEqual(peer.AllowedNetworks, []string{"192.0.2.0/24"}) ||
		!peer.Introducer || !peer.AutoAcceptFolders {
		t.Errorf("peer %+v", peer)
	}
	if !reflect.DeepEqual(cfg.Options.RawListenAddresses, []string{"tcp://127.0.0.1:0"}) || cfg.Options.CREnabled {
		t.Errorf("options %+v", cfg.Options)
	}
	if cfg.Folders[0].Versioning.Type != "trashcan" || cfg.Folders[0].Versioning.Params["cleanoutDays"] != "1" {
		t.Errorf("trash can not kept: %+v", cfg.Folders[0].Versioning)
	}

	again := cfg.Copy()
	if n := importPolicy(&again); len(n) != 0 || !reflect.DeepEqual(again, cfg.Copy()) {
		t.Errorf("second run: %v", noteKeys(n))
	}
}

// A refusal of usage reporting is kept; consent is asked again.
func TestImportPolicyUsageReporting(t *testing.T) {
	for accepted, want := range map[int]int{-1: -1, 0: 0, 3: 0, 1000: 0} {
		cfg := config.New(protocol.EmptyDeviceID)
		cfg.Options.URAccepted, cfg.Options.URSeen = accepted, 3
		importPolicy(&cfg)
		if cfg.Options.URAccepted != want {
			t.Errorf("urAccepted %d became %d, want %d", accepted, cfg.Options.URAccepted, want)
		}
	}
}

// A hostile backup imports, the core starts on it, and what it runs is safe.
func TestImportHostileAndStart(t *testing.T) {
	root := storageIn(t)
	home := filepath.Join(t.TempDir(), "syncthing")
	fx := hostileBackup(t, root)
	f := filepath.Join(t.TempDir(), "backup.zip")
	os.WriteFile(f, fx.zip(t, ""), 0o600)
	fd, _ := os.Open(f)
	defer fd.Close()
	preview, err := InspectForkBackup(int(fd.Fd()), "")
	if err != nil {
		t.Fatal(err)
	}
	var p forkPreview
	json.Unmarshal([]byte(preview), &p)
	if len(p.Devices) != 1 || !p.Devices[0].Introducer || !p.Devices[0].AutoAccept || p.Devices[0].Name != "Peer\uFFFD" {
		t.Errorf("preview devices %+v", p.Devices)
	}
	if !strings.Contains(noteKeys(p.NotImported), "folder_versioning:") {
		t.Errorf("preview not imported: %s", noteKeys(p.NotImported))
	}
	for _, keep := range []bool{true, false} {
		if err := ImportForkBackup(int(fd.Fd()), "", home, keep, p.Digest); err != nil {
			t.Fatal(err)
		}
		written, err := os.ReadFile(filepath.Join(home, "config.xml"))
		if err != nil {
			t.Fatal(err)
		}
		id := fx.id.id
		if !keep {
			id = protocol.EmptyDeviceID // any: the check reads the file as is
		}
		cfg, _, err := config.ReadXML(bytes.NewReader(written), id)
		if err != nil {
			t.Fatal(err)
		}
		cfg.GUI.APIKey = "" // the core makes one up when there is none; the app sets its own at start
		if err := checkPolicy(cfg); err != nil {
			t.Errorf("keepIdentity=%v: written config: %v", keep, err)
		}
		for _, s := range []string{"/system/bin/sh", "example.com", "fork-api-key", "/data/user"} {
			if bytes.Contains(written, []byte(s)) {
				t.Errorf("keepIdentity=%v: %q in config.xml", keep, s)
			}
		}
	}

	if err := Start(home, "app-key", "android", "secret"); err != nil {
		t.Fatal(err)
	}
	defer Stop()
	var folders []struct {
		ID, Path   string
		Versioning struct{ Type, FSPath string }
	}
	getJSON(t, "/rest/config/folders", &folders)
	if len(folders) != 2 || folders[1].ID != "photos" || folders[1].Versioning.Type != "" || folders[1].Path != root+"/DCIM" {
		t.Errorf("folders %+v", folders)
	}
}

// The file is read again on import; one replaced after the preview is refused.
func TestImportRefusesAChangedBackup(t *testing.T) {
	root := storageIn(t)
	home := filepath.Join(t.TempDir(), "syncthing")
	fx := newBackup(t, config.CurrentVersion, true, fixtureFolder{"f", "F", root + "/f", "sendreceive"})
	f := filepath.Join(t.TempDir(), "backup.zip")
	os.WriteFile(f, fx.zip(t, ""), 0o600)
	fd, _ := os.Open(f)
	defer fd.Close()
	preview, err := InspectForkBackup(int(fd.Fd()), "")
	if err != nil {
		t.Fatal(err)
	}
	var p forkPreview
	json.Unmarshal([]byte(preview), &p)

	hostile := hostileBackup(t, root)
	os.WriteFile(f, hostile.zip(t, ""), 0o600) // same inode, new content
	if err := ImportForkBackup(int(fd.Fd()), "", home, true, p.Digest); !errors.Is(err, errBackupChanged) || err.Error() != ErrBackupChanged() {
		t.Fatalf("got %v, want %v", err, errBackupChanged)
	}
	if _, err := os.Stat(home); !errors.Is(err, os.ErrNotExist) {
		t.Error("something was installed")
	}
	// The same content zipped again (new salt, same entries) is the same backup.
	os.WriteFile(f, fx.zip(t, ""), 0o600)
	if err := ImportForkBackup(int(fd.Fd()), "", home, true, p.Digest); err != nil {
		t.Fatal(err)
	}
}

// A zip whose central directory alone would fill memory is refused before
// archive/zip keeps it.
func TestZipDirectoryBudget(t *testing.T) {
	var buf bytes.Buffer
	w := zip.NewWriter(&buf)
	for i := range 400_000 {
		h := &zip.FileHeader{Name: fmt.Sprintf("%07d", i), Method: zip.Store}
		if _, err := w.CreateHeader(h); err != nil {
			t.Fatal(err)
		}
	}
	w.Close()
	if _, err := read(buf.Bytes(), ""); err == nil || !strings.Contains(err.Error(), "too many files") {
		t.Errorf("got %v", err)
	}
}

// The folder path rule and the addresses the import keeps agree with the
// app's forms (ui/Validation.kt); its test reads the same file.
func TestImportPathAndAddressContract(t *testing.T) {
	var c struct {
		FolderPath []struct {
			Path   string
			Others []string
			OK     bool
		}
		ImportAddress []struct {
			Address, Kind string
			OK            bool
		}
	}
	if err := json.Unmarshal(golden(t, "../validation-contract.json"), &c); err != nil {
		t.Fatal(err)
	}
	if len(c.FolderPath) == 0 || len(c.ImportAddress) == 0 {
		t.Fatal("no cases")
	}
	for _, p := range c.FolderPath {
		if err := folderPathError(folderPath(p.Path), p.Others); (err == nil) != p.OK {
			t.Errorf("path %q (others %q): %v, want ok=%v", p.Path, p.Others, err, p.OK)
		}
	}
	for _, a := range c.ImportAddress {
		ok := isImportDeviceAddress(a.Address)
		if a.Kind == "listen" {
			ok = isImportListenAddress(a.Address)
		}
		if ok != a.OK {
			t.Errorf("%s address %q: %v, want %v", a.Kind, a.Address, ok, a.OK)
		}
	}
}

// Any config the core reads comes out of the policy safe, and stays the same
// when the policy runs again.
func FuzzImportPolicy(f *testing.F) {
	self := protocol.NewDeviceID([]byte("fuzz-self"))
	f.Add(hostileXML(f, "/storage/emulated/0", self))
	f.Add(fixtureConfig{Version: 37, Self: self, SelfName: "Old phone", Peer: fixturePeer,
		Folders: []fixtureFolder{{"f", "F", "/storage/emulated/0/F", "sendreceive"}}}.xml(f))
	f.Add([]byte(`<configuration version="52"><folder id="a" path="/sdcard/A/"><versioning type="staggered"><fsPath>x</fsPath></versioning></folder></configuration>`))
	f.Fuzz(func(t *testing.T, xml []byte) {
		cfg, _, err := config.ReadXML(bytes.NewReader(xml), self)
		if err != nil {
			return
		}
		importPolicy(&cfg)
		if err := checkPolicy(cfg); err != nil {
			t.Fatal(err)
		}
		again := cfg.Copy()
		if n := importPolicy(&again); len(n) != 0 || !reflect.DeepEqual(again, cfg.Copy()) {
			t.Fatalf("not idempotent: %s", noteKeys(n))
		}
	})
}
