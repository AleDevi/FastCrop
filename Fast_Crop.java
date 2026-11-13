import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.gui.ImageCanvas;
import ij.gui.ImageWindow;
import ij.gui.OvalRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.io.FileSaver;
import ij.io.Opener;
import ij.plugin.PlugIn;
import ij.process.FloatPolygon;
import ij.process.ImageProcessor;

import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fast_Crop - a high-speed, low-action manual batch cropping tool for Fiji/ImageJ.
 *
 * Workflow per image: move mouse (or click) to position a fixed-size crop box,
 * press Enter/Space to accept. The crop is saved immediately and the next
 * image opens automatically. Original files are never modified.
 *
 * See README.md distributed alongside this file for full documentation,
 * build/install instructions and keyboard shortcuts.
 */
public class Fast_Crop implements PlugIn, KeyListener, MouseMotionListener, MouseListener, MouseWheelListener {

    // Shape identifiers (also used as the exact GenericDialog choice strings)
    private static final String SHAPE_RECTANGLE = "Rectangle";
    private static final String SHAPE_SQUARE = "Square";
    private static final String SHAPE_CIRCLE = "Circle";
    private static final String SHAPE_ELLIPSE = "Ellipse (draw per image)";
    private static final String SHAPE_FREEHAND = "Freehand (draw per image)";
    private static final double ROTATION_STEP_DEG = 1.0;
    private static final double ROTATION_STEP_SHIFT_DEG = 5.0;
    private static final String ROTATION_TRIM = "Trim (mask outside shape - may leave black corners when rotated)";
    private static final String ROTATION_STRAIGHTEN = "Trim and straighten (rotate content - no black corners; Rectangle/Square only)";

    // ---------------------------------------------------------------
    // Settings gathered once, at the start of the batch
    // ---------------------------------------------------------------
    static class CropSettings {
        File inputDir;
        File outputDir;
        String shape;          // one of the SHAPE_* constants above
        int width;
        int height;
        int diameter;          // used only when shape = Circle
        String anchor;         // "Center" or "Top-left" - ignored for Ellipse/Freehand
        boolean fastMode;       // true = crop follows mouse motion live (Rectangle/Square/Circle only)
        String initialPosMode; // "Center of image" or "Same as previous image"
        String existingMode;   // "Process all", "Skip existing", "Resume"
        String outputFormat;   // "Same as source", "JPEG", "PNG", "TIFF"
        String suffix;         // user-chosen filename suffix, may be empty
        int jpegQuality;
        boolean writeLog;
        String rotationMode;   // ROTATION_TRIM or ROTATION_STRAIGHTEN
    }

    private enum Action { ACCEPT, SKIP, CANCEL, BACK }
    private enum Result { ACCEPTED, SKIPPED, CANCELLED, BACK, FAILED }

    private static final Set<String> SUPPORTED_EXT = new HashSet<String>(
            Arrays.asList("jpg", "jpeg", "png", "tif", "tiff", "bmp"));

    private CropSettings cfg;
    private final Object lock = new Object();
    private volatile Action pendingAction;
    private ImagePlus imp;
    private ImageCanvas canvas;
    private Point cursor;          // current crop-anchor position, image pixel coordinates (template shapes)
    private Point lastAcceptedCursor;
    private File lastOutputFile;   // output of the most recently accepted image (for "back")
    private double currentRotation; // degrees, reset to 0 for each new image
    private double lastAcceptedRotation;
    private Roi drawnBaseRoi;       // for Ellipse/Freehand: the un-rotated shape the user drew, this image
    private Roi lastAcceptedRoi;    // for Ellipse/Freehand "same as previous" pre-population

    private int totalToProcess;
    private int processedCount, skippedCount, failedCount, cancelledFlag;
    private final List<String> logLines = new ArrayList<String>();

