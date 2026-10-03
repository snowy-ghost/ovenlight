package policy

import (
	"slices"
	"sort"
	"strconv"
	"strings"

	"github.com/tailscale/hujson"
)

// ---------- text edits ----------

type edit struct {
	pos, del int
	text     string
}

func applyEdits(src []byte, edits []edit) []byte {
	sort.SliceStable(edits, func(i, j int) bool { return edits[i].pos > edits[j].pos })
	out := slices.Clone(src)
	for _, e := range edits {
		out = slices.Concat(out[:e.pos], []byte(e.text), out[e.pos+e.del:])
	}
	return out
}

// item is one array element or object member to add, with an optional comment line.
type item struct {
	comment string
	text    string
}

// layout is how the file is written, so additions look like the rest of it.
type layout struct {
	src       []byte
	unit      string // one level of indentation
	multiline bool   // false for a policy written on one line
	trailing  bool   // lists end with a trailing comma
}

func newLayout(src []byte, root *hujson.Value) layout {
	obj, _ := root.Value.(*hujson.Object)
	// A policy on one line stays on one line; an empty one gets the usual layout.
	multiline := strings.Contains(strings.TrimSpace(string(src)), "\n") || obj == nil || len(obj.Members) == 0
	l := layout{src: src, unit: "\t", multiline: multiline, trailing: true}
	if obj != nil && len(obj.Members) > 0 {
		if ind, ok := lineIndent(src, obj.Members[0].Name.StartOffset); ok && ind != "" {
			l.unit = ind
		}
	}
	// Follow the file's own habit; a file without multi-line lists gets HuJSON's.
	with, without := 0, 0
	for v := range root.All() {
		var last *hujson.Value
		switch c := v.Value.(type) {
		case *hujson.Object:
			if len(c.Members) > 0 {
				last = &c.Members[len(c.Members)-1].Value
			}
		case *hujson.Array:
			if len(c.Elements) > 0 {
				last = &c.Elements[len(c.Elements)-1]
			}
		}
		if last == nil || !strings.Contains(string(src[v.StartOffset:v.EndOffset]), "\n") {
			continue
		}
		if j := skipExtra(src, last.EndOffset); j < len(src) && src[j] == ',' {
			with++
		} else {
			without++
		}
	}
	l.trailing = with > 0 || without == 0
	return l
}

// lines writes items one per line at indentation ind, each preceded by its comment,
// with commas between them and a trailing one when the file uses them.
func (l layout) lines(items []item, ind string, trailing bool) string {
	var b strings.Builder
	for i, it := range items {
		if it.comment != "" {
			b.WriteString("\n" + ind + it.comment)
		}
		b.WriteString("\n" + ind + it.text)
		if trailing || i < len(items)-1 {
			b.WriteString(",")
		}
	}
	return b.String()
}

func inline(items []item) string {
	texts := make([]string, len(items))
	for i, it := range items {
		t := strings.Join(strings.Fields(stripComments(it.text)), " ")
		texts[i] = strings.NewReplacer(", }", " }", ", ]", " ]").Replace(t)
	}
	return strings.Join(texts, ", ")
}

// tagOwnersMember and sectionMember are new top-level members, written for the
// indentation of the root object's members.
func (l layout) tagOwnersMember(entries []item) item {
	return item{
		comment: "// Ovenlight: tags for shared app nodes and the guests invited to them (ovenlight setup-sharing).",
		text:    `"tagOwners": {` + l.lines(entries, l.unit+l.unit, l.trailing) + "\n" + l.unit + "}",
	}
}

func (l layout) sectionMember(section string, rules []item) item {
	return item{text: strconv.Quote(section) + ": [" + l.lines(rules, l.unit+l.unit, l.trailing) + "\n" + l.unit + "]"}
}

