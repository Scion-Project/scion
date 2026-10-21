# CI notes (ci.yaml)

`.github/workflows/ci.yaml` implements suggested next step #2 in
`docs/HANDOFF.md`: build with `native/build-android.sh`, then
`./gradlew assembleDebug`, and upload the APK. Both jobs pass on hosted
runners (first green run: 39602151945).

## 1. Summary

Two jobs, both on `macos-14`:

| Job | What it builds | Rough cost | Purpose |
|---|---|---|---|
| `full-build` | static deps + full QEMU cross-build + `assembleDebug` | ~7.5 min cold (measured) | the real `app-debug.apk` artifact |
| `light-build` | static deps + stub `.so` + `lint`/`assembleDebug` | ~5.5 min (measured) | fast gate; still exercises NDK/meson + the whole AGP build |

**Trigger decision: both jobs run on every PR and on pushes to `main` /
`pixel*`.** The full QEMU cross-build turned out to be cheap on hosted
`macos-14` runners: the whole 1056-step ninja build plus deps took about 7.5
minutes with a cold sysroot cache. That's fast enough to gate every PR on the
real build, so `full-build` isn't moved to main-only. If runner minutes ever
become a problem, make `light-build` the PR gate and move `full-build` to
`workflow_dispatch` plus a nightly `schedule` (the comment above the
`light-build` job says the same). Timeouts are 45 min (full) and 30 min
(light), several times the measured time.

A clean CI build gives a ~11.8 MB `libqemu-system-aarch64.so` and a ~6.0 MB
`app-debug.apk`. A local debug APK can be much bigger (10.6 MB on the dev Mac)
with identical contents: AGP's incremental packaging leaves unused space in an
APK it updates in place. Compare entries (`unzip -v`), not file sizes.

## 2. Why macOS, and why these choices

* **macOS is mandatory.** `native/build-android.sh` is written for a Mac host:
  the `pick_host_cc` probe uses `xcode-select -p` / `DEVELOPER_DIR`, checksums
  use `shasum`, and job counts use `sysctl -n hw.logicalcpu`. A Linux runner
  would need the script generalised first.
* **`macos-14` (Apple-silicon) + Rosetta.** NDK r28 ships only a
  `darwin-x86_64` toolchain (`toolchains/llvm/prebuilt/darwin-x86_64`); the
  script picks it with `ls -d .../prebuilt/* | head -1`. On an arm64 runner
  those clang binaries run under Rosetta 2, so the workflow installs it
  (verified working). If Rosetta ever becomes unavailable, switch both jobs to
  `macos-13` (Intel) and delete the Rosetta step; no other change is needed.
* **`setup-android` gets `packages: platform-tools`.** Its default also
  requests the removed `tools` package, which makes sdkmanager exit 1.
* **NDK pinned to `28.2.13676358`**, the exact version in `docs/HANDOFF.md`.
  Installed with `sdkmanager`, then `ANDROID_NDK_HOME` is exported so the build
  script does not have to guess from `~/Library/Android/sdk/ndk`.
* **JDK 21 (temurin).** Matches HANDOFF (AGP 9.3.3 / Gradle 9.7.1 / JDK 21).
  Note `app/build.gradle.kts` still sets `compileOptions` to Java 17 for the
  compiled bytecode — that is unrelated to the JDK that runs Gradle.
* **compileSdk 36 / build-tools 36.0.0** installed explicitly so AGP never has
  to auto-download an SDK component mid-build.
* **Homebrew** supplies `meson ninja pkgconf cmake` (the script requires
  `meson ninja pkg-config cmake`; `pkgconf` provides the `pkg-config` shim).

## 3. Caching

Both native caches are keyed on `hashFiles('native/build-android.sh')` — the
dep URLs, sha256 sums and build flags all live in that script, so any change to
it invalidates both caches.

* **Dependency tarballs** (`native/build/dl`): avoids re-downloading. The
  script already skips a present, checksum-matching tarball.
* **Static sysroot** (`native/build/sysroot`): the ~10 min saver. `full-build`
  splits the native build into two steps — `native/build-android.sh deps`
  (guarded by `if: steps.sysroot-cache.outputs.cache-hit != 'true'`) and
  `native/build-android.sh qemu`. On a sysroot cache hit the deps step is
  skipped and only QEMU is rebuilt. This is safe because the GitHub workspace
  path is stable per repo, so the absolute paths baked into the generated
  `*.pc` files stay valid across restores; the cross files and the
  `pkg-config`/`host-cc` wrapper scripts are regenerated at the top of the
  script on every run, so they are never stale. If a restored sysroot ever
  looks wrong, bump the key (edit the script or add a suffix) to force a
  rebuild.
* **Gradle** caches (`~/.gradle` caches + wrapper dists) are handled by
  `gradle/actions/setup-gradle`.
* `light-build` caches the tarballs but **not** the sysroot — its whole point
  is to rebuild the deps so the NDK/meson cross path is exercised every run.

## 4. Submodules

