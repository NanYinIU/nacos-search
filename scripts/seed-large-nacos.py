#!/usr/bin/env python3
"""Seed Nacos with many large configs for LiveLargeDataPerfTest."""
import concurrent.futures, argparse, time, urllib.parse, urllib.request, json

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base", default="http://127.0.0.1:8848/nacos")
    p.add_argument("--tenant", default="perf-bulk-1")
    p.add_argument("--count", type=int, default=1200)
    p.add_argument("--lines", type=int, default=10000)
    p.add_argument("--workers", type=int, default=32)
    args = p.parse_args()

    # ensure namespace
    urllib.request.urlopen(urllib.request.Request(
        f"{args.base}/v1/console/namespaces",
        data=urllib.parse.urlencode({
            "customNamespaceId": args.tenant,
            "namespaceName": args.tenant,
            "namespaceDesc": "large-perf",
        }).encode(),
        method="POST",
    ), timeout=30).read()

    header = "app:\n  name: bulk\n  features:\n"
    pad = [f"    k{i:05d}: v{i % 97}-{'x' * 8}\n" for i in range(args.lines - 4)]
    content = header + "".join(pad)
    url = f"{args.base}/v1/cs/configs"

    def put(i):
        form = {
            "dataId": f"bulk-large-{i:04d}.yaml",
            "group": "DEFAULT_GROUP",
            "tenant": args.tenant,
            "type": "yaml",
            "content": content,
        }
        req = urllib.request.Request(url, data=urllib.parse.urlencode(form).encode(), method="POST")
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.read().decode().strip() == "true"

    t0 = time.perf_counter()
    ok = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as ex:
        for n, success in enumerate(ex.map(put, range(1, args.count + 1)), 1):
            ok += int(success)
            if n % 100 == 0 or n == args.count:
                print(f"{n}/{args.count} ok={ok} {time.perf_counter()-t0:.1f}s", flush=True)

    q = f"{url}?search=blur&dataId=&group=&tenant={args.tenant}&pageNo=1&pageSize=1"
    with urllib.request.urlopen(q, timeout=60) as r:
        print("totalCount", json.load(r).get("totalCount"))

if __name__ == "__main__":
    main()
