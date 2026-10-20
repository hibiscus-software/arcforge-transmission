"""Writes the transmission towers' and transformers' attach groups: data/arcforgetransmission/attach_groups/<tower>.json.

    python tools/tower_attach_groups_gen.py <addon repo>

Each terminal is read off the tower's geometry in TowerMesh (the same constants, worked the same way): a pin
insulator's crown top, a post insulator's line clamp top, or a suspension string's clamp bottom. Positions are px in
the model's north-facing frame: x across the line, y up from the base block's bottom, z along the line, the origin the
centre column's base block's minimum corner. TowerAttachGroupsTest checks every terminal sits on the model's surface.

Every terminal is one insulator, wired on its own (one wire is one conductor); its label is the phase it starts with
(the wrench relabels it in game). A group is the insulators of one circuit, for the wrench's ABC/ACB swap. There is
no DC.
  LV Distribution Pole      the crossarm's three pins (A, B, C); the neutral (N) on its standoff below the crossarm, for the split phase
                            the pole-mount transformer gives
  MV Sub-Transmission Pole  one circuit: the upper post (A) and the lower arm's two (B, C); the shield peak (G)
  HV H-Frame Tower          one circuit: the three strings; both shield peaks (G)
  EHV Lattice Tower          two circuits (double circuit): west A/B/C top to bottom, east likewise; the shield peak
                            (G)
  Substation Transformers   the primary (A B C, its own tier, along the north edge) and the secondary (A B C, a tier
                            down, along the south): each group names its tier
  Unit Transformers         one circuit: the three bushings on the lid, A B C west to east
"""
import json
import math
import os
import sys


def r(v):
    return round(v, 4)


def p(x, y, z):
    return [r(x), r(y), r(z)]


def lv():
    # pinInsulator(x, armY + 4, (armZ0 + armZ1) / 2): crown top at (armY + 4) + 2 + 5.6.
    arm_y, z = 132.0, (10.0 + 13.0) / 2
    top = arm_y + 4 + 2.0 + 5.6
    # The neutral: pinInsulator(NEUTRAL_X, NEUTRAL_Y, 8) on its standoff, crown top at NEUTRAL_Y + 2 + 5.6.
    neutral_x, neutral_top = 2.2, 100.0 + 2.0 + 5.6
    return {
        "groups": [{"name": "circuit", "circuit": "three_phase",
                    "terminals": [{"label": "A", "pos": p(-8, top, z)}, {"label": "B", "pos": p(1, top, z)},
                                  {"label": "C", "pos": p(24, top, z)}]},
                   {"name": "neutral", "circuit": "single_phase", "terminals": [{"label": "N", "pos": p(neutral_x, neutral_top, 8)}]}],
        "reserved": [],
    }


def mv():
    # postInsulator(x, armY + 4, face + 1.6, 12): clamp top at (armY + 4) + 12 - 1.6 + 1.6.
    face = 8 + 3.62
    z = face + 1.6
    upper, lower = 196.0, 166.0
    return {
        "groups": [{"name": "circuit", "circuit": "three_phase",
                    "terminals": [{"label": "A", "pos": p(-8, upper + 4 + 12, z)},
                                  {"label": "B", "pos": p(-16, lower + 4 + 12, z)},
                                  {"label": "C", "pos": p(32, lower + 4 + 12, z)}]},
                   shield([p(8, 222 + 16, 8)])],
        "reserved": [],
    }


def shield(points):
    """The shield wire's points: each peak's clamp top, a G (ground) point that only Shield Wire joins."""
    return {"name": "shield", "circuit": "ground", "terminals": [{"label": "G", "pos": pos} for pos in points]}


def string_bottom(y, discs):
    # suspension(x, y, z, discs): the clamp bottom at y - 2.2 - 3 * discs - 3.
    return y - 2.2 - 3.0 * discs - 3.0


def hv():
    r_ = 4.85
    bz0 = 8 + r_ - 0.5
    z = (bz0 + bz0 + 6.0) / 2
    y = string_bottom(282.0, 8)
    return {
        "groups": [{"name": "circuit", "circuit": "three_phase",
                    "terminals": [{"label": "A", "pos": p(-42, y, z)}, {"label": "B", "pos": p(8, y, z)},
                                  {"label": "C", "pos": p(58, y, z)}]},
                   shield([p(-24, 316 + 18, 8), p(40, 316 + 18, 8)])],
        "reserved": [],
    }


