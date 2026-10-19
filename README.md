# Arcforge: Transmission

An addon for [Arcforge](https://github.com/zagdrath/arcforge) (NeoForge 26.3). It adds transmission towers and an AC power
grid in the IC2 / GregTech tradition: voltage and current on top of FE, carried over drooping wires, Immersive
Engineering style.

## Playing

1. **Put up towers or pin insulators.**
   - Place a tower (LV pole, MV pole, HV H-frame, EHV lattice).
   - Or mount **Pin Insulators** on the faces of a block. Every pin insulator on one block makes that block's port.
2. **String wires.** Use a **Wire Coil** of the right tier on one insulator, then on another. One wire is one conductor.
   Bare wire shocks anything touching it while live (EHV kills); LV and MV also come rubber-insulated, safe to touch.
   A **Wall Bushing** passes a run through a wall: pin insulators on its faces join through it by tier and phase.
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

**Changing voltage.** A **Unit Transformer** links a generator or machine (the block its cable box faces) to a line of its
tier. A **Substation Transformer** joins one tier's line to the next tier down, stepping down or (Wrench on Configure)
up, so a power plant can send EHV across the map and step it back down at the other end.

**Wire Cutters** cut the wire you look at, or every wire on the insulator you use them on; the coil drops.

**Placing.** Towers and devices face the way you look, to the nearest 45 degrees (as a sign), for corners; the Wrench on
Rotate turns them an eighth at a time. **Dead-End Poles** end or turn a line, and **Guy Wire** anchors them.

**Protection.** A **Fuse Cutout** blows on overcurrent (a **Fuse Link** replaces it). A **Recloser** or **Circuit Breaker**
trips, recloses, and locks out after its last try; open or close it by hand, and set its trip current with the Wrench on
Configure. A **Disconnect Switch** opens a line by hand, so the wire past it is safe to work on. Devices, transformers and
the Service Meter give a comparator signal with their load.

**Lightning** strikes exposed spans in storms and faults the line. String **Shield Wire** between the towers' peaks (G) to
catch it, and put a **Surge Arrester** near each transformer so a surge doesn't destroy it.

**At the end of the line.** A **Service Meter** takes L1, N and L2 from a Pole-Mount Transformer and feeds the block it
faces. **Underground Cable** runs unseen and safe, and the **Clamp Meter** reads any wire's current and loss.

## Config

Settings are in `arcforgetransmission-common.toml`. Admins (permission level 3) can put them back to their defaults from in
game or the server console, as with Arcforge's `/arcforge config reset`:

```
/arcforgetransmission config reset all            # lists the settings that differ from their defaults
/arcforgetransmission config reset all confirm    # resets every one of them
/arcforgetransmission config reset wires.ehv      # one section (or one setting: wires.ehv.maxSpan)
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
