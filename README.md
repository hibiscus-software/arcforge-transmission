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

### Connecting a power plant

Arcforge's power plants are grid generators: each connects at one tier, through a **Unit Transformer** of that tier set to
**Input** (its generator step-up), with its block touching any cell of the transformer's front face. A plant that pushes
FE (the turbines) or one the transformer pulls from both work.

| Plant | Connects at | Nameplate (default config) | One circuit at that tier |
|---|---|---|---|
| Combustion Plant | LV | 200 FE/t | 4,096 FE/t |
| Thermoelectric Plant | LV | 200 FE/t | 4,096 FE/t |
| Diesel Generator | MV | its engine's max: V8 3,000, V12 8,000, V16 15,000 FE/t | 32,768 FE/t |
| Gas Turbine Array | HV | its max: 4,200 FE/t a block of length (21,000 to 37,800) | 262,144 FE/t |
| Steam Turbine Array | EHV | its max, growing with its sections (8,199 to 401,722) | 2,097,152 FE/t |

Reactors drive turbines through their steam, so a reactor connects through its Steam Turbine Array.

- The transformer takes what the plant has, up to its nameplate and the transformer's own rating.
- Its HUD names the plant, with the FE/t it gives now and its nameplate. Its comparator reads the plant's load (what it
  gives over its nameplate).
- A plant on a unit transformer of another tier gives nothing: the HUD says so ("Steam Turbine Array connects at EHV").
- A Pin Insulator or Cable Termination takes FE from a plant only at the plant's own tier, up to its nameplate (below it,
  nothing; above it, an overvoltage that breaks the plant).

The ratings are the data map `arcforgetransmission:generator` (on blocks: a `tier`, and a `rating` in FE/t or `auto` to
read the plant's own max), so a datapack can map another mod's generators. An FE block without an entry works as it
always has: on a pin insulator at LV (higher overvoltages it), and on a unit transformer of any tier.

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
ungrounded yard shocks anyone standing near). **Station Fence Barbed Wire** goes on top of the fence, leaning outward the
way you look as you place it (Wrench on Rotate turns it): nothing jumps it, and touching it pricks
(`fence.barbedWireDamage`, 0 for none). A **Ground Switch** grounds a dead line so it's safe to work on.
Interlocks: a disconnect only operates with its breaker open (link them with **Control Cable**), a ground switch only
closes on a dead line, and a breaker won't close onto a grounded one.

**Protection coordination.** Breakers and reclosers are graded: the one nearest a fault or overload trips first and the
rest hold. A **Relay Panel**, linked with Control Cable to breakers fitted with a **Current Transformer**, gives them an
inverse curve and an instantaneous element (Wrench on Configure: its time dial; sneaking, fuse-saving or fuse-blowing),
trips on a lost phase or unbalance, and, linked to a substation transformer with a CT, saves it from a lightning surge.
A **Sectionalizer** counts a recloser's operations and opens in the dead time. An **Automatic Transfer Switch** moves its
load to its alternate feeder when the preferred one dies, and back when it returns.

**Heavy lines.** **Dead-End Towers** (HV, EHV) end lines or turn corners under tension (place them at 45 degrees);
**Transposition Towers** rotate the phases. On every dead-end (poles, towers, gantries) the strain strings run out along
the line and a slack jumper of the line's own conductor joins each phase through. **Bus** sections placed two blocks
apart in a row meet end to end and join. **Cable Risers** (every tier) take an overhead line underground on
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

**Sound and light.** Transformers hum while energised, louder and a little higher with their load. In rain, energised
EHV lines near you buzz softly and glow faintly blue-violet, more at night. A breaker or recloser opening on a fault, a
fuse blowing, a disconnect opened under load and a lightning flashover flash, throw sparks and crack (a fuse pops, a
breaker clunks), bigger with the fault current and the tier.

**Creative tabs.** Everything is in six tabs: **Towers & Lines**, **Transformers**, **Protection & Switching**,
**Substation Yard**, **Wiring & Tools** and **Materials**.

## Factory distribution

A transmission line's job is feeding factories, so a factory can take its line in at any tier, LV to EHV, and
distribute it at whatever tier its machines need. Everything here scales from the wire's rated amps at each tier
(W = 16 / 32 / 64 / 128 A):

