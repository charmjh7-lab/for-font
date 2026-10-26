package com.forfont.studio;

import static org.junit.Assert.*;

import android.graphics.*;
import android.os.Looper;
import android.view.*;
import java.io.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w1280dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StudioTest {
  MotionEvent event(int action, int tool, float x, float y, int flags) {
    MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
    pp.id = 0;
    pp.toolType = tool;
    MotionEvent.PointerCoords p = new MotionEvent.PointerCoords();
    p.x = x;
    p.y = y;
    p.pressure = 1;
    return MotionEvent.obtain(
        1,
        2,
        action,
        1,
        new MotionEvent.PointerProperties[] {pp},
        new MotionEvent.PointerCoords[] {p},
        0,
        0,
        1,
        1,
        0,
        0,
        InputDevice.SOURCE_STYLUS,
        flags);
  }

  void send(InkBoard b, int action, int tool, float x, float y, int flags) {
    MotionEvent e = event(action, tool, x, y, flags);
    b.onTouchEvent(e);
    e.recycle();
  }

  @Test
  public void onlyPenCreatesInkAndCancelledStrokeLeavesNothing() {
    InkBoard b = new InkBoard(RuntimeEnvironment.getApplication());
    b.layout(0, 0, 1200, 500);
    float x = b.writing.centerX(), y = b.writing.centerY();
    send(b, 0, MotionEvent.TOOL_TYPE_FINGER, x, y, 0);
    send(b, 1, MotionEvent.TOOL_TYPE_FINGER, x, y, 0);
    assertEquals(0, b.glyph.strokes.size());
    send(b, 0, MotionEvent.TOOL_TYPE_STYLUS, x, y, 0);
    send(b, 2, MotionEvent.TOOL_TYPE_STYLUS, x + 30, y + 20, 0);
    send(b, 3, MotionEvent.TOOL_TYPE_STYLUS, x, y, 0);
    assertEquals(0, b.glyph.strokes.size());
    send(b, 0, MotionEvent.TOOL_TYPE_STYLUS, x, y, 0);
    send(b, 1, MotionEvent.TOOL_TYPE_STYLUS, x + 20, y + 30, 0);
    assertEquals(1, b.glyph.strokes.size());
    b.undo();
    assertTrue(b.glyph.strokes.isEmpty());
    b.redo();
    assertEquals(1, b.glyph.strokes.size());
    assertEquals(b.writing.width(), b.comparison.width(), .01f);
    assertEquals(b.writing.top, b.comparison.top, .01f);
  }

  @Test
  public void realInkExportsAndSourceRoundTrips() throws Exception {
    FontProject p = new FontProject(RuntimeEnvironment.getApplication());
    FontProject.Glyph g = new FontProject.Glyph('가');
    FontProject.Stroke s = new FontProject.Stroke(24);
    // Closed loop checks contour holes as well as the stroke outline union path.
    for (int i = 0; i <= 60; i++) {
      double a = i * Math.PI * 2 / 60;
      s.points.add(
          new FontProject.Point(
              500 + (float) Math.cos(a) * 230, 450 + (float) Math.sin(a) * 230, 1));
    }
    g.strokes.add(s);
    p.accept(g);
    p.saveGlyph(g);
    FontProject loaded = new FontProject(RuntimeEnvironment.getApplication());
    assertEquals(s.points.size(), loaded.get('가').strokes.get(0).points.size());
    assertEquals(1, FontProject.parseBackup(p.backup()).size());
    byte[] source = FontProject.sourceBytes(new ArrayList<>(p.glyphs.values()), p.settings());
    assertEquals(1, FontProject.readSource(new ByteArrayInputStream(source)).glyphs.size());
    TtfWriter.Shape shape = InkGeometry.shape(g);
    assertEquals(2, shape.contours.size());
    File f = new File(RuntimeEnvironment.getApplication().getCacheDir(), "roundtrip.ttf");
    try (FileOutputStream out = new FileOutputStream(f)) {
      out.write(TtfWriter.write("테스트", Collections.singletonList(shape)));
    }
    Typeface face = Typeface.createFromFile(f);
    assertNotNull(face);
    Paint paint = new Paint();
    paint.setTypeface(face);
    paint.setTextSize(100);
    assertTrue(paint.hasGlyph("가"));
    paint.setTextSize(200);
    paint.setColor(Color.BLACK);
    paint.setAntiAlias(true);
    Bitmap raster = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888);
    raster.eraseColor(Color.WHITE);
    new Canvas(raster).drawText("가", 0, 160, paint);
    assertEquals(
        "The loop center must stay empty in the actual TTF", Color.WHITE, raster.getPixel(100, 90));
    assertEquals("The loop outline must remain visible", Color.BLACK, raster.getPixel(100, 44));
    File report = new File("build/reports/ink.ttf");
    report.getParentFile().mkdirs();
    try (FileOutputStream out = new FileOutputStream(report)) {
      out.write(TtfWriter.write("테스트", Collections.singletonList(shape)));
    }
  }

  @Test
  public void activityLoadsAndRendersTabletAndPhone() throws Exception {
    try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity a = controller.get();
      for (int n = 0; n < 200 && a.loading; n++) {
        Thread.sleep(10);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
      }
      assertNotNull(a.board);
      for (int[] size : new int[][] {{1280, 800}, {800, 1280}, {400, 850}}) {
        View v = a.getWindow().getDecorView();
        v.measure(
            View.MeasureSpec.makeMeasureSpec(size[0], View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size[1], View.MeasureSpec.EXACTLY));
        v.layout(0, 0, size[0], size[1]);
        assertTrue(a.board.writing.height() > 60);
        assertTrue(a.board.writing.bottom < a.board.getHeight());
        Bitmap image = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(image));
        File report = new File("build/reports/studio-" + size[0] + ".png");
        report.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(report)) {
          image.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
      }
    }
  }
}
