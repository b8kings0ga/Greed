package main

import (
	"testing"

	"github.com/b8kings0ga/Mimir/pkg/nodelocation"
)

func TestVerifiedRegionRejectsCrossCountryLocation(t *testing.T) {
	for _, tc := range []struct {
		result nodelocation.Result
		want   string
	}{
		{nodelocation.Result{Country: "CN", Colo: "LAX", LOCODE: "USLAX"}, ""},
		{nodelocation.Result{Country: "CN", Colo: "LAX", LOCODE: "CNSZX"}, "CNSZX"},
		{nodelocation.Result{Country: "", LOCODE: "USLAX"}, ""},
	} {
		if got := verifiedRegion(tc.result); got != tc.want {
			t.Fatalf("verifiedRegion(%+v) = %q, want %q", tc.result, got, tc.want)
		}
	}
}
