# Tesseta — Photography Prompt Guide

Generation-ready prompt direction for functional photography in the
Tesseta app. Three subject categories, one shared treatment system.
Drop this in the repo at `docs/brand/photography-prompts.md`.

## How to use this file

Every prompt is built the same way:

```
[SHARED TREATMENT BLOCK]  +  [SUBJECT CLAUSE]
```

The shared block is identical for every image in the app. Only the
subject clause changes. This is what makes a grid of exercises, a grid of
equipment, and a list of medications look like one coherent product
instead of three unrelated stock libraries. Do not edit the shared block
per image. Copy it verbatim.

---

## The shared treatment block

Paste this at the front of every prompt, all three categories:

> Warm neutral seamless background, oatmeal color, hex F0EBE0 or a hair
> lighter. Soft diffuse daylight from a single direction, large soft
> source, gentle realistic shadows, no hard flash, no studio specular
> hotspots. Muted natural color, slightly desaturated. Matte finish, no
> glossy advertising sheen. Subject centered with generous negative
> space. Photographic realism, full-frame camera look, 50mm to 85mm
> equivalent lens, moderate depth of field. No text, no graphics, no
> logos, no props beyond what is specified. Quiet, editorial,
> instrument-like mood — a precision-tool catalog, not a supplement ad.

### Negative prompt (use with every image)

> text, watermark, logo, label text, brand name, neon colors, vivid
> saturation, hard flash, studio glamour, lens flare, busy background,
> gym environment clutter, multiple competing objects, cartoon,
> illustration, 3d render, plastic look, rust, scratches, scuffs,
> weathered, vintage, distressed, aged, patina, painterly, stylized,
> hand-drawn, sketch, low-poly, plastic-toy

### Global parameters

- Aspect ratio: 1:1 for medication and equipment (grid cells), 4:5 or
  3:4 for exercise (vertical body framing)
- Consistency: where your generator supports a seed or a style
  reference, lock one and reuse it across an entire category
- Render at high resolution, then downscale for the app; never upscale

---

## Category 1 — Exercise instruction photography

The hardest category to keep consistent, because it involves a body.
The rule: lock one model identity, one wardrobe, one environment, one
camera, and reuse those clauses verbatim across every exercise. Only the
lift-and-phase clause changes.

### Fixed clauses (reuse verbatim in every exercise prompt)

**Model clause:**
> A single athletic person, mid-30s, neutral medium build, short
> hair, calm neutral facial expression with no exertion grimace.

**Wardrobe clause:**
> Plain fitted athletic clothing in heather gray, no logos, no
> patterns, no neon, fitted tank or t-shirt and training shorts,
> flat training shoes.

**Environment clause:**
> Plain warm-neutral studio, oatmeal seamless backdrop, matte rubber
> flooring in a slightly darker warm gray, no gym equipment clutter
> in the background.

**Camera clause:**
> True side-profile view, camera straight-on at the height of the
> lifter's torso, full body and full equipment visible in frame,
> nothing cropped. Even soft daylight, anatomical clarity prioritized
> over mood.

### Prompt template

```
[SHARED TREATMENT BLOCK]
[MODEL CLAUSE]
[WARDROBE CLAUSE]
[ENVIRONMENT CLAUSE]
[CAMERA CLAUSE]
The person is performing: [LIFT NAME], [PHASE DESCRIPTION],
[KEY FORM CUES].
```

Generate 2–3 phases per exercise (start, mid/bottom, end) so the app can
show stills or sequence them.

### Filled example set — five exercises

**Barbell back squat**
- Start: `...performing: barbell back squat, standing start position, barbell racked across upper back, feet shoulder-width, upright torso, neutral spine.`
- Bottom: `...performing: barbell back squat, bottom position, hips below knee level, knees tracking over toes, neutral spine, chest up.`
- End: `...performing: barbell back squat, standing lockout, hips and knees fully extended, barbell across upper back.`

**Romanian deadlift**
- Start: `...performing: Romanian deadlift, standing start, barbell held at hip height with straight arms, shoulders back, neutral spine.`
- Bottom: `...performing: Romanian deadlift, bottom position, barbell lowered to mid-shin, hips pushed back, slight knee bend, flat back, barbell close to legs.`
- End: `...performing: Romanian deadlift, standing lockout, hips fully extended, barbell at hip height.`

**Overhead press**
- Start: `...performing: standing overhead press, start position, barbell racked at shoulder height, elbows under the bar, upright torso.`
- End: `...performing: standing overhead press, top position, barbell pressed fully overhead, arms locked out, bar over mid-foot.`

**Flat dumbbell bench press**
- Start: `...performing: flat dumbbell bench press, start position, lying on a flat bench, dumbbells held at chest level, elbows bent.`
- End: `...performing: flat dumbbell bench press, top position, lying on a flat bench, dumbbells pressed fully above the chest, arms extended.`

**Bent-over barbell row**
- Start: `...performing: bent-over barbell row, start position, hips hinged, flat back, barbell hanging from straight arms below the chest.`
- End: `...performing: bent-over barbell row, top position, hips hinged, flat back, barbell pulled to the lower ribcage, elbows past the torso.`

### Mandatory review note

Current image models are unreliable at correct joint angles and at hand
placement on a barbell. A generated lift can show an impossible spine or
a grip passing through the bar. Every exercise image must be reviewed by
a human for anatomical correctness before it enters the app. A wrong
position teaches a wrong and potentially injurious movement. Treat
generation as a first draft of the exercise library, not the final.