| Tier | Busway sizes (2W / 4W / 8W) | FE/t | Transformer section (steps one tier down) | Feeder / tap frames |
|---|---|---|---|---|
| LV | 32 / 64 / 128 A | 8,192 / 16,384 / 32,768 | n/a (lowest tier) | 16, 32, 64, 128 A |
| MV | 64 / 128 / 256 A | 65,536 / 131,072 / 262,144 | MV→LV, 32,768 FE/t (LV bus 128 A) | 32, 64, 128, 256 A |
| HV | 128 / 256 / 512 A | 524,288 / 1,048,576 / 2,097,152 | HV→MV, 262,144 FE/t (MV bus 256 A) | 64, 128, 256, 512 A |
| EHV | 256 / 512 / 1,024 A | 4,194,304 / 8,388,608 / 16,777,216 | EHV→HV, 2,097,152 FE/t (HV bus 512 A) | 128, 256, 512, 1,024 A |

A transformer section's rating is one full circuit at its primary tier, so its secondary bus carries that tier's
largest busway. All of these are in the config's `factory` section.

**Cable Terminations.** Where a tray cable ends: a whole circuit (A, B and C) in one block, mounted on any face of a block
like a Pin Insulator. LV and MV are compact stress-cone potheads, HV three glass-skirted terminations 2 blocks tall,
EHV three taller glass ones with corona rings, 3 blocks tall. On top of a block it stands on it; on a side it stands
upright on a wall bracket; under one it hangs. On a machine it's that block's port (Wrench: Configure the mode, Sneak +
Configure the current limit), with a Pin Insulator's rules: above LV a foreign block is an overvoltage. On a lineup
section or a busway tap-off it joins its cable through instead. Rotate swaps B and C; Port switches an LV termination to
L1, N and L2 (while no cable is on it).

**Switchgear lineups** (Substation Yard tab). A factory's switchboard, built from sections placed side by side, facing
the same way: metal-enclosed switchgear at LV and MV (1 wide, 2 tall, ANSI-61 grey), gas-insulated bays at HV and EHV
(2 wide, 3 tall, aluminium tanks under a bus tube that runs from bay to bay).

- **Incoming**: where the circuit comes in, overhead wires on its roof (or air) bushings or a tray cable on a Cable
  Termination mounted on it. LV and MV: a load-break switch with current-limiting fuses. HV and EHV: a breaker with a
  disconnector (it won't operate under load) and an earthing switch (it won't close on a live line; the breaker won't
  close while it's earthed).
- **Transformer** (MV, HV, EHV; optional): steps the bus one tier down, rated one full circuit of its own tier (its %Z
  and %R in config). Its fans come on over 100% and let it carry up to 133%; past that it heats and trips its incomer on
  over-temperature (`factory.lineup.overtempTicks`). MV→LV is dry-type in the lineup's enclosure, HV→MV and EHV→HV
  oil-filled with radiators in a bay's footprint. Off-load taps ±2 × 2.5% (Wrench on Configure, with its incomer open).
  Without one, the lineup distributes at its incoming tier: EHV straight to EHV feeders.
- **Main Breaker**: between the incomer (or transformer) and the bus, its frame sized to what feeds it. Its trip unit
  (long-time, short-time, instantaneous) is set with the Wrench (Sneak + Configure picks the setting, Configure steps it).
  It won't close onto a faulted or earthed bus.
- **Feeder**: two breakers a section at LV and MV, one a bay at HV and EHV; Configure steps the nearest one's frame. Each
  leaves by a Cable Termination on its block (at LV and MV the lower breaker's on the lower block, the upper's on the
  upper) or a busway feed-in on the roof. Graded with the main: the feeder nearest a fault trips, the main holds.
- **Bus Tie** (optional): a double-ended lineup is Incoming, (Transformer), Main, Feeders, Bus Tie, Feeders, Main,
  (Transformer), Incoming. The tie is normally open; lose an incomer and close it (by hand or redstone) to carry both
  halves from the other. The two mains and the tie never all close at once (`factory.lineup.allowParallelSources`).

A lineup is one tier; at most `factory.lineup.maxFeederSections` (8) feeder sections a bus half. Its bus is inside it:
sections join their neighbours on their own. A gap, a wrong order or mixed tiers splits it, and each piece says what's
wrong ("Lineup: missing Main Breaker Section") and does nothing. An empty hand on a handle opens or closes it (a tripped
one resets to open first): the flags show red closed, green open, yellow tripped. A rising redstone signal into a
breaker's block works it, and a comparator on it reads its load. Look at any section for the whole lineup: each
incomer's voltage, current and power, each transformer's load and temperature, the mains and the tie, each feeder's
current against its setting.

**Busway** (Wiring & Tools tab) carries a feeder to its machines at every tier: LV bus duct (a slim ribbed housing with
plug-in openings every block), MV metal-enclosed bus (access covers every few blocks), HV gas-insulated bus and EHV
gas-insulated line (three aluminium tubes, flanged, an SF6 gauge every few blocks, on floor stands or ceiling hangers).
Each comes in three sizes, 2W, 4W and 8W (1, 2 or 3 bars on its joints): see the table above. Place it like pipe: it
joins busway of its tier and size into straights, elbows, tees and crosses; a size change needs a **Reducer** (which
carries its own breaker at the smaller size), and tiers never join. A **Feed-In** brings a feeder's circuit in, set on a
lineup feeder's roof or on a Cable Termination. A **Tap-Off** (LV a plug-in tap box, MV a bolted one at an access cover,
HV and EHV a small GIS tee module) clips onto a straight with its own breaker (frames 1W to 8W, Wrench on Configure),
graded with the feeder upstream; mount a Cable Termination on it and run a drop cable to the machine. Busway loses about
half what wire of its rating would (`factory.conductors.<tier>.busResistance`), heats four times more slowly
(`busHeat`), and is enclosed: no shocks, no lightning, no sag. Burnt out, a block is damaged (dull and smoking) and
breaks the circuit until it's replaced.

