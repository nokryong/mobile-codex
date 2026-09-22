# Git runtime relocation — 0.1.11

The reported symptom was `git --version` failing with a reference to
`libpcre2-8.so`. Inspection of the public 0.1.7 APK (SHA-256
`06c2073c02d59082238e64051f792ea9ddc840c208588950dfef808a7855cdbd`)
confirmed that PCRE2 was packaged. Git's `DT_NEEDED` pointed to the renamed
`libdep_a6bbeb410e269e50.so`, but its `.gnu.version_r` / `DT_VERNEED` still
referenced `libpcre2-8.so`. zlib and OpenSSL references had the same issue.
Across 141 packaged tool ELF files, 24 had stale version-requirement names.

The packager now rewrites each version requirement's `vn_file` using the same
mapping as `DT_NEEDED` and `DT_SONAME`. It preserves symbol version names,
hashes and indices. It checks the serialized ELF again and rejects stale
references during preparation and `--verify`. The prepared-runtime test also
checks that the referenced packaged provider has the matching SONAME.

Validation includes a real compiled Linux ELF fixture with a versioned shared
dependency: the old rewrite is rejected, the repaired executable runs with
the original library removed, and auxiliary ABI version data is unchanged.
The same rewrite was applied to Git extracted from the public APK and its
remaining version requirement names were checked. This is structural ELF
validation, not execution of the Android binary on a phone.

The ARM64 instrumentation test additionally runs `git grep -P` with a
lookahead expression after an offline init/add/commit. These device tests are
compiled by CI; they still require a connected ARM64 Android device to run.
On-device checks: Settings → developer tools check, `git --version`,
init/add/commit/status, PCRE2 grep, and an HTTPS remote operation.

Existing installations need a rebuilt APK signed by a compatible key. No
repository files or user Git configuration need to be deleted or reset.

Reference: [Android Bionic version requirement resolution](https://android.googlesource.com/platform/bionic/+/b996d60/linker/linker.cpp),
`VersionTracker::init_verneed`, which matches `vn_file` against dependency SONAMEs.
