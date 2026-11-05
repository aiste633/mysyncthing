// Copyright (C) 2026 mysyncthing contributors.
// SPDX-License-Identifier: AGPL-3.0-or-later

package stbridge

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"math/rand"
	"os"
	"path/filepath"
	"testing"
	"testing/quick"
	"time"

	"golang.org/x/crypto/argon2"
)

// cheapSealKey swaps the Argon2id derivation for a hash of the same inputs
// until the test ends, for tests that seal many times. Not for parallel tests.
func cheapSealKey(t testing.TB) {
	real := deriveSealKey
	deriveSealKey = func(password string, salt []byte, p argonParams) []byte {
		h := sha256.New()
		h.Write([]byte(password))
		h.Write(salt)
		binary.Write(h, binary.BigEndian, []any{p.passes, p.memoryKiB, p.lanes})
		return h.Sum(nil)
	}
	t.Cleanup(func() { deriveSealKey = real })
}

func unsealBytes(b []byte, password string) ([]byte, error) {
	return unseal(bytes.NewReader(b), int64(len(b)), password)
}

// The real derivation: Argon2id with the parameters stored in the file.
func TestSealRealKey(t *testing.T) {
	if sealParams != (argonParams{passes: 3, memoryKiB: 64 << 10, lanes: 4}) {
		t.Errorf("export parameters %+v, want RFC 9106's 64 MiB, 3 passes, 4 lanes", sealParams)
	}
	plain := []byte("a zip, as far as seal is concerned")
	sealed, err := seal(plain, "correct horse battery", sealParams)
	if err != nil {
		t.Fatal(err)
	}
	n := len(sealMagic)
	if got := (argonParams{binary.BigEndian.Uint32(sealed[n+1:]), binary.BigEndian.Uint32(sealed[n+5:]), sealed[n+9]}); got != sealParams {
		t.Errorf("header holds %+v", got)
	}
	salt := sealed[n+10 : n+26]
	if want := argon2.IDKey([]byte("correct horse battery"), salt, 3, 64<<10, 4, 32); !bytes.Equal(deriveSealKey("correct horse battery", salt, sealParams), want) {
		t.Error("the key is not Argon2id's")
	}
	if got, err := unsealBytes(sealed, "correct horse battery"); err != nil || !bytes.Equal(got, plain) {
		t.Fatalf("unsealed %q, %v", got, err)
	}
	if _, err := unsealBytes(sealed, "correct horse batterY"); !errors.Is(err, errWrongPassword) {
		t.Errorf("wrong password: %v", err)
	}
	if _, err := unsealBytes(sealed, ""); !errors.Is(err, errNeedPassword) {
		t.Errorf("no password: %v", err)
	}
}