**Cable tray and Insulated Cable** (Wiring & Tools tab) carry a circuit from a feeder or a tap-off to a machine. Lay
**Cable Tray** (standard, 6 slots), **Wide Cable Tray** (12 slots, in two layers) or **Heavy-Duty Cable Tray** (6 slots,
deep, for HV and EHV) on a floor or wall, or hang it under a block: runs join up, with risers and bends. Use an
**Insulated Cable** of a tier on one Cable Termination, then on another of that tier: it's laid along the shortest run of
tray between them (a free drop counts double, `dropWeight`, so it goes into the nearest tray), a free drop of up to
`factory.tray.maxFreeDrop` (6) blocks at each end, and one item is used for every
`cableBlocksPerItem` (8) blocks. Two terminations within a drop of each other take a cable straight between them, no tray.

| Cable | Cores | Rating | FE/t | Tray slots | Tray |
|---|---|---|---|---|---|
| LV | three 2 px cores, side by side | 16 A | 4,096 | 1 | any |
| MV | three 2 px cores, red stripe | 32 A | 32,768 | 1 | any (not beside LV) |
| HV | trefoil, orange stripe | 64 A | 262,144 | 2 | heavy-duty |
| EHV | trefoil, purple stripe | 128 A | 2,097,152 | 3 | heavy-duty |

