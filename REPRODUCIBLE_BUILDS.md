# Reproducible builds

vpGram APK releases are reproducible: anyone can build the APK from this
repository and verify that it is bit-identical to the release we publish.
This guarantees that the binary we ship contains exactly the source you
can read here — no hidden patches, no extra components.

## What you need

- Docker (24.0 or newer)
- ~10 GB of free disk space
- ~30-60 minutes (the first run downloads ~3 GB of Android SDK + NDK)
- A released APK to verify, downloaded from
  https://github.com/vpgram/vpgram-android/releases
- Python 3 (for `apkdiff.py`, included in this repository)

## Step 1 — find the build commit

Each release APK has the source revision it was built from baked into
its `BuildConfig`. Two ways to read it:

**On a running app**: open *Settings → About* in vpGram. Below the
version and ABI lines, the screen shows a 7-character commit SHA, e.g.

```
v12.4.20 (65300)
direct arm64-v8a
a3f8c1d
```

**Without installing**: read the release notes at
https://github.com/vpgram/vpgram-android/releases/tag/v<version>.
Each release lists the full commit hash.

## Step 2 — build the APK from source

```bash
git clone https://github.com/vpgram/vpgram-android.git
cd vpgram-android
git checkout <full-commit-sha>

# Build via the same Dockerfile our CI uses.
docker build -t vpgram-build .
docker run --rm \
  -e COMMIT_SHA="$(git rev-parse HEAD)" \
  -v "$(pwd):/home/source" \
  vpgram-build
```

The build takes 30-60 minutes depending on your CPU and network. The
output APK appears in:

```
TMessagesProj/build/outputs/apk/afat/release/
```

It will be unsigned (signing happens after the reproducible build step
on our side, with a key not stored in this repository — see [SECURITY.md](./SECURITY.md)).

## Step 3 — compare with the released APK

```bash
python3 apkdiff.py \
  /path/to/downloaded/vpgram-v<version>.apk \
  TMessagesProj/build/outputs/apk/afat/release/<output>.apk
```

Expected output:

```
APKs are the same!
```

`apkdiff.py` walks both APKs entry by entry and compares bytes,
ignoring the v1 JAR signature files (`META-INF/MANIFEST.MF`,
`META-INF/CERT.SF`, `META-INF/CERT.RSA`). The APK Signing Block — which
contains the v2 and v3 signatures — lives outside the ZIP container and
is automatically excluded from the comparison. So your unsigned build
should be byte-identical to our signed release modulo the signature.

## Step 4 — verify the signing certificate

Reproducibility tells you the bytes match the source. To also confirm the
release was signed by the real vpGram team, check the signing certificate:

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner verify \
  --print-certs \
  /path/to/downloaded/vpgram-v<version>.apk
```

The certificate SHA-256 must be:

```
C2:0C:D1:F9:8D:83:5B:14:41:67:8D:33:C6:20:D6:46:19:EB:DB:53:F1:55:AB:20:3C:50:8D:1F:03:70:2D:36
```

This fingerprint is also published in [SECURITY.md](./SECURITY.md). If
the certificate is anything else, it is not an authentic vpGram release.

## What if APKs differ?

If `apkdiff.py` reports a difference:

1. Confirm you checked out the **exact** commit hash from Step 1. Even
   a single commit off produces different bytes.
2. Confirm you exported `COMMIT_SHA` to that hash before `docker run`.
   This value is embedded in `BuildConfig.COMMIT_SHA` and propagates
   into the binary.
3. Open an issue at
   https://github.com/vpgram/vpgram-android/issues with:
   - The release version you compared against
   - Your host OS, Docker version, CPU architecture
   - The full output of `apkdiff.py`

## Notes for auditors

- The `Dockerfile` in this repository is the source of truth for the
  build environment. Our CI uses a pre-warmed copy of the same image
  for faster releases; both produce identical output.
- The signing step happens outside the Dockerfile, with a key held in
  CI secret storage and never committed. The unsigned APK that comes
  out of `docker run` is what gets signed; the signature is added on
  top without modifying the rest of the file.
- `apkdiff.py` is the same comparison utility used by Telegram for its
  own reproducible builds (https://core.telegram.org/reproducible-builds);
  unmodified so far.
