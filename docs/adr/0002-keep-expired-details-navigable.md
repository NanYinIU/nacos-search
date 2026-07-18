# Keep expired configuration details navigable

Nacos Search retains identity-isolated configuration details as cache navigation targets after TTL expiry, including beyond a seven-day deep-stale threshold, because stable offline code navigation is more useful than treating freshness expiry as content deletion. Stale targets are visually distinct and never claim current remote existence; deep-stale navigation forces a single-detail refresh, authoritative COMPLETE snapshots or explicit not-found responses remove deleted targets, and capacity/manual cleanup bound retention instead of age alone.
