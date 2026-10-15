// Package core is Ovenlight's tailnet on Android: one tsnet node per membership,
// and the one authenticated local proxy that every web view and every native
// request goes through. Kotlin reaches it through gomobile, and every call blocks,
// so Kotlin makes them off the main thread.
package core

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"regexp"
	"strings"
	"sync"
	"time"

	_ "golang.org/x/mobile/bind" // keeps x/mobile in the module graph, for gomobile and the notices
	"tailscale.com/envknob"
	"tailscale.com/net/netmon"
)

// No node uploads logs to Tailscale, as on iOS.
func init() { envknob.SetNoLogsNoSupport() }

// Platform is implemented in Kotlin. Go calls it on its own threads.
type Platform interface {
	// InterfacesJSON lists the phone's network interfaces, from
	// java.net.NetworkInterface: [{"name","index","mtu","up","loopback",
	// "pointToPoint","multicast","addrs":[{"ip","prefixLen"}]}].
	InterfacesJSON() string
	// Log writes a line to logcat. Only debug builds get lines, and sign-in links
	// and keys are already removed from them.
	Log(line string)
}

var (
	initOnce sync.Once
	platform Platform
	debug    bool
	theProxy *proxy
)

var discardLogger = log.New(io.Discard, "", 0)

// exit ends the process. Tests replace it.
var exit = func(err error) {
	logLine("core: " + err.Error())
	os.Exit(1)
}

// Init sets up the interface getter, logging and the proxy. Call it once, before
// any web view or node exists.
func Init(p Platform, debugBuild bool) error {
	err := errors.New("core: Init already ran")
	initOnce.Do(func() {
		platform, debug = p, debugBuild
		if debug {
			log.SetOutput(lineWriter{})
		} else {
			log.SetOutput(io.Discard)
		}
		log.SetFlags(0)
		netmon.RegisterInterfaceGetter(interfaces)
		var logf func(string, ...any)
		if debug {
			logf = func(format string, args ...any) { logLine(fmt.Sprintf(format, args...)) }
		}
		theProxy, err = startProxy(openNode, logf, exit)
	})
	return err
}

// ProxyAddress is "127.0.0.1:<port>", for ProxyController's override.
func ProxyAddress() string {
	if theProxy == nil {
		return ""
	}
	return theProxy.addr()
}

// ProxySecret is the Basic auth password, made for this launch.
func ProxySecret() string {
	if theProxy == nil {
		return ""
	}
	return theProxy.secret
}

// ProxyRealm is the realm of the proxy's challenge, which tells it from any other.
func ProxyRealm() string {
	if theProxy == nil {
		return ""
	}
	return theProxy.realm
}

// SetRoute points an app-<data store ID> key at a membership's node.
func SetRoute(key, membershipID string) error {
	if theProxy == nil {
		return errors.New("core: Init hasn't run")
	}
	return theProxy.setRoute(key, membershipID)
}

// DebugAnswerWithin changes how long the proxy holds a CONNECT and dials, in debug
// builds only, so a test can reach Chromium's own 10 second tunnel timeout.
func DebugAnswerWithin(millis int64) {
	if debug {
		answerWithin.Store(int64(time.Duration(millis) * time.Millisecond))
	}
}

// ClearRoute removes a key's route, closes its tunnels and refuses its held CONNECTs.
// node-<membership ID> is cleared when that membership is removed.
func ClearRoute(key string) {
	if theProxy != nil {
		theProxy.clearRoute(key)
	}
}

// NetworkChanged reports the default network's interface and gateway ("" for
// none) and wakes every running node's link monitor, which on Android otherwise
// looks only every 10 minutes.
func NetworkChanged(ifName, gatewayIP string) {
	netmon.UpdateLastKnownDefaultRouteInterface(ifName)
	updateDefaultGateway(gatewayIP)
	for _, n := range openNodes() {
		n.injectLinkChange()
	}
}

type platformInterface struct {
	Name         string `json:"name"`
	Index        int    `json:"index"`
	MTU          int    `json:"mtu"`
	Up           bool   `json:"up"`
	Loopback     bool   `json:"loopback"`
	PointToPoint bool   `json:"pointToPoint"`
	Multicast    bool   `json:"multicast"`
	Addrs        []struct {
		IP        string `json:"ip"`
		PrefixLen int    `json:"prefixLen"`
	} `json:"addrs"`
}

// interfaces answers netmon from Kotlin's list: Android denies apps the netlink
// socket Go's own net.Interfaces needs.
func interfaces() ([]netmon.Interface, error) {
	if platform == nil {
		return nil, errors.New("core: no platform")
	}
	var list []platformInterface
	if err := json.Unmarshal([]byte(platform.InterfacesJSON()), &list); err != nil {
		return nil, fmt.Errorf("core: interfaces: %w", err)
	}
	var out []netmon.Interface
	for _, pi := range list {
		ni := &net.Interface{Index: pi.Index, MTU: pi.MTU, Name: pi.Name}
		for flag, on := range map[net.Flags]bool{net.FlagUp: pi.Up, net.FlagRunning: pi.Up, net.FlagLoopback: pi.Loopback,
			net.FlagPointToPoint: pi.PointToPoint, net.FlagMulticast: pi.Multicast, net.FlagBroadcast: !pi.Loopback && !pi.PointToPoint} {
			if on {
				ni.Flags |= flag
			}
		}
		addrs := []net.Addr{}
		for _, a := range pi.Addrs {
			ip := net.ParseIP(strings.SplitN(a.IP, "%", 2)[0])
			if ip == nil {
				continue
			}
			bits := 128
			if ip.To4() != nil {
				ip, bits = ip.To4(), 32
			}
			addrs = append(addrs, &net.IPNet{IP: ip, Mask: net.CIDRMask(a.PrefixLen, bits)})
		}
		out = append(out, netmon.Interface{Interface: ni, AltAddrs: addrs})
	}
	return out, nil
}

// Sign-in links and keys never reach a log, as iOS 1.0.2 keeps the sign-in link
// out of its public log.
// Tailscale's links are .../a/<id>, Headscale's .../register/<id> (older ones
// .../register?key=<id>); keys are tskey-... and Headscale's hskey-....
var secretInLine = regexp.MustCompile(`https?://\S*/(a/|register[/?])\S+|(tskey|hskey)-[A-Za-z0-9-]+`)

func redact(line string) string {
	return secretInLine.ReplaceAllString(line, "[removed]")
}

func logLine(line string) {
	if debug && platform != nil {
		platform.Log(redact(strings.TrimRight(line, "\n")))
	}
}

// lineWriter carries the log package's output (tsnet's own lines among it) to
// Platform.Log in debug builds.
type lineWriter struct{}

func (lineWriter) Write(b []byte) (int, error) {
	logLine(string(b))
	return len(b), nil
}
