# Changelog

## Unreleased: Phase 4, substations, line hardware and the voltage model

### Added
- **Voltage.** Every wire drops R I + X I along its route; each insulator's voltage is on the HUD. A load below
  `voltage.fullPower` (90%) gets less of what it asks, none at `voltage.cutoff` (70%), settling over a few ticks.
- **Voltage Regulators** (MV, HV): source side to load side through a boost of up to 10% (16 steps of 5/8%), holding the
  load side within `voltage.tapBand`. Substation transformers have a **load tap changer** doing the same for their low
  side. Wrench on Configure holds a tap or lets it run.
- **Capacitor Banks** (MV, HV) and **Shunt Reactors** (HV, EHV): reactive power that raises or lowers the voltage near
  them; switched by hand, redstone or a Relay Panel (on `voltage.switchLow` / `switchHigh`).
- **Bus** (HV, EHV): pipe bus on glass station posts, sections in a row joining up; **Dead-End Gantries** (HV, EHV) to
  bring lines into a yard.
- **MV Distribution Transformer**: the pole can with an MV primary, so MV feeders serve houses directly.
- **Relay Panel**, with **Current** and **Potential Transformers** fitted to what it measures and **Control Cable** to link
  it: inverse-time and instantaneous overcurrent, fuse-saving or fuse-blowing, lost-phase and unbalance tripping, voltage
  switching of banks, and transformer differential (a CT-fitted substation transformer survives a lightning surge).
- **Ground Switches** (MV, HV, EHV) and **interlocks**: disconnects only operate with their breaker open, ground switches
  only close on a dead line, breakers refuse to close (or reclose) onto a grounded one.
- **Station Fence** and **Ground Grid**: a fault in a fenced yard gives step-potential shocks unless Ground Grid lies under it.
- **HV and EHV Dead-End Towers** and **Transposition Towers** (the jumpers cross, rotating the phases), **Cable Risers**
  (every tier) and **HV and EHV Underground Cable**.
- **Sectionalizer** (MV): counts recloser operations, opens in the dead time. **Automatic Transfer Switches** (LV, MV),
  galvanized pad-mounted cabinets plated like the transformers.
- **Storage** mode on Unit Transformers and Pin Insulators: a battery behind it covers the grid's shortfall and charges
  from its surplus.
- **Fault events**: lightning, ground fault, phase loss and unbalance, raised where they happen.
- Recipes, Shift item info, HUD lines and comparator output for all of it; every number in the config (new sections
  `transformers`, `voltage`, `groundFaults`, more in `protection`, reactance per tier in `wires`).
- Game tests (`./gradlew runGameTestServer`) and a model renderer (`./gradlew renderPreviews`).

### Changed
- **Creative tabs**: one a kind of thing instead of one for everything: Towers & Lines, Transformers, Protection &
  Switching, Substation Yard, Wiring & Tools, and Materials.
- **Transformers are electrical links**, not FE buffers: substation and pole-mount transformers pass power either way as
  the grid needs, holding none. The Configure step-up/step-down toggle is gone (Configure now holds or frees the tap
  changer). `poleTransformer.buffer` is replaced by `transformers.poleRating` and `transformers.substationRating`.
- **Protection is graded and timed**: breakers and reclosers add `protection.gradingInterval` ticks for each protective
  device between them and the trouble, so the nearest trips first. **Fuses melt on a curve** (`protection.fuseMelt`): fast
  at fault current, slowly just over their rating, instead of the tick they pass it.
- **Lightning** strikes now cause a flashover fault that protection clears (the span burns only if nothing does), instead
  of tripping devices directly.
