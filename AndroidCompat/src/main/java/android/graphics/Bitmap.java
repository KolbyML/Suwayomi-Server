package android.graphics;

import android.annotation.ColorInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Log;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

public final class Bitmap {
    private static final String TAG = "Bitmap";

    private static final boolean ENABLE_NATIVE_IMAGE =
            Boolean.parseBoolean(System.getProperty("suwayomi.native.image",
                    System.getenv().getOrDefault("SUWAYOMI_NATIVE_IMAGE", "false")));

    private static volatile boolean nativeBridgeAvailable = true;

    private int width;
    private int height;
    private BufferedImage image;

    private NativeRef nativeImageRef;
    private NativeRef nativeCanvasRef;

    private Bitmap() {}

    private Bitmap(BufferedImage image) {
        this.image = image;
        this.width = image.getWidth();
        this.height = image.getHeight();
    }

    static boolean shouldUseNativeImage() {
        return ENABLE_NATIVE_IMAGE && nativeBridgeAvailable;
    }

    private static void disableNativeBridge(UnsatisfiedLinkError error) {
        nativeBridgeAvailable = false;
        Log.w(TAG, "Native image bridge unavailable, falling back to Java ImageIO", error);
    }

    static Bitmap createBitmap(byte[] bytes) {
        if (shouldUseNativeImage()) {
            Bitmap bitmap = new Bitmap();
            try {
                long[] result = bitmap.createNativeImage(bytes);
                bitmap.nativeImageRef = new NativeRef(result[0]);
                bitmap.width = (int) result[1];
                bitmap.height = (int) result[2];
                return bitmap;
            } catch (UnsatisfiedLinkError error) {
                disableNativeBridge(error);
            }
        }

        return new Bitmap(decodeBufferedImage(bytes));
    }

    BufferedImage getImage() {
        return requireImage();
    }

    BufferedImage ensureMutableImageBacking() {
        BufferedImage current = requireImage();
        if (nativeCanvasRef == null && nativeImageRef == null) {
            return current;
        }

        int type = current.getType();
        if (type == BufferedImage.TYPE_CUSTOM || type == 0) {
            type = BufferedImage.TYPE_INT_ARGB;
        }

        BufferedImage mutable = new BufferedImage(current.getWidth(), current.getHeight(), type);
        Graphics2D graphics = mutable.createGraphics();
        graphics.drawImage(current, 0, 0, null);
        graphics.dispose();

        image = mutable;
        releaseNativeObject();
        return mutable;
    }

    BufferedImage requireImage() {
        if (image != null) {
            return image;
        }

        if (nativeCanvasRef != null) {
            image = decodeBufferedImage(exportNativeCanvas(nativeCanvasRef.address()));
            width = image.getWidth();
            height = image.getHeight();
            return image;
        }

        if (nativeImageRef != null) {
            image = decodeBufferedImage(exportNativeImage(nativeImageRef.address()));
            width = image.getWidth();
            height = image.getHeight();
            return image;
        }

        throw new IllegalStateException("Bitmap has no backing image");
    }

    public int getHeight() {
        return height;
    }

    public int getWidth() {
        return width;
    }

    public enum CompressFormat {
        JPEG(0),
        PNG(1),
        WEBP(2),
        WEBP_LOSSY(3),
        WEBP_LOSSLESS(4);

        final int nativeInt;

        CompressFormat(int nativeInt) {
            this.nativeInt = nativeInt;
        }
    }

    public enum Config {
        ALPHA_8(1),
        RGB_565(3),
        ARGB_4444(4),
        ARGB_8888(5),
        RGBA_F16(6),
        HARDWARE(7),
        RGBA_1010102(8),

