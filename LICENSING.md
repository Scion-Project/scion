# Licensing

This file is a plain-English map of who may use what under which licence. It does not replace the licence files.

The repository-level `LICENSE` is AGPL-3.0 and is not changed.

## Original work: MIT-0

Files that the Scion project wrote entirely from scratch (every commit by the project's authors, no third-party notice, nothing ported or derived from another codebase) are licensed **MIT-0** (MIT No Attribution, SPDX `MIT-0`; text in `LICENSES/MIT-0.txt`). Each such file carries `SPDX-License-Identifier: MIT-0` in its first lines, except the files listed in the table below, which cannot hold a comment.

MIT-0 paths (top-level location, number of files):

- `.github`: 3 files
- `(top level)`: 5 files
- `app`: 50 files
- `docs`: 10 files
- `native`: 4 files
- `tools`: 6 files

The full per-file list is in the pull request that introduced this file, and every file's own first lines are authoritative.

Note: 2 of these files already carried a project-chosen `SPDX-License-Identifier` tag (MIT or AGPL-3.0-or-later); it was replaced by `MIT-0`.

## Files that keep their existing licence

- Anything not listed above, and any file that has no explicit `SPDX-License-Identifier: MIT-0` tag, stays under the repository's existing licence, as before.
- `third_party/inferno` (the Pyre/Inferno submodule: GPL-2.0 for QEMU, AGPL-3.0 for Inferno's own code) and everything it builds.
- `gradlew`, `gradlew.bat`, `gradle/wrapper/*`: Gradle's, Apache-2.0.
- `native/patches/*` (patch against libslirp) and `native/keymaps/en-us` (generated from QEMU data, GPL-2.0).
- `docs/ios27/*` and `docs/IOS27.md`: notes from analysing Apple firmware; `tools/ipsw_probe.py` and `README.md`: partly taken from other sources (see the PR for the reasons).