- Wire shocks follow the live networks (through transformers too); a grounded line is dead.
- **Unit Transformers** serve every FE block touching their whole front face, and on Input take FE pushed into any of
  their blocks (a Steam Turbine Array's energy port beside one feeds it).
- **Automatic Transfer Switches** link through a transfer link, not jumpers, and judge each feeder by its own side's
  sources; Wrench on Configure holds one (manual) or lets it run (automatic).
- Port statuses say why nothing moves: **No FE block**, **Line dead** and **Nothing to trade with** (live, but nothing
  at the other end); only a port that trades power reads **Circuit complete**.
- The HUD is sent to the players with the block's chunk loaded as its own small message, not a block update (no chunk
  redraw, no wires resent each time a current changes). Clients show the server's wire ratings and spans, fuse factor,
  tap step and sectionalizer count.
- Wires' tubes are worked out once and cached, not every frame.
- A load's voltage share follows the load (its first node), never falls below `voltage.minShare` (1%), and recovers
  once the sag ends or it has no reading.
- The grid's save keys each node by its group and label (old saves are matched by place and take keys as they load),
  and keeps a regulator's boost.
- The game tests live in their own source set (`src/gametest`) and are never in the jar. Arcforge's version range is
  pinned to `[2.8.1,2.9)`.
- Burnt Wire smelts (or blasts) to a copper nugget. Ground Grid takes a pickaxe.
- A comparator by a device, transformer or Pole-Mount Transformer updates as soon as its reading changes.

### Fixed
- A limited port capped every pair on its line; now only its own traffic.
- A source's reactive losses were billed to islands it isn't joined to; now per island.
- Parallel transformers and looped lines put everything on one path (one burnt, the other idle); they share by impedance.
- A fault fed from several sources could exceed the fault limit on a wire; every source is scaled by the same ratio,
  and reactance now limits fault current.
- A capacitor bank's own voltage skewed the node voltages near it.
- Coupling side ids could overflow, and shunt ids could collide with port ids; losses went unbilled when the voltage
  rounds ran out.
