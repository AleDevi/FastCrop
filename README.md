# Fast Crop — high-speed manual batch cropping for Fiji/ImageJ

Crop hundreds of photos by hand, one after another, with **one keystroke per image**.
A crop box follows your mouse, **Enter** saves the crop, and the next image opens by itself.

Typical session: **hover → Enter → hover → Enter → …**

- Five crop shapes: Rectangle, Square, Circle (fixed size, follow the mouse), Ellipse and Freehand (drawn per image)
- Mouse-wheel rotation of the crop, with two options: **Trim** (black outside the rotated shape) or
  **Trim and straighten** (the image is rotated so the rectangle fills the frame, no black corners)
- JPEG EXIF orientation is corrected, so portrait photos show up the right way
- Originals are never modified; existing outputs are never silently overwritten
- Skip / resume of interrupted batches, undo of the previous image, batch summary and optional log file

> Made with free AI tools: the specification was written with **ChatGPT (free)**, the plugin with
> **Claude (free plan)**. Tested by me in Fiji on real photo batches.
> The full story, with the prompts, is on [mythingsai.com](https://mythingsai.com).

## Install

**Easiest:** download `Fast_Crop.jar` from the [latest release](../../releases/latest), copy it into
`Fiji.app/plugins/` and restart Fiji. It appears as **Plugins › Fast Crop**.

**From the source:** copy `Fast_Crop.java` into `Fiji.app/plugins/` and restart Fiji, or open it in
**File › New › Script…**, set the language to **Java** and choose **Run › Compile and Run**.

**Build the jar yourself:**
```
javac -cp /path/to/Fiji.app/jars/ij-1.54*.jar -d build Fast_Crop.java
cd build && jar cf ../Fast_Crop.jar *.class
```

## Use

**Plugins › Fast Crop** opens a single setup dialog:

| Setting | Options |
|---|---|
| Input / output folder | any folders (subfolders are not processed) |
| Crop shape | Rectangle, Square, Circle (fixed size) · Ellipse, Freehand (drawn per image) |
| Size | width × height, or diameter for Circle |
| Anchor | Center (default) or Top-left, relative to the cursor |
| Workflow mode | **Fast**: the crop follows the mouse · **Adjustment**: the crop moves only on click or arrow keys |
| Rotation handling | **Trim** or **Trim and straighten** (Rectangle/Square only) |
| Start position on each image | image center (default) or same as the previous image |
| Existing outputs | process all, skip, or resume |
| Output | same format as the source (default), JPEG, PNG or TIFF · optional suffix · JPEG quality (default 100) |
| Log file | `fast_crop_log.txt` in the output folder (on by default) |

### Keyboard and mouse

| Key | Action |
|---|---|
| Enter / Space | Accept the crop and save |
| S | Skip this image |
| Esc | Cancel the batch |
| B / Backspace | Go back and redo the previous image (its output is deleted) |
| Arrow keys | Nudge the crop 1 px (Shift: 10 px) |
| C | Recenter the crop and reset the rotation |
| Mouse wheel | Rotate the crop 1° per notch (Shift: 5°) |
| Mouse move / click | Position the crop (Rectangle, Square, Circle) |
| Mouse drag | Draw the shape (Ellipse, Freehand) |

While you are cropping, the mouse wheel rotates the crop instead of zooming.
Use `+` / `-` or the magnifier tool to zoom.

### File names

- Output folder ≠ input folder → same file name (plus your suffix, if any).
- Output folder = input folder → `_crop` is added, so the original is never overwritten.
- If a file with that name already exists, `_1`, `_2`, … is added.

### Image quality

The only operation is a crop: no resizing, filtering or colour changes. TIFF, PNG and BMP are lossless.
JPEG is re-encoded at the quality you choose (default 100). Crops that go past the image edge are
padded with black and always have the exact requested size.

## Limitations

- Non-rectangular shapes are saved as rectangles with black outside the shape (no transparency),
  so every output format works the same way, JPEG included.
- **Trim and straighten** works for Rectangle and Square only.
- EXIF orientation is read for JPEG only. The rare orientations 5 and 7 are handled but not
  independently verified; you always see the corrected image before cropping.
- No resizing of the crop by dragging handles: the size is fixed for the whole batch by design.

## Licence

[GPL-3.0](LICENSE), like ImageJ.