`actions/checkout` uses `submodules: true` (not `recursive`) in `full-build`:
Inferno's `pixel-dev` has no nested git submodules, and its meson wraps (`dtc`,
`mlib`, `libyuv`, `keycodemapdb`) are fetched during `meson setup`, so the QEMU
build step needs network (default on hosted runners). `light-build` checks out
with `submodules: false` — it only runs `deps`, which builds glib/pixman from
tarballs and never touches the Inferno tree.

## 5. The light job's tradeoff (read this)

The brief asked for a configure/meson-setup **dry run** of QEMU in the fallback
job. I did not add one, deliberately:

* The build script couples `meson setup` and `ninja` inside `build_qemu` with
  no CLI entry point that stops after configuration, so a QEMU meson dry run
  can only be had by either (a) duplicating the long `-D` option list from
  `configure_qemu()` into the workflow — which will silently drift from the
  script — or (b) adding a `configure`-only subcommand to the script, which
  would mean modifying the repo (out of scope for this task).
* What `light-build` does instead still gives most of the value cheaply:
  `native/build-android.sh deps` runs **real cross meson builds** of glib and
  pixman through the NDK cross file, so a broken toolchain, a bad cross file, a
  missing host tool, or a dependency checksum failure all fail fast. The QEMU
  `-D` option set is only validated by `full-build`.

**Recommended follow-up (for the lead, not applied here):** add a thin
entry point to `native/build-android.sh` so CI can smoke-test the QEMU meson
options without compiling. Minimal, non-invasive version — add one case and
reuse the existing `configure_qemu`:

```sh
# in the case statement:
configure) build_qemu_configure ;;

build_qemu_configure() {
  [[ -f "$SRC_QEMU/meson.build" ]] || { echo "run: git submodule update --init" >&2; exit 1; }
  rm -rf "$WORK/qemu-dryrun"
  configure_qemu "$WORK/qemu-dryrun"   # meson setup only, no ninja
}
```

Then `light-build` could run `native/build-android.sh deps` followed by
`native/build-android.sh configure` (after the Inferno submodule init) to catch
a bad `-D` flag in seconds. Until that exists, `full-build` is the only QEMU
check.

## 6. Build-script bugs CI found (fixed in this PR)

The first hosted runs exposed two problems in `native/build-android.sh` that a
dev Mac had hidden:

* **Flaky GNU mirror, silently ignored.** `ftpmirror.gnu.org` redirected the
  nettle download to a mirror with an expired TLS certificate. `fetch()` now
  retries (`--retry 5 --retry-all-errors`), falls back to `ftp.gnu.org`, and
  removes partial or mismatching files. Download and unpack failures are now
  fatal: bash command substitutions don't inherit `set -e`, so
  `s="$(unpack "$(fetch …)")"` had kept going until `./configure: No such
  file or directory`.
* **Host packages leaking into the cross-build.** libyuv (a CMake subproject)
  found the runner's `/opt/homebrew/lib/libjpeg.a` and exported
  `/opt/homebrew/include`, where Homebrew's nettle 4 headers shadowed our
  nettle 3.10. `trng.c` then linked against `nettle_drbg_ctr_aes256_update`,
  which 3.10 doesn't have. The cross file's `[cmake]` section now restricts
  library, header and package searches to our sysroot and disables the JPEG
  lookup. Any Mac with Homebrew libjpeg was getting the same leak; it only
  broke where Homebrew nettle 4 was also installed.

## 7. Checklist / maintenance

1. **Rosetta / runner arch.** Verified on `macos-14`. If GitHub drops
   Rosetta, switch to `macos-13`.
2. **Runtime vs. timeout.** Measured ~7.5 min (full, cold) and ~5.5 min
   (light) against timeouts of 45 and 30 min. Revisit if Gaia/rebases make
   the build much bigger.
3. **Action versions.** All six actions are pinned to real, long-published
   tags (all pre-2026, so comfortably older than two weeks):
   `actions/checkout@v4.2.2`, `actions/setup-java@v4.7.0`,
   `android-actions/setup-android@v3.2.2`, `actions/cache@v4.2.0`,
   `actions/upload-artifact@v4.6.0`, `gradle/actions/setup-gradle@v4.2.2`.
   For a supply-chain-hardened repo, re-pin each to its commit SHA.
4. **SDK component names.** Confirm `build-tools;36.0.0` and `platforms;android-36`
   are the exact packages AGP 9.3.3 wants; adjust if the toolchain moves.
5. **APK path.** `app/build/outputs/apk/debug/app-debug.apk` assumes the stock
   AGP output layout and `applicationId` with no extra flavours — correct for
   the current `app/build.gradle.kts`.
6. **Artifact licensing.** Publishing `app-debug.apk` is fine: it contains only
   our Kotlin + the GPL/AGPL QEMU `.so` and no Apple files (docs/ETHICS.md).
   Keep it that way if the app ever bundles more assets.
7. **Branch filter.** `on.push.branches` is `[main, pixel*]`; adjust to the
   repo's real branch names.
