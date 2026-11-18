---
name: Orion-Lite
description: The paper's figures, live. A projector-legible SDN demo console drawn in the grammar of an NSDI paper.
colors:
  ink-blue: "#1f5fa8"
  ink-blue-deep: "#174a85"
  ink-blue-wash: "#e9f1fb"
  ink-blue-pale: "#b8cbe4"
  failure-red: "#b3261e"
  failure-red-wash: "#fbecea"
  paper: "#ffffff"
  ink: "#111111"
  ink-2: "#474747"
  ink-3: "#6a6a6a"
  rule: "#d4d4d4"
  rule-soft: "#ececec"
  fill: "#f4f4f4"
typography:
  headline:
    fontFamily: "'Source Serif 4', Georgia, 'Times New Roman', serif"
    fontSize: "1.4rem"
    fontWeight: 600
    lineHeight: 1.25
    letterSpacing: "-0.01em"
  title:
    fontFamily: "'Source Serif 4', Georgia, 'Times New Roman', serif"
    fontSize: "1.35rem"
    fontWeight: 600
    lineHeight: 1
    letterSpacing: "-0.01em"
  caption:
    fontFamily: "'Source Serif 4', Georgia, 'Times New Roman', serif"
    fontSize: "0.98rem"
    fontWeight: 400
    lineHeight: 1.5
  guide:
    fontFamily: "'Source Serif 4', Georgia, 'Times New Roman', serif"
    fontSize: "1.05rem"
    fontWeight: 400
    lineHeight: 1
  body:
    fontFamily: "'Source Sans 3', system-ui, -apple-system, 'Segoe UI', sans-serif"
    fontSize: "1rem"
    fontWeight: 400
    lineHeight: 1.45
    fontFeature: "tnum"
  label:
    fontFamily: "'Source Sans 3', system-ui, -apple-system, 'Segoe UI', sans-serif"
    fontSize: "0.95rem"
    fontWeight: 600
    lineHeight: 1
  data:
    fontFamily: "'Source Sans 3', system-ui, -apple-system, 'Segoe UI', sans-serif"
    fontSize: "0.85rem"
    fontWeight: 400
    fontFeature: "tnum"
rounded:
  none: "0px"
  control: "3px"
spacing:
  head: "64px"
  gutter: "1.6rem"
  pane: "1.4rem"
  row: "0.7rem"
components:
  button:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    typography: "{typography.label}"
    rounded: "{rounded.control}"
    padding: "0.6em 0.95em"
  button-hover:
    backgroundColor: "{colors.fill}"
  button-primary:
    backgroundColor: "{colors.ink-blue}"
    textColor: "{colors.paper}"
    typography: "{typography.label}"
    rounded: "{rounded.control}"
    padding: "0.6em 0.95em"
  button-primary-hover:
    backgroundColor: "{colors.ink-blue-deep}"
  button-quiet:
    backgroundColor: "transparent"
    textColor: "{colors.ink-2}"
    padding: "0.6em 0.4em"
  button-danger:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.failure-red}"
    rounded: "{rounded.control}"
  button-danger-hover:
    backgroundColor: "{colors.failure-red-wash}"
  tab:
    backgroundColor: "transparent"
    textColor: "{colors.ink-3}"
    padding: "0.85rem 0.7rem 0.7rem"
  tab-active:
    textColor: "{colors.ink}"
  segmented-on:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.paper}"
  input:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    padding: "0.45em 0.55em"
  switch-box:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    rounded: "{rounded.none}"
  toast:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    padding: "0.6rem 0.9rem"
---

# Design System: Orion-Lite

## Overview

**Creative North Star: "The Paper's Figures, Live"**

The console is an NSDI paper that moves. The live network is Figure 1, drawn tier by tier on a white page in black ink, with a serif caption the controller's state rewrites. Every other view is a numbered, captioned figure or a booktabs table ("Table 2: Intents and what the controller made of them."). Controls are the only thing that looks pressable; everything else reads as print.

