// Copyright (C) 2026 mysyncthing contributors.
// SPDX-License-Identifier: AGPL-3.0-or-later

package stbridge

import (
	"encoding/binary"
	"errors"
	"fmt"
	"math"
)

// readJavaPrefs decodes Syncthing-Fork's sharedpreferences.dat: a
// SharedPreferences map written with Java's ObjectOutputStream. It is not a
// general deserializer: it knows only the classes such a map can hold, and
// any other class, or anything malformed, is an error. Values come back as
// string, bool, int32, int64, float32 or []string (a string set).
func readJavaPrefs(b []byte) (map[string]any, error) {
	r := &javaReader{b: b}
	if len(b) < 4 || binary.BigEndian.Uint32(b) != 0xACED0005 {
		return nil, errors.New("not a Java serialization stream")
	}
	r.i = 4
	v, err := r.object(0)
	if err != nil {
		return nil, err
	}
	m, ok := v.(map[string]any)
	if !ok {
		return nil, errors.New("not a map")
	}
	if r.i != len(b) {
		return nil, errors.New("trailing data")
	}
	return m, nil
}

const (
	tcNull        = 0x70
	tcReference   = 0x71
	tcClassDesc   = 0x72
	tcObject      = 0x73
	tcString      = 0x74
	tcBlockData   = 0x77
	tcEndBlock    = 0x78
	handleBase    = 0x7E0000
	maxJavaDepth  = 4 // map > set > string, with room for class descriptors
	maxJavaHandle = 1 << 16
)

// javaClasses are the classes allowed, each with the superclass it must have
// in the stream ("" for none). Fixing the hierarchy also rules out cycles.
var javaClasses = map[string]string{
	"java.util.HashMap": "", "java.util.HashSet": "", "java.lang.Boolean": "", "java.lang.Number": "",
	"java.lang.Integer": "java.lang.Number", "java.lang.Long": "java.lang.Number", "java.lang.Float": "java.lang.Number",
}

type javaClass struct {
	name   string
	fields []javaField
	super  *javaClass
}

type javaField struct {
	code byte
	name string
}

type javaReader struct {
	b       []byte
	i       int
	handles []any
}

var errJavaTruncated = errors.New("truncated Java serialization stream")

func (r *javaReader) take(n int) ([]byte, error) {
	if n < 0 || n > len(r.b)-r.i {
		return nil, errJavaTruncated
	}
	v := r.b[r.i : r.i+n]
	r.i += n
	return v, nil
}

func (r *javaReader) u8() (byte, error) {
	v, err := r.take(1)
	if err != nil {
		return 0, err
	}
	return v[0], nil
}

func (r *javaReader) u16() (int, error) {
	v, err := r.take(2)
	if err != nil {
		return 0, err
	}
	return int(binary.BigEndian.Uint16(v)), nil
}

func (r *javaReader) u32() (uint32, error) {
	v, err := r.take(4)
	if err != nil {
		return 0, err
	}
	return binary.BigEndian.Uint32(v), nil
}

// utf reads a short string. Java's modified UTF-8 differs from UTF-8 only for
// NUL and characters outside the BMP, which preference keys and values here
// do not use; they decode as replacement characters at worst.
func (r *javaReader) utf() (string, error) {
	n, err := r.u16()
	if err != nil {
		return "", err
	}
	v, err := r.take(n)
	return string(v), err
}

func (r *javaReader) newHandle(v any) (int, error) {
	if len(r.handles) >= maxJavaHandle {
		return 0, errors.New("too many objects")
	}
	r.handles = append(r.handles, v)
	return len(r.handles) - 1, nil
}

func (r *javaReader) ref() (any, error) {
	h, err := r.u32()
	if err != nil {
		return nil, err
	}
	i := int64(h) - handleBase
	if i < 0 || i >= int64(len(r.handles)) || r.handles[i] == nil {
		return nil, errors.New("bad reference")
	}
	return r.handles[i], nil
}

func (r *javaReader) classDesc(depth int) (*javaClass, error) {
	if depth > maxJavaDepth {
		return nil, errors.New("nested too deeply")
	}
	tc, err := r.u8()
	if err != nil {
		return nil, err
	}
	switch tc {
	case tcNull:
		return nil, nil
	case tcReference:
		v, err := r.ref()
		if err != nil {
			return nil, err
		}
		c, ok := v.(*javaClass)
		if !ok {
			return nil, errors.New("reference is not a class")
		}
		return c, nil
	case tcClassDesc:
	default:
		return nil, fmt.Errorf("unexpected tag %#x for a class", tc)
	}
	name, err := r.utf()
	if err != nil {
		return nil, err
	}
	superName, ok := javaClasses[name]
	if !ok {
		return nil, fmt.Errorf("class %q is not allowed", name)
	}
	if _, err := r.take(9); err != nil { // serialVersionUID, flags
		return nil, err
	}
	c := &javaClass{name: name}
	if _, err := r.newHandle(c); err != nil {
		return nil, err
	}
	n, err := r.u16()
	if err != nil {
		return nil, err
	}
	for range n {
		code, err := r.u8()
		if err != nil {
			return nil, err
		}
		fname, err := r.utf()
		if err != nil {
			return nil, err
		}
		switch code {
		case 'Z', 'I', 'J', 'F':
		default:
			return nil, fmt.Errorf("field %q of type %q is not allowed", fname, code)
		}
		c.fields = append(c.fields, javaField{code, fname})
	}
	if tc, err := r.u8(); err != nil || tc != tcEndBlock {
		return nil, errors.New("class annotations are not allowed")
	}
	if c.super, err = r.classDesc(depth + 1); err != nil {
		return nil, err
	}
	got := ""
	if c.super != nil {
		got = c.super.name
	}
	if got != superName {
		return nil, fmt.Errorf("class %q has superclass %q", name, got)
	}
	return c, nil
}

