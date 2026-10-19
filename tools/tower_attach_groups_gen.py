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
  MV Sub-Transmission Pole  one circuit: the upper post (A) and the lower arm's two (B, C); the shield peak reserved
  HV H-Frame Tower          one circuit: the three strings; both shield peaks reserved
  EHV Lattice Tower          two circuits (double circuit): west A/B/C top to bottom, east likewise; the shield peak
                            reserved
  Substation Transformers   the primary (A B C, its own tier, along the north edge) and the secondary (A B C, a tier
                            down, along the south): each group names its tier
  Unit Transformers         one circuit: the three bushings on the lid, A B C west to east
"""
import json
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
                                  {"label": "C", "pos": p(32, lower + 4 + 12, z)}]}],
        "reserved": [{"name": "shield", "use": "the shield wire (later)", "pos": p(8, 222 + 16, 8)}],
    }


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
                                  {"label": "C", "pos": p(58, y, z)}]}],
        "reserved": [{"name": "shield_west", "use": "the shield wire (later)", "pos": p(-24, 316 + 18, 8)},
                     {"name": "shield_east", "use": "the shield wire (later)", "pos": p(40, 316 + 18, 8)}],
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
    return {"groups": groups, "reserved": [{"name": "shield", "use": "the shield wire (later)", "pos": p(8, 496 + 14, 8)}]}


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


MODELS = {"lv_distribution_pole": lv, "mv_sub_transmission_pole": mv, "hv_h_frame_tower": hv, "ehv_lattice_tower": ehv,
          "mv_substation_transformer": substation("mv"), "hv_substation_transformer": substation("hv"),
          "ehv_substation_transformer": substation("ehv"), "lv_unit_transformer": unit(0), "mv_unit_transformer": unit(1),
          "hv_unit_transformer": unit(2), "ehv_unit_transformer": unit(3)}


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
