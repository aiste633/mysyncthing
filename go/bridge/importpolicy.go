// Copyright (C) 2026 mysyncthing contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package stbridge

import (
	"cmp"
	"net/netip"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/syncthing/syncthing/lib/config"
	"github.com/syncthing/syncthing/lib/protocol"
)

// A backup is untrusted input: an unencrypted one sits in shared storage,
// where any app with All files access can rewrite it. The core accepts
// whatever its parser reads, so importPolicy takes out what could act beyond
// "a setup the user saw in the preview and approved":
//
//   - external versioning runs a command in the app's process;
//   - a custom versions folder is cleaned out by Trash can and Staggered
//     versioning, deleting every old file in it;
//   - a folder outside shared storage can hand the app's own files (keys,
//     login) to a peer, and a custom marker name turns off the guard against
//     an unmounted SD card;
//   - report and upgrade URLs, discovery, STUN and relay servers send this
//     phone's address and data to whoever runs them;
//   - control and bidi characters make the preview read differently from
//     what is imported.
//
// What it takes out is reset to the core's default and listed as not
// imported; the user can set it again on the phone. Applied to the config
// the preview shows and to the one installed, so both are the same.
func importPolicy(cfg *config.Configuration) []notImported {
	var out []notImported
	note := func(key, subject string) { out = append(out, notImported{key, subject}) }
	defaults := config.New(protocol.EmptyDeviceID)

	// Folders. Two that overlap (the same, one inside the other) would sync
	// the same files twice; which one was meant can't be told, so neither
	// is imported.
	var kept []config.FolderConfiguration
	for _, f := range cfg.Folders {
		f.Label = cleanText(f.Label)
		name := cmp.Or(f.Label, cleanText(f.ID))
		switch {
		case hasUnsafeText(f.ID):
			note("folder_id", name)
			continue
		case f.FilesystemType != config.FilesystemTypeBasic:
			note("folder_filesystem", name)
			continue
		}
		f.Path = folderPath(f.Path)
		if folderPathError(f.Path, nil) != nil {
			note("folder_path", name)
			continue
		}
		kept = append(kept, f)
	}
	overlaps := make([]bool, len(kept))
	for i := range kept {
		for j := range kept {
			if i != j && folderPathError(kept[i].Path, []string{kept[j].Path}) == errPathOverlap {
				overlaps[i] = true
			}
		}
	}
	safe := make([]config.FolderConfiguration, 0, len(kept))
	for i, f := range kept {
		if overlaps[i] {
			note("folder_overlap", cmp.Or(f.Label, f.ID))
			continue
		}
		for _, key := range safeFolderOptions(&f) {
			note(key, cmp.Or(f.Label, f.ID))
		}
		safe = append(safe, f)
	}
	cfg.Folders = safe

	// Defaults for new folders: "" or "~" is the core's default (the home
	// directory), which the app points at Syncthing/ in shared storage.
	df := &cfg.Defaults.Folder
	if df.Path != "" && df.Path != "~" && folderPathError(folderPath(df.Path), nil) != nil || df.FilesystemType != config.FilesystemTypeBasic {
		df.Path, df.FilesystemType = defaults.Defaults.Folder.Path, config.FilesystemTypeBasic
		note("default_folder_path", "")
	}
	for _, key := range safeFolderOptions(df) {
		note("default_"+key, "")
	}

	for i := range cfg.Devices {
		d := &cfg.Devices[i]
		d.Name = cleanText(d.Name)
		name := cmp.Or(d.Name, d.DeviceID.Short().String())
		if dropped := safeDevice(d); dropped != "" {
			note(dropped, name)
		}
	}
	if dropped := safeDevice(&cfg.Defaults.Device); dropped != "" {
		note("default_"+dropped, "")
	}

	o, def := &cfg.Options, defaults.Options
	// Reports and upgrade checks go to the Syncthing project's servers only.
	// Consent is never taken from a backup: the app asks again. A refusal
	// is kept, it can't send anything.
	o.URURL, o.CRURL, o.ReleasesURL = def.URURL, def.CRURL, def.ReleasesURL
	if o.URAccepted > 0 {
		o.URAccepted, o.URSeen = 0, 0
	}
	o.URUniqueID = ""
	o.AuditEnabled, o.AuditFile = false, ""
	if !onlyDefault(o.RawGlobalAnnServers) {
		o.RawGlobalAnnServers = def.RawGlobalAnnServers
		note("discovery_servers", "")
	}
	if !onlyDefault(o.RawStunServers) {
		o.RawStunServers = def.RawStunServers
		note("stun_servers", "")
	}
	if listen, ok := keepIf(o.RawListenAddresses, func(a string) bool { return a == "default" || isImportListenAddress(a) }); !ok {
		o.RawListenAddresses = orIfEmpty(listen, def.RawListenAddresses)
		note("listen_addresses", "")
	}
	if nets, ok := keepIf(o.AlwaysLocalNets, isNetwork); !ok {
		o.AlwaysLocalNets = nets
		note("local_networks", "")
	}
	if o.LocalAnnMCAddr != def.LocalAnnMCAddr || o.LocalAnnPort != def.LocalAnnPort {
		o.LocalAnnMCAddr, o.LocalAnnPort = def.LocalAnnMCAddr, def.LocalAnnPort
		note("local_discovery", "")
	}
	if len(o.FeatureFlags) > 0 {
		o.FeatureFlags = nil
		note("feature_flags", "")
	}
	o.StartBrowser = false

	// The GUI: login and access are set by the app at every start (locked);
	// the rest is reset to what the app expects.
	cfg.GUI.User, cfg.GUI.Password, cfg.GUI.APIKey = "", "", ""
	cfg.GUI.RawAddress, cfg.GUI.RawUnixSocketPermissions = defaults.GUI.RawAddress, ""
	cfg.GUI = locked(cfg.GUI)
	if !themeName.MatchString(cfg.GUI.Theme) {
		cfg.GUI.Theme = defaults.GUI.Theme
	}
	cfg.LDAP = config.LDAPConfiguration{}
	return out
}

