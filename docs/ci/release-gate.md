# Release gate (ADR-0031)

Each mandatory invariant has **one** authoritative automated home. Do not
restate these behaviours in an aggregate suite — extend the named suite, or
add a gated live-smoke scenario when the check needs a real server.

| ADR-0031 invariant | Authoritative coverage |
|---|---|
| V1 / V3 protocol adapter contracts | `ProtocolDialectContractTest` (+ `V1ProtocolAdapterTest` / `V3ProtocolAdapterTest`) |
| Graded P0 / optional capabilities | `GradedCapabilitiesTest` |
| Basic / Bearer (and related) auth contracts | `ProtocolDialectContractTest` (+ dialect adapter tests) |
| AUTO failure classification / no erroneous V3→V1 fallback | `GenerationResolverTest` |
| Authentication execution-flight (shared login, budget, non-replay) | `ProtocolDialectContractTest` |
| Offline bootstrap / last-known generation | `LastKnownGenerationStoreTest`, `SessionGenerationIntegrationTest` |
| Credential crash-window / versioned slots | `CredentialSlotStoreTest`, `EnvironmentProfileStoreTest`, `AccessSafetyTest` |
| Cross-identity cache exposure | `DualStackBrowsingTest` (`V1 and V3 adapters use the same gateway cache by distinct identities`) |
| Manual namespace without discovery | `DualStackBrowsingTest` (`manual namespace works…`, `namespace discovery denial…`) |
| Misdirected write (edit binding / identity refuse) | `EditSessionServiceTest` (`publishing under a changed access identity is refused…`) |
| Replayed write / single-write state machine | `PublishControllerTest` + `ProtocolDialectContractTest` (`publish sends exactly one write…`) |
| Ambiguous write reconciliation | `PublishControllerTest` (verified / baseline / third-value / deleted / SSU) |
| Read-only publish denial / write-intent withhold | `PublishControllerTest`, `EditSessionServiceTest` |
| Metadata-preserving verified write | `PublishControllerTest` (`read-back with matching content and all metadata is verified`) |
| V1 CAS conflict; V3 never fabricates CAS | `V1PublishContractTest`, `V3PublishContractTest`, `OperationGatewayPublishTest` (`PublishGateway remaps WriteConflict to CasConflict`) |
| Cache observation high-water | `ObservationHighWaterTest` (+ `CacheWriteGateTest` for mutation landing) |
| Cache confidence dimensions | `CacheConfidenceTest` |
| Tombstone late-completion / no resurrection | `ProfileDeletionLifecycleTest` |
| Visibility ordering races | `ReleaseGateOrderingRacesTransportIntegrationTest` |
| Live smoke: Nacos 2.5.3 V1 / 3.2.3 V3 (no legacy adapter) | `LiveSmokeTest` (gated by `NACOS_LIVE_V1_ENDPOINT` / `NACOS_LIVE_V3_ENDPOINT`) |

CI still runs Jupiter (`test`) and Vintage (`testVintage`); live smoke retains
its environment gates. See `docs/ci/jenkins.md` and the root `Jenkinsfile`.