Density is that of a two-column conference paper projected at the front of a room: few colours, hairline rules, large type, and states carried by line style and labels as well as colour so they survive a washed-out projector and colour-blind viewers. The system explicitly rejects the category default of a dark NOC dashboard of cards and glowing graphs.

Motion is ink settling, not UI animating: a rewritten caption phrase flashes blue-wash and fades over 2.6s, a measured result settles in from a 3px blur over 420ms, flow strokes thicken over 400ms. All on one ease-out curve (cubic-bezier(0.16, 1, 0.3, 1)), all collapsed under reduced motion.

**Key Characteristics:**
- White page, black ink, hairline rules; no cards, no shadows.
- One blue for traffic and for what can be pressed; one red for failure and unknown state.
- Serif for captions, headings, figure numbers and guide words; sans with tabular numerals for controls and data.
- Figure marks are square-cornered; unknown is dashed, down is red dashed with a cross.
- Every view is numbered and captioned like a paper figure or table.

## Colors

A print palette: near-black ink on white, a ladder of greys for secondary ink and rules, and exactly two hues with fixed meanings.

### Primary
- **Ink Blue** (`ink-blue`): traffic (flow strokes on links, per-link packet counts), the primary Run button, link buttons, selection and focus outlines, checkbox accent, the caret. If it is blue, it is either moving packets or pressable.
- **Deep Ink Blue** (`ink-blue-deep`): hover on primary buttons and link buttons; the text colour of flow counts, delivered-packet labels and "hot" hosts.
- **Blue Wash** (`ink-blue-wash`): the transient highlight behind a caption phrase the controller just rewrote; text selection; input focus halo.
- **Pale Blue** (`ink-blue-pale`): the queue segment in convergence bars, one of three stacked segments (pale blue, ink, blue).

### Secondary
- **Failure Red** (`failure-red`): down links (dashed, with a cross), unknown switches (dashed box), drop counts, NOT_POSSIBLE status, error toasts, alert status in the running head, destructive buttons. Chosen darker than the contract's #c23b22 so dashed red strokes survive a washed-out projector.
- **Red Wash** (`failure-red-wash`): the fill behind drop-count badges and the hover of destructive and delete-icon buttons.

### Neutral
- **Ink** (`ink`): all primary text, link wires, switch boxes, the running-head rule, booktabs top and bottom rules, the active tab underline, the selected segment.
- **Ink 2** (`ink-2`): guide words, ledes, table captions, secondary labels in the figure.
- **Ink 3** (`ink-3`): tier labels, status-bar terms, hints, inactive tabs, pending links and absent switches.
- **Rule** (`rule`): hairline dividers between panes, tabs and list rows; empty matrix cells.
- **Soft Rule** (`rule-soft`): inner row dividers in data tables and lists.
- **Fill** (`fill`): button hover, unknown-switch interior, matrix diagonal, bar tracks, disabled inputs.
- **Paper** (`paper`): the page, switch interiors, count badges, the halo stroke (`paint-order: stroke`) that lifts figure labels off wires.

### Named Rules
**The Two Hues Rule.** Blue means traffic or pressable; red means failure or unknown. No third hue exists, and neither colour is ever decorative.

**The Not-By-Colour-Alone Rule.** Every red state also changes line style or carries a label: down is red, thicker and dashed (9 6) with a cross; unknown is red dashed (7 5) on a grey fill; pending is grey dotted (2 5). A greyscale print of Figure 1 must still read correctly.

## Typography

**Display Font:** Source Serif 4 (with Georgia, Times New Roman)
**Body Font:** Source Sans 3 (with system-ui, -apple-system, Segoe UI)
**Label/Mono Font:** ui-monospace / SF Mono / Menlo for inline code only

Both families are bundled through Fontsource (sans 400/600/700, serif 400/400-italic/600) so the console works offline.

**Character:** The serif is the paper speaking (titles, captions, figure numbers, section references in italic); the sans is the instrument (buttons, tabs, fields, numbers). Root size is 18px, 20px from 1600px wide, so everything is set for the back of the room.

