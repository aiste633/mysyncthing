// Copyright (C) 2026 mysyncthing contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package stbridge

import (
	"archive/zip"
	"bytes"
	"compress/flate"
	"crypto/hmac"
	"crypto/pbkdf2"
	"crypto/rand"
	"crypto/sha1" //nolint:gosec // WinZip AES is defined with SHA-1
	"crypto/tls"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/syncthing/syncthing/lib/config"
	"github.com/syncthing/syncthing/lib/protocol"
)

// The app's own backup: config.xml, cert.pem and key.pem as the core keeps
// them, plus the app's settings in ownSettingsFile. Nothing else: no database
// (see forkimport.go), no GUI login or API key (each install has its own).
// With a password every entry is WinZip AES-256 (AE-2), which zip4j, 7-Zip
// and this app's import read.

const (
	ownSettingsFile    = "mysyncthing.json"
	ownSettingsVersion = 1
	// WinZip AES has a fixed 1000 PBKDF2 rounds, so the password itself must
	// be hard to guess.
	minExportPassword = 12
	maxExportPassword = 1024 // bytes
)

// ownSettings is ownSettingsFile: the app's run conditions and proxy.
type ownSettings struct {
	Version    int           `json:"version"`
	Conditions runConditions `json:"conditions"`
	Proxy      proxySettings `json:"proxy"`
}

// readOwnSettings checks the settings as strictly as the app would apply them.
func readOwnSettings(js []byte) (*ownSettings, error) {
	var head struct {
		Version int `json:"version"`
	}
	if err := json.Unmarshal(js, &head); err != nil {
		return nil, err
	}
	if head.Version != ownSettingsVersion {
		return nil, fmt.Errorf("version %d, this app reads version %d", head.Version, ownSettingsVersion)
	}
	var s ownSettings
	dec := json.NewDecoder(bytes.NewReader(js))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&s); err != nil {
		return nil, err
	}
	if dec.More() {
		return nil, errors.New("data after the settings")
	}
	if err := s.Conditions.check(); err != nil {
		return nil, err
	}
	if err := s.Proxy.check(); err != nil {
		return nil, err
	}
	return &s, nil
}

// ExportPasswordError says why password can't protect an export, or returns
// "" when it can. Empty means no password.
func ExportPasswordError(password string) string {
	switch {
	case password == "":
		return ""
	case !utf8.ValidString(password) || strings.IndexFunc(password, unicode.IsControl) >= 0:
		return "no line breaks or control characters"
	case utf8.RuneCountInString(password) < minExportPassword:
		return fmt.Sprintf("at least %d characters", minExportPassword)
	case len(password) > maxExportPassword:
		return fmt.Sprintf("at most %d bytes", maxExportPassword)
	}
	return ""
}

// ExportBackup writes a backup of the setup under homeDir to fd (the caller
// keeps it open and closes it), encrypted when password is set. settingsJSON
// is ownSettingsFile's content; it is checked like an import would check it.
// The core may be running: it replaces config.xml atomically.
func ExportBackup(fd int, homeDir, password, settingsJSON string) error {
	dup, err := syscall.Dup(fd)
	if err != nil {
		return err
	}
	f := os.NewFile(uintptr(dup), "export")
	defer f.Close()
	var buf bytes.Buffer
	if err := writeBackup(&buf, homeDir, password, []byte(settingsJSON), time.Now()); err != nil {
		return err
	}
	if err := f.Truncate(0); err != nil && !errors.Is(err, syscall.EINVAL) {
		return err // EINVAL: a pipe from a document provider, nothing to cut
	}
	if _, err := f.Write(buf.Bytes()); err != nil {
		return err
	}
	if err := f.Sync(); err != nil && !errors.Is(err, syscall.EINVAL) {
		return err
	}
	return nil
}

