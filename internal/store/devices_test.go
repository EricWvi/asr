package store

import (
	"context"
	"errors"
	"strings"
	"testing"
)

func TestDeviceTokenResolvesUntilRevoked(t *testing.T) {
	s := openTest(t)
	ctx := context.Background()
	alice, bob := newUser(t, s, "alice"), newUser(t, s, "bob")

	d, token, err := s.CreateDevice(ctx, alice, "  Eric 的 MacBook  ", PlatformMac)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(token, DeviceTokenPrefix) || d.Name != "Eric 的 MacBook" {
		t.Fatalf("device = %+v, token = %q", d, token)
	}
	got, u, err := s.DeviceUser(ctx, token)
	if err != nil || got.ID != d.ID || u.ID != alice || got.Platform != PlatformMac {
		t.Fatalf("DeviceUser = %+v, %+v, %v", got, u, err)
	}
	if _, _, err := s.DeviceUser(ctx, token+"x"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("wrong token: err = %v", err)
	}

	// Devices are scoped to their user.
	if list, _ := s.ListDevices(ctx, bob); len(list) != 0 {
		t.Fatalf("bob sees %+v", list)
	}
	if err := s.DeleteDevice(ctx, bob, d.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("bob revoked alice's device: %v", err)
	}
	if list, _ := s.ListDevices(ctx, alice); len(list) != 1 || list[0].ID != d.ID {
		t.Fatalf("alice's devices = %+v", list)
	}

	if err := s.DeleteDevice(ctx, alice, d.ID); err != nil {
		t.Fatal(err)
	}
	if _, _, err := s.DeviceUser(ctx, token); !errors.Is(err, ErrNotFound) {
		t.Fatalf("revoked token: err = %v", err)
	}
}

func TestCreateDeviceValidates(t *testing.T) {
	s := openTest(t)
	ctx := context.Background()
	alice := newUser(t, s, "alice")
	for _, c := range []struct{ name, platform string }{
		{" ", PlatformMac},
		{strings.Repeat("长", MaxDeviceName+1), PlatformMac},
		{"Pixel", "ios"},
	} {
		if _, _, err := s.CreateDevice(ctx, alice, c.name, c.platform); !errors.Is(err, ErrInvalid) {
			t.Errorf("CreateDevice(%q, %q): err = %v", c.name, c.platform, err)
		}
	}
}
