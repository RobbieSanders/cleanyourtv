package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class WallpaperFramingTest {
    @Test public void portraitMovesDownWithoutExposingEdges() {
        assertEquals(-560f, WallpaperFraming.translation(1080f, 2200f, 0f), 0.01f);
        assertEquals(-140f, WallpaperFraming.translation(1080f, 2200f, 420f), 0.01f);
        assertEquals(0f, WallpaperFraming.translation(1080f, 2200f, 2000f), 0.01f);
        assertEquals(-1120f, WallpaperFraming.translation(1080f, 2200f, -2000f), 0.01f);
    }

    @Test public void exactFitCannotShift() {
        assertEquals(0f, WallpaperFraming.translation(1080f, 1080f, 400f), 0.01f);
    }
}
