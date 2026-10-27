// Copyright (C) 2026 mysyncthing contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package stbridge

import (
	"archive/zip"
	"bytes"
	"cmp"
	"crypto/tls"
	"encoding/json"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"syscall"

	"golang.org/x/sys/unix"

	"github.com/syncthing/syncthing/lib/config"
	"github.com/syncthing/syncthing/lib/protocol"
	"github.com/syncthing/syncthing/lib/syncthing"
)

// Import of a Syncthing-Fork backup (Fork's Settings > Import and Export): a
// zip, optionally WinZip-AES encrypted, holding config.xml, cert.pem, key.pem,
// sharedpreferences.dat, the GUI's HTTPS certificate and the index database.
// Only the first four are read, in memory. The database is never imported:
// with an empty one the core treats every folder as new, so a file missing on
// this phone can not be mistaken for a deletion and spread to other devices.

// Entries read from a backup, with the most each may hold.
var forkEntries = map[string]int64{
	"config.xml":            4 << 20,
	"cert.pem":              64 << 10,
	"key.pem":               64 << 10,
	"sharedpreferences.dat": 1 << 20,
}

type forkBackup struct {
	certPEM, keyPEM []byte
	id              protocol.DeviceID
	configXML       []byte
	cfg             config.Configuration // migrated to the current version
	configVersion   int
	prefs           map[string]any
}

// ErrNeedPassword and ErrWrongPassword return the messages of the errors the
// app tells apart, to ask for a password or ask again.
func ErrNeedPassword() string  { return errNeedPassword.Error() }
func ErrWrongPassword() string { return errWrongPassword.Error() }

// InspectForkBackup reads the backup open at fd (the caller keeps it open
// and closes it) and returns a JSON preview (see forkPreview). Nothing is
// written.
func InspectForkBackup(fd int, password string) (string, error) {
	b, err := readForkBackupFd(fd, password)
	if err != nil {
		return "", err
	}
	bs, err := json.Marshal(b.preview(statFolder))
	return string(bs), err
}

// ImportForkBackup replaces the config under homeDir with the backup's. With
// keepIdentity the phone takes over the backup's device ID (its certificate
// and key); otherwise it keeps its own, or gets a new one, and the backup's
// ID is dropped from the config. The database is removed. The core must be
// stopped; the app's GUI login and API key are applied at the next start.
func ImportForkBackup(fd int, password, homeDir string, keepIdentity bool) error {
	b, err := readForkBackupFd(fd, password)
	if err != nil {
		return err
	}
	mu.Lock()
	defer mu.Unlock()
	if app != nil {
		return errors.New("stop Syncthing before importing")
	}
	return b.install(homeDir, keepIdentity)
}

func readForkBackupFd(fd int, password string) (*forkBackup, error) {
	dup, err := syscall.Dup(fd)
	if err != nil {
		return nil, err
	}
	f := os.NewFile(uintptr(dup), "backup")
	defer f.Close()
	size, err := f.Seek(0, io.SeekEnd)
	if err != nil {
		return nil, err
	}
	return readForkBackup(f, size, password)
}

