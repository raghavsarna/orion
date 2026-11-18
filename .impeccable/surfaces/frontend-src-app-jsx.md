---
version: 1
slug: "frontend-src-app-jsx"
primary_target: "frontend/src/App.jsx"
related_targets: []
---

# Demo console (frontend/)

Scope: the whole Orion-Lite UI at http://localhost:3000. Mode: operate (a presenter drives it live on a classroom projector).
Audience/job: presenter walks the class through paper concepts in a few minutes of a 15-20 min slot; the class must see what happened and which section of the paper it is.
Constraints: projector legibility (large type, high contrast, states not by colour alone); works offline (fonts bundled); LLM box degrades without a key; scenarios assume the fabric topology and say so otherwise.

## Direction contract

THESIS: The console is the paper's figures come alive: the live network is "Figure 1" with a caption the controller's state rewrites, and every other view is a numbered, captioned figure or table. It refuses the category default, a dark NOC dashboard of cards and glowing graphs.

OWN-WORLD: White page, black ink, hairline rules, one ink blue (#1f5fa8) for traffic and for what can be pressed, one red (#c23b22) for failure and unknown state. Serif (Source Serif 4) for captions, figure numbers and headings; sans (Source Sans 3) for controls and data with tabular numerals. Switches are square-cornered boxes, links are hairlines, unknown is dashed, down is red dashed with a cross. No cards, no shadows, no gradients.

STORY: The presenter opens a walkthrough tied to a paper section, clicks Run, the figure changes (traffic counts on links, dashed switches, caption rewritten), and the step's explanation states what the paper predicts and what was measured. The class leaves able to name Fail Static, ECMP, NIB, reconciliation, convergence.

FIRST VIEWPORT: Running head across the top: "Orion-Lite" in serif, the current paper section as guide words, live status (controller, NIB version, fail mode). Left ~62%: Figure 1, the topology drawn tier by tier, with its live caption underneath. Right ~38%: tabs Walkthrough | Intents | Inspect | Measure | Log; Walkthrough open, current step expanded with its Run button in ink blue, other steps as a numbered list.

FORM: Paper's Figures (USENIX/NSDI two-column figure grammar), candidate 1 of my list, chosen by the user as the pick card. seed:37274db5efbe87fa

Signature interaction: running a step redraws Figure 1 and rewrites its caption; per-link flow counts show ECMP spreading as numbers on the wires.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