        _TYPE_3BYTE_BGR(BufferedImage.TYPE_3BYTE_BGR),
        _TYPE_4BYTE_ABGR(BufferedImage.TYPE_4BYTE_ABGR),
        _TYPE_4BYTE_ABGR_PRE(BufferedImage.TYPE_4BYTE_ABGR_PRE),
        _TYPE_BYTE_BINARY(BufferedImage.TYPE_BYTE_BINARY),
        _TYPE_BYTE_GRAY(BufferedImage.TYPE_BYTE_GRAY),
        _TYPE_BYTE_INDEXED(BufferedImage.TYPE_BYTE_INDEXED),
        _TYPE_CUSTOM(BufferedImage.TYPE_CUSTOM),
        _TYPE_INT_ARGB(BufferedImage.TYPE_INT_ARGB),
        _TYPE_INT_ARGB_PRE(BufferedImage.TYPE_INT_ARGB_PRE),
        _TYPE_INT_BGR(BufferedImage.TYPE_INT_BGR),
        _TYPE_INT_RGB(BufferedImage.TYPE_INT_RGB),
        _TYPE_USHORT_555_RGB(BufferedImage.TYPE_USHORT_555_RGB),
        _TYPE_USHORT_565_RGB(BufferedImage.TYPE_USHORT_565_RGB),
        _TYPE_USHORT_GRAY(BufferedImage.TYPE_USHORT_GRAY),
        ;

        final int nativeInt;

        private static final Config[] sConfigs = {
                null, ALPHA_8, null, RGB_565, ARGB_4444, ARGB_8888, RGBA_F16, HARDWARE, RGBA_1010102
        };

        Config(int ni) {
            this.nativeInt = ni;
        }

        static Config nativeToConfig(int ni) {
            return sConfigs[ni];
        }
    }

    private static int configToBufferedImageType(Config config) {
        switch (config) {
            case ALPHA_8:
                return BufferedImage.TYPE_BYTE_GRAY;
            case RGB_565:
                return BufferedImage.TYPE_USHORT_565_RGB;
            case ARGB_8888:
                return BufferedImage.TYPE_INT_ARGB;
            case _TYPE_3BYTE_BGR:
            case _TYPE_4BYTE_ABGR:
            case _TYPE_4BYTE_ABGR_PRE:
            case _TYPE_BYTE_BINARY:
            case _TYPE_BYTE_GRAY:
            case _TYPE_BYTE_INDEXED:
            case _TYPE_CUSTOM:
            case _TYPE_INT_ARGB:
            case _TYPE_INT_ARGB_PRE:
            case _TYPE_INT_BGR:
            case _TYPE_INT_RGB:
            case _TYPE_USHORT_555_RGB:
            case _TYPE_USHORT_565_RGB:
            case _TYPE_USHORT_GRAY:
                return config.nativeInt;
            default:
                throw new UnsupportedOperationException("Bitmap.Config(" + config + ") not supported");
        }
    }

    private static Config bufferedImageTypeToConfig(int type) {
        switch (type) {
            case BufferedImage.TYPE_BYTE_GRAY:
                return Config.ALPHA_8;
            case BufferedImage.TYPE_USHORT_565_RGB:
                return Config.RGB_565;
            case BufferedImage.TYPE_INT_ARGB:
                return Config.ARGB_8888;
            case BufferedImage.TYPE_3BYTE_BGR:
                return Config._TYPE_3BYTE_BGR;
            case BufferedImage.TYPE_4BYTE_ABGR:
                return Config._TYPE_4BYTE_ABGR;
            case BufferedImage.TYPE_4BYTE_ABGR_PRE:
                return Config._TYPE_4BYTE_ABGR_PRE;
            case BufferedImage.TYPE_BYTE_BINARY:
                return Config._TYPE_BYTE_BINARY;
            case BufferedImage.TYPE_BYTE_INDEXED:
                return Config._TYPE_BYTE_INDEXED;
            case BufferedImage.TYPE_CUSTOM:
                return Config._TYPE_CUSTOM;
            case BufferedImage.TYPE_INT_ARGB_PRE:
                return Config._TYPE_INT_ARGB_PRE;
            case BufferedImage.TYPE_INT_BGR:
                return Config._TYPE_INT_BGR;
            case BufferedImage.TYPE_INT_RGB:
                return Config._TYPE_INT_RGB;
            case BufferedImage.TYPE_USHORT_555_RGB:
                return Config._TYPE_USHORT_555_RGB;
            case BufferedImage.TYPE_USHORT_GRAY:
                return Config._TYPE_USHORT_GRAY;
            default:
                Log.w(TAG, "Encountered unsupported image type " + type);
                return null;
        }
    }

