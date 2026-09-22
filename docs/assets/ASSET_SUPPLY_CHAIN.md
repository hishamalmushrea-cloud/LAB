# External asset supply chain

## Trust boundary

`config/assets-manifest.json` is the authoritative inventory for build inputs that are too large
for Git. Each entry records an asset-set or upstream version, exact byte length, SHA-256 digest,
ABI, source revision, license reference, download URL, and destination path. `app/build.gradle.kts`
loads the debug and release entries from this file; it no longer downloads mutable MD5 sidecars or
queries a `latest` release.

The shared build-logic downloader writes to a sibling `.part` file, resumes with an HTTP `Range`
request, validates `Content-Range`, bounds the transfer by the manifest size, verifies SHA-256, and
then uses `ATOMIC_MOVE` to replace the destination. A failed transfer or digest check never removes
or overwrites the previous destination. Concurrent Gradle tasks serialize on a sibling lock file.
Only HTTPS sources are accepted.

`downloadDocDb` is pinned to OfflineDocumentationTools release `db-2025-07-16b`; it is not a
latest-release lookup.

## URL stability

Entries marked `immutable-release` use a versioned upstream release URL. Entries marked
`mutable-legacy` are existing files on `appdevforall.org`, whose host currently exposes no
versioned path. Their size and SHA-256 digest still make consumption fail closed if a file is
changed in place, but this is weaker provenance than an immutable release URL. New or replaced
assets must use versioned release URLs where the publisher supports them. Migrating the remaining
legacy entries requires the upstream publishing pipeline to retain versioned objects; changing a
digest merely to accept silently replaced bytes is not allowed.

## Updating an asset

1. Publish the asset at a versioned HTTPS URL and retain its license/notices.
2. Update its URL, version, ABI, source revision, license reference and destination in the manifest.
3. Set `size` and `sha256` to `null` only while preparing the change. The secret-free
   `Inventory external assets` workflow streams the bytes and exposes the resulting values in its
   check plus a short-lived artifact.
4. Review and commit the generated size and SHA-256 values. Run
   `python3 scripts/asset_manifest.py config/assets-manifest.json` to enforce a complete manifest.
5. Run the downloader unit tests and the relevant Gradle build before publishing.

Downloaded files, `.part` files, locks, and generated release payloads are ignored by Git. The old
zero-byte release placeholders and stale tracked `core.cgt` were removed because they were build
outputs, not usable or verifiable source artifacts.

## Manifest provenance (detached Ed25519 signature)

Size and SHA-256 prove that a downloaded file matches the manifest; they do not prove who wrote
the manifest. `scripts/manifest_provenance.py` closes that gap with a detached signature stored
next to the inventory as `config/assets-manifest.sig.json`.

The signature commits to canonical manifest bytes: UTF-8 JSON, sorted keys, compact separators,
with any `signature` field excluded. Reformatting the tracked file therefore never invalidates a
signature, while any semantic change to an asset's URL, version, size or digest does.

```bash
# once, on an offline machine; keep the seed out of Git
python3 scripts/manifest_provenance.py keygen --seed-out ~/.cotg/manifest-seed.b64

# after editing the manifest
python3 scripts/manifest_provenance.py sign config/assets-manifest.json \
  --key-id release-2026 --seed-file ~/.cotg/manifest-seed.b64

# anyone, including CI
python3 scripts/manifest_provenance.py verify config/assets-manifest.json \
  --public-key "$COTG_MANIFEST_PUBLIC_KEY"
```

The signing seed is never read from a command-line argument: it comes from `--seed-file`, the
`COTG_MANIFEST_SIGNING_SEED` environment variable, or an interactive prompt. Verification requires
an external trust anchor — a base64 public key (`--public-key` / `COTG_MANIFEST_PUBLIC_KEY`) or a
pinned `--key-id`; the key embedded in the signature document is convenience metadata only and is
never trusted on its own.

CI enforcement lives in the `phase_one_configuration` job of `.github/workflows/debug.yml`. While
no signature file is committed the step reports a notice and passes, so introducing the tooling
does not break existing branches. Once `config/assets-manifest.sig.json` is committed, the job
fails if the repository variable `COTG_MANIFEST_PUBLIC_KEY` is unset or the signature does not
verify. Publishing the public key as a repository variable (not a secret) is deliberate: it must be
visible to reviewers and to forks.

`scripts/ed25519.py` is a dependency-free RFC 8032 implementation, checked against the RFC test
vectors, so verification works on a bare CI image with no third-party packages. It is not constant
time and is intended only for signing and verifying public release metadata.