def ehv():
    # arm(side, level, reach) returns tip - side * 1; suspension(tipX, level - 4, 8, 13).
    c = 8.0
    levels = {"A": (432.0, 56.0), "B": (368.0, 80.0), "C": (304.0, 64.0)}
    groups = []
    for name, side in (("west", -1), ("east", 1)):
        terminals = []
        for label in ("A", "B", "C"):
            level, reach = levels[label]
            x = c + side * reach - side * 1.0
            terminals.append({"label": label, "pos": p(x, string_bottom(level - 4.0, 13), c)})
        groups.append({"name": name, "circuit": "three_phase", "terminals": terminals})
    groups.append(shield([p(8, 496 + 14, 8)]))
    return {"groups": groups, "reserved": []}


def bushing_top(tier, y, unit):
    """TowerMesh.bushing: 0 LV the pin insulator (crown top at y + 7.6), 1 MV the post (y + 14, a unit's 10), 2 HV and
    3 EHV a glass stack (clevis 1, a disc every 3, the clamp 1: HV 4 discs, a unit's 3; EHV 6, a unit's 4)."""
    if tier == 0:
        return y + 7.6
    if tier == 1:
        return y + (10 if unit else 14)
    discs = (3 if unit else 4) if tier == 2 else (4 if unit else 6)
    return y + 1 + 3 * discs + 1


# TowerMesh.Substation: (halfX, halfZ, height, high tier, spacing), the pad 4 and the lid 2 px.
SUBSTATIONS = {"mv": ((13, 10, 26, 1, 9), "mv", "lv"), "hv": ((17, 13, 34, 2, 11), "hv", "mv"),
               "ehv": ((21, 16, 42, 3, 13), "ehv", "hv")}


def substation(key):
    """bushing(tier, 8 + i * spacing, top, z) along the north (primary, its tier) and south (secondary, a tier down)
    edges."""
    (half_x, half_z, height, high, spacing), high_id, low_id = SUBSTATIONS[key]
    top = 4 + height + 2

    def row(z, tier):
        return [{"label": label, "pos": p(8 + i * spacing, bushing_top(tier, top, False), z)} for i, label in zip((-1, 0, 1), "ABC")]

    return lambda: {
        "groups": [{"name": "primary", "circuit": "three_phase", "tier": high_id, "terminals": row(8 - half_z + 4, high)},
                   {"name": "secondary", "circuit": "three_phase", "tier": low_id, "terminals": row(8 + half_z - 4, high - 1)}],
        "reserved": [],
    }


def unit(tier):
    """TowerMesh.Unit: tier 0 (LV) to 3 (EHV); the tank's half-depth 4.5 + 1.5 tier behind the 2.5 px cable box, its
    height 9 + 3 tier, the lid 1 more; bushings across it at x 8 + i * spacing."""
    half_z, height = 4.5 + 1.5 * tier, 9 + 3 * tier
    spacing = (5.6, 6.0, 6.5, 7.5)[tier]
    top = height + 1
    return lambda: {
        "groups": [{"name": "line", "circuit": "three_phase",
                    "terminals": [{"label": label, "pos": p(8 + i * spacing, bushing_top(tier, top, True), 2.5 + half_z)}
                                  for i, label in zip((-1, 0, 1), "ABC")]}],
        "reserved": [],
    }


def abc(points):
    return [{"label": label, "pos": pos} for label, pos in zip("ABC", points)]


def fuse():
    """TowerMesh.fuse: the top contact's top (line) and the bottom contact's bottom (load), on the body's axis (z FUSE_Z)."""
    return lambda: {"groups": [{"name": "line", "circuit": "three_phase", "terminals": [{"label": "A", "pos": p(8, 15.2, 3.5)}]},
                               {"name": "load", "circuit": "three_phase", "terminals": [{"label": "A", "pos": p(8, 4.8, 3.5)}]}],
                    "reserved": []}


def breaker(tier):
    """TowerMesh.Breaker: halfZ 6 + 2 tier, height 9 + 4 tier on 2 px feet and a 1 px lid; bushings (a unit's) across
    it at x 8 + i * spacing, the line row 2.5 in from the north edge, the load row from the south."""
    half_z, top = 6 + 2 * tier, 2 + 9 + 4 * tier + 1
    spacing = (5.6, 6.0, 6.5, 8.0)[tier]
    y = bushing_top(tier, top, True)
    return lambda: {"groups": [{"name": "line", "circuit": "three_phase",
                                "terminals": abc([p(8 + i * spacing, y, 8 - half_z + 2.5) for i in (-1, 0, 1)])},
                               {"name": "load", "circuit": "three_phase",
                                "terminals": abc([p(8 + i * spacing, y, 8 + half_z - 2.5) for i in (-1, 0, 1)])}],
                    "reserved": []}