    // ================================================================
    // Entry point
    // ================================================================
    @Override
    public void run(String arg) {
        cfg = showSetupDialog();
        if (cfg == null) return; // user cancelled setup

        List<File> allImages = gatherImages(cfg.inputDir);
        if (allImages.isEmpty()) {
            IJ.error("Fast Crop", "No supported images (jpg/jpeg/png/tif/tiff/bmp) found in:\n" + cfg.inputDir);
            return;
        }

        List<File> queue = new ArrayList<File>();
        int alreadyDone = 0;
        boolean skipExisting = cfg.existingMode.startsWith("Skip") || cfg.existingMode.startsWith("Resume");
        for (File f : allImages) {
            if (skipExisting && defaultOutputFile(f).exists()) {
                alreadyDone++;
            } else {
                queue.add(f);
            }
        }
        if (queue.isEmpty()) {
            IJ.showMessage("Fast Crop", "All " + allImages.size() + " images already have an output file. Nothing to do.");
            return;
        }

        showShortcutReminder();

        totalToProcess = queue.size();
        // Per-queue-slot record of what happened, so "back" can precisely undo exactly
        // one step regardless of whether it was an accept or a skip.
        File[] outputForSlot = new File[queue.size()];
        boolean[] wasAcceptedSlot = new boolean[queue.size()];

        int idx = 0;
        while (idx < queue.size()) {
            File f = queue.get(idx);
            Result r;
            try {
                r = processOne(f, idx, totalToProcess, alreadyDone);
            } catch (Exception ex) {
                r = Result.FAILED;
                log("FAILED  " + f.getName() + "  (" + ex.getClass().getSimpleName() + ": " + ex.getMessage() + ")");
            }

            if (r == Result.CANCELLED) {
                break;
            } else if (r == Result.ACCEPTED) {
                processedCount++;
                outputForSlot[idx] = lastOutputFile;
                wasAcceptedSlot[idx] = true;
                idx++;
            } else if (r == Result.SKIPPED) {
                skippedCount++;
                wasAcceptedSlot[idx] = false;
                idx++;
            } else if (r == Result.FAILED) {
                failedCount++;
                idx++;
            } else if (r == Result.BACK) {
                if (idx > 0) {
                    idx--; // step back to the previous slot and redo it
                    if (wasAcceptedSlot[idx]) {
                        File prevOut = outputForSlot[idx];
                        if (prevOut != null && prevOut.exists()) prevOut.delete();
                        processedCount--;
                        log("UNDO    removed output of " + queue.get(idx).getName() + ", redoing it");
                    } else {
                        skippedCount--;
                        log("UNDO    redoing " + queue.get(idx).getName() + " (was skipped)");
                    }
                    outputForSlot[idx] = null;
                } // else: already at the first image, nothing to go back to - just redo it
            }
        }

        writeLogFileIfNeeded(alreadyDone);
        showSummary(alreadyDone);
    }

    // ================================================================
    // Setup dialog
    // ================================================================
    private CropSettings showSetupDialog() {
        GenericDialog gd = new GenericDialog("Fast Crop - setup");
        gd.addDirectoryField("Input folder", "");
        gd.addDirectoryField("Output folder", "");
        gd.addChoice("Crop shape", new String[]{
                SHAPE_RECTANGLE, SHAPE_SQUARE, SHAPE_CIRCLE, SHAPE_ELLIPSE, SHAPE_FREEHAND}, SHAPE_RECTANGLE);
        gd.addNumericField("Width (px)  [Rectangle/Square only]", 1200, 0);
        gd.addNumericField("Height (px)  [Rectangle only - Square uses Width]", 800, 0);
        gd.addNumericField("Diameter (px)  [Circle only]", 800, 0);
        gd.addChoice("Anchor (crop position relative to cursor)  [ignored for Ellipse/Freehand]",
                new String[]{"Center", "Top-left"}, "Center");
        gd.addChoice("Workflow mode  [ignored for Ellipse/Freehand - those are always drawn by hand]", new String[]{
                "Fast (crop follows mouse, Enter confirms)",
                "Adjustment (click/arrows to position, Enter confirms)"}, "Fast (crop follows mouse, Enter confirms)");
        gd.addChoice("Rotation handling  [Straighten only applies to Rectangle/Square]",
                new String[]{ROTATION_TRIM, ROTATION_STRAIGHTEN}, ROTATION_TRIM);
        gd.addChoice("Initial crop position for each new image", new String[]{
                "Center of image", "Same position as previous image"}, "Center of image");
        gd.addChoice("Existing output files", new String[]{
                "Process all (auto-unique name on collision)",
                "Skip images that already have output",
                "Resume from first unprocessed image"}, "Process all (auto-unique name on collision)");
        gd.addChoice("Output format", new String[]{"Same as source", "JPEG", "PNG", "TIFF"}, "Same as source");
        gd.addStringField("Filename suffix (optional; default '_crop' only if output = input folder)", "");
        gd.addNumericField("JPEG quality (1-100, only used for JPEG output)", 100, 0);
        gd.addCheckbox("Write a log file (fast_crop_log.txt) to the output folder", true);

        gd.showDialog();
        if (gd.wasCanceled()) return null;

        CropSettings c = new CropSettings();
        c.inputDir = new File(gd.getNextString().trim());
        c.outputDir = new File(gd.getNextString().trim());
        c.shape = gd.getNextChoice();
        c.width = (int) gd.getNextNumber();
        c.height = (int) gd.getNextNumber();
        c.diameter = (int) gd.getNextNumber();
        if (c.shape.equals(SHAPE_SQUARE)) c.height = c.width;
        c.anchor = gd.getNextChoice();
        String workflow = gd.getNextChoice();
        c.fastMode = workflow.startsWith("Fast");
        c.rotationMode = gd.getNextChoice();
        c.initialPosMode = gd.getNextChoice();
        c.existingMode = gd.getNextChoice();
        c.outputFormat = gd.getNextChoice();
        c.suffix = gd.getNextString().trim();
        c.jpegQuality = (int) gd.getNextNumber();
        c.writeLog = gd.getNextBoolean();

        // Validation
        StringBuilder err = new StringBuilder();
        if (!c.inputDir.isDirectory()) err.append("- Input folder does not exist.\n");
        if ((c.shape.equals(SHAPE_RECTANGLE) || c.shape.equals(SHAPE_SQUARE)) && (c.width <= 0 || c.height <= 0))
            err.append("- Width and height must be positive.\n");
        if (c.shape.equals(SHAPE_CIRCLE) && c.diameter <= 0)
            err.append("- Diameter must be positive.\n");
        if (c.jpegQuality < 1 || c.jpegQuality > 100) err.append("- JPEG quality must be between 1 and 100.\n");
        if (err.length() > 0) {
            IJ.error("Fast Crop - invalid settings", err.toString());
            return null;
        }
        if (!c.outputDir.exists()) {
            if (!c.outputDir.mkdirs()) {
                IJ.error("Fast Crop", "Could not create output folder:\n" + c.outputDir);
                return null;
            }
        }
        return c;
    }

