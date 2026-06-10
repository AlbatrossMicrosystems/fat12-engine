# PROVENANCE

This document records the origin, lineage, and copyright status of the FAT12 engine
published in this repository (the `com.ams.fat12ex.core` module, "the Engine").

## 1. Lineage

The Engine was ported from the author's **own prior, unpublished Kotlin implementation**
of the public Microsoft FAT file-system specification. Both that prior implementation and
this Engine are independent implementations of the published specification.

## 2. Independence and originality

The Engine is an **original, independent implementation** derived solely from the published
Microsoft FAT on-disk format. **No third-party FAT source code was referenced,
transliterated, or copied** while creating this Engine. The only commonality with any other
FAT implementation is the public specification itself and its published constants
(end-of-chain markers, the `0xE5` deleted-entry marker, the 32-byte directory entry, the
`< 4085`-cluster FAT12 boundary) — facts of the specification, present in every conforming
FAT driver, not copyrightable expression.

A code-level independence review was performed and is **retained in the author's private
project records**.

## 3. Sole-copyright attestation

The author holds the **sole personal copyright** in the Engine. It was written entirely as
the author's own personal work:

- **No employer** owns or co-owns it (it was not created within scope of employment).
- **No client** commissioned or owns it.
- **No co-author** contributed copyrightable material.
- **No third-party code snippets** (GPL or otherwise) are incorporated.

The author therefore has the exclusive right to license the Engine, and releases it under
the **Apache License, Version 2.0** (see `LICENSE`).

## 4. Packaging note

The Engine is packaged under the `com.ams.fat12ex.*` namespace.