### Hierarchy
- **Title** (serif 600, 1.35rem, line-height 1, -0.01em): "Orion-Lite" in the running head.
- **Headline** (serif 600, 1.4rem, 1.25): walkthrough titles in the side pane; the paper section number sits before it in italic 400 Ink 3. Subheads are serif 600 at 1.05rem; scenario titles serif 600 at 1.12rem.
- **Guide** (serif italic 400, 1.05rem): guide words in the running head naming the current paper section, single line with ellipsis.
- **Caption** (serif 400, 0.98rem, 1.5, max 75ch): figure captions, with "Figure N:" in serif 600 and the figure's title in italic. Ledes and results are serif at 1rem to 1.02rem, line-height 1.55, max 62ch.
- **Body** (sans 400, 1rem, 1.45, tabular numerals): step text and running copy.
- **Label** (sans 600, 0.95rem, line-height 1): buttons, tabs, segmented controls; field labels and legends are sans 600 at 0.82rem in Ink 2.
- **Data** (sans 400, 0.85rem to 0.92rem, tabular numerals): table cells, matrix, bar rows, figure key.

### Named Rules
**The Serif Speaks, The Sans Operates Rule.** Anything the paper would print (title, caption, heading, section number, empty-state sentence) is serif; anything a person clicks or counts is sans.

**The Projector Compensation Rule.** Figure type scales with `--k`, the inverse of the SVG's render scale, clamped between 1 and 1.45 (switch IDs and sub-labels cap lower, at 1.2 and 1.25), so labels hold their physical size when Figure 1 is drawn small on a 1024px projector. Figure label sizes are written as `calc(Npx * var(--k, 1))`; base sizes run 14px to 22px.

## Layout

A full-height grid: a running head (min 64px, 1px ink rule below) over a two-column main. The left column (1.6fr, about 62%) is the "paper": Figure 1 with its caption, then Figure 2 below a hairline rule. The right column (min 380px, 1fr) is a tabbed side pane separated by a hairline, each pane scrolling on its own. Figure 1's SVG is capped at the viewport height minus the head and 190px so its caption stays in the first viewport.

Rhythm is rem-based and modest: 1.6rem page gutters, 1.4rem pane padding, 0.7rem to 0.8rem between controls in a row, 1.2rem to 1.6rem between figures and tables. Measured prose runs at 62ch to 75ch.

Responsive: at 900px and below the columns stack, the side pane gains an ink top rule instead of a left hairline, and the figure loses its height cap. At 640px and below gutters drop to 1rem, Figure 1 keeps a 640px minimum width inside a horizontal scroller with a right-edge fade mask and an italic scroll hint, and scenario rows reflow to two columns.

## Elevation & Depth

Flat. There are no shadows anywhere. Depth is conveyed by rules: 2px ink rules top and bottom for booktabs tables and lists, 1px ink for header rules and the running head, 1px grey hairlines between rows and panes. The single floating element, the toast, is held off the page only by its 1px ink border (red for errors).

### Named Rules
**The Print Has No Shadows Rule.** Nothing casts a shadow or glows. If something must float, it gets a 1px ink border on paper and nothing more.

## Shapes

Figure marks are square-cornered: switch boxes, the focus ring around a step's subject, count badges and drop badges have no radius. Links are straight hairlines (1.4px ink), thickening to 2px when down, 2.6px when focused and 3px when selected. Controls (buttons, inputs, selects, segmented groups, icon buttons, toasts) take a barely-there 3px radius so they read as pressable against the print. Step markers in walkthroughs are the one circle: 1.85rem numbered rings.

**The Square Marks Rule.** Anything drawn in a figure is square-cornered. Only controls carry the 3px radius.

## Components

