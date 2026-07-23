# Wire PublishController into ConfigDetailPanel Save

**Closes gap in:** #11, #12
**Blocked by:** 01 (dirty/target guards prefer fencing)

## Acceptance
- Save goes through PublishController / EditSession, not legacy NacosApiService.publishConfiguration for locked paths.
- Profile write opt-in (writesEnabled) defaults false for new/migrated profiles.
- V1 CAS + V3 ordinary paths; CAS conflict / unknown / reconciliation user-visible.
- Dirty guards block retarget while dirty.