func readForkBackup(r io.ReaderAt, size int64, password string) (*forkBackup, error) {
	z, err := zip.NewReader(r, size)
	if err != nil {
		return nil, fmt.Errorf("not a zip file: %w", err)
	}
	found := map[string]*zip.File{}
	for _, f := range z.File {
		if _, ok := forkEntries[f.Name]; !ok {
			continue // database, HTTPS certificate, anything else
		}
		if found[f.Name] != nil {
			return nil, fmt.Errorf("%s appears twice", f.Name)
		}
		found[f.Name] = f
	}
	for _, name := range []string{"config.xml", "cert.pem", "key.pem"} {
		if found[name] == nil {
			return nil, fmt.Errorf("not a Syncthing backup: %s is missing", name)
		}
	}
	// Encryption is per entry: an unencrypted entry next to encrypted ones
	// could have been added by anyone, so the password must cover them all.
	encrypted := 0
	for _, f := range found {
		if f.Flags&0x1 != 0 {
			encrypted++
		}
	}
	if encrypted != 0 && encrypted != len(found) {
		return nil, errors.New("the backup mixes encrypted and unencrypted files")
	}

	data := map[string][]byte{}
	for name, f := range found {
		if data[name], err = readZipEntry(f, password, forkEntries[name]); err != nil {
			return nil, err
		}
	}

	b := &forkBackup{certPEM: data["cert.pem"], keyPEM: data["key.pem"], configXML: data["config.xml"]}
	cert, err := tls.X509KeyPair(b.certPEM, b.keyPEM)
	if err != nil {
		return nil, fmt.Errorf("device certificate: %w", err)
	}
	b.id = protocol.NewDeviceID(cert.Certificate[0])

	// Checked before the core reads the config: it would add a missing own
	// device itself, and drop what a newer version added.
	var raw struct {
		Version int `xml:"version,attr"`
		Devices []struct {
			ID string `xml:"id,attr"`
		} `xml:"device"`
	}
	if err := xml.Unmarshal(b.configXML, &raw); err != nil {
		return nil, fmt.Errorf("config.xml: %w", err)
	}
	if raw.Version > config.CurrentVersion {
		return nil, fmt.Errorf("the backup is from a newer Syncthing (config version %d, this app reads up to %d)", raw.Version, config.CurrentVersion)
	}
	// The identity must be one of the config's devices, or certificate and
	// config do not belong together.
	own := false
	for _, d := range raw.Devices {
		id, err := protocol.DeviceIDFromString(d.ID)
		own = own || err == nil && id == b.id
	}
	if !own {
		return nil, errors.New("the device certificate does not belong to this config")
	}
	if b.cfg, b.configVersion, err = config.ReadXML(bytes.NewReader(b.configXML), b.id); err != nil {
		return nil, fmt.Errorf("config.xml: %w", err)
	}

	if dat := data["sharedpreferences.dat"]; dat != nil {
		if b.prefs, err = readJavaPrefs(dat); err != nil {
			return nil, fmt.Errorf("sharedpreferences.dat: %w", err)
		}
	}
	return b, nil
}

// install writes the backup's config (and identity) under homeDir. The new
// directory is built next to it and swapped in with one atomic rename, so an
// interruption leaves either the old setup or the new one, never a mix.
func (b *forkBackup) install(homeDir string, keepIdentity bool) error {
	homeDir = filepath.Clean(homeDir)
	staging := homeDir + ".import"
	if err := os.RemoveAll(staging); err != nil {
		return err
	}
	if err := os.MkdirAll(staging, 0o700); err != nil {
		return err
	}
	defer os.RemoveAll(staging) // after the swap, this is the old setup

	certFile, keyFile := filepath.Join(staging, "cert.pem"), filepath.Join(staging, "key.pem")
	cfg := b.cfg.Copy()
	if keepIdentity {
		if err := writeFileSync(certFile, b.certPEM); err != nil {
			return err
		}
		if err := writeFileSync(keyFile, b.keyPEM); err != nil {
			return err
		}
	} else {
		cert, err := ownCertificate(homeDir, certFile, keyFile)
		if err != nil {
			return err
		}
		if cfg, err = b.withIdentity(protocol.NewDeviceID(cert.Certificate[0])); err != nil {
			return err
		}
	}
	// The old install's GUI login and API key go; the app sets its own at the next start.
	cfg.GUI.User, cfg.GUI.Password, cfg.GUI.APIKey = "", "", ""
	cfg.Options.StartBrowser = false

	var buf bytes.Buffer
	if err := cfg.WriteXML(&buf); err != nil {
		return err
	}
	if err := writeFileSync(filepath.Join(staging, "config.xml"), buf.Bytes()); err != nil {
		return err
	}

	if _, err := os.Lstat(homeDir); errors.Is(err, os.ErrNotExist) {
		return os.Rename(staging, homeDir)
	}
	return unix.Renameat2(unix.AT_FDCWD, staging, unix.AT_FDCWD, homeDir, unix.RENAME_EXCHANGE)
}

