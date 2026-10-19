# Arcforge: Transmission

An addon for [Arcforge](https://github.com/zagdrath/arcforge) (NeoForge 26.3). It adds transmission towers and an AC power
grid in the IC2 / GregTech tradition: voltage and current on top of FE, carried over drooping wires, Immersive
Engineering style.

## Playing

1. **Put up towers or pin insulators.**
   - Place a tower (LV pole, MV pole, HV H-frame, EV lattice).
   - Or mount **Pin Insulators** on the faces of a block. Every pin insulator on one block makes that block's port.
2. **String wires.** Use a **Wire Coil** of the right tier on one insulator, then on another. One wire is one conductor.
3. **Wire whole circuits.** FE moves only when a port is complete:
   - **three-phase:** A, B and C, each on its own wire run;
   - **single-phase:** L1 (or L2) and N.

   The phase labels are set with the Wrench:
   - **Rotate** on a pin insulator steps its label (A → B → C, or L1 → L2 → N); on a tower or a transformer's
     primaries it swaps B and C across the circuit.
   - **Port** switches a pin insulator between three-phase and single-phase labels (only while it has no wires).
4. **Set the pin insulators' mode** with the Wrench on Configure:
   - **input:** FE from the block into the grid;
   - **output:** FE from the grid into the block.

   Sneak + Configure sets the current limit. Keep each wire's current under its rating, or it burns out.
5. **Watch the voltage.** Other mods' machines are LV only: MV and above destroys them.

The **Pole-Mount Transformer** hangs on a pole. It takes three phases in on its lid and gives US split phase (L1, N, L2)
out on its front.

**Wire Cutters** cut the wire you look at, or every wire on the insulator you use them on; the coil drops.

## Config

Settings are in `arcforgetransmission-common.toml`. Admins (permission level 3) can put them back to their defaults from in
game or the server console, as with Arcforge's `/arcforge config reset`:

```
/arcforgetransmission config reset all            # lists the settings that differ from their defaults
/arcforgetransmission config reset all confirm    # resets every one of them
/arcforgetransmission config reset wires.ev       # one section (or one setting: wires.ev.maxSpan)
```

## Building

```
./gradlew build          # the mod (compiles against libs/arcforge-<version>+<mc>.jar)
./gradlew test           # the grid's unit tests, attach points and texel density (plain Java)
python tools/audit.py    # the art: rebuilds every generator and checks the output
```

The textures, models and attach points all come from the generators in `tools/`. Change a generator, run it, then run the
audit. Don't edit the generated files by hand.

## Code

| Package | What's in it |
|---|---|
| `grid` | The pure-Java core: tiers, phases, the graph, the flow solver, burnout. It is unit tested. |
| `grid.data` | Attach points, read from the generated JSON. |
| `grid.world` | The saved grid, the per-dimension tick, the config. |
| `grid.block` | Pin Insulators, the transformer and the creative blocks. |
| `grid.item` | Wire coils. |
| `grid.client` | Wire and transformer rendering. |
| `tower` | The towers. |
