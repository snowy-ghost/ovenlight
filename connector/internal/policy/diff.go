package policy

import (
	"fmt"
	"strings"
)

// Diff returns a unified diff of two texts with three lines of context, or "" when
// they are equal.
func Diff(a, b string) string {
	if a == b {
		return ""
	}
	x, y := splitLines(a), splitLines(b)
	ops := diffLines(x, y)

	const context = 3
	var out strings.Builder
	out.WriteString("--- current policy\n+++ proposed policy\n")
	// Group operations into hunks: runs of changes with up to 2*context equal lines
	// between them.
	i := 0
	for i < len(ops) {
		for i < len(ops) && ops[i].kind == ' ' {
			i++
		}
		if i == len(ops) {
			break
		}
		start := max(0, i-context)
		end := i
		for end < len(ops) {
			if ops[end].kind != ' ' {
				end++
				continue
			}
			run := end
			for run < len(ops) && ops[run].kind == ' ' {
				run++
			}
			if run == len(ops) || run-end > 2*context {
				end = min(run, end+context)
				break
			}
			end = run
		}
		hunk := ops[start:end]
		aStart, bStart := hunk[0].ai+1, hunk[0].bi+1
		aLen, bLen := 0, 0
		for _, op := range hunk {
			if op.kind != '+' {
				aLen++
			}
			if op.kind != '-' {
				bLen++
			}
		}
		fmt.Fprintf(&out, "@@ -%d,%d +%d,%d @@\n", aStart, aLen, bStart, bLen)
		for _, op := range hunk {
			out.WriteByte(op.kind)
			out.WriteString(op.text)
			out.WriteByte('\n')
		}
		i = end
	}
	return out.String()
}

func splitLines(s string) []string {
	s = strings.TrimSuffix(s, "\n")
	return strings.Split(s, "\n")
}

type diffOp struct {
	kind   byte // ' ', '-', '+'
	text   string
	ai, bi int // line index in a and b where this op sits
}

// diffLines is Myers' O(ND) algorithm: policy edits are small, so D stays small.
func diffLines(a, b []string) []diffOp {
	n, m := len(a), len(b)
	maxD := n + m
	offset := maxD
	v := make([]int, 2*maxD+2)
	var trace [][]int
	for d := 0; d <= maxD; d++ {
		trace = append(trace, append([]int(nil), v...))
		for k := -d; k <= d; k += 2 {
			var x int
			if k == -d || (k != d && v[offset+k-1] < v[offset+k+1]) {
				x = v[offset+k+1]
			} else {
				x = v[offset+k-1] + 1
			}
			y := x - k
			for x < n && y < m && a[x] == b[y] {
				x++
				y++
			}
			v[offset+k] = x
			if x >= n && y >= m {
				return backtrack(a, b, trace, d, offset)
			}
		}
	}
	return nil
}

func backtrack(a, b []string, trace [][]int, d, offset int) []diffOp {
	x, y := len(a), len(b)
	var rev []diffOp
	for ; d > 0; d-- {
		v := trace[d]
		k := x - y
		var prevK int
		if k == -d || (k != d && v[offset+k-1] < v[offset+k+1]) {
			prevK = k + 1
		} else {
			prevK = k - 1
		}
		prevX := v[offset+prevK]
		prevY := prevX - prevK
		for x > prevX && y > prevY {
			x--
			y--
			rev = append(rev, diffOp{kind: ' ', text: a[x], ai: x, bi: y})
		}
		if x == prevX {
			y--
			rev = append(rev, diffOp{kind: '+', text: b[y], ai: x, bi: y})
		} else {
			x--
			rev = append(rev, diffOp{kind: '-', text: a[x], ai: x, bi: y})
		}
	}
	for x > 0 && y > 0 {
		x--
		y--
		rev = append(rev, diffOp{kind: ' ', text: a[x], ai: x, bi: y})
	}
	ops := make([]diffOp, len(rev))
	for i := range rev {
		ops[i] = rev[len(rev)-1-i]
	}
	return ops
}