// A backup sealed by the first version of the format still opens: changing
// the format must never strand existing backups. Regenerate only for a new
// format version, as a new file: UPDATE_SEALED_GOLDEN=1 go test -run TestSealedGolden
func TestSealedGolden(t *testing.T) {
	path := filepath.Join("testdata", "fork", "sealed-v1.mysyncthing")
	if os.Getenv("UPDATE_SEALED_GOLDEN") == "1" {
		home, _ := exportHome(t)
		var buf bytes.Buffer
		if err := writeBackup(&buf, home, ExportStrong, "test-password", settingsJSON(t, someSettings), time.Date(2026, 11, 5, 18, 0, 0, 0, time.UTC)); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, buf.Bytes(), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	b, err := read(golden(t, "sealed-v1.mysyncthing"), "test-password")
	if err != nil {
		t.Fatal(err)
	}
	if b.own == nil || b.own.Proxy != someSettings.Proxy || len(b.cfg.Folders) != 1 {
		t.Errorf("own settings %+v, folders %+v", b.own, b.cfg.Folders)
	}
}

// Every changed byte, in the header or the ciphertext, and every cut, fails;
// none of them panics or yields data.
func TestSealTamper(t *testing.T) {
	cheapSealKey(t)
	sealed, err := seal(bytes.Repeat([]byte("zip "), 40), "correct horse battery", sealParams)
	if err != nil {
		t.Fatal(err)
	}
	for i := range sealed {
		for _, bit := range []byte{0x01, 0x80} {
			b := bytes.Clone(sealed)
			b[i] ^= bit
			if got, err := unsealBytes(b, "correct horse battery"); err == nil {
				t.Fatalf("byte %d bit %x changed: unsealed %q", i, bit, got)
			}
		}
	}
	for n := range len(sealed) {
		if _, err := unsealBytes(sealed[:n], "correct horse battery"); err == nil {
			t.Fatalf("cut to %d bytes: unsealed", n)
		}
	}
	if _, err := unsealBytes(append(bytes.Clone(sealed), 0), "correct horse battery"); err == nil {
		t.Fatal("a byte appended: unsealed")
	}
}

// What a file may ask of the import is checked before any key derivation.
func TestSealParamsBounded(t *testing.T) {
	derived := 0
	real := deriveSealKey
	deriveSealKey = func(string, []byte, argonParams) []byte { derived++; return make([]byte, 32) }
	t.Cleanup(func() { deriveSealKey = real })

	ok := func(p argonParams) bool {
		return p.memoryKiB >= 19<<10 && p.memoryKiB <= 256<<10 && p.passes >= 1 && p.passes <= 10 && p.lanes >= 1 && p.lanes <= 16
	}
	// Property: the check agrees with the rule for any parameters.
	r := rand.New(rand.NewSource(5))
	edges := []uint32{0, 1, 2, 9, 10, 11, 16, 17, 19<<10 - 1, 19 << 10, 64 << 10, 256 << 10, 256<<10 + 1, 1<<32 - 1}
	for range 20000 {
		p := argonParams{edges[r.Intn(len(edges))], edges[r.Intn(len(edges))], uint8(edges[r.Intn(len(edges))])}
		if r.Intn(2) == 0 {
			p = argonParams{r.Uint32(), r.Uint32() % (300 << 10), uint8(r.Intn(256))}
		}
		if (p.check() == nil) != ok(p) {
			t.Fatalf("%+v: check %v, want ok=%v", p, p.check(), ok(p))
		}
		// A file asking for p: refused unless p is allowed.
		b := make([]byte, sealHeaderLen+16)
		n := copy(b, sealMagic)
		b[n] = sealVersion
		binary.BigEndian.PutUint32(b[n+1:], p.passes)
		binary.BigEndian.PutUint32(b[n+5:], p.memoryKiB)
		b[n+9] = p.lanes
		before := derived
		unsealBytes(b, "pw")
		if (derived > before) != ok(p) {
			t.Fatalf("%+v: derived a key %v, allowed %v", p, derived > before, ok(p))
		}
	}
	if _, err := seal([]byte("x"), "pw", argonParams{passes: 3, memoryKiB: 512 << 10, lanes: 4}); err == nil {
		t.Error("sealed with parameters the import would refuse")
	}
	if _, err := seal([]byte("x"), "", sealParams); err == nil {
		t.Error("sealed without a password")
	}
}

func TestUnsealRefuses(t *testing.T) {
	cheapSealKey(t)
	sealed, err := seal([]byte("zip"), "pw", sealParams)
	if err != nil {
		t.Fatal(err)
	}
	newer := bytes.Clone(sealed)
	newer[len(sealMagic)]++
	if _, err := unsealBytes(newer, "pw"); err == nil || errors.Is(err, errWrongPassword) {
		t.Errorf("newer format: %v", err)
	}
	// Too large is refused from the size alone, before anything is read.
	if _, err := unseal(failingReaderAt{}, maxSealed+1, "pw"); err == nil || err.Error() != "the backup is too large" {
		t.Errorf("too large: %v", err)
	}
}

type failingReaderAt struct{}

func (failingReaderAt) ReadAt([]byte, int64) (int, error) { return 0, errors.New("read") }

// Property: anything sealed comes back unchanged for its password only, and
// two seals of the same input differ (fresh salt and nonce).
func TestSealRoundTripProperty(t *testing.T) {
	cheapSealKey(t)
	prop := func(plain []byte, password string) bool {
		if password == "" {
			password = "x"
		}
		a, errA := seal(plain, password, sealParams)
		b, errB := seal(plain, password, sealParams)
		if errA != nil || errB != nil || bytes.Equal(a, b) {
			return false
		}
		got, err := unsealBytes(a, password)
		_, wrong := unsealBytes(a, password+"!")
		return err == nil && bytes.Equal(got, plain) && errors.Is(wrong, errWrongPassword)
	}
	if err := quick.Check(prop, &quick.Config{MaxCount: 500, Rand: rand.New(rand.NewSource(9))}); err != nil {
		t.Fatal(err)
	}
}

// Any bytes behind a sealed header are refused or opened without panics.
func FuzzUnseal(f *testing.F) {
	cheapSealKey(f)
	for _, p := range []string{"pw", "correct horse battery"} {
		b, err := seal([]byte("PK\x03\x04 not really a zip"), p, sealParams)
		if err != nil {
			f.Fatal(err)
		}
		f.Add(b, p)
	}
	f.Add([]byte(sealMagic), "pw")
	f.Fuzz(func(t *testing.T, b []byte, password string) {
		if plain, err := unsealBytes(b, password); err == nil {
			if len(plain) > len(b) {
				t.Fatalf("unsealed %d bytes from %d", len(plain), len(b))
			}
		}
		read(b, password) // the whole import path, sealed or not
	})
}