// appendTo adds items after the last member or element of an object or array,
// matching its layout: one per line with the same indentation, or inline.
func (l layout) appendTo(v *hujson.Value, items []item) []edit {
	src := l.src
	var lastStart, lastEnd, count int
	switch c := v.Value.(type) {
	case *hujson.Array:
		count = len(c.Elements)
		if count > 0 {
			lastStart, lastEnd = c.Elements[count-1].StartOffset, c.Elements[count-1].EndOffset
		}
	case *hujson.Object:
		count = len(c.Members)
		if count > 0 {
			lastStart, lastEnd = c.Members[count-1].Name.StartOffset, c.Members[count-1].Value.EndOffset
		}
	}
	open, closing := v.StartOffset, v.EndOffset-1 // the bracket or brace bytes

	if count == 0 {
		inner := src[open+1 : closing]
		if !l.multiline {
			return []edit{{pos: open + 1, del: len(inner), text: inline(items)}}
		}
		parentIndent := lineLeadingBlanks(src, open)
		text := l.lines(items, parentIndent+l.unit, l.trailing)
		if len(strings.TrimSpace(string(inner))) == 0 {
			return []edit{{pos: open + 1, del: len(inner), text: text + "\n" + parentIndent}}
		}
		// Keep comments already inside the empty list: add the items after them.
		end := closing
		for end > open+1 && isSpace(src[end-1]) {
			end--
		}
		return []edit{{pos: end, text: text}}
	}

	j := skipExtra(src, lastEnd)
	hadComma := j < len(src) && src[j] == ','
	at := lastEnd
	if hadComma {
		at = j + 1
	}
	ind, ownLine := lineIndent(src, lastStart)
	if !ownLine || !l.multiline {
		text := ", " + inline(items)
		if hadComma {
			text = " " + inline(items) + ","
		}
		return []edit{{pos: at, text: text}}
	}
	at = afterTrailingComment(src, at)
	var edits []edit
	prefix := ""
	if !hadComma {
		// The old last item now needs a comma: right after it, not after a comment
		// that follows it.
		if at == lastEnd {
			prefix = ","
		} else {
			edits = append(edits, edit{pos: lastEnd, text: ","})
		}
	}
	return append(edits, edit{pos: at, text: prefix + l.lines(items, ind, hadComma)})
}

// insertRootMembers adds new members at the top of the root object.
func (l layout) insertRootMembers(root *hujson.Value, members []item) []edit {
	src := l.src
	obj := root.Value.(*hujson.Object)
	open := root.StartOffset
	if !l.multiline {
		text := inline(members)
		if len(obj.Members) > 0 {
			text += ", "
		}
		return []edit{{pos: open + 1, text: text}}
	}
	if len(obj.Members) == 0 {
		inner := src[open+1 : root.EndOffset-1]
		text := l.lines(members, l.unit, l.trailing) + "\n"
		if len(strings.TrimSpace(string(inner))) == 0 {
			return []edit{{pos: open + 1, del: len(inner), text: text}}
		}
		return []edit{{pos: root.EndOffset - 1, text: text}}
	}
	ind, ownLine := lineIndent(src, obj.Members[0].Name.StartOffset)
	if !ownLine {
		return []edit{{pos: open + 1, text: inline(members) + ", "}}
	}
	at := afterTrailingComment(src, open+1)
	// Members are followed by the old first member, so each gets a comma; then a blank
	// line before what was the first member.
	return []edit{{pos: at, text: l.lines(members, ind, true) + "\n"}}
}

func stripComments(s string) string {
	var out []string
	for _, line := range strings.Split(s, "\n") {
		if t := strings.TrimSpace(line); !strings.HasPrefix(t, "//") {
			out = append(out, line)
		}
	}
	return strings.Join(out, "\n")
}

// isSpace reports JSON whitespace.
func isSpace(c byte) bool { return c == ' ' || c == '\t' || c == '\n' || c == '\r' }

// skipExtra returns the offset of the first byte at or after i that isn't whitespace
// or part of a comment.
func skipExtra(b []byte, i int) int {
	for i < len(b) {
		switch {
		case isSpace(b[i]):
			i++
		case i+1 < len(b) && b[i] == '/' && b[i+1] == '/':
			for i < len(b) && b[i] != '\n' {
				i++
			}
		case i+1 < len(b) && b[i] == '/' && b[i+1] == '*':
			end := strings.Index(string(b[i+2:]), "*/")
			if end < 0 {
				return len(b)
			}
			i += 2 + end + 2
		default:
			return i
		}
	}
	return i
}

// afterTrailingComment returns the end of the line starting at i when the rest of the
// line is only blanks and one comment, so an insertion doesn't steal that comment from
// the value it follows. Otherwise it returns i.
func afterTrailingComment(b []byte, i int) int {
	j := i
	for j < len(b) && (b[j] == ' ' || b[j] == '\t') {
		j++
	}
	if j+1 < len(b) && b[j] == '/' && b[j+1] == '/' {
		for j < len(b) && b[j] != '\n' {
			j++
		}
		return j
	}
	if j+1 < len(b) && b[j] == '/' && b[j+1] == '*' {
		end := strings.Index(string(b[j+2:]), "*/")
		if end >= 0 && !strings.Contains(string(b[j:j+2+end]), "\n") {
			k := j + 2 + end + 2
			for k < len(b) && (b[k] == ' ' || b[k] == '\t') {
				k++
			}
			if k == len(b) || b[k] == '\n' || b[k] == '\r' {
				return k
			}
		}
	}
	return i
}

// lineIndent returns the blanks before offset on its line, and whether only blanks
// precede it (the value starts its own line).
func lineIndent(b []byte, offset int) (string, bool) {
	lead := lineLeadingBlanks(b, offset)
	start := offset
	for start > 0 && b[start-1] != '\n' {
		start--
	}
	return lead, start+len(lead) == offset
}