def disconnect(tier):
    """TowerMesh.disconnect: posts (a unit's bushings) on the 2 px base, a phase every spacing across, the line post at
    8 - half and the load post at 8 + half; the terminal is each post's top."""
    spacing, half = (6, 7, 9, 12)[tier], (5, 6, 8, 10)[tier]
    y = bushing_top(tier, 2, True)
    return lambda: {"groups": [{"name": "line", "circuit": "three_phase", "terminals": abc([p(8 + i * spacing, y, 8 - half) for i in (-1, 0, 1)])},
                               {"name": "load", "circuit": "three_phase", "terminals": abc([p(8 + i * spacing, y, 8 + half) for i in (-1, 0, 1)])}],
                    "reserved": []}


STRAIN = 8.2
DEAD_END_TILT = 8.0


def clamp(x, y, z0, direction, length):
    """A strain string's clamp (TowerMesh.strainEnd / glassStrainEnd): out from z0 the way direction points, DEAD_END_TILT
    degrees below level."""
    tilt = math.radians(DEAD_END_TILT)
    return p(x, y - length * math.sin(tilt), z0 + direction * length * math.cos(tilt))


def dead_end_lv():
    """TowerMesh.deadEndLv: strain strings from the north arm's face (z 3) and the south's (z 13) at y 134, x -8, 1 and
    24; the neutral's from its standoff (x 2.2, y 99.3, z 8 -/+ 0.7). Each terminal the clamp's outer face."""
    xs = (-8, 1, 24)
    return lambda: {"groups": [{"name": "north", "circuit": "three_phase", "terminals": abc([clamp(x, 134, 3, -1, STRAIN) for x in xs])},
                               {"name": "south", "circuit": "three_phase", "terminals": abc([clamp(x, 134, 13, 1, STRAIN) for x in xs])},
                               {"name": "north_neutral", "circuit": "single_phase", "terminals": [{"label": "N", "pos": clamp(2.2, 99.3, 7.3, -1, STRAIN)}]},
                               {"name": "south_neutral", "circuit": "single_phase", "terminals": [{"label": "N", "pos": clamp(2.2, 99.3, 8.7, 1, STRAIN)}]}],
                    "reserved": []}


def dead_end_mv():
    """TowerMesh.deadEndMv: strings from the north arms' face (8 - 3.62 - 3.2) and the south's (8 + 3.62 + 3.6), A on the
    upper arm (x -8, y 198), B and C on the lower (x -16 and 32, y 168); the shield peak (G)."""
    north, south = 8 - 3.62 - 3.6 + 0.4, 8 + 3.62 + 3.6 - 0.4 + 0.4
    phases = ((-8, 198), (-16, 168), (32, 168))
    return lambda: {"groups": [{"name": "north", "circuit": "three_phase", "terminals": abc([clamp(x, y, north, -1, STRAIN) for x, y in phases])},
                               {"name": "south", "circuit": "three_phase", "terminals": abc([clamp(x, y, south, 1, STRAIN) for x, y in phases])},
                               shield([p(8, 222 + 16, 8)])],
                    "reserved": []}


def service():
    """TowerMesh.service: the spools' tops, L1 N L2 west to east."""
    return lambda: {"groups": [{"name": "service", "circuit": "single_phase",
                                "terminals": [{"label": label, "pos": p(x, 25.2, 9)} for label, x in (("L1", 4.5), ("N", 8), ("L2", 11.5))]}],
                    "reserved": []}


# --- Phase 4: the yard and the line's heavy hardware --------------------------------------------------------------

def glass_strain_length(discs):
    """TowerMesh.glassStrainLength: the clevis 1, a disc every 3, the clamp 1.6."""
    return 1.0 + 3.0 * discs + 1.6