var themeName = regexp.MustCompile(`^[a-z]{1,32}$`)

// safeFolderOptions resets a folder's versioning and marker to what can't
// act outside the folder, and returns the keys of what it reset.
func safeFolderOptions(f *config.FolderConfiguration) []string {
	var keys []string
	v := &f.Versioning
	switch v.Type {
	case "", "simple", "staggered", "trashcan":
	default: // "external" runs a command; anything else the core can't run
		v.Type, v.Params = "", nil
		keys = append(keys, "folder_versioning")
	}
	if v.FSPath != "" || v.FSType != config.FilesystemTypeBasic {
		v.FSPath, v.FSType = "", config.FilesystemTypeBasic
		keys = append(keys, "folder_versions_path")
	}
	if f.MarkerName != config.DefaultMarkerName {
		f.MarkerName = config.DefaultMarkerName
		keys = append(keys, "folder_marker")
	}
	return keys
}

// safeDevice keeps the addresses and networks of d the app's forms accept,
// except relays (a server of the backup's choosing), and returns the key of
// what it dropped, or "". With no address left the device is found by
// discovery ("dynamic").
func safeDevice(d *config.DeviceConfiguration) string {
	addrs, okAddrs := keepIf(d.Addresses, func(a string) bool { return a == "dynamic" || isImportDeviceAddress(a) })
	nets, okNets := keepIf(d.AllowedNetworks, isNetwork)
	d.Addresses, d.AllowedNetworks = orIfEmpty(addrs, []string{"dynamic"}), nets
	if !okAddrs || !okNets {
		return "device_addresses"
	}
	return ""
}

// keepIf returns the elements of s that pass keep, and whether all did.
func keepIf(s []string, keep func(string) bool) ([]string, bool) {
	out := slices.DeleteFunc(slices.Clone(s), func(e string) bool { return !keep(e) })
	return out, len(out) == len(s)
}

func orIfEmpty(s, def []string) []string {
	if len(s) == 0 {
		return def
	}
	return s
}

func onlyDefault(s []string) bool {
	return !slices.ContainsFunc(s, func(e string) bool { return e != "default" })
}

func isNetwork(s string) bool {
	_, err := netip.ParsePrefix(s)
	return err == nil
}

// --- Text

// unsafeRune is what can make text read differently from what it is: control
// characters (C0, DEL, C1) and the bidi controls (U+061C, U+200E-200F,
// U+202A-202E, U+2066-2069) that reorder what follows them.
func unsafeRune(r rune) bool {
	return unicode.IsControl(r) || r == 0x061C || r == 0x200E || r == 0x200F ||
		r >= 0x202A && r <= 0x202E || r >= 0x2066 && r <= 0x2069
}

func hasUnsafeText(s string) bool {
	return !utf8.ValidString(s) || strings.ContainsFunc(s, unsafeRune)
}

// cleanText replaces unsafe characters in a name or label with U+FFFD, so the
// preview shows that something was there.
func cleanText(s string) string {
	return strings.Map(func(r rune) rune {
		if unsafeRune(r) {
			return utf8.RuneError
		}
		return r
	}, strings.ToValidUTF8(s, string(utf8.RuneError)))
}

// --- Folder paths: ui/Validation.kt folderPath and folderPathError, pinned to
// the same cases by testdata/validation-contract.json.

