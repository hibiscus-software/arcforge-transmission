/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

// The grid's pure-Java core, unit tested outside the game: tiers, phases, the graph of insulators (nodes) and wires
// (edges), its networks and cached routes, and the flow solver (FlowSolver): power matched source to sink through
// transformers (Coupling), voltage drop and load response, capacitor banks and reactors (Shunt), storage, grounding,
// phase loss and fault currents for protection. GridTicker runs one tick of it.
package net.zagdrath.arcforgetransmission.grid;
