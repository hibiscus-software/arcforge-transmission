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
   - **output:** FE from the grid into the block;
   - **storage:** the block is a grid-tied battery: it covers what the grid's sources can't and charges from what they
     have spare (the sources give first, then storage).

   Sneak + Configure sets the current limit. Keep each wire's current under its rating, or it burns out. Look at an
   insulator to see its status: *Circuit complete* once it trades power, *Nothing to trade with* (live, but nothing at
   the other end wants or offers FE), *Line dead*, *No FE block* (nothing it can serve where it's mounted), or what's
   wrong with the wiring.
5. **Watch the voltage.** Other mods' machines are LV only: MV and above destroys them.

**Tiers.** One three-phase circuit carries the tier's volts times its rated amps in FE/t; the EHV lattice carries two
circuits, so a full EHV tower line carries twice that. At its rating a circuit loses about 2.5% of its power in each
longest span (EHV's superconductor next to nothing):

| Tier | Volts | Rated A | FE/t per three-phase circuit | Conductor | Longest span |
|---|---|---|---|---|---|
| LV | 256 | 16 | 4,096 | copper | 16 |
| MV | 1,024 | 32 | 32,768 | aluminium | 24 |
| HV | 4,096 | 64 | 262,144 | silver alloy | 32 |
| EHV | 16,384 | 128 | 2,097,152 | superconductor | 64 |

Everything rated by the tier follows it: fuses, breakers and reclosers trip at shares of the wire's rating, unit and
substation transformers pass the tier's rated power, and the Pole-Mount and MV Distribution Transformers 4,096 FE/t
(`transformers.poleRating`).

The **Pole-Mount Transformer** hangs on a pole. It takes three phases in on its lid and gives US split phase (L1, N, L2)
out on its front.

**Changing voltage.** A **Unit Transformer** links generators and machines to a line of its tier: everything touching its
front face (the side its cable box faces), and on **Input** anything that pushes FE into any of its blocks (a Steam
Turbine Array's energy port beside it). **Output** feeds the blocks at its front; **Storage** makes them a battery, as
above. A **Substation Transformer** joins one tier's line to the next tier down, stepping down or (Wrench on Configure)
up, so a power plant can send EHV across the map and step it back down at the other end.

**Wire Cutters** cut the wire you look at, or every wire on the insulator you use them on; the coil drops.

**Voltage.** Every wire drops voltage with the current through it, so a long, heavily loaded feeder sags (the HUD shows
each insulator's voltage). Below 90% a load gets less than it asks; at 70%, nothing. A **Voltage Regulator** (MV, HV) in
the line steps its tap to hold the voltage past it, and a substation transformer's load tap changer holds its low side. A
**Capacitor Bank** switched in raises the voltage near it; a **Shunt Reactor** lowers it. Switch them by hand, by redstone,
or from a Relay Panel.

**Transformers work both ways.** A substation transformer, a Pole-Mount Transformer and the **MV Distribution
Transformer** (an MV feeder straight to houses) pass power whichever way the grid needs, holding none.

**Substations.** Bring lines in on a **Dead-End Gantry** (each phase's strain string runs out along the incoming line,
and a slack jumper of the line's own conductor loops from its clamp to the drop on the yard side), join them on **Bus** sections (placed in a row, they join),
switch them with breakers and disconnects, and fence the yard with **Station Fence** over **Ground Grid** (a fault in an
ungrounded yard shocks anyone standing near). A **Ground Switch** grounds a dead line so it's safe to work on.
Interlocks: a disconnect only operates with its breaker open (link them with **Control Cable**), a ground switch only
closes on a dead line, and a breaker won't close onto a grounded one.

**Protection coordination.** Breakers and reclosers are graded: the one nearest a fault or overload trips first and the
rest hold. A **Relay Panel**, linked with Control Cable to breakers fitted with a **Current Transformer**, gives them an
inverse curve and an instantaneous element (Wrench on Configure: its time dial; sneaking, fuse-saving or fuse-blowing),
trips on a lost phase or unbalance, and, linked to a substation transformer with a CT, saves it from a lightning surge.
A **Sectionalizer** counts a recloser's operations and opens in the dead time. An **Automatic Transfer Switch** moves its
load to its alternate feeder when the preferred one dies, and back when it returns.

**Heavy lines.** **Dead-End Towers** (HV, EHV) end lines or turn corners under tension (place them at 45 degrees);
**Transposition Towers** rotate the phases. **Cable Risers** (every tier) take an overhead line underground on
**Underground Cable** (now HV and EHV too). A Unit Transformer (or Pin Insulators) set to **Storage** makes a battery
behind it grid-tied.

**Placing.** Towers and devices face the way you look, to the nearest 45 degrees (as a sign), for corners; the Wrench on
Rotate turns them an eighth at a time. **Dead-End Poles** end or turn a line, and **Guy Wire** anchors them.

**Protection.** A **Fuse Cutout** blows on overcurrent (a **Fuse Link** replaces it). A **Recloser** or **Circuit Breaker**
trips, recloses, and locks out after its last try; open or close it by hand, and set its trip current with the Wrench on
Configure. A **Disconnect Switch** opens a line by hand, so the wire past it is safe to work on. Devices, transformers and
the Service Meter give a comparator signal with their load.

**Interlocks, in full.** A disconnect won't operate while any breaker it's linked to is closed (one in an unloaded chunk
counts as closed), nor close onto a grounded line. An **Automatic Transfer Switch** judges its preferred feeder by that
feeder's own sources (power coming back through the switch from the load side doesn't count); the Wrench on Configure
holds it where it is (manual) or lets it run itself (automatic).

**Lightning** strikes exposed spans in storms and faults the line. String **Shield Wire** between the towers' peaks (G) to
catch it, and put a **Surge Arrester** near each transformer so a surge doesn't destroy it.

**At the end of the line.** A **Service Meter** takes L1, N and L2 from a Pole-Mount Transformer and feeds the block it
faces. **Underground Cable** runs unseen and safe, and the **Clamp Meter** reads any wire's current and loss.

**Creative tabs.** Everything is in six tabs: **Towers & Lines**, **Transformers**, **Protection & Switching**,
**Substation Yard**, **Wiring & Tools** and **Materials**.

## Config

Settings are in `arcforgetransmission-common.toml`. On a server, clients show the server's wire ratings and spans, fuse
factor, tap step and sectionalizer count (sent when they join and when the config reloads), not their own. Admins (permission level 3) can put them back to their defaults from in
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
./gradlew runGameTestServer   # the game tests (src/gametest, never in the jar): real blocks in a running world, with Arcforge's own
./gradlew renderPreviews      # renders every structure's model to build/previews (-Pdesigns=NAME,NAME~ALT,NAME@lo:hi,NAME#3)
python tools/audit.py    # the art: rebuilds every generator and checks the output
```

Generated files come from the generators in `tools/`. Change a generator, run it, then run the audit. Don't edit them
by hand:

| Generator | Writes |
|---|---|
| `transmission_tower_texture_gen.py` | `textures/block/transmission_tower/` (the towers' model sheet and particles), the tower item sprites |
| `tower_attach_groups_gen.py` | `data/.../attach_groups/` for every tower, transformer and device |
| `grid_block_model_gen.py` | the Pin Insulators', Pole-Mount Transformer's, Wall Bushing's and Surge Arrester's models, textures and attach groups |
| `wire_texture_gen.py` | `textures/block/wire/`, the coil and cable sprites, the Wire Cutters, Burnt Wire |
| `material_texture_gen.py` | the bauxite and aluminium textures (recoloured from Arcforge's nickel) |

Everything else (blockstates, item definitions, other models, recipes, loot tables, tags, lang) is written by hand, and
the audit checks it: every registered block and item has its files and name, every model and texture named exists,
every translation key is both defined and used, and every recipe's items resolve.

## Code

| Package | What's in it |
|---|---|
| (root) | `ArcforgeTransmission`: the mod's entry point (registries, config, payloads). |
| `client` | Client-only extras: the items' info tooltips. |
| `grid` | The pure-Java core: tiers, phases, the graph and its networks (and their cached shapes), the flow solver (transformers as links, parallel paths, voltage drop, capacitor banks and reactors, storage, grounding, fault currents), moving the energy without losing any, matching saved nodes to insulators, burnout. Unit tested. |
| `grid.protection` | Curves, the fuse element, graded overcurrent, reclosing and lockout, differential, interlocks, sectionalizer, transfer switch, tap control, unbalance and its watch. Unit tested. |
| `grid.data` | Attach points, read from the generated JSON. |
| `grid.world` | The saved grid, the per-dimension tick (ports, transformers, shunts, grounding, faults, then each block's reaction), shocks, lightning, yards, the config, the HUD and settings payloads. |
| `grid.block` | Pin Insulators, the pole-mount transformers, the Wall Bushing, the Surge Arrester, Station Fence and Ground Grid. |
| `grid.item` | Wire coils and cable, the tools, Control Cable, CT and PT. |
| `grid.client` | Wire and transformer rendering, the insulator HUD, the payloads' client side. |
| `tower` | Everything placed whole: towers, transformers, devices, line hardware, the yard; the creative tabs. |
| `tower.model` | The structures' models, built in code (`ModelPreview` in the test sources renders them). |
| `tower.client` | Structure rendering and outlines. |
| `material` | Bauxite and aluminium: ore, raw ore, dust, ingot, blocks, and their world generation. |
| `gametest` (src/gametest) | Game tests: the grid built from real blocks in a running world. Its own source set, run by `runGameTestServer`, never shipped. |
