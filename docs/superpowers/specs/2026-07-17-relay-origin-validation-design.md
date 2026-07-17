# Relay Origin Validation Design

## Problem

Fresh native installations enable the hosted relay by default. Their configured
direct web origin remains `http://localhost:8787`, while pairing links use the
host-specific relay origin `https://h-<host-id>.relay.supermux.dev`.

The web channel currently validates cookie-authenticated mutating requests only
against the configured direct origin. A browser opened through the relay sends
the relay origin and therefore receives `403 bad origin` for every POST, PUT,
PATCH, or DELETE request. This blocks onboarding actions such as agent login and
agent installation.

## Design

Keep the configured direct origin as the primary trusted origin and add the
broker's live relay URL as a second explicitly trusted origin. The web channel
already receives both values: `publicUrl` contains the configured direct origin,
and `getRelayUrl()` returns the URL reported by the active relay provider.

For a cookie-authenticated mutating API request, origin validation will succeed
when the request's normalized origin equals either:

1. the normalized configured `publicUrl` origin; or
2. the normalized live relay URL origin, when one exists.

The comparison remains exact at the URL-origin level (scheme, hostname, and
effective port). It will not infer trust from `Host`, `X-Forwarded-Host`, or
other request headers.

## Preserved Behavior

- Direct and LAN browser access continues to use `MUX_WEB_PUBLIC_URL`.
- Hosted relay browser access works only for this broker's live relay URL.
- Unrelated, malformed, and stale relay origins remain rejected with HTTP 403.
- Bearer-authenticated native requests continue to bypass the browser CSRF
  check because they do not use ambient cookie credentials.
- Requests without an `Origin` header retain the existing non-browser behavior.
- Pairing URLs and cookie attributes are unchanged.

## Error Handling

If the relay is disabled, offline, or has no URL, only the configured direct
origin is trusted. If either configured URL is malformed, it does not match;
the request is rejected unless another valid allowed origin matches.

## Testing

Add a regression test that constructs a web channel with a localhost
`publicUrl` and a host-specific `getRelayUrl()`. Using a real device cookie, it
will submit a mutating API request with the relay `Origin` header and assert that
the request no longer returns 403.

The same test boundary will verify that an unrelated origin still returns 403.
Existing cookie-origin unit tests will continue to cover direct-origin,
malformed-origin, and missing-origin behavior. Run the focused web-channel and
cookie tests, followed by the broader broker test suite and type checks.

## Out of Scope

- Trusting arbitrary reverse-proxy headers or relay subdomains.
- Replacing `MUX_WEB_PUBLIC_URL` with the relay URL.
- Changing relay provisioning, pairing links, authentication, or cookie scope.
