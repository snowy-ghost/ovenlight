//go:build ovenlight_testkey

package main

import (
	"testing"
	"time"
)

func TestCompressedSchedule(t *testing.T) {
	defer func(h time.Duration) { updateHour = h }(updateHour)
	updateHour = testHour("5s")
	// A day of two minutes: 7 seconds past midnight is 01:24, and so is every two minutes
	// later, up to the last two minutes before midnight.
	for _, s := range []int{7, 127, 86400 - 113} {
		if d := dayTime(time.Date(2026, 10, 21, 0, 0, s, 0, time.Local)); d != time.Hour+24*time.Minute {
			t.Errorf("%d s: %v", s, d)
		}
	}
	seen := time.Date(2026, 10, 21, 9, 0, 0, 0, time.UTC)
	idx := mustIndex(t, testIndex("1.2.0", "1.1.0", "1.1.0", 1, ""))
	if _, due, _ := updateTarget("1.1.5", idx, seen); !due.Equal(seen.Add(4 * time.Minute)) {
		t.Errorf("due %v", due)
	}
}

func TestTestHour(t *testing.T) {
	if testHour("") != time.Hour || testHour("5s") != 5*time.Second {
		t.Error("testHour")
	}
	for _, bad := range []string{"7s", "-5s", "0s", "2h", "an hour"} {
		func() {
			defer func() {
				if recover() == nil {
					t.Errorf("%q was taken", bad)
				}
			}()
			testHour(bad)
		}()
	}
}