LV is kept out of tray blocks carrying MV and up (`segregateTiers`), and a full block refuses another cable; the refusal
says which block. Cables sharing a tray block heat each other: up to 3 a block keep their full rating, 4 to 6 keep 80%,
more keep 70% (`fullUpTo`, `midUpTo`, `derateMid`, `derateLow`), and a cable's rating is its most crowded block's, so it
burns out at that. Look at a tray block for its fill and derating; the Clamp Meter on it lists each cable (its current
against its derated rating, and why it's derated). Breaking a tray block, or a termination at either end, drops the
cable; the Wire Cutters on a termination take its cables up whole (not while they're live). Burnt out, the whole cable is
gone.

**Machines at their tier.** Arcforge's machines are mapped as loads (the data map `arcforgetransmission:load`, in
`data/arcforgetransmission/data_maps/block/load.json`; a datapack can map any mod's): each takes FE only at its own tier,
up to its rating (`auto`: its own energy capacity; it never takes more than its own input limit). A Cable Termination or
Pin Insulator on it delivers only at that tier: at a lower one it says so ("Diamond Press takes HV") and delivers
nothing; a higher one is an overvoltage. A machine may have several feeds of its tier, each held to its own circuit,
sharing its rating between them. Its HUD shows the machine, its tier, its draw across all its feeds and its rating; a
comparator on the termination reads its draw as a share of its rating. An unmapped foreign block takes FE at LV only, as
before.

| Tier | One circuit | Machines |
|---|---|---|
| LV | 4,096 FE/t | every other FE machine and multiblock (crushers, presses, furnaces, reactors, pumps, Heavy Water Tower, Water Intake, Centrifuge Cascade, ...) |
| MV | 32,768 FE/t | Electrolyzer, Arc Quarry, Chargepad |
| HV | 262,144 FE/t | Diamond Press, Carbon Reclaimer |
| EHV | 2,097,152 FE/t | none by default (a machine past one circuit takes several feeds, or an 8W gas-insulated line) |

The Chunk Loader and storage (the Battery Array, energy cells) aren't loads.

Inside the grid a cable, a busway joint or a lineup's bus is three conductors, A to A, B to B and C to C: the solver,
loss, burnout, the Clamp Meter and protection treat it as three wires.

## Config

Settings are in `arcforgetransmission-common.toml`; each player's sounds and particles (the hum, corona, arcs: their
volumes, budgets and toggles) in `arcforgetransmission-client.toml`. On a server, clients show the server's wire ratings and spans, fuse
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
python tools/block_model_preview.py . build/previews fence_run   # renders block models (front, side, 45 degrees) for review (`factory`: every busway and tray scene)
```

Generated files come from the generators in `tools/`. Change a generator, run it, then run the audit. Don't edit them
by hand:

| Generator | Writes |
|---|---|
| `transmission_tower_texture_gen.py` | `textures/block/transmission_tower/` (the towers' model sheet, factory distribution's paint, aluminium, silicone and rubber included, and particles), the tower and Cable Termination item sprites |
| `tower_attach_groups_gen.py` | `data/.../attach_groups/` for every tower, transformer and device |
| `grid_block_model_gen.py` | the Pin Insulators', Pole-Mount Transformer's, Wall Bushing's and Surge Arrester's models, textures and attach groups; the Station Fence's and its Barbed Wire's models |
| `wire_texture_gen.py` | `textures/block/wire/`, the coil and cable sprites, the Wire Cutters, Burnt Wire |
| `material_texture_gen.py` | the bauxite and aluminium textures (recoloured from Arcforge's nickel) |
| `transmission_sound_gen.py` | `sounds/` (the transformer hum, corona buzz, arc crack, fuse pop, breaker clunk; seeded, numpy and soundfile) and `sounds.json` |
| `transmission_particle_gen.py` | `textures/particle/` (corona, arc flash, spark) and `particles/` |
| `factory_block_model_gen.py` | busway (runs in three sizes, feed-ins, tap-offs, reducers; dull damaged copies) and cable tray models, textures, blockstates, item definitions and `data/.../block_shapes/` (their hitboxes); the tray cables' jacket textures |

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
| `effects` | Sounds and particles, the common side: their registries, the arc event (its packet too) and the hum's model (unit tested). |
| `effects.client` | The transformers' hum, corona on wet EHV lines, the arcs, the particles, and the client config. |
| `tower` | Everything placed whole: towers, transformers, devices, line hardware, the yard; the creative tabs. |
| `tower.model` | The structures' models, built in code (`ModelPreview` in the test sources renders them; `FactoryPreview` its factory scenes). `FactoryMesh`: the Cable Terminations, the lineups' sections and their operators. |
| `tower.client` | Structure rendering and outlines. |
| `factory` | Factory distribution: Cable Terminations (and their tall parts); switchgear lineups (`Lineup` plans a row of sections, unit tested; `SectionBreaker` runs a breaker on the grid's protection; `SectionBlockEntity` the section in the world). `factory.client`: their rendering and the lineup HUD. |
| `material` | Bauxite and aluminium: ore, raw ore, dust, ingot, blocks, and their world generation. |
| `gametest` (src/gametest) | Game tests: the grid built from real blocks in a running world. Its own source set, run by `runGameTestServer`, never shipped. |
