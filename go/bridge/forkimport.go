// Copyright (C) 2026 mysyncthing contributors.
// SPDX-License-Identifier: AGPL-3.0-or-later

package stbridge

import (
	"archive/zip"
	"bytes"
	"cmp"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"maps"
	"net/url"
	"os"
	"path/filepath"
	"slices"
	"strconv"
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
// Only the first four are read, in memory. The app's own export (export.go)
// has the same layout with mysyncthing.json in place of the preferences. The database is never imported:
// with an empty one the core treats every folder as new, so a file missing on
// this phone can not be mistaken for a deletion and spread to other devices.

// Entries read from a backup, with the most each may hold.
var forkEntries = map[string]int64{
	"config.xml":            4 << 20,
	"cert.pem":              64 << 10,
	"key.pem":               64 << 10,
	"sharedpreferences.dat": 1 << 20,
	ownSettingsFile:         64 << 10,
}

type forkBackup struct {
	certPEM, keyPEM []byte
	id              protocol.DeviceID
	configXML       []byte
	cfg             config.Configuration // migrated to the current version
	configVersion   int
	prefs           map[string]any // Fork's preferences
	own             *ownSettings   // or the app's own settings
	policy          []notImported  // what importPolicy took out of the config
	digest          string         // of the entries read, see backupDigest
}

// ErrNeedPassword and ErrWrongPassword return the messages of the errors the
// app tells apart, to ask for a password or ask again.
func ErrNeedPassword() string  { return errNeedPassword.Error() }
func ErrWrongPassword() string { return errWrongPassword.Error() }
func ErrBackupChanged() string { return errBackupChanged.Error() }

var errBackupChanged = errors.New("the backup changed after the preview")

// InspectForkBackup reads the backup open at fd (the caller keeps it open
// and closes it) and returns a JSON preview (see forkPreview). Nothing is
// written. The preview's digest goes to ImportForkBackup.
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
// digest is the preview's: the file is read again here, and must still be
// the one the user saw (another app may have replaced it meanwhile).
func ImportForkBackup(fd int, password, homeDir string, keepIdentity bool, digest string) error {
	b, err := readForkBackupFd(fd, password)
	if err != nil {
		return err
	}
	if b.digest != digest {
		return errBackupChanged
	}
	mu.Lock()
	defer mu.Unlock()
	if app != nil {
		return errors.New("stop Syncthing before importing")
	}
	return b.install(homeDir, keepIdentity)
}

// maxImportPassword bounds what reaches the key derivation: the app allows
// 1024 characters (ui/ImportScreen.kt MAX_BACKUP_PASSWORD), at most 3 bytes each in UTF-8.
const maxImportPassword = 3 * 1024

func readForkBackupFd(fd int, password string) (*forkBackup, error) {
	if len(password) > maxImportPassword {
		return nil, fmt.Errorf("password longer than %d bytes", maxImportPassword)
	}
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

// maxZipDirectory bounds the zip's central directory, which archive/zip
// reads into memory, entry by entry, before any name can be checked. A Fork
// backup holds the database too (LevelDB: thousands of files at most, about
// 100 bytes each); millions of tiny entries would exhaust memory.
const maxZipDirectory = 16 << 20

func readForkBackup(r io.ReaderAt, size int64, password string) (*forkBackup, error) {
	br := &budgetReaderAt{r: r, left: maxZipDirectory}
	z, err := zip.NewReader(br, size)
	if errors.Is(err, errZipDirectory) {
		return nil, errors.New("the backup holds too many files")
	} else if err != nil {
		return nil, fmt.Errorf("not a zip file: %w", err)
	}
	br.lift()
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

	b := &forkBackup{certPEM: data["cert.pem"], keyPEM: data["key.pem"], configXML: data["config.xml"], digest: backupDigest(data)}
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
	if b.cfg, b.configVersion, b.policy, err = readConfig(b.configXML, b.id); err != nil {
		return nil, fmt.Errorf("config.xml: %w", err)
	}

	if data["sharedpreferences.dat"] != nil && data[ownSettingsFile] != nil {
		return nil, errors.New("not a Syncthing backup: it holds settings of two apps")
	}
	if js := data[ownSettingsFile]; js != nil {
		if b.own, err = readOwnSettings(js); err != nil {
			return nil, fmt.Errorf("%s: %w", ownSettingsFile, err)
		}
	}
	if dat := data["sharedpreferences.dat"]; dat != nil {
		if b.prefs, err = readJavaPrefs(dat); err != nil {
			return nil, fmt.Errorf("sharedpreferences.dat: %w", err)
		}
	}
	return b, nil
}

// readConfig reads config.xml for the device id, made safe by importPolicy.
func readConfig(xml []byte, id protocol.DeviceID) (config.Configuration, int, []notImported, error) {
	cfg, version, err := config.ReadXML(bytes.NewReader(xml), id)
	if err != nil {
		return config.Configuration{}, 0, nil, err
	}
	return cfg, version, importPolicy(&cfg), nil
}

// backupDigest identifies what was read from a backup: SHA-256 over each
// entry's name, length and content, in name order.
func backupDigest(data map[string][]byte) string {
	h := sha256.New()
	for _, name := range slices.Sorted(maps.Keys(data)) {
		fmt.Fprintf(h, "%s\x00%d\x00", name, len(data[name]))
		h.Write(data[name])
	}
	return hex.EncodeToString(h.Sum(nil))
}

// budgetReaderAt lets zip.NewReader read at most left bytes, which bounds the
// central directory it keeps in memory. Entry contents are read after lift,
// with their own limits (readZipEntry).
type budgetReaderAt struct {
	r      io.ReaderAt
	left   int64
	lifted bool
}

var errZipDirectory = errors.New("zip directory too large")

func (b *budgetReaderAt) ReadAt(p []byte, off int64) (int, error) {
	if !b.lifted {
		if int64(len(p)) > b.left {
			return 0, errZipDirectory
		}
		b.left -= int64(len(p))
	}
	return b.r.ReadAt(p, off)
}

func (b *budgetReaderAt) lift() { b.lifted = true }

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
	// importPolicy dropped the old install's GUI login and API key; the app
	// sets its own at the next start.
	unpause(&cfg) // also for exports from other apps, or older ones

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
	cfg, _, _, err := readConfig(b.configXML, id)
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

// unpause resumes every device and folder. A pause is the phone's state at
// the time (often a run condition's pause of all devices), not a setting: a
// setup taken from a backup starts syncing, and this phone's own pause
// switch and run conditions decide from there.
func unpause(cfg *config.Configuration) {
	for i := range cfg.Devices {
		cfg.Devices[i].Paused = false
	}
	for i := range cfg.Folders {
		cfg.Folders[i].Paused = false
	}
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
	Source        string          `json:"source"` // "fork" or "own" (this app's export)
	DeviceID      string          `json:"deviceID"`
	DeviceName    string          `json:"deviceName"`
	ConfigVersion int             `json:"configVersion"`
	Folders       []previewFolder `json:"folders"`
	Devices       []previewDevice `json:"devices"`
	Conditions    runConditions   `json:"conditions"`
	Proxy         proxySettings   `json:"proxy"`
	NotImported   []notImported   `json:"notImported"`
	Digest        string          `json:"digest"` // passed back to ImportForkBackup
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
	// May add devices (introducer) or folders (auto-accept) to this phone:
	// the preview says so.
	Introducer bool `json:"introducer"`
	AutoAccept bool `json:"autoAccept"`
}

func (b *forkBackup) preview(stat func(config.FolderConfiguration) (bool, bool)) forkPreview {
	p := forkPreview{
		DeviceID:      b.id.String(),
		ConfigVersion: b.configVersion,
		Folders:       []previewFolder{},
		Devices:       []previewDevice{},
		Digest:        b.digest,
	}
	for _, d := range b.cfg.Devices {
		if d.DeviceID == b.id {
			p.DeviceName = d.Name
		} else {
			p.Devices = append(p.Devices, previewDevice{d.DeviceID.String(), d.Name, d.Introducer, d.AutoAcceptFolders})
		}
	}
	for _, f := range b.cfg.Folders {
		exists, marker := stat(f)
		p.Folders = append(p.Folders, previewFolder{f.ID, f.Label, f.Path, f.Type.String(), exists, marker})
	}
	if b.own != nil {
		p.Source, p.Conditions, p.Proxy, p.NotImported = "own", b.own.Conditions, b.own.Proxy, []notImported{}
	} else {
		p.Source = "fork"
		p.Conditions, p.Proxy, p.NotImported = mapForkPrefs(b.prefs, b.cfg)
	}
	p.NotImported = append(p.NotImported, b.policy...)
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

// runConditions are the app's run conditions (core/Settings.kt RunConditions),
// without the user's pause: a backup never pauses syncing (see unpause).
type runConditions struct {
	MeteredWifi         bool   `json:"meteredWifi"`
	MobileData          bool   `json:"mobileData"`
	Roaming             bool   `json:"roaming"`
	Power               string `json:"power"` // Any, ChargingOnly, BatteryOnly
	RespectBatterySaver bool   `json:"respectBatterySaver"`
	StartOnBoot         bool   `json:"startOnBoot"`
	TimeSchedule        bool   `json:"timeSchedule"`
	// Kept while TimeSchedule is off. Left out when empty: the app reads a
	// missing list as empty, but not null.
	Windows []syncWindow `json:"windows,omitempty"`
}

// syncWindow is a time of day the app may sync in (core/Schedule.kt
// SyncWindow): Start to End in minutes after midnight, by the phone's clock;
// an End before Start ends the next day. Days are ISO weekdays, 1 (Monday)
// to 7, on which the window starts.
type syncWindow struct {
	Start int   `json:"start"`
	End   int   `json:"end"`
	Days  []int `json:"days"`
}

const (
	minutesPerDay  = 24 * 60
	maxSyncWindows = 8
)

// check refuses what the app could not store (core/Schedule.kt syncWindowError).
func (w syncWindow) check() error {
	switch {
	case w.Start < 0 || w.Start >= minutesPerDay || w.End < 0 || w.End >= minutesPerDay:
		return fmt.Errorf("time window %d-%d: times from 0 to %d minutes", w.Start, w.End, minutesPerDay-1)
	case w.Start == w.End:
		return errors.New("time window: start and end are the same")
	case len(w.Days) == 0:
		return errors.New("time window without days")
	}
	seen := map[int]bool{}
	for _, d := range w.Days {
		if d < 1 || d > 7 || seen[d] {
			return fmt.Errorf("time window: day %d", d)
		}
		seen[d] = true
	}
	return nil
}

// check refuses run conditions the app could not store (core/Schedule.kt scheduleError).
func (c runConditions) check() error {
	if !powerSources[c.Power] {
		return fmt.Errorf("power source %q", c.Power)
	}
	switch {
	case len(c.Windows) > maxSyncWindows:
		return fmt.Errorf("%d time windows, at most %d", len(c.Windows), maxSyncWindows)
	case c.TimeSchedule && len(c.Windows) == 0:
		return errors.New("time schedule without time windows")
	}
	for _, w := range c.Windows {
		if err := w.check(); err != nil {
			return err
		}
	}
	return nil
}

// proxySettings is the app's proxy (core/Settings.kt ProxySettings).
type proxySettings struct {
	Type     string `json:"type"` // None, Socks5, Http
	Host     string `json:"host"`
	Port     int    `json:"port"`
	User     string `json:"user"`
	Password string `json:"password"`
	Only     bool   `json:"only"`
}

var (
	proxySchemes = map[string]string{"None": "", "Socks5": "socks5", "Http": "http"}
	powerSources = map[string]bool{"Any": true, "ChargingOnly": true, "BatteryOnly": true}
	torProxy     = proxySettings{Type: "Socks5", Host: "127.0.0.1", Port: 9050, Only: true}
)

// check refuses what the app could not apply: SetProxy's rules.
func (p proxySettings) check() error {
	scheme, ok := proxySchemes[p.Type]
	if !ok {
		return fmt.Errorf("proxy type %q", p.Type)
	}
	if scheme == "" {
		if p != (proxySettings{Type: "None"}) {
			return errors.New("proxy fields without a proxy type")
		}
		return nil
	}
	_, err := proxyFrom(scheme, p.Host, p.Port, p.User, p.Password)
	return err
}

// forkProxy reads a proxy address as Fork stores it: a URL with the scheme
// socks5 or http, a host and a port.
func forkProxy(addr, wantScheme string) (proxySettings, bool) {
	u, err := url.Parse(strings.TrimSpace(addr))
	if err != nil || u.Scheme != wantScheme || u.Path != "" && u.Path != "/" || u.RawQuery != "" || u.Fragment != "" {
		return proxySettings{}, false
	}
	port, err := strconv.Atoi(u.Port())
	if err != nil {
		return proxySettings{}, false
	}
	p := proxySettings{Type: map[string]string{"socks5": "Socks5", "http": "Http"}[wantScheme], Host: u.Hostname(), Port: port}
	if u.User != nil {
		p.User = u.User.Username()
		p.Password, _ = u.User.Password()
	}
	return p, p.check() == nil
}

// notImported is a Fork setting the app has no equivalent for. Key is stable
// for the app's texts; Subject names the folder or device it belongs to.
type notImported struct {
	Key     string `json:"key"`
	Subject string `json:"subject,omitempty"`
}

// mapForkPrefs maps Fork's preferences to the app's run conditions and
// proxy, with Fork's defaults for missing keys (Fork stores only changed ones).
//
// Networks: Fork allows unmetered Wi-Fi (run_on_wifi), metered Wi-Fi (also
// run_on_metered_wifi), mobile data (run_on_mobile_data) and roaming (also
// run_on_roaming); the app has the same choices, except that it always
// allows unmetered Wi-Fi. Fork without Wi-Fi gets no metered Wi-Fi either and
// is reported. The app never syncs on a network Fork would not have used.
func mapForkPrefs(prefs map[string]any, cfg config.Configuration) (runConditions, proxySettings, []notImported) {
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
	mobile := boolean("run_on_mobile_data", false)
	c := runConditions{
		MeteredWifi:         wifi && boolean("run_on_metered_wifi", false),
		MobileData:          mobile,
		Roaming:             mobile && boolean("run_on_roaming", false),
		Power:               "Any",
		RespectBatterySaver: boolean("respect_battery_saving", true),
		StartOnBoot:         boolean("always_run_in_background", false),
	}
	if !wifi {
		note("networks", "")
	}
	switch str("power_source", "ac_and_battery_power") {
	case "ac_power":
		c.Power = "ChargingOnly"
	case "battery_power":
		c.Power = "BatteryOnly"
	}
	switch v, _ := prefs["btnStateForceStartStop"].(int32); v {
	case 1:
		note("force_start", "")
	case 2:
		note("force_stop", "")
	}

	// Tor wins over the addresses, as in Fork; one proxy is taken.
	proxy := proxySettings{Type: "None"}
	if boolean("use_tor", false) {
		proxy = torProxy
	}
	for _, s := range []struct{ pref, scheme, key string }{
		{"socks_proxy_address", "socks5", "socks_proxy"},
		{"http_proxy_address", "http", "http_proxy"},
	} {
		addr := strings.TrimSpace(str(s.pref, ""))
		if addr == "" {
			continue
		}
		if p, ok := forkProxy(addr, s.scheme); ok && proxy.Type == "None" {
			proxy = p
		} else {
			note(s.key, "")
		}
	}

	for _, s := range []struct {
		pref, key string
	}{
		{"run_in_flight_mode", "flight_mode"},
		{"respect_master_sync", "master_sync"},
		{"use_wifi_whitelist", "wifi_allowlist"},
		{"run_on_time_schedule", "schedule"},
		{"broadcast_service_control", "broadcasts"},
	} {
		if boolean(s.pref, false) {
			note(s.key, "")
		}
	}
	if strings.TrimSpace(str("environment_variables", "")) != "" {
		note("environment", "")
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
	return c, proxy, out
}
