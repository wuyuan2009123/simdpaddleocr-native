# Publishing to Maven Central

`simdpaddleocr4j` ships on Maven Central as
`io.github.wuyuan2009123:simdpaddleocr4j`.

```xml
<dependency>
  <groupId>io.github.wuyuan2009123</groupId>
  <artifactId>simdpaddleocr4j</artifactId>
  <version>1.0.0</version>
</dependency>
```

The jar carries all six 64-bit natives under `/native/<os>-<arch>/`, so there is
nothing else to install.

## One-time setup

1. **Namespace** — sign in at <https://central.sonatype.com>, add namespace
   `io.github.wuyuan2009123` and verify it with the GitHub account
   `wuyuan2009123`. Nothing can be published until it shows *Verified*.
2. **Token** — Account → Generate User Token. The username/password pair goes
   into `~/.m2/settings.xml` as a `<server id="central">`.
3. **GPG key** — Central requires every artifact to be signed and the public key
   to be on a keyserver:

   ```bash
   gpg --quick-generate-key "your name <you@example.com>" rsa4096 default never
   FPR=$(gpg --list-secret-keys --with-colons | awk -F: '/^fpr/{print $10; exit}')
   gpg --keyserver hkps://keyserver.ubuntu.com --send-keys "$FPR"
   ```

   A passphrase-less key is easier for CI; if you set one, export it as
   `MAVEN_GPG_PASSPHRASE`.

## Local release

```bash
cd java
MAVEN_GPG_PASSPHRASE=... mvn -P release -pl core -am deploy
```

The `release` profile adds:

| plugin | what |
| --- | --- |
| `maven-source-plugin` | `-sources.jar` (natives excluded) |
| `maven-javadoc-plugin` | `-javadoc.jar` |
| `maven-gpg-plugin` | `.asc` signature per artifact |
| `central-publishing-maven-plugin` | uploads the bundle to the portal |

The parent POM is deployed alongside the module because `simdpaddleocr4j`
inherits from it.

## CI release

`.github/workflows/java-release.yml` runs on a `java-v*` tag and on manual
dispatch. Repo secrets it needs:

| secret | value |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | portal token username |
| `MAVEN_CENTRAL_PASSWORD` | portal token password |
| `MAVEN_GPG_PRIVATE_KEY` | `gpg --armor --export-secret-keys <fpr>` |
| `MAVEN_GPG_PASSPHRASE` | key passphrase, empty for a passphrase-less key |

```bash
export FPR=$(gpg --list-secret-keys --with-colons | awk -F: '/^fpr/{print $10; exit}')
gpg --armor --export-secret-keys "$FPR"     # paste into MAVEN_GPG_PRIVATE_KEY
```

Bump `<version>` in `java/pom.xml`, commit, then tag the same version:

```bash
git tag java-v1.0.0 && git push origin java-v1.0.0
```

The workflow refuses to run when the tag and the POM version disagree.

## Portal step

`autoPublish` is `false` in the release profile, so the upload lands in
*Deployments* as **Validated** and you press **Publish** to push it to Central.
Set `<autoPublish>true</autoPublish>` once you trust the pipeline — publishing is
irreversible.

## Troubleshooting

- **`Status: 524`** on upload — Cloudflare timed out on the 12 MB bundle. Retry,
  preferably from a faster network path.
- **`No versions available for ... [1.81,1.82)`** — Maven 4 RC cannot resolve the
  version range that `bcpg` uses. Publish with Maven 3.9.x.
- **Namespace not verified** — the portal answers 400 before it even reads the
  bundle.
