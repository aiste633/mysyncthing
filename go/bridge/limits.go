// Copyright (C) 2026 mysyncthing contributors.
// SPDX-License-Identifier: AGPL-3.0-or-later

package stbridge

import (
	"errors"
	"fmt"
	"reflect"
	"strings"
	"unicode/utf8"

	"github.com/syncthing/syncthing/lib/config"
)

// Length limits. The app's forms keep every value within these
// (ui/Validation.kt Limits, same numbers); lengthGuard and importPolicy keep
// what reaches the core by other ways, the web GUI and imported backups,
// within them too. The core itself checks no length.
const (
	// maxText is the most characters (runes) of any text in the config. The
	// sync protocol carries device names, folder IDs and folder labels up to
	// 1024 (bep.proto, buf.validate max_len); a device drops the connection
	// of a peer that sends more, and a Hello over 32767 bytes, the device's
	// own name in it, makes the sender's core panic (lib/protocol/bep_hello.go).
	maxText = 1024
	// maxFileName is the most bytes of one path part (NAME_MAX).
	maxFileName = 255
	// maxPath is the most bytes of a whole path (PATH_MAX less the NUL).
	maxPath = 4095
	// maxEntries is the most entries of an address or server list.
	maxEntries = 16
	// maxListenTotal is the most bytes of all listen addresses together: the
	// global discovery server takes announcements of at most 8 KiB.
	maxListenTotal = 4096
	// maxIgnores is the most bytes of an ignore file the app edits.
	maxIgnores = 256_000
)

func textTooLong(s string) bool { return utf8.RuneCountInString(s) > maxText }

var errPathLong = errors.New("path too long")

// pathLengthError says whether path p has a part over maxFileName bytes or
// is over maxPath bytes in all.
func pathLengthError(p string) error {
	if len(p) > maxPath {
		return errPathLong
	}
	for _, part := range strings.Split(p, "/") {
		if len(part) > maxFileName {
			return errPathLong
		}
	}
	return nil
}

// lengthProblems lists every value in cfg over its limit, each as the
// field's path (list indices left out) and the value, so a change can be
// told from what was there before. Text in any field, also fields a later
// Syncthing adds, is held to maxText; paths, lists and ignore lines have
// their own limits.
func lengthProblems(cfg config.Configuration) map[string]bool {
	out := map[string]bool{}
	add := func(field, value string) { out[field+"\x00"+value] = true }
	var walk func(field string, v reflect.Value)
	walk = func(field string, v reflect.Value) {
		switch v.Kind() {
		case reflect.String:
			if textTooLong(v.String()) {
				add(field, v.String())
			}
		case reflect.Struct:
			t := v.Type()
			for i := range t.NumField() {
				f := t.Field(i)
				if !f.IsExported() {
					continue
				}
				name := field + "." + f.Name
				switch name {
				case ".Folders.Path", ".Defaults.Folder.Path", ".Folders.Versioning.FSPath", ".Defaults.Folder.Versioning.FSPath":
					if p := v.Field(i).String(); pathLengthError(p) != nil {
						add(name, p)
					}
					continue
				case ".Defaults.Ignores.Lines":
					if lines := strings.Join(v.Field(i).Interface().([]string), "\n"); len(lines) > maxIgnores {
						add(name, lines)
					}
					continue
				case ".Options.RawListenAddresses":
					if addrs := v.Field(i).Interface().([]string); len(addrs) > maxEntries || len(strings.Join(addrs, "")) > maxListenTotal {
						add(name, strings.Join(addrs, "\n"))
					}
				case ".Devices.Addresses":
					// An introducer's list, copied by the core as it adds the device, has
					// no limit in the protocol; the forms and imports keep their own within.
					continue
				case ".Options.RawGlobalAnnServers", ".Defaults.Device.Addresses":
					if list := v.Field(i).Interface().([]string); len(list) > maxEntries {
						add(name, strings.Join(list, "\n"))
					}
				}
				walk(name, v.Field(i))
			}
		case reflect.Slice, reflect.Array:
			if k := v.Type().Elem().Kind(); k != reflect.String && k != reflect.Struct && k != reflect.Pointer && k != reflect.Map && k != reflect.Slice && k != reflect.Interface {
				return
			}
			for i := range v.Len() {
				walk(field, v.Index(i))
			}
		case reflect.Map:
			for it := v.MapRange(); it.Next(); {
				walk(field+"{}", it.Key())
				walk(field+"[]", it.Value())
			}
		case reflect.Pointer, reflect.Interface:
			if !v.IsNil() {
				walk(field, v.Elem())
			}
		}
	}
	walk("", reflect.ValueOf(cfg))
	return out
}

// lengthGuard refuses a config change that brings in a value over its limit,
// such as a long name saved in the web GUI. Values already over (from a
// version without limits) don't block other changes.
type lengthGuard struct{}

func (lengthGuard) VerifyConfiguration(from, to config.Configuration) error {
	before := lengthProblems(from)
	for p := range lengthProblems(to) {
		if !before[p] {
			field := p[:strings.IndexByte(p, 0)]
			return fmt.Errorf("%s is too long: MySyncthing takes at most %d characters per name or address, %d entries per list, %d bytes per path (%d per folder name in it)",
				strings.TrimPrefix(field, "."), maxText, maxEntries, maxPath, maxFileName)
		}
	}
	return nil
}

func (lengthGuard) CommitConfiguration(_, _ config.Configuration) bool { return true }

func (lengthGuard) String() string { return "lengthGuard" }
