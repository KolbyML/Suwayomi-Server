package android.widget;

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

public class Toast {
    public static final int LENGTH_LONG = 1;
    public static final int LENGTH_SHORT = 0;

    private CharSequence text;

    private static final ThreadLocal<java.util.ArrayList<String>> CAPTURE = new ThreadLocal<>();

    public static void beginCapture() {
        CAPTURE.set(new java.util.ArrayList<>());
    }

    public static java.util.List<String> endCapture() {
        java.util.ArrayList<String> captured = CAPTURE.get();
        CAPTURE.remove();
        if (captured == null) {
            return java.util.Collections.emptyList();
        }
        return captured;
    }

    private static void captureToast(CharSequence text) {
        java.util.ArrayList<String> captured = CAPTURE.get();
        if (captured == null) {
            return;
        }
        captured.add(text == null ? "" : text.toString());
    }

    private Toast(CharSequence text) {
        this.text = text;
    }

    public Toast(android.content.Context context) {
        throw new RuntimeException("Stub!");
    }

    public void show() {
        System.out.printf("made a Toast: \"%s\"\n", text == null ? "" : text.toString());
        captureToast(text);
    }

    public void cancel() {
        throw new RuntimeException("Stub!");
    }

    public void setView(android.view.View view) {
        throw new RuntimeException("Stub!");
    }

    public android.view.View getView() {
        throw new RuntimeException("Stub!");
    }

    public void setDuration(int duration) {
        throw new RuntimeException("Stub!");
    }

    public int getDuration() {
        throw new RuntimeException("Stub!");
    }

    public void setMargin(float horizontalMargin, float verticalMargin) {
        throw new RuntimeException("Stub!");
    }

    public float getHorizontalMargin() {
        throw new RuntimeException("Stub!");
    }

    public float getVerticalMargin() {
        throw new RuntimeException("Stub!");
    }

    public void setGravity(int gravity, int xOffset, int yOffset) {
        throw new RuntimeException("Stub!");
    }

    public int getGravity() {
        throw new RuntimeException("Stub!");
    }

    public int getXOffset() {
        throw new RuntimeException("Stub!");
    }

    public int getYOffset() {
        throw new RuntimeException("Stub!");
    }

    public static Toast makeText(android.content.Context context, java.lang.CharSequence text, int duration) {
        return new Toast(text);
    }

    public static android.widget.Toast makeText(android.content.Context context, int resId, int duration) throws android.content.res.Resources.NotFoundException {
        throw new RuntimeException("Stub!");
    }

    public void setText(int resId) {
        throw new RuntimeException("Stub!");
    }

    public void setText(java.lang.CharSequence s) {
        throw new RuntimeException("Stub!");
    }
}
