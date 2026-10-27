// Copyright (C) 2026 mysyncthing contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package stbridge

import (
	"archive/zip"
	"bytes"
	"compress/flate"
	"crypto/aes"
	"crypto/hmac"
	"crypto/pbkdf2"
	"crypto/sha1" //nolint:gosec // WinZip AES is defined with SHA-1
	"encoding/binary"
	"errors"
	"fmt"
	"hash/crc32"
	"io"
)

var (
	errNeedPassword  = errors.New("the backup is password protected")
	errWrongPassword = errors.New("wrong password")
)

// readZipEntry returns the content of f, at most limit bytes, decrypting
// WinZip AES (what zip4j, and so Syncthing-Fork, writes). The traditional
// ZipCrypto is not supported: it is broken, and Fork never used it.
func readZipEntry(f *zip.File, password string, limit int64) ([]byte, error) {
	if f.UncompressedSize64 > uint64(limit) {
		return nil, fmt.Errorf("%s is too large", f.Name)
	}
	if f.Flags&0x1 == 0 {
		rc, err := f.Open() // checks size and CRC
		if err != nil {
			return nil, err
		}
		defer rc.Close()
		return readLimited(rc, limit, f.Name)
	}
	if password == "" {
		return nil, errNeedPassword
	}
	if f.Method != 99 {
		return nil, fmt.Errorf("%s uses an unsupported encryption", f.Name)
	}
	ae, err := parseAESExtra(f.Extra)
	if err != nil {
		return nil, fmt.Errorf("%s: %w", f.Name, err)
	}
	raw, err := f.OpenRaw()
	if err != nil {
		return nil, err
	}
	saltLen := 4 + 4*int(ae.strength) // 8, 12 or 16 bytes
	keyLen := 8 + 8*int(ae.strength)  // 16, 24 or 32 bytes
	if f.CompressedSize64 > uint64(limit)+uint64(saltLen)+12 {
		return nil, fmt.Errorf("%s is too large", f.Name)
	}
	data, err := io.ReadAll(raw)
	if err != nil {
		return nil, err
	}
	if len(data) < saltLen+2+10 {
		return nil, fmt.Errorf("%s is truncated", f.Name)
	}
	salt, verifier := data[:saltLen], data[saltLen:saltLen+2]
	body, mac := data[saltLen+2:len(data)-10], data[len(data)-10:]

	keys, err := pbkdf2.Key(sha1.New, password, salt, 1000, 2*keyLen+2)
	if err != nil {
		return nil, err
	}
	if !hmac.Equal(keys[2*keyLen:], verifier) {
		return nil, errWrongPassword
	}
	h := hmac.New(sha1.New, keys[keyLen:2*keyLen])
	h.Write(body)
	if !hmac.Equal(h.Sum(nil)[:10], mac) {
		return nil, fmt.Errorf("%s is damaged or the password is wrong", f.Name)
	}
	plain, err := aesCTRLittleEndian(keys[:keyLen], body)
	if err != nil {
		return nil, err
	}

	var out []byte
	switch ae.method {
	case zip.Store:
		if int64(len(plain)) > limit {
			return nil, fmt.Errorf("%s is too large", f.Name)
		}
		out = plain
	case zip.Deflate:
		fr := flate.NewReader(bytes.NewReader(plain))
		defer fr.Close()
		if out, err = readLimited(fr, limit, f.Name); err != nil {
			return nil, err
		}
	default:
		return nil, fmt.Errorf("%s uses an unsupported compression", f.Name)
	}
	// AE-2 leaves the CRC out (the MAC covers the data); AE-1 keeps it.
	if ae.version == 1 && crc32.ChecksumIEEE(out) != f.CRC32 {
		return nil, fmt.Errorf("%s is damaged", f.Name)
	}
	if uint64(len(out)) != f.UncompressedSize64 {
		return nil, fmt.Errorf("%s is damaged", f.Name)
	}
	return out, nil
}

// readLimited reads r to the end, failing past limit bytes (a zip bomb, or a
// header that understates the size).
func readLimited(r io.Reader, limit int64, name string) ([]byte, error) {
	b, err := io.ReadAll(io.LimitReader(r, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(b)) > limit {
		return nil, fmt.Errorf("%s is too large", name)
	}
	return b, nil
}

type aesExtra struct {
	version  uint16 // 1 = AE-1, 2 = AE-2
	strength byte   // 1 = AES-128, 2 = AES-192, 3 = AES-256
	method   uint16 // compression method of the plaintext
}

// parseAESExtra finds the WinZip AES extra field (header ID 0x9901).
func parseAESExtra(extra []byte) (aesExtra, error) {
	for len(extra) >= 4 {
		id := binary.LittleEndian.Uint16(extra)
		size := int(binary.LittleEndian.Uint16(extra[2:]))
		if size > len(extra)-4 {
			break
		}
		field := extra[4 : 4+size]
		extra = extra[4+size:]
		if id != 0x9901 {
			continue
		}
		if size != 7 || field[2] != 'A' || field[3] != 'E' {
			break
		}
		ae := aesExtra{
			version:  binary.LittleEndian.Uint16(field),
			strength: field[4],
			method:   binary.LittleEndian.Uint16(field[5:]),
		}
		if ae.version < 1 || ae.version > 2 || ae.strength < 1 || ae.strength > 3 {
			break
		}
		return ae, nil
	}
	return aesExtra{}, errors.New("no valid AES encryption header")
}

// aesCTRLittleEndian is AES in counter mode as WinZip defines it: a 128-bit
// little-endian counter starting at 1, unlike crypto/cipher's big-endian CTR.
func aesCTRLittleEndian(key, data []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	out := make([]byte, len(data))
	var ctr, stream [aes.BlockSize]byte
	for i := 0; i < len(data); i += aes.BlockSize {
		for j := range ctr { // increment
			ctr[j]++
			if ctr[j] != 0 {
				break
			}
		}
		block.Encrypt(stream[:], ctr[:])
		end := min(i+aes.BlockSize, len(data))
		for j := i; j < end; j++ {
			out[j] = data[j] ^ stream[j-i]
		}
	}
	return out, nil
}
