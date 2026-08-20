# Choose the suggested Namespace from isolated discovery

The settings Namespace field is the profile's suggested Namespace (a preference that seeds a new project session), not the live project-session Namespace. It is an editable, filterable combo: display name is shown, Namespace ID is stored, and typing an ID remains valid when discovery has not run or is forbidden.

Isolated Namespace discovery fills the chooser from two gestures that share one option list:

- The first open of an empty chooser, against the current 诊断快照, when the server URL is usable. This is **not** a 连接诊断: it does not read the configured Namespace and must not headline success or failure on the Test Connection line. While that request is in flight the popup shows one non-selectable Loading row; a failure replaces it with one non-selectable failure row. Options already present for this identity are shown as-is with no second request. An invalid or empty URL does not request and does not show Loading.
- Test Connection still runs the full 连接诊断 (including discovery after generation resolution even when the configured-Namespace read fails) and refreshes the same list.

Changing endpoint, API-generation policy, or authentication inputs clears options while keeping the typed identifier; the next open may discover again. Connection diagnosis still treats a readable configured Namespace as connected; a permission-denied read is 访问受阻 and must not be headlined as connection failure.