// fields reads the primitive field values of c and its superclasses, which
// come first in the stream.
func (r *javaReader) fields(c *javaClass, out map[string]any) error {
	if c == nil {
		return nil
	}
	if err := r.fields(c.super, out); err != nil {
		return err
	}
	for _, f := range c.fields {
		var v any
		switch f.code {
		case 'Z':
			b, err := r.u8()
			if err != nil {
				return err
			}
			v = b != 0
		case 'I':
			u, err := r.u32()
			if err != nil {
				return err
			}
			v = int32(u)
		case 'F':
			u, err := r.u32()
			if err != nil {
				return err
			}
			v = math.Float32frombits(u)
		case 'J':
			b, err := r.take(8)
			if err != nil {
				return err
			}
			v = int64(binary.BigEndian.Uint64(b))
		}
		out[f.name] = v
	}
	return nil
}

// block reads the size header HashMap and HashSet write before their entries.
func (r *javaReader) block(n int) ([]byte, error) {
	tc, err := r.u8()
	if err != nil {
		return nil, err
	}
	l, err := r.u8()
	if err != nil {
		return nil, err
	}
	if tc != tcBlockData || int(l) != n {
		return nil, errors.New("unexpected block data")
	}
	return r.take(n)
}

func (r *javaReader) count(b []byte) (int, error) {
	n := int32(binary.BigEndian.Uint32(b))
	if n < 0 || int(n) > len(r.b)-r.i { // every entry takes at least one byte
		return 0, errors.New("bad element count")
	}
	return int(n), nil
}

func (r *javaReader) object(depth int) (any, error) {
	if depth > maxJavaDepth {
		return nil, errors.New("nested too deeply")
	}
	tc, err := r.u8()
	if err != nil {
		return nil, err
	}
	switch tc {
	case tcNull:
		return nil, nil
	case tcReference:
		return r.ref()
	case tcString:
		s, err := r.utf()
		if err != nil {
			return nil, err
		}
		_, err = r.newHandle(s)
		return s, err
	case tcObject:
	default:
		return nil, fmt.Errorf("unexpected tag %#x for an object", tc)
	}
	c, err := r.classDesc(depth + 1)
	if err != nil {
		return nil, err
	}
	if c == nil {
		return nil, errors.New("object without a class")
	}
	// Placeholder until the value is complete: a reference to an object
	// from inside itself is not something a preferences map contains.
	h, err := r.newHandle(nil)
	if err != nil {
		return nil, err
	}
	f := map[string]any{}
	if err := r.fields(c, f); err != nil {
		return nil, err
	}
	var v any
	switch c.name {
	case "java.util.HashMap":
		b, err := r.block(8) // capacity, size
		if err != nil {
			return nil, err
		}
		n, err := r.count(b[4:])
		if err != nil {
			return nil, err
		}
		m := make(map[string]any, n)
		for range n {
			k, err := r.object(depth + 1)
			if err != nil {
				return nil, err
			}
			ks, ok := k.(string)
			if !ok {
				return nil, errors.New("map key is not a string")
			}
			if m[ks], err = r.object(depth + 1); err != nil {
				return nil, err
			}
		}
		v = m
	case "java.util.HashSet":
		b, err := r.block(12) // capacity, load factor, size
		if err != nil {
			return nil, err
		}
		n, err := r.count(b[8:])
		if err != nil {
			return nil, err
		}
		s := make([]string, 0, n)
		for range n {
			e, err := r.object(depth + 1)
			if err != nil {
				return nil, err
			}
			es, ok := e.(string)
			if !ok {
				return nil, errors.New("set element is not a string")
			}
			s = append(s, es)
		}
		v = s
	case "java.lang.Boolean", "java.lang.Integer", "java.lang.Long", "java.lang.Float":
		var ok bool
		if v, ok = f["value"]; !ok {
			return nil, errors.New("boxed value without a value")
		}
	default:
		return nil, fmt.Errorf("class %q is not a value", c.name)
	}
	if c.name == "java.util.HashMap" || c.name == "java.util.HashSet" {
		if tc, err := r.u8(); err != nil || tc != tcEndBlock {
			return nil, errors.New("missing end of block data")
		}
	}
	r.handles[h] = v
	return v, nil
}
