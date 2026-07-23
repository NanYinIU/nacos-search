# Do not follow endpoint redirects

The transport never follows a 3xx response automatically for reads, login, or writes, and maps it to EndpointRedirected with only a sanitized target origin and context path. Users must confirm and update the environment endpoint explicitly, which advances both revisions and creates a fresh access identity; this prevents credentials or tokenized URLs from crossing targets and prevents a publish from being replayed by redirect semantics, independently of the deferred custom TLS and certificate-management scope.