// lineLeadingBlanks returns the indentation of the line that contains offset.
func lineLeadingBlanks(b []byte, offset int) string {
	start := offset
	for start > 0 && b[start-1] != '\n' {
		start--
	}
	end := start
	for end < offset && (b[end] == ' ' || b[end] == '\t') {
		end++
	}
	return string(b[start:end])
}

// deleteElements removes the array elements or object members at the given indexes,
// with their commas, and each one's "// Ovenlight:" comment line. An element on its own
// line takes its whole line with it.
func (l layout) deleteElements(v *hujson.Value, del map[int]bool) []edit {
	src := l.src
	type span struct{ start, end int }
	var sp []span
	switch c := v.Value.(type) {
	case *hujson.Array:
		for i := range c.Elements {
			sp = append(sp, span{c.Elements[i].StartOffset, c.Elements[i].EndOffset})
		}
	case *hujson.Object:
		for i := range c.Members {
			sp = append(sp, span{c.Members[i].Name.StartOffset, c.Members[i].Value.EndOffset})
		}
	}
	var edits []edit
	for i := 0; i < len(sp); {
		if !del[i] {
			i++
			continue
		}
		a := i
		for i < len(sp) && del[i] {
			i++
		}
		b := i - 1
		_, ownLine := lineIndent(src, sp[a].start)
		ownLine = ownLine && l.multiline
		j := skipExtra(src, sp[b].end)
		switch {
		case j < len(src) && src[j] == ',':
			from, to := sp[a].start, j+1
			if ownLine {
				from, to = l.commentLineStart(sp[a].start), restOfLine(src, to)
				// Right after the opening bracket, a blank line that followed goes too.
				if k := from - 1; k >= 0 && strings.TrimRight(string(src[:k]), " \t\r\n") != "" {
					before := strings.TrimRight(string(src[:from]), " \t\r\n")
					if strings.HasSuffix(before, "{") || strings.HasSuffix(before, "[") {
						if next := restOfLine(src, to); next > to && strings.TrimSpace(string(src[to:next])) == "" {
							to = next
						}
					}
				}
			} else {
				for to < len(src) && (src[to] == ' ' || src[to] == '\t') {
					to++
				}
			}
			edits = append(edits, edit{pos: from, del: to - from})
		case a > 0:
			// The last elements go: the comma before them goes too.
			k := skipExtra(src, sp[a-1].end)
			if !ownLine {
				edits = append(edits, edit{pos: k, del: sp[b].end - k})
				break
			}
			from := l.commentLineStart(sp[a].start) - 1 // and the line break before it
			edits = append(edits, edit{pos: k, del: 1}, edit{pos: from, del: sp[b].end - from})
		default:
			from := sp[a].start
			if ownLine {
				from = l.commentLineStart(sp[a].start) - 1
			}
			edits = append(edits, edit{pos: from, del: sp[b].end - from})
		}
	}
	return edits
}

// memberIndex is the index of the member of the root object whose value is v.
func memberIndex(root, v *hujson.Value) int {
	return slices.IndexFunc(root.Value.(*hujson.Object).Members, func(m hujson.ObjectMember) bool {
		return m.Value.StartOffset == v.StartOffset
	})
}

// onlyOvenlightComments reports whether the only comments between an array's elements
// are the "// Ovenlight:" lines Ovenlight wrote.
func onlyOvenlightComments(src []byte, v *hujson.Value) bool {
	var between []byte
	at := v.StartOffset + 1
	for _, el := range v.Value.(*hujson.Array).Elements {
		between = append(between, src[at:el.StartOffset]...)
		at = el.EndOffset
	}
	between = append(between, src[at:v.EndOffset-1]...)
	for _, line := range strings.Split(string(between), "\n") {
		if t := strings.Trim(line, " \t\r,"); t != "" && !strings.HasPrefix(t, "// Ovenlight:") {
			return false
		}
	}
	return true
}

// commentLineStart is the start of the line holding offset, or of the line before it
// when that line is a comment Ovenlight wrote.
func (l layout) commentLineStart(offset int) int {
	start := offset
	for start > 0 && l.src[start-1] != '\n' {
		start--
	}
	if start == 0 {
		return start
	}
	prev := start - 1
	for prev > 0 && l.src[prev-1] != '\n' {
		prev--
	}
	if strings.HasPrefix(strings.TrimSpace(string(l.src[prev:start])), "// Ovenlight:") {
		return prev
	}
	return start
}

// restOfLine returns the offset after the line break that ends i's line when only
// blanks or a comment follow i; otherwise i.
func restOfLine(b []byte, i int) int {
	j := i
	for j < len(b) && (b[j] == ' ' || b[j] == '\t' || b[j] == '\r') {
		j++
	}
	if j+1 < len(b) && b[j] == '/' && b[j+1] == '/' {
		for j < len(b) && b[j] != '\n' {
			j++
		}
	}
	if j < len(b) && b[j] == '\n' {
		return j + 1
	}
	return i
}
