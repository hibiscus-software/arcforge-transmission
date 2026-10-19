# Changelog

## Unreleased: Phase 1, the power grid

### Added
- **The grid.** Voltage and current on top of FE (1 V × 1 A = 1 FE/t), carried over drooping wires.
  - Four tiers:
    - LV 128 V, copper, 8 A
    - MV 512 V, aluminium, 16 A
    - HV 2,048 V, silver alloy, 32 A
    - EV 8,192 V, superconductor, 64 A
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
  - **Overvoltage.** An MV, HV or EV pin insulator that is switched on against another mod's FE block breaks, destroys or blows up that block (configurable).
  - **Saving.** The grid is saved per dimension and survives saving and chunk unloading. Unloaded ports neither give nor take.
- **Wire Coils** (LV, MV, HV, EV).
  - Use one on an insulator, then another, to string a wire. Sneak-use in the air drops the loose end.
  - The sag is 0.05 × span. Each wire is a solid square tube 1 / 1.25 / 1.5 / 2 px across in one flat colour (no texture to shimmer at a distance, nothing finer than a pixel), and the EV wire glows.
  - The coil's sprite is a black plastic spool wound with the tier's conductor.
  - Breaking either end drops the coil; a burnt-out wire drops nothing.
- **Pin Insulators** (LV, MV, HV, EV).
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
  - **Hitboxes.** Each wood pole, crossarm and peak is one uniform box, and each insulator has its own. Looking at a tower outlines its whole frame as one shape; looking at an insulator outlines only that insulator. The EV lattice's body is a uniform box per bay, stepping in with its legs, with a box per crossarm.
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