def bus(tier):
    """TowerMesh.bus: a pipe a phase at x 8 + i * spacing, its post on the line between its two blocks (z 16, BUS_POST_Z);
    its tap clamp's top at the stand, the 1 px cap, the post, the pipe's diameter and 0.8."""
    spacing = (0, 0, 16, 24)[tier]
    stand, post, r = (0, 0, 24, 32)[tier], (0, 0, 24, 36)[tier], (0, 0, 1.5, 2.0)[tier]
    y = stand + 1 + post + 2 * r + 0.8
    return lambda: {"groups": [{"name": "bus", "circuit": "three_phase", "terminals": abc([p(8 + i * spacing, y, 16) for i in (-1, 0, 1)])}],
                    "reserved": []}


GANTRY_TILT = DEAD_END_TILT


def gantry(tier):
    """TowerMesh.gantry: each phase dead-ends on its girder's north face (2.5 px off the middle) on a glass string running
    out along the line, tilted GANTRY_TILT degrees below level (TowerMesh.glassStrainEnd: the clamp's outer face), and
    drops to a suspension string 3.2 px past its south face (the clamp's bottom); shield peaks on the legs. The jumper
    between them is drawn by the renderer, from the line terminal to the yard's."""
    spacing, beam, leg = (0, 0, 20, 28)[tier], (0, 0, 64, 96)[tier], (0, 0, 32, 48)[tier]
    discs, drop = (0, 0, 6, 8)[tier], (0, 0, 4, 6)[tier]
    xs = [8 + i * spacing for i in (-1, 0, 1)]
    length, tilt = glass_strain_length(discs), math.radians(GANTRY_TILT)
    north, y = 8 - 2.5 - length * math.cos(tilt), beam + 2 - length * math.sin(tilt)
    return lambda: {"groups": [{"name": "line", "circuit": "three_phase", "terminals": abc([p(x, y, north) for x in xs])},
                               {"name": "yard", "circuit": "three_phase",
                                "terminals": abc([p(x, string_bottom(beam, drop), 8 + 2.5 + 3.2) for x in xs])},
                               shield([p(8 - leg, beam + 4 + 14, 8), p(8 + leg, beam + 4 + 14, 8)])],
                    "reserved": []}


def regulator(tier):
    """TowerMesh.regulator: per phase a tank (x 8 + i * spacing) with its source bushing north and load bushing south,
    r - 1 off its middle, on its lid (the 4 px deck plus its height); a unit's bushings."""
    spacing, r, h = (0, 11, 15)[tier], (0, 4.5, 5.5)[tier], (0, 14, 20)[tier]
    top = bushing_top(tier, 4 + h, True)
    return lambda: {"groups": [{"name": "source", "circuit": "three_phase", "terminals": abc([p(8 + i * spacing, top, 8 - (r - 1)) for i in (-1, 0, 1)])},
                               {"name": "load", "circuit": "three_phase", "terminals": abc([p(8 + i * spacing, top, 8 + (r - 1)) for i in (-1, 0, 1)])}],
                    "reserved": []}


def relay_panel():
    """The Relay Panel has no insulators: it's linked to what it protects with Control Cable."""
    return lambda: {"groups": [], "reserved": []}


def ground_switch(tier):
    """TowerMesh.groundSwitch: a post per phase (a unit's bushing) on the 2 px base at x 8 + i * the disconnect's spacing."""
    spacing = (6, 7, 9, 12)[tier]
    top = bushing_top(tier, 2, True)
    return lambda: {"groups": [{"name": "line", "circuit": "three_phase", "terminals": abc([p(8 + i * spacing, top, 8) for i in (-1, 0, 1)])}],
                    "reserved": []}


def capacitor(tier):
    """TowerMesh.capacitorBank: a bushing per phase (x 8 + i * 12) on the top shelf (2 + rows x shelf)."""
    shelf, rows = (0, 10, 12)[tier], (0, 2, 3)[tier]
    top = bushing_top(tier, 2 + rows * shelf, True)
    return lambda: {"groups": [{"name": "bank", "circuit": "three_phase", "terminals": abc([p(8 + i * 12, top, 8) for i in (-1, 0, 1)])}],
                    "reserved": []}


def reactor(tier):
    """TowerMesh.reactor: three bushings (a substation's size) across the lid at z 6, x 8 + i * spacing."""
    height, spacing = (0, 0, 26, 34)[tier], (0, 0, 8, 10)[tier]
    top = bushing_top(tier, 4 + height + 2, False)
    return lambda: {"groups": [{"name": "reactor", "circuit": "three_phase", "terminals": abc([p(8 + i * spacing, top, 6) for i in (-1, 0, 1)])}],
                    "reserved": []}


