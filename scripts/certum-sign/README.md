# Windows code signing (#116)

Every shipped Windows binary is Authenticode-signed with Certum's **Open Source Code Signing** certificate
(publisher "Open Source Developer Ahmet Hüseyin Dok", RSA 3072 in the SimplySign cloud, serial
`38cdf412188a7cda5c5cb9eec8fb0cbd`, issued by Certum Code Signing 2021 CA, **expires 2027-11-10**).
The open-source certificate forbids commercial software: if supermux is ever sold, switch to Certum Standard Code Signing.

## How the release signs

`release.yml`'s `build-desktop-windows` job, and only it (tags and dry runs; `ci.yml` never signs):

1. `createDistributable` builds the app image.
2. **Sign the host helpers and the launcher in the app image**: `supermux-broker.exe`, `mux-sessiond.exe`, `supermux.exe`.
3. `packageMsi -x createDistributable` builds the MSI *from that signed image* (`build.gradle.kts` wires `packageMsi`'s
   `appImage` to `createDistributable`'s output).
4. **Sign the .msi**.
5. **Verify the Windows signatures**: `signtool verify /pa /tw`, the signer's name and a timestamp on every file. A
   failure here fails the release.

That's 4 signatures and 2 logins per run, against Certum's 5000 signatures a month.

The JNI libraries (`*.dll` in the jars) are not signed: their sha256 is pinned in `native.properties` and checked before
loading. If they are ever signed, `rewritePackagedNativeDigests` must run after the signing (signing changes bytes).

## The signing tool: our own client

`scripts/certum-sign/` is ours (Ahmet, 2026-11-10: no third-party code in the signing path):

- `totp.ts`: the one-time code (RFC 6238, SHA-256, 6 digits, 30 s) from the `otpauth://` seed, computed in-process.
- `cloud.ts`: Certum's cloud (`cloudsign.webnotarius.pl` only): OAuth/CAS login with the e-mail + one-time code, the
  signing card and its certificate, and signing one SHA-256 digest. Certum documents no API: the protocol was read from
  Le-Syl21/ssign and IvanHanloth/super-simply-sign (references only, no code copied in the signing path).
- `sign.ts`: per file, `signtool sign /dg` writes the to-be-signed digest, the cloud signs it, `signtool sign /di`
  puts the signature in, `signtool timestamp /tr http://time.certum.pl /td sha256` timestamps it. One login per run of
  the script; a refused code is never retried (repeated refusals lock the account).

Secrets (GitHub Actions, never printed): `SIMPLYSIGN_EMAIL`, `SIMPLYSIGN_OTP_URI`. The job runs in the
`windows-signing` environment (its approval gate) and the `certum-simplysign` concurrency group.

## Renewal and rotation

- The certificate expires **2027-11-10**: renew with Certum before then; a new certificate keeps the same SimplySign
  account. Check its issuer: if it is no longer Certum Code Signing 2021 CA, replace
  `scripts/certum-sign/certum-code-signing-2021-ca.cer`.
- A leaked or rotated seed: reset SimplySign's TOTP in Certum's portal (a new QR), then update `SIMPLYSIGN_OTP_URI`.
- Timestamped signatures stay valid after the certificate expires.

## Fallback: SimplySign Desktop on the runner

If Certum changes its cloud protocol and our client breaks, sign with Certum's own software instead:

1. Install SimplySign Desktop on the Windows runner (pin the installer by SHA-256).
2. Log in by typing the account e-mail and a fresh one-time code into its window (a PowerShell `SendKeys` script; see
   jay0lee/certum-cloud-code-sign for a working reference), then wait for the certificate in `Cert:\CurrentUser\My`.
3. `signtool sign /sha1 <thumbprint> /fd sha256 /tr http://time.certum.pl /td sha256 <file>` for the same files, in
   the same order. Use the **x64** signtool (the ARM64 one cannot talk to SimplySign's driver).

It depends on Desktop's login window, so a SimplySign update can break it; the verify step still guards the release.
