package com.forfont.studio;

import android.graphics.*;
import java.util.*;

final class InkGeometry {
  static Path center(FontProject.Stroke s) {
    Path p = new Path();
    if (s.points.isEmpty()) return p;
    FontProject.Point first = s.points.get(0);
    p.moveTo(first.x, first.y);
    for (int i = 1; i < s.points.size(); i++) {
      FontProject.Point a = s.points.get(i - 1), b = s.points.get(i);
      p.quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2);
    }
    FontProject.Point last = s.points.get(s.points.size() - 1);
    p.lineTo(last.x, last.y);
    return p;
  }

  static Path outline(FontProject.Glyph g) throws java.io.IOException {
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
    Path combined = new Path();
    for (FontProject.Stroke s : g.strokes) {
      Path fill = new Path();
      if (s.points.size() == 1) {
        FontProject.Point p = s.points.get(0);
        fill.addCircle(p.x, p.y, s.width / 2, Path.Direction.CW);
      } else {
        paint.setStrokeWidth(s.width);
        paint.getFillPath(center(s), fill);
      }
      if (!combined.op(fill, Path.Op.UNION)) throw new java.io.IOException("획 윤곽을 합칠 수 없습니다.");
    }
    return combined;
  }

  static TtfWriter.Shape shape(FontProject.Glyph g) throws Exception {
    Path path = outline(g);
    PathMeasure measure = new PathMeasure(path, false);
    float total = 0;
    do {
      total += measure.getLength();
    } while (measure.nextContour());
    measure.setPath(path, false);
    ArrayList<int[][]> contours = new ArrayList<>();
    float[] p = new float[2];
    float step = Math.max(2f, total / 24000f);
    do {
      float length = measure.getLength();
      if (length <= 0) continue;
      int count = Math.max(4, (int) Math.ceil(length / step));
      ArrayList<int[]> points = new ArrayList<>();
      int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE;
      for (int i = 0; i < count; i++) {
        measure.getPosTan(length * i / count, p, null);
        int x = Math.round(p[0]), y = 800 - Math.round(p[1]);
        if (x != lastX || y != lastY) {
          points.add(new int[] {x, y});
          lastX = x;
          lastY = y;
        }
      }
      if (points.size() > 2) contours.add(points.toArray(new int[0][]));
    } while (measure.nextContour());
    orientContours(contours);
    int advance = 1000;
    if (g.code < 0x1100) {
      float right = 0;
      for (FontProject.Stroke s : g.strokes)
        for (FontProject.Point point : s.points) right = Math.max(right, point.x + s.width / 2);
      advance = Math.max(200, Math.min(1100, Math.round(right + 70)));
    }
    return new TtfWriter.Shape(g.code, advance, contours);
  }

  // Skia boolean operations can produce even-odd contours with equal winding.
  // TrueType uses non-zero winding: outer contours clockwise, holes counter-clockwise.
  static void orientContours(List<int[][]> contours) {
    for (int[][] contour : contours) {
      int depth = 0;
      for (int[][] other : contours) {
        if (other != contour && contains(other, contour[0][0], contour[0][1])) depth++;
      }
      long area = 0;
      for (int i = 0; i < contour.length; i++) {
        int[] a = contour[i], b = contour[(i + 1) % contour.length];
        area += (long) a[0] * b[1] - (long) b[0] * a[1];
      }
      if ((area < 0) != (depth % 2 == 0)) {
        for (int l = 0, r = contour.length - 1; l < r; l++, r--) {
          int[] point = contour[l];
          contour[l] = contour[r];
          contour[r] = point;
        }
      }
    }
  }

  static boolean contains(int[][] polygon, float x, float y) {
    boolean inside = false;
    for (int i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
      int[] a = polygon[i], b = polygon[j];
      if ((a[1] > y) != (b[1] > y) && x < (b[0] - a[0]) * (y - a[1]) / (float) (b[1] - a[1]) + a[0])
        inside = !inside;
    }
    return inside;
  }
}
