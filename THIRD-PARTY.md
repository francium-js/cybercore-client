# Third-party software in this mod

This mod ships the libraries below inside `META-INF/jars/`. Each one is the **unmodified**
upstream artifact, nested as its own jar file rather than merged into ours, so any of them can be
replaced: a copy placed loose in `mods/` takes precedence over the nested one.

## MCEF — Minecraft Chromium Embedded Framework

* Nested as: `META-INF/jars/mcef-3.3.0-26.1.jar`
* Copyright (C) 2025 CCBlueX, Copyright (C) 2023 CinemaMod Group
* License: **GNU Lesser General Public License, version 2.1** (the full text travels with the
  nested jar as `LICENSE_mcef`)
* Source: https://github.com/CCBlueX/mcef — branch `26.1`, published version `3.3.0-26.1`
  (`com.github.CCBlueX:mcef:3.3.0-26.1` on https://jitpack.io)

MCEF is used as a library and is not modified by this project. It embeds java-cef / CEF /
Chromium, which carry their own BSD-style licenses; those notices ship inside the MCEF jar.
MCEF downloads the Chromium runtime itself on first launch.

## Libraries MCEF needs but does not ship

MCEF compiles against these and expects its host to provide them, so they are nested here too:

| Library | Version | License | Why |
|---|---|---|---|
| [OkHttp](https://square.github.io/okhttp/) | 4.12.0 | Apache-2.0 | MCEF's Chromium runtime downloader |
| [Okio](https://square.github.io/okio/) | 3.6.0 | Apache-2.0 | required by OkHttp |
| [Kotlin standard library](https://kotlinlang.org/) | 1.9.10 | Apache-2.0 | required by OkHttp/Okio |
| [LWJGL EGL bindings](https://www.lwjgl.org/) | 3.4.1 | BSD-3-Clause | MCEF's GPU-acceleration probe, loaded on every platform |

Minecraft already provides commons-compress, commons-io, commons-codec and Guava, which MCEF also
uses; those are deliberately **not** nested, so the game's own versions stay in charge.