def rotated(points):
    """A transposition's far side: the conductor in at position k leaves at position k + 1 (labels follow the conductor)."""
    return [points[(k + 1) % 3] for k in range(3)]


def dead_end_hv(transpose):
    """TowerMesh.deadEndHv: strings from the north beam's face (8 - 4.85 + 0.5 - 6) and the south's (8 + 4.85 - 0.5 + 6) at
    y 285.5, x -42, 8 and 58, six discs; the shield peaks on its poles (x -26 and 42)."""
    y, n, so, length = 282 + 3.5, 8 - 4.85 + 0.5 - 6, 8 + 4.85 - 0.5 + 6, glass_strain_length(6)
    north = [clamp(x, y, n, -1, length) for x in (-42, 8, 58)]
    south = [clamp(x, y, so, 1, length) for x in (-42, 8, 58)]
    return lambda: {"groups": [{"name": "north", "circuit": "three_phase", "terminals": abc(north)},
                               {"name": "south", "circuit": "three_phase", "terminals": abc(rotated(south) if transpose else south)},
                               shield([p(-26, 316 + 18, 8), p(42, 316 + 18, 8)])],
                    "reserved": []}


def dead_end_ehv(transpose):
    """TowerMesh.lattice(1 or 2): at each arm tip (tip - side), strings 3.9 px each side of the middle at the arm's level - 2,
    eight discs; per side the arms in order lower (C), middle (B), top (A); the far side rotated on the transposition."""
    c, length = 8.0, glass_strain_length(8)
    arms = ((304.0, 64.0), (368.0, 80.0), (432.0, 56.0))
    groups = []
    for name, side in (("west", -1), ("east", 1)):
        north = [clamp(c + side * reach - side, level - 2, c - 3.9, -1, length) for level, reach in arms]
        south = [clamp(c + side * reach - side, level - 2, c + 3.9, 1, length) for level, reach in arms]
        if transpose:
            south = rotated(south)
        groups.append({"name": name + "_north", "circuit": "three_phase",
                       "terminals": [{"label": label, "pos": pos} for label, pos in zip("CBA", north)]})
        groups.append({"name": name + "_south", "circuit": "three_phase",
                       "terminals": [{"label": label, "pos": pos} for label, pos in zip("CBA", south)]})
    groups.append(shield([p(8, 496 + 14, 8)]))
    return lambda: {"groups": groups, "reserved": []}


def riser_cable(x_conduit, z_conduit):
    """TowerMesh.conduit: the cable box's top (y 4), its three cables 2.5 px apart across it, 0.5 px south of the conduit."""
    return {"name": "cable", "circuit": "three_phase", "terminals": abc([p(x_conduit + dx, 4, z_conduit + 0.5) for dx in (-2.5, 0, 2.5)])}


def riser_lv():
    """TowerMesh.riserLv: potheads (posts 7.6 px) on the crossarm (y 136, z 11.5) where the LV pole's pins are."""
    return lambda: {"groups": [{"name": "overhead", "circuit": "three_phase",
                                "terminals": abc([p(x, 136 + 7.6, 11.5) for x in (-8, 1, 24)])},
                               riser_cable(8, 8 + 4.2)],
                    "reserved": []}


def riser_mv():
    """TowerMesh.riserMv: the MV pole's post insulators as terminations; the conduit 4.6 px off the arms' face."""
    face = 8 + 3.62
    z = face + 1.6
    return lambda: {"groups": [{"name": "overhead", "circuit": "three_phase",
                                "terminals": abc([p(-8, 196 + 4 + 12, z), p(-16, 166 + 4 + 12, z), p(32, 166 + 4 + 12, z)])},
                               riser_cable(8, face + 4.6), shield([p(8, 222 + 16, 8)])],
                    "reserved": []}


def riser_stand(tier):
    """TowerMesh.riserStand: terminations (glass posts on a 7 px stress cone over the deck) at x 8 + i * spacing; each
    cable's box top (y 4) under it."""
    deck, post, spacing = (0, 0, 20, 28)[tier], (0, 0, 22, 32)[tier], (0, 0, 12, 16)[tier]
    xs = [8 + i * spacing for i in (-1, 0, 1)]
    return lambda: {"groups": [{"name": "overhead", "circuit": "three_phase", "terminals": abc([p(x, deck + 7 + post, 8) for x in xs])},
                               {"name": "cable", "circuit": "three_phase", "terminals": abc([p(x, 4, 8) for x in xs])}],
                    "reserved": []}


