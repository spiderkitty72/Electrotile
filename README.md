# Electrotile

An Android puzzle game about electric circuits. Every square on the board holds a piece of a
circuit: wire, a resistor, a capacitor, a diode, a lamp, a battery or an output meter. Tap a
tile to turn it 90°. Turn the pieces until the circuit meets the level's targets, such as
lighting a lamp without burning it out, setting the output to a voltage window, drawing a set
current from the battery or charging a capacitor.

The circuit is re-simulated after every tap, so live conductors glow and each target shows its
current reading.

## Web version (HTML5 + PWA)

`web/` is the same game as a static HTML5 site with no build step. Copy the folder's contents
to any directory Apache serves, for example:

```sh
sudo cp -r web/. /var/www/html/electrotile/
```

then open `http://your-server/electrotile/`. Everything uses relative paths, so it works at the
site root or in a subfolder. The included `.htaccess` sets the manifest's MIME type and stops
browsers caching the service worker; it needs `AllowOverride FileInfo` and is optional.

It is a Progressive Web App: browsers offer to install it to the home screen, and once loaded
it plays offline. Installing and offline play need HTTPS (or `localhost`). After changing any
web file, bump `CACHE` in `web/sw.js` so installed copies pick up the update.

Progress is saved in the browser's local storage. Logic tests run with `npm test` in `web/`.
CI uploads a ready-to-copy `electrotile-web` artifact on every push.

## Project layout

- `core/` is plain Kotlin with no Android dependencies: the board model, the DC circuit
  solver, level targets and the handcrafted levels. Its unit tests check that every level's
  solution meets its targets.
- `app/` is the Jetpack Compose Android app: level select, the game board and progress saving.
- `web/` is the HTML5 + PWA version. `web/js/engine.js` is a JavaScript port of `core/`, so a
  level change has to be made in both `Level.kt` and `engine.js`.

## How the simulation works

`CircuitSolver` runs modified nodal analysis on the grid. Every port on a tile edge is a node,
touching ports on neighbouring tiles are joined, and wire tiles connect their ports through a
tiny resistance so the current in each wire arm can be drawn. Capacitors are open circuits at
DC and report the voltage they charge to. Diodes use a 0.7 V forward drop and are iterated
until every diode's on/off state is consistent. A battery current above 5 A counts as a short
circuit.

## Adding a level

Levels live in `core/src/main/kotlin/com/electrotile/core/Level.kt`. A level is written in its
solved orientation as a grid of tokens, and the game scrambles the rotatable tiles at the
start. Token codes are documented on `parseBoard`, for example `R1:200` is a 200 Ω resistor
turned once clockwise and `B0:30:0.08:0.15` is a 30 Ω lamp that lights between 80 and 150 mA.
`LevelsTest` fails if a solution does not meet its targets.

## Building

```sh
./gradlew :core:test          # game logic tests
./gradlew :app:assembleDebug  # APK at app/build/outputs/apk/debug/
```

Requires JDK 17 and the Android SDK (compile SDK 35). CI builds the debug APK on every push and
attaches it to the workflow run as `electrotile-debug-apk`.
