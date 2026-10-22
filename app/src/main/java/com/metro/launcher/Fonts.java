package com.metro.launcher;

import android.graphics.Typeface;

import java.io.File;

/** Google Sans ships in the TV's system fonts; use it directly instead of bundling a font. */
final class Fonts {
    static final Typeface REG = load("Google_Sans-400-100_0-0_0.ttf", Typeface.create("sans-serif", Typeface.NORMAL));
    static final Typeface MED = load("Google_Sans-500-100_0-0_0.ttf", Typeface.create("sans-serif-medium", Typeface.NORMAL));

    private static Typeface load(String name, Typeface fallback) {
        for (String dir : new String[]{"/system/fonts/", "/product/fonts/"}) {
            File f = new File(dir, name);
            if (f.exists()) {
                try { return Typeface.createFromFile(f); } catch (RuntimeException ignored) { }
            }
        }
        return fallback;
    }

    private Fonts() { }
}
