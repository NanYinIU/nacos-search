# Support Nacos 2.x through v1 and Nacos 3.x through v3

Nacos Search treats Nacos 2.0–2.5 through the legacy v1 HTTP API and Nacos 3.0–3.2 through the native v3 API as its two first-class protocol generations. A standard Nacos 3.2 installation must work without the legacy API adapter; Nacos 1.x remains best-effort and is excluded from the regression matrix, bounding compatibility cost while retaining first-class support for current 2.x deployments.