    private static BufferedImage decodeBufferedImage(byte[] bytes) {
        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
            if (decoded == null) {
                throw new IllegalArgumentException("Unable to decode image");
            }
            return decoded;
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        }
    }

    private static void checkXYSign(int x, int y) {
        if (x < 0) {
            throw new IllegalArgumentException("x must be >= 0");
        }
        if (y < 0) {
            throw new IllegalArgumentException("y must be >= 0");
        }
    }

    private static void checkWidthHeight(int width, int height) {
        if (width <= 0) {
            throw new IllegalArgumentException("width must be > 0");
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height must be > 0");
        }
    }

    public static Bitmap createBitmap(int width, int height, Config config) {
        if (shouldUseNativeImage()) {
            Bitmap bitmap = new Bitmap();
            try {
                bitmap.nativeCanvasRef = new NativeRef(bitmap.createNativeCanvas(width, height));
                bitmap.width = width;
                bitmap.height = height;
                return bitmap;
            } catch (UnsatisfiedLinkError error) {
                disableNativeBridge(error);
            }
        }

        BufferedImage bufferedImage = new BufferedImage(width, height, configToBufferedImageType(config));
        return new Bitmap(bufferedImage);
    }

    public static Bitmap createBitmap(@NonNull Bitmap source, int x, int y, int width, int height) {
        checkXYSign(x, y);
        checkWidthHeight(width, height);
        if (x + width > source.getWidth()) {
            throw new IllegalArgumentException("x + width must be <= bitmap.width()");
        }
        if (y + height > source.getHeight()) {
            throw new IllegalArgumentException("y + height must be <= bitmap.height()");
        }

        BufferedImage sourceImage = source.requireImage();
        BufferedImage subImage = sourceImage.getSubimage(x, y, width, height);
        int type = subImage.getType();
        if (type == BufferedImage.TYPE_CUSTOM || type == 0) {
            type = BufferedImage.TYPE_INT_ARGB;
        }
        BufferedImage copy = new BufferedImage(subImage.getWidth(), subImage.getHeight(), type);
        Graphics2D graphics = copy.createGraphics();
        graphics.drawImage(subImage, 0, 0, null);
        graphics.dispose();
        return new Bitmap(copy);
    }

    void drawBitmap(Bitmap sourceBitmap, Rect src, Rect dst, Paint paint) {
        if (shouldUseNativeImage() &&
                nativeCanvasRef != null &&
                sourceBitmap.nativeImageRef != null) {
            try {
                drawBitmap(
                        sourceBitmap.nativeImageRef.address(),
                        nativeCanvasRef.address(),
                        new int[] { src.left, src.top, src.right, src.bottom },
                        new int[] { dst.left, dst.top, dst.right, dst.bottom }
                );
                return;
            } catch (UnsatisfiedLinkError error) {
                disableNativeBridge(error);
            }
        }

        BufferedImage targetImage = ensureMutableImageBacking();
        BufferedImage sourceImage = sourceBitmap.requireImage();
        BufferedImage cropped = sourceImage.getSubimage(src.left, src.top, src.getWidth(), src.getHeight());
        Graphics2D graphics = targetImage.createGraphics();
        graphics.drawImage(cropped, dst.left, dst.top, dst.getWidth(), dst.getHeight(), null);
        graphics.dispose();
    }

    public boolean compress(CompressFormat format, int quality, OutputStream stream) {
        if (stream == null) {
            throw new NullPointerException();
        }
        if (quality < 0 || quality > 100) {
            throw new IllegalArgumentException("quality must be 0..100");
        }

        if (shouldUseNativeImage()) {
            try {
                if (nativeCanvasRef != null) {
                    stream.write(getImage(nativeCanvasRef.address(), format.nativeInt, sanitizeQuality(quality)));
                    return true;
                }
                if (nativeImageRef != null) {
                    stream.write(compressImage(nativeImageRef.address(), format.nativeInt, sanitizeQuality(quality)));
                    return true;
                }
            } catch (UnsatisfiedLinkError error) {
                disableNativeBridge(error);
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        }

        return compressWithImageIo(format, quality, stream);
    }

    private int sanitizeQuality(int quality) {
        if (quality == 0 || quality > 90) {
            return 90;
        }
        return quality;
    }

    private boolean compressWithImageIo(CompressFormat format, int quality, OutputStream stream) {
        String formatString;
        if (format == CompressFormat.PNG) {
            formatString = "png";
        } else if (format == CompressFormat.JPEG) {
            formatString = "jpg";
        } else if (format == CompressFormat.WEBP || format == CompressFormat.WEBP_LOSSY) {
            formatString = "webp";
        } else {
            throw new IllegalArgumentException("unsupported compression format! " + format);
        }

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(formatString);
        if (!writers.hasNext()) {
            throw new IllegalStateException("no image writers found for this format!");
        }
        ImageWriter writer = writers.next();

        try {
            ImageOutputStream imageOutputStream = ImageIO.createImageOutputStream(stream);
            writer.setOutput(imageOutputStream);

            BufferedImage targetImage = requireImage();
            BufferedImage outputImage = targetImage;
            ImageWriteParam params = writer.getDefaultWriteParam();
            if ("jpg".equals(formatString)) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(((float) quality) / 100f);

                outputImage = new BufferedImage(targetImage.getWidth(), targetImage.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = outputImage.createGraphics();
                graphics.drawImage(targetImage, 0, 0, null);
                graphics.dispose();
            }

            writer.write(null, new IIOImage(outputImage, null, null), params);
            imageOutputStream.close();
            writer.dispose();
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        }

        return true;
    }

    public Bitmap copy(Config config, boolean isMutable) {
        BufferedImage source = requireImage();
        BufferedImage target = new BufferedImage(width, height, configToBufferedImageType(config));
        Graphics2D graphics = target.createGraphics();
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return new Bitmap(target);
    }

    private void checkPixelsAccess(int x, int y, int width, int height, int offset, int stride, int[] pixels) {
        checkXYSign(x, y);
        if (width < 0) {
            throw new IllegalArgumentException("width must be >= 0");
        }
        if (height < 0) {
            throw new IllegalArgumentException("height must be >= 0");
        }
        if (x + width > getWidth()) {
            throw new IllegalArgumentException("x + width must be <= bitmap.width()");
        }
        if (y + height > getHeight()) {
            throw new IllegalArgumentException("y + height must be <= bitmap.height()");
        }
        if (Math.abs(stride) < width) {
            throw new IllegalArgumentException("abs(stride) must be >= width");
        }
        int lastScanline = offset + (height - 1) * stride;
        int length = pixels.length;
        if (offset < 0 || (offset + width > length) || lastScanline < 0 || (lastScanline + width > length)) {
            throw new ArrayIndexOutOfBoundsException();
        }
    }

    private void checkPixelAccess(int x, int y) {
        checkXYSign(x, y);
        if (x >= getWidth()) {
            throw new IllegalArgumentException("x must be < bitmap.width()");
        }
        if (y >= getHeight()) {
            throw new IllegalArgumentException("y must be < bitmap.height()");
        }
    }

    public void getPixels(@ColorInt int[] pixels, int offset, int stride, int x, int y, int width, int height) {
        checkPixelsAccess(x, y, width, height, offset, stride, pixels);
        requireImage().getRGB(x, y, width, height, pixels, offset, stride);
    }

    @ColorInt
    public int getPixel(int x, int y) {
        checkPixelAccess(x, y);
        return requireImage().getRGB(x, y);
    }

    /**
     * <p>Write the specified {@link Color} into the bitmap (assuming it is
     * mutable) at the x,y coordinate. The color must be a
     * non-premultiplied ARGB value in the {@link ColorSpace.Named#SRGB sRGB}
     * color space.</p>
     *
     * @param x     The x coordinate of the pixel to replace (0...width-1)
     * @param y     The y coordinate of the pixel to replace (0...height-1)
     * @param color The ARGB color to write into the bitmap
     *
     * @throws IllegalStateException if the bitmap is not mutable
     * @throws IllegalArgumentException if x, y are outside of the bitmap's
     *         bounds.
     */
    public void setPixel(int x, int y, @ColorInt int color) {
        checkPixelAccess(x, y);
        ensureMutableImageBacking().setRGB(x, y, color);
    }

    /**
     * <p>Replace pixels in the bitmap with the colors in the array. Each element
     * in the array is a packed int representing a non-premultiplied ARGB
     * {@link Color} in the {@link ColorSpace.Named#SRGB sRGB} color space.</p>
     *
     * @param pixels   The colors to write to the bitmap
     * @param offset   The index of the first color to read from pixels[]
     * @param stride   The number of colors in pixels[] to skip between rows.
     *                 Normally this value will be the same as the width of
     *                 the bitmap, but it can be larger (or negative).
     * @param x        The x coordinate of the first pixel to write to in
     *                 the bitmap.
     * @param y        The y coordinate of the first pixel to write to in
     *                 the bitmap.
     * @param width    The number of colors to copy from pixels[] per row
     * @param height   The number of rows to write to the bitmap
     *
     * @throws IllegalStateException if the bitmap is not mutable
     * @throws IllegalArgumentException if x, y, width, height are outside of
     *         the bitmap's bounds.
     * @throws ArrayIndexOutOfBoundsException if the pixels array is too small
     *         to receive the specified number of pixels.
     */
    public void setPixels(@NonNull @ColorInt int[] pixels, int offset, int stride,
            int x, int y, int width, int height) {
        if (width == 0 || height == 0) {
            return; // nothing to do
        }
        checkPixelsAccess(x, y, width, height, offset, stride, pixels);
        ensureMutableImageBacking().setRGB(x, y, width, height, pixels, offset, stride);
    }

    public void eraseColor(int c) {
        java.awt.Color color = Color.valueOf(c).toJavaColor();
        Graphics2D graphics = ensureMutableImageBacking().createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
    }

    public void recycle() {
        // do nothing
    }

    @Nullable
    public final Config getConfig() {
        if (image == null && (nativeImageRef != null || nativeCanvasRef != null)) {
            return Config.ARGB_8888;
        }
        int type = requireImage().getType();
        return bufferedImageTypeToConfig(type);
    }

    private byte[] exportNativeCanvas(long canvasRef) {
        if (shouldUseNativeImage()) {
            try {
                return getImage(canvasRef, CompressFormat.PNG.nativeInt, 90);
            } catch (UnsatisfiedLinkError error) {
                disableNativeBridge(error);
            }
        }
        throw new IllegalStateException("Native canvas export requested without a native bridge");
    }

    private byte[] exportNativeImage(long imageRef) {
        if (shouldUseNativeImage()) {
            try {
                return compressImage(imageRef, CompressFormat.PNG.nativeInt, 90);
            } catch (UnsatisfiedLinkError error) {
                disableNativeBridge(error);
            }
        }
        throw new IllegalStateException("Native image export requested without a native bridge");
    }

    private void releaseNativeObject() {
        if (nativeImageRef != null) {
            long address = nativeImageRef.address();
            nativeImageRef.clear();
            nativeImageRef = null;
            if (address != 0 && shouldUseNativeImage()) {
                try {
                    releaseNativeImage(address);
                } catch (UnsatisfiedLinkError error) {
                    disableNativeBridge(error);
                }
            }
        }

        if (nativeCanvasRef != null) {
            long address = nativeCanvasRef.address();
            nativeCanvasRef.clear();
            nativeCanvasRef = null;
            if (address != 0 && shouldUseNativeImage()) {
                try {
                    releaseNativeCanvas(address);
                } catch (UnsatisfiedLinkError error) {
                    disableNativeBridge(error);
                }
            }
        }
    }

    @Override
    protected void finalize() {
        releaseNativeObject();
    }

    private native long[] createNativeImage(byte[] bytes);

    private native long createNativeCanvas(int width, int height);

    private native void drawBitmap(long imageRef, long canvasRef, int[] src, int[] dst);

    private native byte[] getImage(long canvasRef, int format, int quality);

    private native byte[] compressImage(long imageRef, int format, int quality);

    private native void releaseNativeImage(long imageRef);

    private native void releaseNativeCanvas(long canvasRef);
}
