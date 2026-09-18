// Mark this mobile client down while its private overlay is still alive.
// Nomad Leave only drains; a stopped phone must not wait for heartbeat expiry.
package main

import (
	"encoding/json"
	"fmt"
	"github.com/hashicorp/nomad/helper/pool"
	"github.com/hashicorp/nomad/nomad/structs"
	"net"
	"net/http"
	"net/netip"
	"net/rpc"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

func run() error {
	idBytes, err := os.ReadFile("/var/lib/ratatoskr/nomad/client/client-id")
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	secret, err := os.ReadFile("/var/lib/ratatoskr/nomad/client/secret-id")
	if err != nil {
		return err
	}
	id := strings.TrimSpace(string(idBytes))
	if len(id) != 36 {
		return fmt.Errorf("invalid local node identity")
	}
	client := &http.Client{Timeout: 4 * time.Second}
	response, err := client.Get("http://127.0.0.1:14646/v1/status/leader")
	if err != nil {
		return err
	}
	var leader string
	err = json.NewDecoder(response.Body).Decode(&leader)
	response.Body.Close()
	if err != nil {
		return err
	}
	host, _, err := net.SplitHostPort(leader)
	if err != nil {
		return err
	}
	address, err := netip.ParseAddr(host)
	if err != nil || !netip.MustParsePrefix("200::/7").Contains(address) {
		return fmt.Errorf("offline notification requires the private Yggdrasil route")
	}
	response, err = client.Get("http://127.0.0.1:14646/v1/agent/self")
	if err != nil {
		return err
	}
	var self struct {
		Config map[string]any
		Stats  map[string]map[string]string
	}
	err = json.NewDecoder(response.Body).Decode(&self)
	response.Body.Close()
	if err != nil {
		return err
	}
	if self.Stats["client"]["node_id"] != id {
		return fmt.Errorf("local agent identity mismatch")
	}
	region, _ := self.Config["Region"].(string)
	if region == "" {
		region = "global"
	}
	var process *os.Process
	entries, _ := os.ReadDir("/proc")
	for _, entry := range entries {
		pid, e := strconv.Atoi(entry.Name())
		if e != nil {
			continue
		}
		exe, e := os.Readlink(filepath.Join("/proc", entry.Name(), "exe"))
		if e != nil || filepath.Base(exe) != "nomad" {
			continue
		}
		cmd, _ := os.ReadFile(filepath.Join("/proc", entry.Name(), "cmdline"))
		if !strings.Contains(string(cmd), "\x00agent\x00") {
			continue
		}
		if process != nil {
			return fmt.Errorf("more than one local Nomad agent")
		}
		process, e = os.FindProcess(pid)
		if e != nil {
			return e
		}
	}
	if process == nil {
		return fmt.Errorf("local Nomad agent not found")
	}
	defer process.Release()
	conn, err := net.DialTimeout("tcp", leader, 4*time.Second)
	if err != nil {
		return err
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err = conn.Write([]byte{byte(pool.RpcNomad)}); err != nil {
		return err
	}
	rpcClient := rpc.NewClientWithCodec(pool.NewClientCodec(conn))
	defer rpcClient.Close()
	// Prevent a final ready heartbeat racing with the down update. OpenRC then
	// stops Ratatoskr's process groups and powers off; no node record is purged.
	if err = process.Signal(syscall.SIGSTOP); err != nil {
		return err
	}
	committed := false
	defer func() {
		if !committed {
			process.Signal(syscall.SIGCONT)
		}
	}()
	args := structs.NodeUpdateStatusRequest{NodeID: id, Status: structs.NodeStatusDown, WriteRequest: structs.WriteRequest{Region: region, AuthToken: strings.TrimSpace(string(secret))}}
	var reply structs.NodeUpdateResponse
	if err = rpcClient.Call("Node.UpdateStatus", &args, &reply); err != nil {
		return err
	}
	committed = true
	fmt.Printf("PODROID_NODE_OFFLINE node=%s index=%d\n", id, reply.Index)
	return nil
}
func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "mobile offline notification:", err)
		os.Exit(1)
	}
}