const internalStorage = "/storage/emulated/0"

// sharedStorage is where folders may be: shared storage and SD cards. Tests
// that start the core point it at a temporary directory.
var sharedStorage = "/storage"

type pathError string

func (e pathError) Error() string { return string(e) }

const (
	errPathRequired = pathError("folder path is empty")
	errPathAbsolute = pathError("folder path is not absolute")
	errPathControl  = pathError("folder path has control characters")
	errPathParts    = pathError("folder path has empty, . or .. parts")
	errPathStorage  = pathError("folder path is not in shared storage or on an SD card")
	errPathOverlap  = pathError("folder path is the same as, inside or around another folder")
)

// folderPath is a path as the app saves it: trimmed, without a trailing "/",
// and /sdcard, the old name of internal storage, spelled out.
func folderPath(path string) string {
	p := strings.TrimRight(strings.TrimSpace(path), "/")
	for _, alias := range []string{"/sdcard", "/storage/self/primary"} {
		if p == alias || strings.HasPrefix(p, alias+"/") {
			return internalStorage + strings.TrimPrefix(p, alias)
		}
	}
	return p
}

// folderPathError checks p, as folderPath returns it, against the paths of
// other folders.
func folderPathError(p string, others []string) error {
	inside := func(a, b string) bool { return a == b || strings.HasPrefix(a, b+"/") }
	switch {
	case p == "":
		return errPathRequired
	case !strings.HasPrefix(p, "/"):
		return errPathAbsolute
	case hasUnsafeText(p):
		return errPathControl
	case slices.ContainsFunc(strings.Split(p, "/")[1:], func(s string) bool { return s == "" || s == "." || s == ".." }):
		return errPathParts
	case !inside(p, sharedStorage) || p == sharedStorage || p == sharedStorage+"/emulated":
		return errPathStorage
	}
	for _, o := range others {
		if o = folderPath(o); inside(p, o) || inside(o, p) {
			return errPathOverlap
		}
	}
	return nil
}

// --- Addresses: the TCP and QUIC part of ui/Validation.kt isDeviceAddress
// and isListenAddress. Relay and dynamic+ URLs name a server and are not
// imported; the forms take them, so the app accepts all the import keeps.

var (
	urlHead = regexp.MustCompile(`^([a-z][a-z0-9+.-]*)://([^/?#\t\n\x0B\f\r ]*)([^\t\n\x0B\f\r ]*)$`)
	hostRe  = regexp.MustCompile(`^[A-Za-z0-9._-]+$`)
	portRe  = regexp.MustCompile(`^[0-9]{1,5}$`)
	ip6Re   = regexp.MustCompile(`^[0-9A-Fa-f:.]+$`)
)

type hostPort struct {
	scheme, host string
	port         int // -1 when left out
	rest         string
}

// splitHostPort splits an address URL as ui/Validation.kt hostPort does.
func splitHostPort(s string) (hostPort, bool) {
	m := urlHead.FindStringSubmatch(s)
	if m == nil || strings.Contains(m[2], "@") {
		return hostPort{}, false
	}
	a := hostPort{scheme: m[1], port: -1, rest: m[3]}
	authority, port := m[2], ""
	switch {
	case strings.HasPrefix(authority, "["):
		end := strings.Index(authority, "]")
		if end < 0 {
			return hostPort{}, false
		}
		after := authority[end+1:]
		if after != "" && !strings.HasPrefix(after, ":") || !ip6Re.MatchString(authority[1:end]) {
			return hostPort{}, false
		}
		a.host, port = authority[1:end], strings.TrimPrefix(after, ":")
	case strings.Count(authority, ":") > 1:
		return hostPort{}, false
	default:
		a.host, port, _ = strings.Cut(authority, ":")
		if a.host != "" && !hostRe.MatchString(a.host) {
			return hostPort{}, false
		}
	}
	if port != "" {
		n, err := strconv.Atoi(port)
		if !portRe.MatchString(port) || err != nil || n > 65535 {
			return hostPort{}, false
		}
		a.port = n
	}
	return a, true
}

var tcpQuic = map[string]bool{"tcp": true, "tcp4": true, "tcp6": true, "quic": true, "quic4": true, "quic6": true}

func isImportDeviceAddress(s string) bool {
	a, ok := splitHostPort(s)
	return ok && tcpQuic[a.scheme] && a.host != "" && a.port != 0 && (a.rest == "" || a.rest == "/")
}

func isImportListenAddress(s string) bool {
	a, ok := splitHostPort(s)
	return ok && tcpQuic[a.scheme] && (a.host != "" || strings.Contains(s, "://:")) && (a.rest == "" || a.rest == "/")
}
