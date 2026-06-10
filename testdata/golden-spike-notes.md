# Wave-0 Spike Notes — golden reproduce (A1) + fsck oracle (A2)

> Internal Phase-9 Wave-0 handoff note. Plan 02 turns the verified A1 commands into the
> published `testdata/README.md`; Plan 03 consumes the A2 fsck gate mode for CI.
> Spike run 2026-06-10 under WSL (dosfstools is Windows-unavailable; goldens were made under WSL).

## dosfstools version

```
ii  dosfstools  4.2-1.2build1  amd64  utilities for making and checking MS-DOS FAT filesystems
fsck.fat 4.2 (2021-01-31)
```

Upstream **4.2** — matches the version that produced the committed goldens, and the version
that ships on `ubuntu-latest` (Ubuntu 24.04 noble). CI pins this intent by asserting
`mkfs.fat --version` reports 4.2 (Pitfall 4).

## A1 — golden byte-reproduce: **PASS (all three, exact byte-match)**

The committed goldens differ from a plain `mkfs.fat` run in exactly **7 bytes** — the
volume-label root-directory entry's creation/write **timestamp** fields. `mkfs.fat` 4.2
honours `SOURCE_DATE_EPOCH` (reproducible-build support), so pinning it yields a
byte-for-byte identical image. All three goldens were stamped **2026-06-08 19:39:04 UTC →
`SOURCE_DATE_EPOCH=1780947544`**.

Verified commands (each produced `diffcount=0` vs the committed golden):

```bash
SOURCE_DATE_EPOCH=1780947544 mkfs.fat -F 12 -n "TEST1440K" -i a58bab35 -C fat12_1440k_empty.img 1440
SOURCE_DATE_EPOCH=1780947544 mkfs.fat -F 12 -n "TEST720K"  -i a58bc8c6 -C fat12_720k_empty.img 720
SOURCE_DATE_EPOCH=1780947544 mkfs.fat -F 12 -n "TEST360K"  -i a58bded5 -C fat12_360k_empty.img 360
```

- `-C <file> <N>` creates `<file>` of N×1024 bytes (1440/720/360 KiB) then makes the FS.
- `-i <volid>` pins the 32-bit volume serial (otherwise random).
- `SOURCE_DATE_EPOCH` pins the label-entry timestamp (otherwise current time → the 7-byte diff).
- Without `SOURCE_DATE_EPOCH` the images are identical **except** those 7 timestamp bytes.

**D-09 fallback: NOT triggered.** The committed goldens are authentic 4.2 output and are
reproducible as-is — they are NOT regenerated/replaced (D-08 default path holds).

## A2 — fsck-clean: **PASS (both engine images, exit 0, no warnings)**

Emitted via `./gradlew :core:fsckEmit` → `core/build/fsck/{fresh,ops}.img` (engine writes,
NOT goldens — independent oracle):

```
fsck.fat -n fresh.img → "1 files, 0/2847 clusters"   exit=0
fsck.fat -n ops.img   → "3 files, 5/2847 clusters"   exit=0
```

No benign free-count / dirty-bit warning. The engine's fresh-format and post-write/mkdir/delete
output are both accepted clean by dosfstools 4.2.

## Plan-02 / Plan-03 handoff

- **(a) testdata/README.md (Plan 02):** publish the three verified `mkfs.fat` command lines
  above, the dosfstools **4.2** version, and the `SOURCE_DATE_EPOCH=1780947544` note for
  exact reproduction.
- **(b) CI fsck gate (Plan 03):** **`fail-on-nonzero`** — A2 was clean, so the CI step runs
  `set -e; fsck.fat -n fresh.img; fsck.fat -n ops.img` with NO tolerance/`|| true`.
- **Private remote:** `origin → https://github.com/AlbatrossMicrosystems/fat12-engine.git`
  (PRIVATE; gh authed as AlbatrossMicrosystems). Repo name `fat12-engine` matches the badge URL.
- **Live `:core:test` count:** **2119** (fsck-excluded; 0 failures/0 errors, 23 suites).
