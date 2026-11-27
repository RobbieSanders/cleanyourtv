package com.gathin.tvhome;

import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import org.json.JSONObject;

import java.io.File;

/** Optional, non-destructive framing for theme wallpapers. Offsets use a 1920x1080 reference screen. */
final class WallpaperFraming {
    private final JSONObject defaults;
    private final JSONObject overrides;

    WallpaperFraming(JSONObject theme) {
        defaults = theme;
        overrides = theme == null ? null : theme.optJSONObject("wallpaper_framing");
    }

    private static double number(JSONObject object, String key, double fallback, double min, double max) {
        if (object == null) return fallback;
        double value = object.optDouble(key, fallback);
        return Double.isNaN(value) || Double.isInfinite(value) ? fallback : Math.max(min, Math.min(max, value));
    }

    private double value(JSONObject photo, String key, double fallback, double min, double max) {
        return number(photo, key, number(defaults, key, fallback, min, max), min, max);
    }

    /** Positive offsets move the picture down/right. Clamp to the available crop so no empty edges appear. */
    static float translation(float viewport, float scaled, float offset) {
        return Math.max(Math.min(0f, viewport - scaled), Math.min(0f, (viewport - scaled) / 2f + offset));
    }

    void apply(ImageView view, File file) {
        JSONObject photo = overrides == null || file == null ? null : overrides.optJSONObject(file.getName());
        double zoom = value(photo, "wallpaper_scale", 1d, 1d, 3d);
        double offsetX = value(photo, "wallpaper_offset_x", 0d, -3840d, 3840d);
        double offsetY = value(photo, "wallpaper_offset_y", 0d, -2160d, 2160d);
        if (zoom == 1d && offsetX == 0d && offsetY == 0d) {
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
            return;
        }
        Drawable drawable = view.getDrawable();
        int w = view.getWidth() - view.getPaddingLeft() - view.getPaddingRight();
        int h = view.getHeight() - view.getPaddingTop() - view.getPaddingBottom();
        if (drawable == null || w <= 0 || h <= 0) return;
        int dw = drawable.getIntrinsicWidth();
        int dh = drawable.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) {
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
            return;
        }
        float scale = (float) (Math.max(w / (double) dw, h / (double) dh) * zoom);
        float x = translation(w, dw * scale, (float) (offsetX * w / 1920d));
        float y = translation(h, dh * scale, (float) (offsetY * h / 1080d));
        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate(x, y);
        view.setScaleType(ImageView.ScaleType.MATRIX);
        view.setImageMatrix(matrix);
    }
}