---

## Category 2 — Gym and spa equipment photography

The most forgiving category. Rigid objects, generators handle them well.

### Fixed clauses

**Framing clause:**
> A single piece of equipment, isolated, centered, no gym environment
> around it, no people. A soft realistic contact shadow grounds the
> object so it does not float.

**Materials clause:**
> Override the shared treatment for this category: render as professional
> product photography of real, modern commercial gym equipment — the kind
> of catalog and website imagery used by premium brands like Rogue Fitness,
> Eleiko, Matrix Fitness, Life Fitness, Cybex, Hammer Strength, Precor, and
> Technogym. Photorealistic, DSLR-grade sharpness, accurate true-to-life
> colors (not muted or desaturated), natural specular highlights on steel
> and chrome, realistic surface reflections on plastic and upholstery,
> glossy where the real material is glossy. Pristine showroom condition,
> brand-new and unused — factory-fresh finishes, perfectly clean, no
> scratches, scuffs, rust, patina, or signs of use. Industrial design
> language of high-end commercial fitness equipment. Materials present
> where appropriate: polished or brushed steel, knurled iron, anodized
> aluminum, high-density rubber grips and flooring, fresh leather or vinyl
> upholstery with crisp stitching, ABS plastic shrouds, color-coded
> weight plates.

### Prompt template

```
[SHARED TREATMENT BLOCK]
[FRAMING CLAUSE]
[MATERIALS CLAUSE]
Three-quarter angle. [or: Straight-on side profile.]
The object is: [EQUIPMENT DESCRIPTION].
```

Keep one camera distance and one shadow direction across the whole
equipment set so a grid of them reads as a system.

### Filled example set — five equipment pieces

1. `...The object is: a single adjustable dumbbell resting on the floor, iron plates and knurled steel handle.`
2. `...The object is: an Olympic barbell lying on the floor, knurled steel, no plates loaded.`
3. `...The object is: a flat weight bench upholstered in fresh black leather with a steel frame. Straight-on side profile.`
4. `...The object is: a wall-mounted dual cable pulley station, steel frame and cable, matte finish.`
5. `...The object is: an empty sauna interior in warm cedar wood, simple bench, soft daylight from one side.` (spa item — lean warmer and quieter)

Additional spa subjects to reuse the template for: a stack of folded
towels, a smooth river stone, a foam roller and coiled resistance bands,
a glass carafe of water. Same treatment, warmer and quieter.

---

## Category 3 — Medication and supplement photography

Read this constraint first. **Generate generic, unlabeled product
photography. Never generate realistic drug labels.** A generated label
carries fictional dose, concentration, and regulatory codes that look
authentic but are wrong. In a medication tracker that is a safety
hazard. Generate the honest physical object; let the app render the real
drug name and dose as UI text beside the image. A bonus: one generic
amber-vial image serves every injectable in the list, so this is less
generation work, not more.

### Fixed clauses

**Framing clause:**
> A single medical product, isolated, centered, macro or near-macro so
> it reads clearly at small sizes, the product sharp with a shallow
> depth of field, a soft realistic contact shadow.

**Finish clause:**
> Clean pharmaceutical-grade realism, matte, no advertising gloss. The
> label is blank, plain white, or absent. No printed text of any kind.

### Prompt template

```
[SHARED TREATMENT BLOCK]
[FRAMING CLAUSE]
[FINISH CLAUSE]
The product is: [PHYSICAL FORM DESCRIPTION, NO DRUG NAME].
```

Describe by physical form, never by drug name.

### Filled example set — five medication forms

1. **Injectable oil (covers TRT / testosterone cypionate, etc.)**
   `...The product is: a small clear glass pharmaceutical vial with a metallic crimp cap and a blank white label, containing a pale amber oil.`

2. **Lyophilized peptide (covers sermorelin and similar)**
   `...The product is: a small pharmaceutical vial containing white lyophilized powder, metallic crimp cap, blank label, beside a separate small vial of clear liquid.`

3. **Film-coated tablet (covers finasteride and similar)**
   `...The product is: two small round white film-coated tablets resting side by side.`

4. **Oral capsule (covers prescription capsules)**
   `...The product is: two small opaque oral capsules, plain, no markings.`

5. **Softgel supplement (covers omega-3, vitamin D, etc.)**
   `...The product is: two translucent amber softgel capsules.`

Additional forms to reuse the template for: a blister pack of unmarked
tablets, a single white capsule, a small dropper bottle of clear liquid,
a powder scoop. All blank, all unlabeled.

### If the generator refuses

If a generator pattern-matches "vial" plus medical context and refuses,
strip every medical and drug word and prompt it as pure still life:

> a small glass vial with amber liquid and a metal cap, product
> photography, neutral oatmeal background, soft daylight, macro

The object is identical; reframing as generic still life clears most
content filters.

---

## Summary rules

1. One treatment system, three subject types. The shared block is
   never edited per image.
2. Within a category, lock the fixed clauses and reuse them verbatim.
   Only the subject clause changes.
3. Exercise photography requires human anatomical review before use.
4. Medication photography is generic and unlabeled. The app supplies
   real drug data as text. Never generate a realistic drug label.
5. Photography in this app is functional and lives in defined places
   (exercise library, equipment reference, medication list). It never
   sits behind charts or data.
