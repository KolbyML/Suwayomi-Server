package android.graphics;

import android.annotation.ColorInt;
import android.annotation.ColorLong;
import android.annotation.NonNull;
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;

public final class Canvas {
    private final Bitmap bitmap;

    private BufferedImage canvasImage;
    private Graphics2D canvas;
    private final List<AffineTransform> transformStack = new ArrayList<AffineTransform>();

    public Canvas(Bitmap bitmap) {
        this.bitmap = bitmap;
    }

    private void ensureGraphics() {
        if (canvas != null) {
            return;
        }

        canvasImage = bitmap.ensureMutableImageBacking();
        canvas = canvasImage.createGraphics();
    }

    public void drawBitmap(Bitmap sourceBitmap, Rect src, Rect dst, Paint paint) {
        if (canvas == null && Bitmap.shouldUseNativeImage()) {
            try {
                bitmap.drawBitmap(sourceBitmap, src, dst, paint);
                return;
            } catch (RuntimeException ignored) {
                // Fall through to the Java path if the bitmap cannot stay native-backed.
            }
        }

        ensureGraphics();
        BufferedImage sourceImage = sourceBitmap.getImage();
        BufferedImage sourceImageCropped = sourceImage.getSubimage(src.left, src.top, src.getWidth(), src.getHeight());
        canvas.drawImage(sourceImageCropped, dst.left, dst.top, dst.getWidth(), dst.getHeight(), null);
    }

    public void drawBitmap(Bitmap sourceBitmap, float left, float top, Paint paint) {
        Rect src = new Rect(0, 0, sourceBitmap.getWidth(), sourceBitmap.getHeight());
        Rect dst = new Rect((int) left, (int) top, (int) left + sourceBitmap.getWidth(), (int) top + sourceBitmap.getHeight());
        drawBitmap(sourceBitmap, src, dst, paint);
    }

    public void drawText(@NonNull char[] text, int index, int count, float x, float y, @NonNull Paint paint) {
        drawText(new String(text, index, count), x, y, paint);
    }

    public void drawText(@NonNull String str, float x, float y, @NonNull Paint paint) {
        ensureGraphics();
        applyPaint(paint);
        AttributedString text = paint.getTypeface().createWithFallback(str);
        canvas.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        GlyphVector glyphVector = paint.getTypeface().getFont().createGlyphVector(canvas.getFontRenderContext(), text.getIterator());
        Shape textShape = glyphVector.getOutline();
        switch (paint.getStyle()) {
            case FILL:
                canvas.drawString(text.getIterator(), x, y);
                break;
            case STROKE:
                save();
                translate(x, y);
                canvas.draw(textShape);
                restore();
                break;
            case FILL_AND_STROKE:
                save();
                translate(x, y);
                canvas.draw(textShape);
                canvas.fill(textShape);
                restore();
                break;
        }
    }

    public void drawText(@NonNull String text, int start, int end, float x, float y, @NonNull Paint paint) {
        drawText(text.substring(start, end), x, y, paint);
    }

    public void drawText(@NonNull CharSequence text, int start, int end, float x, float y, @NonNull Paint paint) {
        drawText(text.subSequence(start, end).toString(), x, y, paint);
    }

    public void drawRoundRect(@NonNull RectF rect, float rx, float ry, @NonNull Paint paint) {
        throw new RuntimeException("Stub!");
    }

    public void drawRoundRect(float left, float top, float right, float bottom, float rx, float ry, @NonNull Paint paint) {
        throw new RuntimeException("Stub!");
    }

    public void drawPath(@NonNull Path path, @NonNull Paint paint) {
        throw new RuntimeException("Stub!");
    }

    public void translate(float dx, float dy) {
        if (dx == 0.0f && dy == 0.0f) {
            return;
        }
        ensureGraphics();
        canvas.translate(dx, dy);
    }

    public void scale(float sx, float sy) {
        if (sx == 1.0f && sy == 1.0f) {
            return;
        }
        ensureGraphics();
        canvas.scale(sx, sy);
    }

    public final void scale(float sx, float sy, float px, float py) {
        if (sx == 1.0f && sy == 1.0f) {
            return;
        }
        translate(px, py);
        scale(sx, sy);
        translate(-px, -py);
    }

    public void rotate(float degrees) {
        if (degrees == 0.0f) {
            return;
        }
        ensureGraphics();
        canvas.rotate(degrees);
    }