### Buttons
Print-like and plain: a 1px ink outline on paper.
- **Shape:** gently squared (3px radius).
- **Default:** paper ground, ink text and border, sans 600 0.95rem, padding 0.6em by 0.95em, optional 16px line icon before the label.
- **Primary:** Ink Blue fill and border with white text; hover to Deep Ink Blue. Reserved for Run and the main commit action of a form.
- **Quiet:** borderless, Ink 2 text, tight horizontal padding; hover to ink on fill. Used for navigation such as "All walkthroughs" and "Restore".
- **Danger:** red text and border; hover to Red Wash.
- **Hover / Focus:** 150ms ease-out background and colour change; focus is a 2px Ink Blue outline offset 2px. Disabled is 45% opacity.
- **Icon button:** 2rem square, borderless, Ink 3; hover turns red on Red Wash (used only for delete).
- **Link button:** inline Ink Blue text, underlined with a 3px offset.

### Tabs
Walkthrough | Intents | Inspect | Measure | Log across the top of the side pane. Sans 600 0.95rem in Ink 3; hover to ink; the active tab is ink with a 3px ink underline sitting on the pane's hairline.

### Segmented Control
A 1px ink outlined group (3px radius) of paper buttons divided by hairlines; the selected segment inverts to ink with white text.

### Inputs / Fields
- **Style:** paper ground, 1px mid-grey border, 3px radius, sans 0.95rem, padding 0.45em by 0.55em. Labels sit above in sans 600 0.82rem Ink 2.
- **Focus:** border turns Ink Blue with a 2px Blue Wash halo.
- **Disabled:** fill ground. Checkboxes take the Ink Blue accent.
- Forms sit between a 1px ink rule above and a hairline below, never in a box.

### Tables
Booktabs: 2px ink rules at top and bottom, 1px ink under the header, soft-rule row dividers, a 1px ink rule over a summary (median) row. The caption sits above in serif, "Table N:" in serif 600 ink. Empty rows are an italic serif sentence in Ink 3.

### Walkthrough Steps
A numbered list under a 2px ink rule, rows divided by hairlines. Each step title is sans 600 1.05rem beside a circular numbered marker: Ink 3 ring when ahead, solid ink with white numeral when current, blue ring when done, red ring on error. The result appears below in serif and settles in from a blur.

### Figure 1 (signature)
The topology as a paper figure. Switches are square paper boxes with a 2px ink stroke, bold sans ID and a sub-label; unknown switches are red dashed on fill, absent switches grey dotted, selected switches a 3px blue stroke. Links are ink hairlines; flows are rounded blue strokes whose width follows traffic, with per-link counts in a square paper badge outlined in blue. A square ink ring marks the subject of the current step. Labels lift off wires with a 5px paper halo. A figure key below shows each mark with its plain-language meaning, and the caption beneath is rewritten as the controller's state changes, the changed phrase flashing in Blue Wash.

### Toast
Fixed bottom-left, max 34rem: paper with a 1px ink border (red border and text for errors), 3px radius, settling in over 300ms.

## Do's and Don'ts

### Do:
- **Do** number and caption every view as "Figure N:" or "Table N:" with a serif caption, the figure caption below and the table caption above.
- **Do** carry every state in line style or a label as well as colour: red dashed (9 6) with a cross for down, red dashed (7 5) on fill for unknown, grey dotted for pending.
- **Do** write figure label sizes as `calc(Npx * var(--k, 1))` so they hold their size on a 1024px projector.
- **Do** keep Ink Blue for traffic and pressable things and Failure Red for failure and unknown; reserve the filled blue button for the one main action in view.
- **Do** separate regions with rules (2px ink for table bounds, 1px ink for headers, grey hairlines for rows and panes).
- **Do** keep numbers in tabular numerals and right-align numeric columns.

### Don't:
- **Don't** use cards, shadows, glows or decorative gradient fills; the only gradient in the build is the mobile scroll-fade mask on Figure 1.
- **Don't** round figure marks; switch boxes, rings and badges stay square.
- **Don't** introduce a third hue, or use blue or red for decoration.
- **Don't** drift toward a dark NOC dashboard look: dark grounds, tiles of cards, glowing graphs.
- **Don't** lighten the red back toward #c23b22; dashed red strokes wash out on projectors.
