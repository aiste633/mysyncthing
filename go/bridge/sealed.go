// Copyright (C) 2026 mysyncthing contributors.
// SPDX-License-Identifier: AGPL-3.0-or-later

package stbridge

import (
	"bytes"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"io"

	"golang.org/x/crypto/argon2"
	"golang.org/x/crypto/chacha20poly1305"
)

// The strong export: the backup zip, unencrypted, sealed as a whole with
// XChaCha20-Poly1305 under a key that Argon2id (RFC 9106) derives from the
// password. Unlike WinZip AES, the key derivation is memory-hard, and the
// entry names and sizes are hidden too. Only this app reads it.
//
//	offset  size  field
//	0       12    sealMagic
//	12      1     sealVersion
//	13      4     Argon2id passes, big-endian
//	17      4     Argon2id memory in KiB, big-endian
//	21      1     Argon2id lanes
//	22      16    salt
//	38      24    nonce
//	62      ...   ciphertext of the zip, with the 16-byte tag
//
// The header is the AEAD's associated data: changing any of its bytes,
// the parameters included, fails the tag like a wrong password does.

const (
	sealMagic     = "mysyncthing\x00"
	sealVersion   = 1
	sealHeaderLen = len(sealMagic) + 1 + 4 + 4 + 1 + 16 + chacha20poly1305.NonceSizeX
)

// argonParams are Argon2id's cost settings, stored in each file so they can
// be raised later without breaking older backups.
type argonParams struct {
	passes, memoryKiB uint32
	lanes             uint8
}

// sealParams are what an export uses: RFC 9106's second recommended option
// (64 MiB, 3 passes, 4 lanes), about half a second on a current phone.
var sealParams = argonParams{passes: 3, memoryKiB: 64 << 10, lanes: 4}

// check bounds what a file may ask of the import: never weaker than OWASP's
// minimum (19 MiB), never more than 256 MiB or 10 passes, so a crafted file
// can't make the phone run out of memory or spin for minutes.
func (p argonParams) check() error {
	switch {
	case p.memoryKiB < 19<<10 || p.memoryKiB > 256<<10:
		return fmt.Errorf("Argon2id memory %d KiB, allowed 19456 to 262144", p.memoryKiB)
	case p.passes < 1 || p.passes > 10:
		return fmt.Errorf("Argon2id passes %d, allowed 1 to 10", p.passes)
	case p.lanes < 1 || p.lanes > 16:
		return fmt.Errorf("Argon2id lanes %d, allowed 1 to 16", p.lanes)
	}
	return nil
}

// deriveSealKey is a variable only so that the fuzz test can swap in a cheap
// stand-in: a real derivation per input would stall the fuzzer.
var deriveSealKey = func(password string, salt []byte, p argonParams) []byte {
	return argon2.IDKey([]byte(password), salt, p.passes, p.memoryKiB, p.lanes, chacha20poly1305.KeySize)
}

// maxSealed bounds a sealed file: the zip holds at most the entries the
// import reads, with their caps (forkEntries), plus the zip's own overhead.
const maxSealed = int64(sealHeaderLen + 8<<20 + chacha20poly1305.Overhead)

// isSealed tells whether data starts like a sealed backup.
func isSealed(head []byte) bool { return bytes.HasPrefix(head, []byte(sealMagic)) }

// seal encrypts plain (the backup zip) for password with params p.
func seal(plain []byte, password string, p argonParams) ([]byte, error) {
	if password == "" {
		return nil, errors.New("a strong backup needs a password")
	}
	if err := p.check(); err != nil {
		return nil, err
	}
	header := make([]byte, sealHeaderLen)
	n := copy(header, sealMagic)
	header[n] = sealVersion
	binary.BigEndian.PutUint32(header[n+1:], p.passes)
	binary.BigEndian.PutUint32(header[n+5:], p.memoryKiB)
	header[n+9] = p.lanes
	salt, nonce := header[n+10:n+26], header[n+26:]
	if _, err := rand.Read(header[n+10:]); err != nil { // salt and nonce
		return nil, err
	}
	aead, err := chacha20poly1305.NewX(deriveSealKey(password, salt, p))
	if err != nil {
		return nil, err
	}
	return aead.Seal(header, nonce, plain, header), nil
}

// unseal is the reverse of seal. A wrong password and a changed file are
// the same to it: the tag does not match.
func unseal(r io.ReaderAt, size int64, password string) ([]byte, error) {
	if size < int64(sealHeaderLen+chacha20poly1305.Overhead) {
		return nil, errors.New("the backup is cut short")
	}
	if size > maxSealed {
		return nil, errors.New("the backup is too large")
	}
	data := make([]byte, size)
	if _, err := r.ReadAt(data, 0); err != nil && !errors.Is(err, io.EOF) {
		return nil, err
	}
	header, body := data[:sealHeaderLen], data[sealHeaderLen:]
	n := len(sealMagic)
	if !isSealed(header) {
		return nil, errors.New("not a sealed backup")
	}
	if header[n] != sealVersion {
		return nil, fmt.Errorf("the backup is from a newer version of this app (format %d)", header[n])
	}
	p := argonParams{
		passes:    binary.BigEndian.Uint32(header[n+1:]),
		memoryKiB: binary.BigEndian.Uint32(header[n+5:]),
		lanes:     header[n+9],
	}
	if err := p.check(); err != nil {
		return nil, err
	}
	if password == "" {
		return nil, errNeedPassword
	}
	salt, nonce := header[n+10:n+26], header[n+26:]
	aead, err := chacha20poly1305.NewX(deriveSealKey(password, salt, p))
	if err != nil {
		return nil, err
	}
	plain, err := aead.Open(nil, nonce, body, header)
	if err != nil {
		return nil, errWrongPassword
	}
	return plain, nil
}
