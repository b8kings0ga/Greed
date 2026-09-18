// Use Mimir's naming implementation rather than maintaining a phone-specific alias.
package main

import (
	"flag"
	"fmt"
	"github.com/b8kings0ga/Mimir/pkg/nodename"
	"io"
	"net/http"
	"os"
	"strings"
	"time"
)

func main() {
	region := flag.String("region", "", "verified five-character region")
	key := flag.String("key", "", "persistent node identity")
	flag.Parse()
	if *key == "" {
		panic("persistent identity is required")
	}
	if *region == "" {
		client := &http.Client{Timeout: 15 * time.Second}
		response, err := client.Get("https://cloudflare.com/cdn-cgi/trace")
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		defer response.Body.Close()
		if response.StatusCode != 200 {
			panic("trace request failed")
		}
		body, err := io.ReadAll(io.LimitReader(response.Body, 8192))
		if err != nil {
			panic(err)
		}
		fields := map[string]string{}
		for _, line := range strings.Split(string(body), "\n") {
			k, v, ok := strings.Cut(line, "=")
			if ok {
				fields[k] = strings.TrimSpace(v)
			}
		}
		if len(fields["loc"]) != 2 || len(fields["colo"]) != 3 {
			panic("invalid trace region")
		}
		*region = strings.ToLower(fields["loc"] + fields["colo"])
	}
	name, err := nodename.GenerateWithRole(*region, *key, nodename.RoleWorker)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	fmt.Println(name)
}