// ownCertificate copies this phone's identity into the staging files, or
// creates one there if the phone has none yet.
func ownCertificate(homeDir, certFile, keyFile string) (tls.Certificate, error) {
	certPEM, err1 := os.ReadFile(filepath.Join(homeDir, filepath.Base(certFile)))
	keyPEM, err2 := os.ReadFile(filepath.Join(homeDir, filepath.Base(keyFile)))
	if err1 == nil && err2 == nil {
		cert, err := tls.X509KeyPair(certPEM, keyPEM)
		if err != nil {
			return tls.Certificate{}, fmt.Errorf("this phone's certificate: %w", err)
		}
		if err := writeFileSync(certFile, certPEM); err != nil {
			return tls.Certificate{}, err
		}
		return cert, writeFileSync(keyFile, keyPEM)
	}
	return syncthing.LoadOrGenerateCertificate(certFile, keyFile)
}

// withIdentity is the config for a phone with its own ID. The backup's ID is
// removed everywhere: the install it belongs to may still run, and is not a
// device this phone shares with. The core adds this phone (named by the app
// at start) and shares every folder with it.
func (b *forkBackup) withIdentity(id protocol.DeviceID) (config.Configuration, error) {
	cfg, _, err := config.ReadXML(bytes.NewReader(b.configXML), id)
	if err != nil {
		return config.Configuration{}, err
	}
	drop := func(ds []config.FolderDeviceConfiguration) []config.FolderDeviceConfiguration {
		return slices.DeleteFunc(ds, func(d config.FolderDeviceConfiguration) bool { return d.DeviceID == b.id })
	}
	cfg.Devices = slices.DeleteFunc(cfg.Devices, func(d config.DeviceConfiguration) bool { return d.DeviceID == b.id })
	for i := range cfg.Folders {
		cfg.Folders[i].Devices = drop(cfg.Folders[i].Devices)
	}
	cfg.Defaults.Folder.Devices = drop(cfg.Defaults.Folder.Devices)
	return cfg, nil
}

func writeFileSync(path string, data []byte) error {
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return err
	}
	if _, err := f.Write(data); err != nil {
		f.Close()
		return err
	}
	if err := f.Sync(); err != nil {
		f.Close()
		return err
	}
	return f.Close()
}

// forkPreview is what the app shows before importing.
type forkPreview struct {
	DeviceID      string          `json:"deviceID"`
	DeviceName    string          `json:"deviceName"`
	ConfigVersion int             `json:"configVersion"`
	Folders       []previewFolder `json:"folders"`
	Devices       []previewDevice `json:"devices"`
	Conditions    forkConditions  `json:"conditions"`
	NotImported   []notImported   `json:"notImported"`
}

type previewFolder struct {
	ID     string `json:"id"`
	Label  string `json:"label"`
	Path   string `json:"path"`
	Type   string `json:"type"`
	Exists bool   `json:"exists"` // the directory is there
	Marker bool   `json:"marker"` // and holds the folder marker
}

type previewDevice struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

func (b *forkBackup) preview(stat func(config.FolderConfiguration) (bool, bool)) forkPreview {
	p := forkPreview{
		DeviceID:      b.id.String(),
		ConfigVersion: b.configVersion,
		Folders:       []previewFolder{},
		Devices:       []previewDevice{},
	}
	for _, d := range b.cfg.Devices {
		if d.DeviceID == b.id {
			p.DeviceName = d.Name
		} else {
			p.Devices = append(p.Devices, previewDevice{d.DeviceID.String(), d.Name})
		}
	}
	for _, f := range b.cfg.Folders {
		exists, marker := stat(f)
		p.Folders = append(p.Folders, previewFolder{f.ID, f.Label, f.Path, f.Type.String(), exists, marker})
	}
	p.Conditions, p.NotImported = mapForkPrefs(b.prefs, b.cfg)
	return p
}

func statFolder(f config.FolderConfiguration) (exists, marker bool) {
	switch err := f.CheckPath(); {
	case err == nil:
		return true, true
	case errors.Is(err, config.ErrMarkerMissing):
		return true, false
	default:
		return false, false
	}
}

// forkConditions are the app's run conditions (core/Settings.kt RunConditions).
type forkConditions struct {
	WifiOnly            bool `json:"wifiOnly"`
	UnmeteredOnly       bool `json:"unmeteredOnly"`
	ChargingOnly        bool `json:"chargingOnly"`
	RespectBatterySaver bool `json:"respectBatterySaver"`
	StartOnBoot         bool `json:"startOnBoot"`
	PausedByUser        bool `json:"pausedByUser"`
}

