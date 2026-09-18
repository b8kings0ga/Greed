// Use Mimir's naming implementation rather than maintaining a phone-specific alias.
package main

import (
	"context"
	"flag"
	"fmt"
	"net"
	"os"
	"strings"

	"github.com/b8kings0ga/Mimir/pkg/nodelocation"
	"github.com/b8kings0ga/Mimir/pkg/nodename"
)

func main() {
	region := flag.String("region", "", "verified five-character region")
	key := flag.String("key", "", "persistent node identity")
	flag.Parse()
	if *key == "" {
		panic("persistent identity is required")
	}
	if *region == "" {
		ctx, cancel := context.WithTimeout(context.Background(), nodelocation.Budget)
		result := nodelocation.Locate(ctx, &net.Dialer{}, false)
		cancel()
		*region = verifiedRegion(result)
		if *region == "" {
			fmt.Println(nodename.Fallback(*key))
			return
		}
	}
	name, err := nodename.GenerateWithRole(*region, *key, nodename.RoleWorker)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	fmt.Println(name)
}

func verifiedRegion(result nodelocation.Result) string {
	if len(result.Country) != 2 || len(result.LOCODE) != 5 || !strings.EqualFold(result.Country, result.LOCODE[:2]) {
		return ""
	}
	return result.LOCODE
}