    public final void rotate(float degrees, float px, float py) {
        if (degrees == 0.0f) {
            return;
        }
        ensureGraphics();
        canvas.rotate(degrees, px, py);
    }

    public int getSaveCount() {
        return transformStack.size();
    }

    public int save() {
        ensureGraphics();
        transformStack.add(canvas.getTransform());
        return getSaveCount();
    }

    public void restoreToCount(int saveCount) {
        ensureGraphics();
        if (saveCount < 1) {
            throw new IllegalArgumentException("Underflow in restoreToCount - more restores than saves");
        }
        if (saveCount > getSaveCount()) {
            throw new IllegalArgumentException("Overflow in restoreToCount");
        }
        AffineTransform transform = transformStack.get(saveCount - 1);
        canvas.setTransform(transform);
        while (transformStack.size() >= saveCount) {
            transformStack.remove(transformStack.size() - 1);
        }
    }

    public void restore() {
        restoreToCount(getSaveCount());
    }

    public boolean getClipBounds(@NonNull Rect bounds) {
        ensureGraphics();
        Rectangle clipBounds = canvas.getClipBounds();
        if (clipBounds == null) {
            bounds.left = 0;
            bounds.top = 0;
            bounds.right = canvasImage.getWidth();
            bounds.bottom = canvasImage.getHeight();
            return true;
        }
        bounds.left = clipBounds.x;
        bounds.top = clipBounds.y;
        bounds.right = clipBounds.x + clipBounds.width;
        bounds.bottom = clipBounds.y + clipBounds.height;
        return clipBounds.width != 0 && clipBounds.height != 0;
    }

    public void drawColor(@ColorInt int colorInt) {
        ensureGraphics();
        java.awt.Color color = Color.valueOf(colorInt).toJavaColor();
        canvas.setColor(color);
        canvas.fillRect(0, 0, canvasImage.getWidth(), canvasImage.getHeight());
    }

    public void drawColor(@ColorLong long colorLong) {
        ensureGraphics();
        java.awt.Color color = Color.valueOf(colorLong).toJavaColor();
        canvas.setColor(color);
        canvas.fillRect(0, 0, canvasImage.getWidth(), canvasImage.getHeight());
    }

    public void drawPoint(float x, float y, Paint paint) {
        ensureGraphics();
        applyPaint(paint);
        Shape shape = paintToShape(paint, x, y);
        if (paint.getStyle() == Paint.Style.FILL) {
            canvas.fill(shape);
        } else {
            canvas.draw(shape);
        }
    }

    private void applyPaint(Paint paint) {
        canvas.setFont(paint.getTypeface().getFont());
        java.awt.Color color = Color.valueOf(paint.getColorLong()).toJavaColor();
        canvas.setColor(color);
        canvas.setStroke(new BasicStroke(paint.getStrokeWidth(), paintToStrokeCap(paint), BasicStroke.JOIN_ROUND));
        canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                paint.isAntiAlias() ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
        canvas.setRenderingHint(RenderingHints.KEY_DITHERING,
                paint.isDither() ? RenderingHints.VALUE_DITHER_ENABLE : RenderingHints.VALUE_DITHER_DISABLE);
    }

    private static int paintToStrokeCap(Paint paint) {
        switch (paint.getStrokeCap()) {
            case BUTT:
                return BasicStroke.CAP_BUTT;
            case SQUARE:
                return BasicStroke.CAP_SQUARE;
            case ROUND:
                return BasicStroke.CAP_ROUND;
            default:
                throw new UnsupportedOperationException("Stroke cap " + paint.getStrokeCap() + " not supported");
        }
    }

    private static Shape paintToShape(Paint paint, float x, float y) {
        int width = (int) (paint.getStrokeWidth() * 2);
        if (width <= 0) {
            width = 1;
        }
        int upLeftX = (int) (x - (float) width / 2);
        int upLeftY = (int) (y - (float) width / 2);
        switch (paint.getStrokeCap()) {
            case BUTT:
                return new Rectangle((int) x, (int) y, 1, 1);
            case SQUARE:
                return new Rectangle(upLeftX, upLeftY, width, width);
            case ROUND:
                return new Ellipse2D.Float(upLeftX, upLeftY, width, width);
            default:
                throw new UnsupportedOperationException("Stroke cap " + paint.getStrokeCap() + " not supported");
        }
    }
}
