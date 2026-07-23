# Keep environment profiles global and active sessions project-scoped

Nacos Search keeps reusable environment profiles and PasswordSafe credentials at application scope, while every project owns its selected environment, namespace, and search state. Each network, cache, or publish operation captures an immutable operation context from that project session when it starts; later session changes cannot retarget the operation or let its result update the new session, accepting migration complexity in exchange for preventing one open project from changing another project's Nacos target.