def sectionalizer():
    """TowerMesh.sectionalizer: the MV recloser's layout, a smaller tank (half 6, height 8; the lid's top at 11)."""
    top = bushing_top(1, 2 + 8 + 1, True)
    return lambda: {"groups": [{"name": "line", "circuit": "three_phase", "terminals": abc([p(8 + i * 6, top, 8 - 6 + 2.5) for i in (-1, 0, 1)])},
                               {"name": "load", "circuit": "three_phase", "terminals": abc([p(8 + i * 6, top, 8 + 6 - 2.5) for i in (-1, 0, 1)])}],
                    "reserved": []}


def ats(tier):
    """TowerMesh.ats: three rows on the roof (1.6 + height): preferred north, load south, alternate in the middle."""
    half, height, spacing = (9, 12)[tier], (12, 16)[tier], (6.0, 7.5)[tier]
    top = bushing_top(tier, 1.6 + height, True)

    def row(r):
        return abc([p(8 + i * spacing, top, 8 + r * (half - 2.5)) for i in (-1, 0, 1)])

    return lambda: {"groups": [{"name": "preferred", "circuit": "three_phase", "terminals": row(-1)},
                               {"name": "load", "circuit": "three_phase", "terminals": row(1)},
                               {"name": "alternate", "circuit": "three_phase", "terminals": row(0)}],
                    "reserved": []}


MODELS = {"lv_distribution_pole": lv, "mv_sub_transmission_pole": mv, "hv_h_frame_tower": hv, "ehv_lattice_tower": ehv,
          "mv_substation_transformer": substation("mv"), "hv_substation_transformer": substation("hv"),
          "ehv_substation_transformer": substation("ehv"), "lv_unit_transformer": unit(0), "mv_unit_transformer": unit(1),
          "hv_unit_transformer": unit(2), "ehv_unit_transformer": unit(3),
          "lv_fuse_cutout": fuse(), "mv_fuse_cutout": fuse(), "lv_recloser": breaker(0), "mv_recloser": breaker(1),
          "hv_circuit_breaker": breaker(2), "ehv_circuit_breaker": breaker(3), "lv_disconnect_switch": disconnect(0),
          "mv_disconnect_switch": disconnect(1), "hv_disconnect_switch": disconnect(2), "ehv_disconnect_switch": disconnect(3),
          "lv_dead_end_pole": dead_end_lv(), "mv_dead_end_pole": dead_end_mv(), "service_meter": service(),
          "hv_bus": bus(2), "ehv_bus": bus(3), "hv_dead_end_gantry": gantry(2), "ehv_dead_end_gantry": gantry(3),
          "mv_voltage_regulator": regulator(1), "hv_voltage_regulator": regulator(2), "relay_panel": relay_panel(),
          "mv_ground_switch": ground_switch(1), "hv_ground_switch": ground_switch(2), "ehv_ground_switch": ground_switch(3),
          "mv_capacitor_bank": capacitor(1), "hv_capacitor_bank": capacitor(2), "hv_shunt_reactor": reactor(2),
          "ehv_shunt_reactor": reactor(3), "hv_dead_end_tower": dead_end_hv(False), "ehv_dead_end_tower": dead_end_ehv(False),
          "hv_transposition_tower": dead_end_hv(True), "ehv_transposition_tower": dead_end_ehv(True), "lv_cable_riser": riser_lv(),
          "mv_cable_riser": riser_mv(), "hv_cable_riser": riser_stand(2), "ehv_cable_riser": riser_stand(3),
          "mv_sectionalizer": sectionalizer(), "lv_transfer_switch": ats(0), "mv_transfer_switch": ats(1)}


def main():
    repo = sys.argv[1] if len(sys.argv) > 1 else "."
    out = os.path.join(repo, "src/main/resources/data/arcforgetransmission/attach_groups")
    os.makedirs(out, exist_ok=True)
    for name, build in MODELS.items():
        data = {"model": name,
                "units": "px in the model's north-facing frame: x across the line, y up from the base block's bottom, "
                         "z along the line; origin the centre column's base block's minimum corner"}
        data.update(build())
        with open(os.path.join(out, name + ".json"), "w", newline="\n") as f:
            json.dump(data, f, indent=2)
            f.write("\n")
        print("wrote", name)


if __name__ == "__main__":
    main()