func writeBackup(w io.Writer, homeDir, password string, settingsJSON []byte, now time.Time) error {
	if msg := ExportPasswordError(password); msg != "" {
		return fmt.Errorf("password: %s", msg)
	}
	if _, err := readOwnSettings(settingsJSON); err != nil {
		return fmt.Errorf("%s: %w", ownSettingsFile, err)
	}
	read := func(name string) ([]byte, error) {
		b, err := os.ReadFile(filepath.Join(homeDir, name))
		if err == nil && int64(len(b)) > forkEntries[name] {
			err = fmt.Errorf("%s is too large", name)
		}
		return b, err
	}
	certPEM, err := read("cert.pem")
	if err != nil {
		return err
	}
	keyPEM, err := read("key.pem")
	if err != nil {
		return err
	}
	cert, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		return fmt.Errorf("device certificate: %w", err)
	}
	configXML, err := read("config.xml")
	if err != nil {
		return err
	}
	cfg, _, err := config.ReadXML(bytes.NewReader(configXML), protocol.NewDeviceID(cert.Certificate[0]))
	if err != nil {
		return fmt.Errorf("config.xml: %w", err)
	}
	cfg.GUI.User, cfg.GUI.Password, cfg.GUI.APIKey = "", "", ""
	unpause(&cfg)
	var cfgBuf bytes.Buffer
	if err := cfg.WriteXML(&cfgBuf); err != nil {
		return err
	}

	z := zip.NewWriter(w)
	for _, e := range []struct {
		name string
		data []byte
	}{
		{"config.xml", cfgBuf.Bytes()}, {"cert.pem", certPEM}, {"key.pem", keyPEM}, {ownSettingsFile, settingsJSON},
	} {
		if err := writeZipEntry(z, e.name, e.data, password, now); err != nil {
			return fmt.Errorf("%s: %w", e.name, err)
		}
	}
	return z.Close()
}

// writeZipEntry adds data as name, deflated, and with a password encrypted
// as WinZip AES-256 AE-2 (the reverse of readZipEntry).
func writeZipEntry(z *zip.Writer, name string, data []byte, password string, modified time.Time) error {
	if password == "" {
		w, err := z.CreateHeader(&zip.FileHeader{Name: name, Method: zip.Deflate, Modified: modified})
		if err != nil {
			return err
		}
		_, err = w.Write(data)
		return err
	}
	var deflated bytes.Buffer
	fw, err := flate.NewWriter(&deflated, flate.BestCompression)
	if err != nil {
		return err
	}
	if _, err := fw.Write(data); err != nil {
		return err
	}
	if err := fw.Close(); err != nil {
		return err
	}

	const keyLen, saltLen = 32, 16 // AES-256
	salt := make([]byte, saltLen)
	if _, err := rand.Read(salt); err != nil {
		return err
	}
	keys, err := pbkdf2.Key(sha1.New, password, salt, 1000, 2*keyLen+2)
	if err != nil {
		return err
	}
	body, err := aesCTRLittleEndian(keys[:keyLen], deflated.Bytes())
	if err != nil {
		return err
	}
	mac := hmac.New(sha1.New, keys[keyLen:2*keyLen])
	mac.Write(body)

	extra := make([]byte, 11)
	binary.LittleEndian.PutUint16(extra, 0x9901)
	binary.LittleEndian.PutUint16(extra[2:], 7)
	binary.LittleEndian.PutUint16(extra[4:], 2) // AE-2: no CRC, the MAC covers the data
	copy(extra[6:], "AE")
	extra[8] = 3 // AES-256
	binary.LittleEndian.PutUint16(extra[9:], zip.Deflate)

	raw := make([]byte, 0, saltLen+2+len(body)+10)
	raw = append(append(append(append(raw, salt...), keys[2*keyLen:]...), body...), mac.Sum(nil)[:10]...)
	fh := &zip.FileHeader{
		Name:               name,
		Method:             99,
		Flags:              0x1,
		Extra:              extra,
		CompressedSize64:   uint64(len(raw)),
		UncompressedSize64: uint64(len(data)),
	}
	fh.SetModTime(modified) //nolint:staticcheck // CreateRaw, unlike CreateHeader, takes only the MS-DOS time
	w, err := z.CreateRaw(fh)
	if err != nil {
		return err
	}
	_, err = w.Write(raw)
	return err
}
