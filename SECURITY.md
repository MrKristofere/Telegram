# Security policy

## Reporting a vulnerability

Email **dev@vpgram.click** with details. Please do not file a public
GitHub issue for security-sensitive reports — give us time to fix and
release a patch first.

We aim to acknowledge reports within 72 hours and to ship a fix
proportionate to severity (usually within 2-4 weeks for high-severity
issues).

## Signing key

vpGram APK releases are cryptographically signed. The signing key
identifies the publisher and is what your phone checks when accepting
an update.

### Current key (in use from v12.4.20)

```
SHA-256: C2:0C:D1:F9:8D:83:5B:14:41:67:8D:33:C6:20:D6:46:19:EB:DB:53:F1:55:AB:20:3C:50:8D:1F:03:70:2D:36
SHA-1:   D5:37:10:27:5A:79:2D:D2:F3:45:5A:15:18:1F:99:5C:73:E1:F0:51
```

Subject: `CN=vpGram, O=vpGram, C=US`
Algorithm: RSA 4096-bit
Held in: CI secret storage, never written to this repository.

To verify a downloaded APK is signed by this key:

```bash
apksigner verify --print-certs vpgram-v<version>.apk
```

The first SHA-256 must match the value above.

### Previous key (retired 2026-05-06, considered compromised)

```
SHA-256: B5:39:AD:D2:50:FB:2D:ED:93:C5:27:38:50:F9:75:D8:58:3D:25:BE:67:58:78:78:43:E3:27:84:67:89:BE:8F
SHA-1:   BE:A6:1D:19:34:DC:45:7F:F4:DF:5A:7C:BE:78:32:30:BB:A4:01:33
```

Subject: `CN=Vepegram`
Algorithm: RSA 2048-bit

This key, together with its keystore password, was committed to this
repository during early development. **It is publicly compromised** —
anyone reading the git history can extract it and produce APKs that
appear to be signed by it.

We retired it on 2026-05-06 via [APK Signature Scheme v3 key rotation](https://source.android.com/docs/security/apksigning/v3#apk-signing-with-key-rotation):

- Releases prior to v12.4.20 are signed with this key.
- Releases from v12.4.20 onward are signed with the current key. The
  APK contains a signing-certificate-lineage block that proves the
  current key is the legitimate successor of the old one, so devices
  with an older release installed accept the upgrade automatically
  (no manual reinstall required).
- The keystore file has been removed from `HEAD` of this repository,
  but git history retains it (we did not force-push the rewrite). This
  is intentional: the key is already public, hiding it from history
  would not improve security and would break existing clones / forks.

#### What this means for users

- If you receive a vpGram APK from any source other than this
  repository's GitHub Releases or the official website, **check the
  certificate fingerprint** (see "Current key" above). An APK signed
  with the old (compromised) key — or with any other key not listed
  here — is not legitimate, even if Android's update flow appears to
  accept it.
- After installing v12.4.20 or newer, your device records the lineage.
  From that point on, Android will reject any update signed solely by
  the old key.

## Reproducible builds

To independently verify that a release contains exactly the source in
this repository, see [REPRODUCIBLE_BUILDS.md](./REPRODUCIBLE_BUILDS.md).

Reproducible builds and signing-key transparency are complementary:
the former proves the bytes match the source, the latter proves who
published those bytes.