    private void showShortcutReminder() {
        String shapeHint = isTemplateShape()
                ? "Move the mouse (or click) to position the crop box, then:\n"
                : "Draw the shape with the mouse (standard ImageJ oval/freehand tool), then:\n";
        IJ.showMessage("Fast Crop - keyboard shortcuts",
                shapeHint + "\n"
                        + "  Enter / Space   accept crop and save\n"
                        + "  S               skip this image (no output)\n"
                        + "  Esc             cancel the entire batch\n"
                        + "  B / Backspace   go back and redo the previous image\n"
                        + "  Arrow keys      nudge crop 1px (hold Shift for 10px)  [template shapes only]\n"
                        + "  C               recenter crop / reset rotation\n"
                        + "  Mouse wheel     rotate the crop shape (hold Shift for bigger steps)\n"
                        + "                  always active - wheel zoom is disabled while cropping;\n"
                        + "                  use +/- or the magnifier tool to zoom instead\n\n"
                        + "The original files are never modified.\nStarting now...");
    }

    // ================================================================
    // File gathering / natural sort
    // ================================================================
    private List<File> gatherImages(File dir) {
        File[] arr = dir.listFiles();
        List<File> list = new ArrayList<File>();
        if (arr != null) {
            for (File f : arr) {
                if (f.isFile() && SUPPORTED_EXT.contains(getExt(f.getName()))) list.add(f);
            }
        }
        java.util.Collections.sort(list, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return naturalCompare(a.getName(), b.getName());
            }
        });
        return list;
    }

    private static int naturalCompare(String a, String b) {
        int ia = 0, ib = 0;
        while (ia < a.length() && ib < b.length()) {
            char ca = a.charAt(ia), cb = b.charAt(ib);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int sa = ia, sb = ib;
                while (ia < a.length() && Character.isDigit(a.charAt(ia))) ia++;
                while (ib < b.length() && Character.isDigit(b.charAt(ib))) ib++;
                String na = a.substring(sa, ia).replaceFirst("^0+(?=\\d)", "");
                String nb = b.substring(sb, ib).replaceFirst("^0+(?=\\d)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                if (ca != cb) return Character.toLowerCase(ca) - Character.toLowerCase(cb);
                ia++;
                ib++;
            }
        }
        return (a.length() - ia) - (b.length() - ib);
    }

    // ================================================================
    // Per-image interactive processing
    // ================================================================
    private Result processOne(File f, int idx, int total, int alreadyDone) throws IOException {
        ImagePlus src = openWithOrientation(f);
        if (src == null) {
            log("FAILED  " + f.getName() + "  (could not open - unsupported/corrupt file)");
            return Result.FAILED;
        }

        this.imp = src;
        src.show();
        ImageWindow win = src.getWindow();
        this.canvas = src.getCanvas();

        currentRotation = cfg.initialPosMode.startsWith("Same") ? lastAcceptedRotation : 0;
        drawnBaseRoi = null;

        if (isTemplateShape()) {
            IJ.setTool("rectangle");
            if (cfg.initialPosMode.startsWith("Same") && lastAcceptedCursor != null) {
                cursor = new Point(
                        Math.min(Math.max(lastAcceptedCursor.x, 0), src.getWidth()),
                        Math.min(Math.max(lastAcceptedCursor.y, 0), src.getHeight()));
            } else {
                cursor = new Point(src.getWidth() / 2, src.getHeight() / 2);
            }
            updateRoiOverlay();
        } else {
            IJ.setTool(cfg.shape.equals(SHAPE_ELLIPSE) ? "oval" : "freehand");
            if (cfg.initialPosMode.startsWith("Same") && lastAcceptedRoi != null) {
                drawnBaseRoi = (Roi) lastAcceptedRoi.clone();
                imp.setRoi(drawnBaseRoi);
            } else {
                imp.deleteRoi();
            }
        }

        win.setTitle("[" + (idx + 1 + alreadyDone) + "/" + (total + alreadyDone) + "]  " + f.getName()
                + "   (Enter=accept  S=skip  Esc=cancel  B=back)");
        IJ.showStatus("Fast Crop: image " + (idx + 1 + alreadyDone) + "/" + (total + alreadyDone) + " - " + f.getName());
        IJ.showProgress(idx + alreadyDone, total + alreadyDone);

        canvas.addMouseMotionListener(this);
        canvas.addMouseListener(this);
        java.awt.event.MouseWheelListener[] nativeWheelListeners = canvas.getMouseWheelListeners();
        for (java.awt.event.MouseWheelListener l : nativeWheelListeners) canvas.removeMouseWheelListener(l);
        canvas.addMouseWheelListener(this);
        canvas.addKeyListener(this);
        win.addKeyListener(this);

        pendingAction = null;
        synchronized (lock) {
            while (pendingAction == null) {
                try {
                    lock.wait();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    pendingAction = Action.CANCEL;
                }
            }
        }

        canvas.removeMouseMotionListener(this);
        canvas.removeMouseListener(this);
        canvas.removeMouseWheelListener(this);
        for (java.awt.event.MouseWheelListener l : nativeWheelListeners) canvas.addMouseWheelListener(l);
        canvas.removeKeyListener(this);
        win.removeKeyListener(this);

        Action a = pendingAction;
        Result result;
        try {
            if (a == Action.ACCEPT) {
                Roi base = isTemplateShape() ? computeBaseRoi(cursor) : drawnBaseRoi;
                if (base == null) {
                    log("SKIPPED " + f.getName() + "  (no shape drawn)");
                    result = Result.SKIPPED;
                    return result;
                }
                File out = saveCrop(src, f, base, currentRotation);
                lastOutputFile = out;
                if (isTemplateShape()) lastAcceptedCursor = new Point(cursor);
                else lastAcceptedRoi = (Roi) base.clone();
                lastAcceptedRotation = currentRotation;
                log("OK      " + f.getName() + "  ->  " + out.getName());
                result = Result.ACCEPTED;
            } else if (a == Action.SKIP) {
                log("SKIPPED " + f.getName());
                result = Result.SKIPPED;
            } else if (a == Action.BACK) {
                result = Result.BACK;
            } else {
                result = Result.CANCELLED;
            }
        } finally {
            src.changes = false;
            src.close();
        }
        return result;
    }

    private boolean isTemplateShape() {
        return cfg.shape.equals(SHAPE_RECTANGLE) || cfg.shape.equals(SHAPE_SQUARE) || cfg.shape.equals(SHAPE_CIRCLE);
    }

    /** Un-rotated ROI for the current cursor position / template settings (Rectangle, Square, Circle). */
    private Roi computeBaseRoi(Point c) {
        if (cfg.shape.equals(SHAPE_CIRCLE)) {
            int d = cfg.diameter;
            int x = cfg.anchor.equals("Center") ? c.x - d / 2 : c.x;
            int y = cfg.anchor.equals("Center") ? c.y - d / 2 : c.y;
            return new OvalRoi(x, y, d, d);
        }
        int x, y;
        if (cfg.anchor.equals("Center")) {
            x = c.x - cfg.width / 2;
            y = c.y - cfg.height / 2;
        } else {
            x = c.x;
            y = c.y;
        }
        return new Roi(x, y, cfg.width, cfg.height);
    }

    /** Rotates any Roi's outline by angleDeg (clockwise) around its own bounding-box center. */
    private static Roi rotateRoi(Roi r, double angleDeg) {
        if (angleDeg == 0) return r;
        FloatPolygon fp = r.getFloatPolygon();
        Rectangle b = r.getBounds();
        double cx = b.x + b.width / 2.0, cy = b.y + b.height / 2.0;
        double rad = Math.toRadians(angleDeg);
        double cos = Math.cos(rad), sin = Math.sin(rad);
        float[] xs = new float[fp.npoints];
        float[] ys = new float[fp.npoints];
        for (int i = 0; i < fp.npoints; i++) {
            double dx = fp.xpoints[i] - cx, dy = fp.ypoints[i] - cy;
            xs[i] = (float) (cx + dx * cos - dy * sin);
            ys[i] = (float) (cy + dx * sin + dy * cos);
        }
        return new PolygonRoi(xs, ys, xs.length, Roi.POLYGON);
    }

    /** The ROI currently displayed/used, i.e. base shape + current rotation. */
    private Roi computeDisplayRoi() {
        Roi base = isTemplateShape() ? computeBaseRoi(cursor) : drawnBaseRoi;
        if (base == null) return null;
        return rotateRoi(base, currentRotation);
    }

    private void updateRoiOverlay() {
        if (imp == null) return;
        Roi r = computeDisplayRoi();
        if (r != null) imp.setRoi(r);
    }

    private void signal(Action a) {
        synchronized (lock) {
            pendingAction = a;
            lock.notifyAll();
        }
    }

    // ---------------- listeners ----------------
    @Override
    public void mouseMoved(MouseEvent e) {
        if (!isTemplateShape() || !cfg.fastMode) return;
        cursor = new Point(canvas.offScreenX(e.getX()), canvas.offScreenY(e.getY()));
        updateRoiOverlay();
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        if (!isTemplateShape()) return; // Ellipse/Freehand: let ImageJ's own tool handle the drag
        cursor = new Point(canvas.offScreenX(e.getX()), canvas.offScreenY(e.getY()));
        updateRoiOverlay();
    }

    @Override
    public void mousePressed(MouseEvent e) {
        if (!isTemplateShape()) return;
        cursor = new Point(canvas.offScreenX(e.getX()), canvas.offScreenY(e.getY()));
        updateRoiOverlay();
    }

    @Override
    public void mouseClicked(MouseEvent e) {
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (isTemplateShape()) return;
        // User just finished drawing an ellipse/freehand ROI with ImageJ's native tool.
        Roi drawn = imp.getRoi();
        if (drawn != null) {
            drawnBaseRoi = drawn;
            currentRotation = 0;
            updateRoiOverlay();
        }
    }

    @Override
    public void mouseEntered(MouseEvent e) {
    }

    @Override
    public void mouseExited(MouseEvent e) {
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        // Wheel is always dedicated to rotation (never zoom) while cropping - consuming
        // the event stops it from bubbling up to ImageWindow, which is what normally zooms.
        e.consume();
        if (!isTemplateShape() && drawnBaseRoi == null) return; // nothing drawn yet to rotate
        double step = e.isShiftDown() ? ROTATION_STEP_SHIFT_DEG : ROTATION_STEP_DEG;
        currentRotation += e.getWheelRotation() * step;
        updateRoiOverlay();
    }

    @Override
    public void keyPressed(KeyEvent e) {
        int step = e.isShiftDown() ? 10 : 1;
        int code = e.getKeyCode();
        if (code == KeyEvent.VK_ENTER || code == KeyEvent.VK_SPACE) {
            signal(Action.ACCEPT);
        } else if (code == KeyEvent.VK_S) {
            signal(Action.SKIP);
        } else if (code == KeyEvent.VK_ESCAPE) {
            signal(Action.CANCEL);
        } else if (code == KeyEvent.VK_B || code == KeyEvent.VK_BACK_SPACE) {
            signal(Action.BACK);
        } else if (isTemplateShape() && code == KeyEvent.VK_LEFT) {
            cursor.x -= step;
            updateRoiOverlay();
        } else if (isTemplateShape() && code == KeyEvent.VK_RIGHT) {
            cursor.x += step;
            updateRoiOverlay();
        } else if (isTemplateShape() && code == KeyEvent.VK_UP) {
            cursor.y -= step;
            updateRoiOverlay();
        } else if (isTemplateShape() && code == KeyEvent.VK_DOWN) {
            cursor.y += step;
            updateRoiOverlay();
        } else if (code == KeyEvent.VK_C) {
            if (isTemplateShape()) cursor = new Point(imp.getWidth() / 2, imp.getHeight() / 2);
            currentRotation = 0;
            updateRoiOverlay();
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
    }

    @Override
    public void keyTyped(KeyEvent e) {
    }

    // ================================================================
    // Cropping + padding + saving
    // ================================================================
    private static ImageProcessor cropWithPadding(ImageProcessor src, Rectangle r) {
        ImageProcessor dst = src.createProcessor(r.width, r.height); // zero/black-filled by default
        int ix1 = Math.max(0, r.x), iy1 = Math.max(0, r.y);
        int ix2 = Math.min(src.getWidth(), r.x + r.width), iy2 = Math.min(src.getHeight(), r.y + r.height);
        if (ix2 > ix1 && iy2 > iy1) {
            src.setRoi(ix1, iy1, ix2 - ix1, iy2 - iy1);
            ImageProcessor sub = src.crop();
            dst.insert(sub, ix1 - r.x, iy1 - r.y);
            src.resetRoi();
        }
        return dst;
    }

    /**
     * Rotates the image content itself (not just the mask) so that the requested
     * Rectangle/Square region becomes axis-aligned and fills the output frame exactly,
     * with no black corners. Works by extracting a square patch large enough to contain
     * the rotated crop at any angle (side = diagonal of the target rect + a small margin),
     * rotating that whole patch by -angleDeg, then cropping the target size from its center.
     */
    private static ImageProcessor straightenCrop(ImageProcessor src, Rectangle baseBounds, double angleDeg) {
        int w = baseBounds.width, h = baseBounds.height;
        int cx = baseBounds.x + w / 2, cy = baseBounds.y + h / 2;
        int diag = (int) Math.ceil(Math.sqrt((double) w * w + (double) h * h)) + 4;
        Rectangle patchRect = new Rectangle(cx - diag / 2, cy - diag / 2, diag, diag);
        ImageProcessor patch = cropWithPadding(src, patchRect);
        patch.setInterpolationMethod(ImageProcessor.BILINEAR);
        patch.rotate(-angleDeg);
        Rectangle finalRect = new Rectangle(diag / 2 - w / 2, diag / 2 - h / 2, w, h);
        return cropWithPadding(patch, finalRect);
    }

    private File saveCrop(ImagePlus src, File inputFile, Roi base, double rotationDeg) throws IOException {
        ImageProcessor cropped;
        boolean canStraighten = isTemplateShape() && !cfg.shape.equals(SHAPE_CIRCLE);
        if (cfg.rotationMode.equals(ROTATION_STRAIGHTEN) && canStraighten && rotationDeg != 0) {
            cropped = straightenCrop(src.getProcessor(), base.getBounds(), rotationDeg);
        } else {
            Roi finalRoi = rotateRoi(base, rotationDeg);
            Rectangle r = finalRoi.getBounds();
            cropped = cropWithPadding(src.getProcessor(), r);
            // Mask out anything outside the (possibly non-rectangular / rotated) shape.
            // For an un-rotated axis-aligned Rectangle/Square this Roi equals the full
            // cropped bounds, so fillOutside is a harmless no-op.
            Roi localRoi = (Roi) finalRoi.clone();
            localRoi.setLocation(0, 0);
            cropped.setRoi(localRoi);
            cropped.setColor(Color.BLACK);
            cropped.fillOutside(localRoi);
            cropped.resetRoi();
        }

        File out = resolveOutputFile(inputFile);
        String ext = getExt(out.getName());
        ImagePlus toSave = new ImagePlus(out.getName(), cropped);
        FileSaver fs = new FileSaver(toSave);
        boolean ok;
        if (ext.equals("jpg") || ext.equals("jpeg")) {
            fs.setJpegQuality(cfg.jpegQuality);
            ok = fs.saveAsJpeg(out.getAbsolutePath());
        } else if (ext.equals("png")) {
            ok = fs.saveAsPng(out.getAbsolutePath());
        } else if (ext.equals("bmp")) {
            ok = fs.saveAsBmp(out.getAbsolutePath());
        } else {
            ok = fs.saveAsTiff(out.getAbsolutePath());
        }
        if (!ok) throw new IOException("FileSaver reported failure writing " + out);
        return out;
    }

    private String targetExtension() {
        if (cfg.outputFormat.equals("Same as source")) return null; // resolved per-file
        if (cfg.outputFormat.equals("JPEG")) return "jpg";
        if (cfg.outputFormat.equals("PNG")) return "png";
        return "tif"; // TIFF
    }

    private boolean sameDir(File a, File b) {
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (IOException e) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }

    /** Output path using default naming rules, WITHOUT collision resolution (used for resume/skip checks). */
    private File defaultOutputFile(File inputFile) {
        String base = stripExt(inputFile.getName());
        String ext = targetExtension();
        if (ext == null) ext = getExt(inputFile.getName());
        boolean sameDirFlag = sameDir(cfg.inputDir, cfg.outputDir);
        String suffix = (cfg.suffix != null && !cfg.suffix.isEmpty()) ? cfg.suffix : (sameDirFlag ? "_crop" : "");
        return new File(cfg.outputDir, base + suffix + "." + ext);
    }

    /** Output path WITH collision resolution (auto-unique suffix if the file already exists). */
    private File resolveOutputFile(File inputFile) {
        File candidate = defaultOutputFile(inputFile);
        if (!candidate.exists()) return candidate;
        String base = stripExt(candidate.getName());
        String ext = getExt(candidate.getName());
        int n = 1;
        File alt;
        do {
            alt = new File(candidate.getParentFile(), base + "_" + n + "." + ext);
            n++;
        } while (alt.exists());
        return alt;
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? name : name.substring(0, i);
    }

    private static String getExt(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase();
    }

    // ================================================================
    // Opening with EXIF orientation correction (JPEG only)
    // ================================================================
    private ImagePlus openWithOrientation(File f) {
        Opener opener = new Opener();
        ImagePlus im = opener.openImage(f.getAbsolutePath());
        if (im == null) return null;
        String ext = getExt(f.getName());
        if (ext.equals("jpg") || ext.equals("jpeg")) {
            try {
                int orientation = ExifOrientation.readOrientation(f);
                if (orientation > 1) {
                    ImageProcessor ip = ExifOrientation.apply(im.getProcessor(), orientation);
                    im.setProcessor(ip);
                }
            } catch (Exception ex) {
                // EXIF parsing is best-effort; if it fails, proceed with the image as-is.
                log("NOTE    " + f.getName() + " - could not read EXIF orientation (" + ex.getMessage() + ")");
            }
        }
        return im;
    }

    /** Minimal, dependency-free EXIF orientation reader/applier (JPEG only). */
    static class ExifOrientation {
        static int readOrientation(File f) throws IOException {
            byte[] data = readHead(f, 131072); // 128KB is ample for the EXIF header of virtually all JPEGs
            if (data.length < 4 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) return 1;
            int pos = 2;
            while (pos + 4 <= data.length) {
                if ((data[pos] & 0xFF) != 0xFF) break;
                int marker = data[pos + 1] & 0xFF;
                if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                    pos += 2;
                    continue;
                }
                if (marker == 0xDA || marker == 0xD9) break; // start of scan / end of image: no more markers
                int segLen = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
                if (marker == 0xE1 && pos + 10 <= data.length
                        && data[pos + 4] == 'E' && data[pos + 5] == 'x' && data[pos + 6] == 'i' && data[pos + 7] == 'f') {
                    int tiffStart = pos + 10;
                    return parseTiff(data, tiffStart);
                }
                pos += 2 + segLen;
            }
            return 1;
        }

        private static int parseTiff(byte[] d, int tiffStart) {
            if (tiffStart + 8 > d.length) return 1;
            boolean little = (d[tiffStart] == 'I' && d[tiffStart + 1] == 'I');
            int ifdOffset = read32(d, tiffStart + 4, little);
            int ifdPos = tiffStart + ifdOffset;
            if (ifdPos + 2 > d.length) return 1;
            int numEntries = read16(d, ifdPos, little);
            for (int i = 0; i < numEntries; i++) {
                int entryPos = ifdPos + 2 + i * 12;
                if (entryPos + 12 > d.length) break;
                int tag = read16(d, entryPos, little);
                if (tag == 0x0112) {
                    int value = read16(d, entryPos + 8, little);
                    return (value >= 1 && value <= 8) ? value : 1;
                }
            }
            return 1;
        }

        private static int read16(byte[] d, int off, boolean little) {
            int b0 = d[off] & 0xFF, b1 = d[off + 1] & 0xFF;
            return little ? (b1 << 8 | b0) : (b0 << 8 | b1);
        }

        private static int read32(byte[] d, int off, boolean little) {
            int b0 = d[off] & 0xFF, b1 = d[off + 1] & 0xFF, b2 = d[off + 2] & 0xFF, b3 = d[off + 3] & 0xFF;
            return little ? (b3 << 24 | b2 << 16 | b1 << 8 | b0) : (b0 << 24 | b1 << 16 | b2 << 8 | b3);
        }

        private static byte[] readHead(File f, int maxBytes) throws IOException {
            FileInputStream in = new FileInputStream(f);
            try {
                long len = Math.min(f.length(), maxBytes);
                byte[] buf = new byte[(int) len];
                int total = 0;
                while (total < buf.length) {
                    int n = in.read(buf, total, buf.length - total);
                    if (n < 0) break;
                    total += n;
                }
                if (total < buf.length) buf = Arrays.copyOf(buf, total);
                return buf;
            } finally {
                in.close();
            }
        }

        /**
         * Applies the EXIF orientation so the pixel data matches what the camera user saw.
         * Orientations 1-4, 6, 8 are exact. 5 and 7 (mirror + rotate, produced only by a
         * few scanners/tools) are handled with the standard rotate+mirror composition but
         * are rare in camera photographs; the tool always shows the corrected image before
         * cropping, so any residual issue is caught visually before anything is saved.
         */
        static ImageProcessor apply(ImageProcessor ip, int orientation) {
            switch (orientation) {
                case 2:
                    ip.flipHorizontal();
                    return ip;
                case 3:
                    ip.flipHorizontal();
                    ip.flipVertical();
                    return ip;
                case 4:
                    ip.flipVertical();
                    return ip;
                case 5:
                    ip = ip.rotateRight();
                    ip.flipHorizontal();
                    return ip;
                case 6:
                    return ip.rotateRight();
                case 7:
                    ip = ip.rotateLeft();
                    ip.flipHorizontal();
                    return ip;
                case 8:
                    return ip.rotateLeft();
                default:
                    return ip;
            }
        }
    }

    // ================================================================
    // Logging / summary
    // ================================================================
    private void log(String line) {
        logLines.add(line);
    }

    private void writeLogFileIfNeeded(int alreadyDone) {
        if (!cfg.writeLog) return;
        File logFile = new File(cfg.outputDir, "fast_crop_log.txt");
        try {
            PrintWriter pw = new PrintWriter(new FileWriter(logFile, true));
            try {
                pw.println("=== Fast Crop run " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " ===");
                pw.println("Input:  " + cfg.inputDir);
                pw.println("Output: " + cfg.outputDir);
                pw.println("Crop:   " + cfg.width + "x" + cfg.height + "  anchor=" + cfg.anchor);
                for (String l : logLines) pw.println(l);
                pw.println("Summary: processed=" + processedCount + " skipped=" + skippedCount
                        + " failed=" + failedCount + " already-done=" + alreadyDone);
                pw.println();
            } finally {
                pw.close();
            }
        } catch (IOException ex) {
            IJ.log("Fast Crop: could not write log file - " + ex.getMessage());
        }
    }

    private void showSummary(int alreadyDone) {
        IJ.showProgress(1.0);
        IJ.showStatus("Fast Crop finished.");
        StringBuilder sb = new StringBuilder();
        sb.append("Processed:   ").append(processedCount).append("\n");
        sb.append("Skipped:     ").append(skippedCount).append("\n");
        sb.append("Failed:      ").append(failedCount).append("\n");
        if (alreadyDone > 0) sb.append("Already done (previous run): ").append(alreadyDone).append("\n");
        sb.append("\nOutput folder:\n").append(cfg.outputDir);
        if (cfg.writeLog) sb.append("\n\nDetails in fast_crop_log.txt");
        IJ.showMessage("Fast Crop - batch finished", sb.toString());
    }
}