- A 200-tower line with about 200 ports took 150 ms a tick; it's under 5 ms.
- FE a sink refused after being promised was lost (two service-meter legs both counted one block's room): energy now
  moves in one transaction, retried until every sink takes what it's given, so nothing drawn goes unplaced.
- Grading ignored fuses' curves: a recloser or breaker past a fuse waits for the fuse to melt at the measured current,
  plus `protection.gradingInterval`. Fuse-saving's fast shot only when what's beyond is fuses. Overload grading counts
  the devices on the branch carrying the overcurrent, not the deepest branch.
- The transformer differential compared magnitudes; it compares |I_in + I_out| with both counted in, against
  `differentialPickup` and `differentialSlope`, so through load and through faults never trip it.
- A sectionalizer counted a fault a fuse past it cleared (the line stayed live) toward its count.
- A disconnect could close onto a grounded line, and treated a breaker in an unloaded chunk as open.
- Players could change the grid where they may not build (adventure mode, spawn protection, claims); creative players
  got coils and fittings back when breaking or cutting.
- The voltage game test left lossy wire in the config if it failed; it's restored however the test ends.

## Unreleased: Phase 3, protection and the field

### Added
- **Fuse Cutouts** (LV, MV). One conductor's fuse hung beside a pole: line on top, load below. It blows the moment its
  current passes its rating (`protection.fuseFactor` times the wire's), opening the line; a **Fuse Link** puts it back.
- **Reclosers** (LV, MV) and **Circuit Breakers** (HV, EHV). Three-phase, north bushings (line) to south (load). Over
  their trip current for `protection.tripTicks` they trip, reclose after `recloseDelay` ticks, and after `recloseShots`
  tries lock out. Empty hand opens or closes one; the Wrench on Configure sets its trip current in eighths of the rating.
- **Disconnect Switches** (LV, MV, HV, EHV): a visible break opened and closed by hand. Wire past an open switch is dead.
- **Lightning.** Storms strike exposed spans (`lightning.strikeChance`), and any bolt near a line faults it: a breaker
  trips, else a fuse blows, else the span burns out. A transformer on the line without a **Surge Arrester** within
  `lightning.arresterRange` blocks is destroyed (`lightning.transformerDamage`).
- **Shield Wire.** Grounded steel strung between the towers' peaks (their new G points, MV and up): a bolt within reach of
  a shield wire above the conductors goes to ground instead.
- **Dead-End Poles** (LV, MV): strain insulators both ways with each conductor jumpered through, for line ends and corners.
  **Guy Wire** anchors a pole to the ground (use it on the pole, then the ground; sneak-use takes the last one off).
- **Clamp Meter.** Reads a wire's current, rating, loss and length, at the wire or from an insulator.
- **Comparator output** from devices (0 open, 1 to 15 with the load through them), transformers and the service meter.
- **Service Meter.** Takes L1, N and L2 from a Pole-Mount Transformer and feeds the FE block it faces, counting the FE.
- **Underground Cable** (LV, MV): insulated coils with a lead sheath. Never drawn, safe to touch, out of lightning's reach.
- **Placing at 45 degrees.** Every tower, pole and device faces the way you look to the nearest eighth of a turn, as a sign
  does, so corners are easy. The Wrench on Rotate turns them an eighth at a time (blocks that serve the block in front,
  like unit transformers, still go square).
- Recipes, item info (Shift) and HUD lines for all of it.

### Changed
- Network work is cached: routes per network, a tower's insulators and links per orientation, placement cells and shapes
  per tier and orientation, outlines per tier. The HUD refreshes every 5 ticks, a grid with no ports skips its solve, and
  storms draw once per grid instead of once per span.
- Towers no longer cast a shadow on the ground under them (light passes through their blocks).
- Outlines follow a turned structure's real shape (each part's own edges at 45 degrees), and diagonal hitboxes fit closer.
- Fuse Cutouts hang close against the pole they're put on, their bracket into it, facing it; looking anywhere on one shows
  its HUD (the upper half its line, the lower its load).
- Dead-End Poles' strain insulators are round glazed porcelain with sheds, like the poles' own insulators (the LV
  pin's size on LV, the MV post's on MV), with a galvanized clevis and round line clamp, instead of flat plates.
- A structure without room names itself ("The HV Disconnect Switch needs its whole space clear"), not "the tower".

### Removed
- The Creative Sources and Sinks. Worlds that had them lose those blocks.

### Fixed
- Wire coils, the Clamp Meter, Fuse Links and Guy Wire did nothing on fuse cutouts, reclosers, breakers and disconnect
  switches (the block took the click as an empty hand). Only an empty hand works a breaker or switch now.
- The Wrench on Rotate turned a fuse cutout instead of changing its phase: it now steps its label (line and load
  together, A, B, C).
- A freeze looking at a tower placed at 45 degrees (its outline was built as one huge shape), the EHV lattice worst.
- An out-of-memory crash when zero-length links (wall bushings, jumpers) formed a loop: routing could build a cycle.

## Unreleased: Phase 2, a usable grid

### Added
- **Substation Transformers** (MV: LV to MV, HV: MV to HV, EHV: HV to EHV). Multi-block, on a concrete pad: a tank with
  radiator banks and a conservator, three primary bushings (its own tier) along the north edge and three secondary
  bushings (a tier down) along the south. FE passes through a buffer, so energy is never made. The Wrench on Configure
  switches it between stepping down and stepping up.
- **Unit Transformers** (LV, MV, HV, EHV). One block with three bushings and a cable box that faces the FE block it
  serves. Input puts a generator's FE onto the line (generator step-up). Output feeds a machine from the line at any
  voltage, with no overvoltage.
- **Wall Bushing.** Mount Pin Insulators on its faces: those of one tier and phase are joined through it, so wires pass
  through a wall. It takes an Arcforge Conduit Facade, and a block used on the facade dresses it in that block's look.
- **Insulated wire** (LV and MV): Arcforge rubber round eight coils. It's safe to touch and drawn in black rubber.
- **Shocks.** Live bare wire hurts whatever touches it, more the higher the voltage: LV 1.5 hearts, MV a quarter of your
  health, HV half, EHV kills. Damage goes through armour, with a zap and sparks. Configurable per tier (`shockDamage`,
  `shockFraction`).
- **Aluminium**, from Bauxite Ore (stone and deepslate, Y -16 to 112): Raw Bauxite, its block, Aluminium Dust, Ingot and
  Block. Smelting, blasting, and Arc Crusher recipes; `c:` tags (`ingots/aluminum` and so on). MV wire is aluminium.
- **Recipes** for everything, from Arcforge materials: treated wood and steel for the towers, each tier's alloy
  (tempered, hardened, arcforged) for its tier, terracotta porcelain and glass insulators, and arcite for the EHV
  superconductor.
- **Voltage ratings.** A block in `arcforgetransmission:rated/<tier>` takes that tier and below off a Pin Insulator
  without an overvoltage. Arcforge's tempered blocks are MV, hardened HV, arcforged EHV.
- **HUD on every insulator:** on towers (when looking at an insulator) and on transformer bushings. It shows the label,
  the wires, and the current against the rating (yellow over 80%, red over), plus a port's mode and FE/t.

- **Turning transformers.** The Wrench on Rotate turns a substation or unit transformer a quarter turn (sneaking: the
  other way), like an Arcforge machine, if it has room. Its wires stay on and re-hang to the bushings' new places.

### Changed
- **EV is now EHV** (Extra High Voltage), everywhere: ids (`ehv_lattice_tower`, `ehv_wire_coil`, ...), config (`wires.ehv`),
  tags and names. Worlds and inventories from before keep their EV blocks and items, as EHV (registry aliases), and
  saved grids still read `ev`.
- Transformer bushings are their tier's own insulator, as on its towers: LV the porcelain pin, MV the porcelain post, HV
  and EHV upright glass disc stacks (a substation's two sides each in their own tier's).
- Unit Transformers grow with their tier, from about a block (LV) to three across and three tall (EHV).
- Wire coils drop the kind they were: bare or insulated. A Wall Bushing's internal links drop nothing.

### Fixed
- Attach files are read at startup, so a block never reads one from the jar mid-game.
- Towers no longer block skylight or shade the ground under them (the EHV lattice's dark patch).

## Unreleased: Phase 1, the power grid

### Added
- **The grid.** Voltage and current on top of FE (1 V × 1 A = 1 FE/t), carried over drooping wires.
  - Four tiers:
    - LV 128 V, copper, 8 A
    - MV 512 V, aluminium, 16 A
    - HV 2,048 V, silver alloy, 32 A
    - EHV 8,192 V, superconductor, 64 A
  - **Circuits.** No DC: every circuit is AC, either single-phase (L1 or L2, plus N) or three-phase (A, B, C).
    - One wire is one conductor. Every insulator is its own connection point and has a phase label.
    - Power flows only in complete circuits: A + B + C, or a line + N.
  - **Current and loss.**
    - Currents on each wire add as phasors, so opposite flows cancel and a shared neutral carries only the difference between its two lines.
    - Each wire loses I²R, where R is the material's resistance × the wire's arc length.
    - Routes are the cached shortest paths, recomputed only when the layout changes.
  - **Refused connections.** A chat message explains why. Refused:
    - two tiers (only transformers will bridge tiers);
    - three-phase to single-phase;
    - the wrong coil;
    - a span longer than the tier's limit (16 / 24 / 32 / 64 blocks).
  - **Overcurrent.**
    - A wire over its rating for 40 ticks burns out. It drops Burnt Wire and can start a fire beneath its middle.
    - The fault event carries the edge id, the cause and the magnitude.
  - **Overvoltage.** An MV, HV or EHV pin insulator that is switched on against another mod's FE block breaks, destroys or blows up that block (configurable).
  - **Saving.** The grid is saved per dimension and survives saving and chunk unloading. Unloaded ports neither give nor take.
- **Wire Coils** (LV, MV, HV, EHV).
  - Use one on an insulator, then another, to string a wire. Sneak-use in the air drops the loose end.
  - The sag is 0.05 × span. Each wire is a solid square tube 1 / 1.25 / 1.5 / 2 px across in one flat colour (no texture to shimmer at a distance, nothing finer than a pixel), and the EHV wire glows.
  - The coil's sprite is a black plastic spool wound with the tier's conductor.
  - Breaking either end drops the coil; a burnt-out wire drops nothing.
- **Pin Insulators** (LV, MV, HV, EHV).
  - Each is one insulator mounted on any face of a block, like Immersive Engineering's connectors. The pin insulators on one block make one port.
  - The Wrench:
    - Configure sets the mode: off, input or output.
    - Sneak + Configure sets the current limit, in eighths of the rating.
    - Rotate steps the phase label (A → B → C, or L1 → L2 → N).
    - The Wrench's sneaking actions (Sneak + Configure, Sneak + Dismantle) work on every grid block: they're handled before the game's sneak-click skips the block.
    - Port switches between three-phase and single-phase (only while unwired).
- **Wire Cutters.** Use them on a wire to cut it (its coil drops where it was cut), or on an insulator to cut every wire on it. Each cut costs one point of durability (238).
- **Pin Insulator HUD.** Looking at a pin insulator shows a small panel under the crosshair: its phase and circuit type, its port's mode and current limit, and its circuit's status with the FE/t through it.
- **Item info.** Every item has Arcforge's "Hold [Shift] for info" line, and a short description while Shift is held.
- **Pole-Mount Transformer** (LV).
  - The can the LV pole used to carry, now its own block, hung on a pole 1 px off its face.
  - It takes three-phase in on its primaries and gives split phase out: L1 + N and L2 + N, sharing the neutral. It works through a small FE buffer.
- **Creative Sources and Sinks**, one of each per tier (creative tab only), with an adjustable FE/t.
- **The towers carry wires.**
  - Each insulator is a connection point.
  - The Wrench on Rotate swaps B and C across a circuit (ABC ↔ ACB).
  - The LV pole's neutral, N, is a pin insulator on a standoff bracket two blocks below the crossarm, as on a real distribution pole.
  - **Hitboxes.** Each wood pole, crossarm and peak is one uniform box, and each insulator has its own. Looking at a tower outlines its whole frame as one shape; looking at an insulator outlines only that insulator. The EHV lattice's body is a uniform box per bay, stepping in with its legs, with a box per crossarm.
- **Config:** `arcforgetransmission-common.toml`, holding every number above.
  - `/arcforgetransmission config reset` (admins) puts it back to its defaults, as Arcforge's `/arcforge config reset` does: `all` lists what differs, `all confirm` resets everything, and a path resets one section or setting. The new values apply at once.
- **tools:**
  - `grid_block_model_gen.py`, `wire_texture_gen.py` and `tower_attach_groups_gen.py`, all deterministic.
  - `audit.py`, which checks:
    - every generator rebuilds identically (PNGs pixel for pixel, text ignoring line endings);
    - every texture comes from a generator;
    - texture sizes, alpha and speckle;
    - each model's UVs are 1:1, no face samples a clear texel, and nothing z-fights.
- **Unit tests** (JUnit 5): the flow solver, losses, overload and burnout, the connection rules, the attach points against the models, and texel density (every tower and transformer quad at 16 texels per block, so no mixels).

### Changed
- The LV Distribution Pole no longer carries a transformer can. The can is now the Pole-Mount Transformer.
- The wood poles' skins are a smooth ramp with soft grain, like a vanilla log's side, without the old cracks and dark bands.
- The Inductors are now **Pin Insulators** (block ids `<tier>_pin_insulator`).
