# Modrinth project page

Copy the sections below into the matching fields on the Modrinth project page.

**Project settings to match this text:**

- **License field:** `MIT` — that is the license of *this* mod's own code (`LICENSE.txt`).
  The bundled MCEF stays under its own LGPL-2.1 and is declared in the "Third-party software"
  section of the description below, which is what both the LGPL and Modrinth's rule on
  redistributed content ask for. Do not delete that section.
- **Environments:** client — required, server — unsupported.
- **Disclosure Modrinth requires:** the description states that MCEF downloads its Chromium
  runtime from the internet on first launch. Keep it.

---

## Summary (short description field, max 256 chars)

```
Companion client mod for the CyberCore web app. Embeds a real Chromium browser (MCEF) into Minecraft, renders it as a transparent HUD overlay, and bridges live player position and server events into the page. Bundles MCEF under LGPL-2.1.
```

---

## Description (main body, Markdown)

# CyberCore Client

**CyberCore Client** is the Minecraft-side half of the CyberCore project. It embeds a real Chromium
browser into the game through [MCEF](https://github.com/CCBlueX/mcef) and wires it up to the CyberCore
web app, so the web UI can render on top of the world and react to what happens in-game.

> **This mod is a companion, not a standalone feature.** On its own it only opens a browser pointed at
> a configurable URL. The interesting parts — the item UI, the live map, the event feed — come from a
> CyberCore web app and a CyberCore-aware server. If you are not running those, this mod will not do
> anything useful for you.

## Features

- **In-game browser overlay** — press **B** to open the web UI fullscreen, press **B** again to close
  it. Mouse, keyboard, scrolling, modifiers and text input all work inside the page, and **F5**
  reloads it. The key is rebindable under **Options → Controls → CyberCore**.
- **Transparent HUD rendering** — while no screen is open, the page keeps rendering as a HUD layer on
  top of the world with a transparent background, so the web app can draw overlays that sit over
  Minecraft itself. The mod tells the page which of the two modes it is in via
  `window.__ccSetOverlayMode(isOverlay)`.
- **GPU-accelerated frames where the platform allows it** — MCEF exposes CEF's accelerated-paint path,
  so on supported systems the finished browser frame stays on the GPU (D3D11 shared handle / dmabuf /
  IOSurface) instead of being copied to a CPU buffer and re-uploaded every frame. If the platform
  refuses, the mod falls back to the ordinary copy path.
- **Live player position bridge** — the client pushes the player's `x`, `z` and view yaw to the page
  (throttled, only on actual change) by calling `window.__ccPlayerPosition(x, z, yaw)`. Great for a
  live minimap or a positional HUD.
- **Server event bridge** — a CyberCore server can send custom payloads that the mod forwards into the
  page as a DOM event: `window.addEventListener('cybercore:event', e => ...)`, with the server's JSON
  in `e.detail`.
- **Server handshake** — position and event bridging stay off until a CyberCore server greets the
  client, so the mod stays inert on vanilla and third-party servers. Protocol version mismatches are
  logged as a warning.
- **Configurable base URL** — set it in-game through [Mod Menu](https://modrinth.com/mod/modmenu).
  Defaults to `https://mc-cybercore.space`, stored in `config/cybercore-client.properties`.
- **Update Cache button** — one click clears the page's Cache Storage and service workers and reloads,
  which saves a lot of pain while developing the web side.
- **Per-profile browser data** — the Chromium cache lives in `cybercore-browser/` inside the game
  directory, so every launcher profile keeps its own browser session.
- **Display-aware rendering** — browser zoom follows the OS content scale (HiDPI / Retina safe) and the
  browser frame rate follows the monitor refresh rate, capped at 240 fps.

## Requirements

| Dependency                                        | Required                                |
| ------------------------------------------------- | --------------------------------------- |
| [Fabric API](https://modrinth.com/mod/fabric-api) | yes                                     |
| MCEF                                              | bundled — nothing to install separately |
| [Mod Menu](https://modrinth.com/mod/modmenu)      | optional — needed for the config screen |

Client-side only. Installing it on a server does nothing.

**First launch downloads Chromium.** MCEF fetches its Chromium/CEF runtime from the internet the first
time the game starts after installing, so that startup takes noticeably longer, needs a connection, and
leaves a sizeable runtime on disk. Later launches reuse it.

## Setup

1. Install Fabric Loader and Fabric API.
2. Drop `cybercore-client-<version>.jar` into your `mods` folder.
3. Launch the game once and let MCEF finish downloading Chromium.
4. Open **Mods → CyberCore Client → Config** and set the base URL to your CyberCore web app.
5. Join a CyberCore server and press **B**.

## Web app integration

The mod talks to the page through a handful of hooks. All of them are optional — implement the ones you
need.

```js
// Called every time the player moves or turns.
window.__ccPlayerPosition = (x, z, yaw) => {
  /* ... */
};

// Called when the player disconnects, so you can clear the last known position.
window.__ccPlayerPositionClear = () => {
  /* ... */
};

// Fullscreen screen (false) vs. transparent HUD overlay (true).
window.__ccSetOverlayMode = (isOverlay) => {
  /* ... */
};

// Optional client-side router hook. If present, the mod calls it instead of
// setting location.href, which keeps your SPA state alive between navigations.
window.__ccNavigate = (path) => router.push(path);

// Events pushed by a CyberCore server.
window.addEventListener("cybercore:event", (e) => console.log(e.detail));
```

The mod marks itself to the page twice, because a lot of the web app's layout hangs on the answer:
every URL it opens gets `isMCEFcliendMod=true` appended as a query parameter, and on every document
load it also writes `localStorage.isMCEFcliendMod = "true"`.

The mod expects two routes to exist under your base URL:

- `/items` — shown when the browser screen is open (**B**)
- `/nothing` — shown while the browser is only rendering as a HUD overlay

## Third-party software

This mod's own code is licensed under the **MIT License**. The jar additionally ships the libraries
below inside `META-INF/jars/`. Each is the **unmodified** upstream artifact, nested as its own jar
rather than merged into this mod's classes, and any of them can be replaced: a copy placed loose in
`mods/` takes precedence over the nested one.

**MCEF — Minecraft Chromium Embedded Framework**

- Nested as `META-INF/jars/mcef-3.3.0-26.1.jar`
- Copyright © 2025 CCBlueX, copyright © 2023 CinemaMod Group
- Licensed under the **GNU Lesser General Public License, version 2.1**. The full license text travels
  with the nested jar as `LICENSE_mcef`.
- Complete corresponding source: <https://github.com/CCBlueX/mcef> — branch `26.1`, released as
  `com.github.CCBlueX:mcef:3.3.0-26.1` via [JitPack](https://jitpack.io).
- MCEF is used as a library and is **not modified** by this project. It embeds java-cef / CEF /
  Chromium, which carry their own BSD-style licenses; those notices ship inside the MCEF jar. MCEF
  downloads the Chromium runtime itself on first launch.

Libraries MCEF compiles against but does not ship, nested here so one file is enough to install:

| Library                                              | Version | License      |
| ---------------------------------------------------- | ------- | ------------ |
| [OkHttp](https://square.github.io/okhttp/)           | 4.12.0  | Apache-2.0   |
| [Okio](https://square.github.io/okio/)               | 3.6.0   | Apache-2.0   |
| [Kotlin standard library](https://kotlinlang.org/)   | 1.9.10  | Apache-2.0   |
| [LWJGL EGL bindings](https://www.lwjgl.org/)         | 3.4.1   | BSD-3-Clause |

The same notice ships inside the jar as `THIRD-PARTY.md`. CyberCore Client is not affiliated with,
endorsed by, or a product of CCBlueX, the CinemaMod Group, or the Chromium project.

## Links

- Source code: <https://github.com/francium-js/cybercore-client>
- Issues: <https://github.com/francium-js/cybercore-client/issues>

---

<details>
<summary><b>Українською</b></summary>

**CyberCore Client** — це майнкрафтна половина проєкту CyberCore. Мод вбудовує справжній браузер
Chromium (через [MCEF](https://github.com/CCBlueX/mcef)) прямо в гру і зв'язує його з веб-застосунком
CyberCore, щоб веб-інтерфейс міг малюватися поверх світу і реагувати на те, що відбувається в грі.

> **Це мод-компаньйон, а не самостійна фіча.** Сам по собі він лише відкриває браузер на заданому URL.
> Усе цікаве приходить з веб-застосунку CyberCore і з сервера, який знає про CyberCore.

### Можливості

- **Браузер в грі** — **B** відкриває веб-інтерфейс на весь екран, **B** ще раз закриває, **F5**
  перезавантажує сторінку. Миша, клавіатура, скрол і введення тексту працюють усередині сторінки.
  Клавішу можна перепризначити в **Налаштування → Керування → CyberCore**.
- **Прозорий HUD** — поки жоден екран не відкритий, сторінка продовжує малюватися шаром поверх світу з
  прозорим фоном. Мод повідомляє сторінці про режим через `window.__ccSetOverlayMode(isOverlay)`.
- **Апаратне прискорення** — MCEF відкриває шлях accelerated paint у CEF, тож там, де платформа це
  дозволяє, готовий кадр браузера лишається на GPU, а не копіюється в пам'ять і назад. Якщо ні — мод
  тихо повертається до звичайного шляху.
- **Позиція гравця** — мод передає `x`, `z` і кут погляду в сторінку через
  `window.__ccPlayerPosition(x, z, yaw)` (з тротлінгом, лише при реальній зміні). Зручно для живої
  мінімапи.
- **Події сервера** — CyberCore-сервер може надсилати пакети, які мод прокидає в сторінку як DOM-подію
  `cybercore:event` з JSON у `e.detail`.
- **Рукостискання з сервером** — міст позиції та подій вимкнений, доки сервер не привітається, тож на
  ванільних і чужих серверах мод неактивний.
- **Налаштовуваний base URL** — через [Mod Menu](https://modrinth.com/mod/modmenu). За замовчуванням
  `https://mc-cybercore.space`, зберігається в `config/cybercore-client.properties`.
- **Кнопка «Оновити кеш»** — чистить Cache Storage і service workers та перезавантажує сторінку.
- **Окремі дані браузера для кожного профілю** — кеш Chromium лежить у теці `cybercore-browser/`
  всередині ігрової директорії.
- **Врахування дисплея** — зум браузера слідує за масштабом системи (HiDPI/Retina), а частота кадрів
  браузера — за частотою монітора, максимум 240 fps.

### Вимоги

Fabric API — обов'язково, Mod Menu — опційно (потрібен для екрана налаштувань). MCEF вкладений у сам
jar, окремо встановлювати не треба. Тільки клієнт.

**Перший запуск завантажує Chromium.** MCEF тягне рушій Chromium/CEF з інтернету при першому старті
після встановлення, тож цей старт буде довшим, потребує з'єднання і займе помітно місця на диску.

### Ліцензії

Код самого мода — під **MIT**. У jar вкладені (незміненими, окремими jar-файлами в `META-INF/jars/`):
**MCEF** © 2025 CCBlueX, © 2023 CinemaMod Group під **LGPL-2.1** (повний текст ліцензії — у самому
вкладеному jar як `LICENSE_mcef`, вихідний код — <https://github.com/CCBlueX/mcef>, гілка `26.1`,
версія `3.3.0-26.1`), а також OkHttp, Okio і Kotlin stdlib (Apache-2.0) і LWJGL EGL (BSD-3-Clause).
MCEF не модифікований і використовується як бібліотека; його можна замінити своєю збіркою, поклавши її
окремо в `mods/` — такий файл має пріоритет над вкладеним. Той самий перелік їде в jar як
`THIRD-PARTY.md`. Проєкт не пов'язаний із CCBlueX, CinemaMod Group чи Chromium і ними не схвалений.

</details>