// notImported is a Fork setting the app has no equivalent for. Key is stable
// for the app's texts; Subject names the folder or device it belongs to.
type notImported struct {
	Key     string `json:"key"`
	Subject string `json:"subject,omitempty"`
}

// mapForkPrefs maps Fork's preferences to the app's run conditions, with
// Fork's defaults for missing keys (Fork stores only changed ones).
//
// Networks: the app never syncs on a network Fork would not have used, since
// that could cost mobile data. Fork allows unmetered Wi-Fi (run_on_wifi),
// metered Wi-Fi (also run_on_metered_wifi) and mobile data (run_on_mobile_data);
// the app's choices allow all three, Wi-Fi only, or unmetered only. The
// widest choice inside Fork's set is taken; anything narrower is reported.
func mapForkPrefs(prefs map[string]any, cfg config.Configuration) (forkConditions, []notImported) {
	boolean := func(key string, def bool) bool {
		if v, ok := prefs[key].(bool); ok {
			return v
		}
		return def
	}
	str := func(key, def string) string {
		if v, ok := prefs[key].(string); ok {
			return v
		}
		return def
	}
	out := []notImported{}
	note := func(key, subject string) { out = append(out, notImported{key, subject}) }

	wifi := boolean("run_on_wifi", true)
	meteredWifi := wifi && boolean("run_on_metered_wifi", false)
	mobile := boolean("run_on_mobile_data", false)
	c := forkConditions{
		RespectBatterySaver: boolean("respect_battery_saving", true),
		StartOnBoot:         boolean("always_run_in_background", false),
	}
	switch {
	case wifi && meteredWifi && mobile:
	case wifi && meteredWifi:
		c.WifiOnly = true
	case wifi && !mobile:
		c.WifiOnly, c.UnmeteredOnly = true, true
	case wifi: // mobile data but not metered Wi-Fi
		c.UnmeteredOnly = true
		note("networks", "")
	default: // mobile data only, or no network at all
		c.WifiOnly, c.UnmeteredOnly = true, true
		note("networks", "")
	}
	switch str("power_source", "ac_and_battery_power") {
	case "ac_power":
		c.ChargingOnly = true
	case "battery_power":
		note("battery_only", "")
	}
	switch v, _ := prefs["btnStateForceStartStop"].(int32); v {
	case 1:
		note("force_start", "")
	case 2:
		c.PausedByUser = true
	}

	if mobile && !boolean("run_on_roaming", false) {
		note("roaming", "")
	}
	for _, s := range []struct {
		pref, key string
	}{
		{"run_in_flight_mode", "flight_mode"},
		{"respect_master_sync", "master_sync"},
		{"use_wifi_whitelist", "wifi_allowlist"},
		{"run_on_time_schedule", "schedule"},
		{"broadcast_service_control", "broadcasts"},
		{"use_tor", "tor"},
	} {
		if boolean(s.pref, false) {
			note(s.key, "")
		}
	}
	for _, s := range []struct {
		pref, key string
	}{
		{"socks_proxy_address", "socks_proxy"},
		{"http_proxy_address", "http_proxy"},
		{"environment_variables", "environment"},
	} {
		if strings.TrimSpace(str(s.pref, "")) != "" {
			note(s.key, "")
		}
	}
	// Per-object keys are "sc_folder_<id>_<setting>"; folder IDs may contain
	// "_", so they are matched against the config's IDs, never split.
	for _, f := range cfg.Folders {
		if boolean("sc_folder_"+f.ID+"_custom_sync_conditions", false) {
			note("folder_conditions", cmp.Or(f.Label, f.ID))
		}
		if boolean("sc_folder_"+f.ID+"_run_script", false) {
			note("folder_script", cmp.Or(f.Label, f.ID))
		}
	}
	for _, d := range cfg.Devices {
		if boolean("sc_device_"+d.DeviceID.String()+"_custom_sync_conditions", false) {
			note("device_conditions", cmp.Or(d.Name, d.DeviceID.Short().String()))
		}
	}
	return c, out
}
