# Wire ConnectionDiagnostic into Settings Test Connection

**Closes gap in:** #10
**Blocked by:** none

## Acceptance
- Test Connection uses ConnectionDiagnostic on draft servers without mutating persisted profiles/sessions/cache.
- UI shows staged report (local → resolve → read → discovery).
- Discovery denied shows "Connected. Manual namespace…" style outcome.
- Apply after diagnostic captures a fresh formal context.

**Status: DONE** (wired in NacosConfigurable.testConnection via diagnoseConnection)
