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
