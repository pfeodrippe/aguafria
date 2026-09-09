# Prototype assets

`corridor.png` is an original AI-generated placeholder, produced with the built-in
image generator on 2026-09-08. It is not an extracted Disco Elysium asset.
The image-generation skill guided the backdrop's framing and the separation of
painted scenery from text rendered by the game.

Final generation prompt:

> Use case: stylized-concept. Asset type: original background painting for a literary narrative RPG prototype, not a UI mockup. Scene: an empty, rain-darkened old European apartment corridor, one tall window and a half-open door, worn plaster and timber, quiet dusk. Style: expressive oil-painted environment art, textured brushwork, sophisticated muted blue-green shadows and warm ochre window light, melancholic and grounded, comparable in tone to painterly detective RPG environments. Composition: portrait 3:4, environmental establishing shot with strong depth, readable dark silhouettes and a beautifully lit doorway as focal point. This is a background behind an independent game interface. Constraints: NO text, NO lettering, NO UI, NO people, NO classroom, NO school props, NO logos. Original environment, not a copy of an existing game screenshot. High-quality painterly edges and light, not flat vector shapes.

Typography: [Libre Baskerville from Google Fonts](https://github.com/google/fonts/tree/main/ofl/librebaskerville),
bundled in `resources/fonts/LibreBaskerville.ttf` with its original `OFL.txt`.
This is a freely redistributable serif alternative, not a claim to use Disco
Elysium's proprietary font files. The renderer rasterizes actual font outlines
and consumes measured advances; dialogue is not baked into the backdrop.

The generated rain spritesheet and quiet audio tone are infrastructure fixtures,
not finished game animation or a Bitwig composition. Replace them through the
documented development export workflow. The initial glyph set covers Latin-1;
full Unicode shaping and a richer animation manifest remain future work.
