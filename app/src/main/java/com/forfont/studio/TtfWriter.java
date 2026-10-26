package com.forfont.studio;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Dependency-free OpenType/TrueType serializer. Coordinates are font units, y-up. */
final class TtfWriter {
  static final class Shape {
    final int code, advance;
    final List<int[][]> contours;
    int xMin = 0, yMin = 0, xMax = 0, yMax = 0, points = 0;

    Shape(int cp, int advance, List<int[][]> contours) {
      code = cp;
      this.advance = advance;
      this.contours = contours;
      boolean first = true;
      for (int[][] contour : contours)
        for (int[] p : contour) {
          if (first) {
            xMin = xMax = p[0];
            yMin = yMax = p[1];
            first = false;
          }
          xMin = Math.min(xMin, p[0]);
          xMax = Math.max(xMax, p[0]);
          yMin = Math.min(yMin, p[1]);
          yMax = Math.max(yMax, p[1]);
          points++;
        }
    }
  }

  static final class Bytes extends ByteArrayOutputStream {
    void u16(int n) {
      write(n >>> 8);
      write(n);
    }

    void u32(long n) {
      u16((int) (n >>> 16));
      u16((int) n);
    }

    void u64(long n) {
      u32(n >>> 32);
      u32(n);
    }

    void raw(byte[] data) {
      write(data, 0, data.length);
    }

    void zeros(int n) {
      for (int i = 0; i < n; i++) write(0);
    }

    void pad() {
      while (size() % 4 != 0) write(0);
    }
  }

  static long checksum(byte[] a) {
    long sum = 0;
    for (int i = 0; i < a.length; i += 4) {
      long n = 0;
      for (int j = 0; j < 4; j++) n = (n << 8) | (i + j < a.length ? a[i + j] & 255 : 0);
      sum = (sum + n) & 0xFFFFFFFFL;
    }
    return sum;
  }

  static byte[] glyph(Shape s) throws IOException {
    if (s.points == 0) return new byte[0];
    if (s.points > 65535 || s.contours.size() > 32767) throw new IOException("글자 윤곽이 너무 복잡합니다.");
    Bytes b = new Bytes();
    b.u16(s.contours.size());
    b.u16(s.xMin);
    b.u16(s.yMin);
    b.u16(s.xMax);
    b.u16(s.yMax);
    int end = -1;
    for (int[][] c : s.contours) {
      end += c.length;
      b.u16(end);
    }
    b.u16(0);
    for (int i = 0; i < s.points; i++) b.write(1); // All sampled outline points are on-curve.
    int prev = 0;
    for (int[][] c : s.contours)
      for (int[] p : c) {
        b.u16(p[0] - prev);
        prev = p[0];
      }
    prev = 0;
    for (int[][] c : s.contours)
      for (int[] p : c) {
        b.u16(p[1] - prev);
        prev = p[1];
      }
    b.pad();
    return b.toByteArray();
  }

  static byte[] write(String family, List<Shape> input) throws IOException {
    ArrayList<Shape> glyphs = new ArrayList<>();
    glyphs.add(
        new Shape(
            0,
            700,
            Arrays.asList(
                new int[][] {{100, 0}, {100, 700}, {600, 700}, {600, 0}},
                new int[][] {{180, 80}, {520, 80}, {520, 620}, {180, 620}})));
    glyphs.add(new Shape(32, 350, Collections.emptyList()));
    TreeMap<Integer, Shape> sorted = new TreeMap<>();
    for (Shape s : input) {
      if (s.code >= 33 && s.code <= 0xFFFF && !s.contours.isEmpty()) sorted.put(s.code, s);
    }
    glyphs.addAll(sorted.values());
    if (glyphs.size() > 65535) throw new IOException("글자 수가 너무 많습니다.");
    TreeMap<String, byte[]> tables = new TreeMap<>();
    Bytes glyf = new Bytes(), loca = new Bytes(), hmtx = new Bytes();
    int xMin = 0,
        yMin = -200,
        xMax = 1000,
        yMax = 800,
        maxPoints = 0,
        maxContours = 0,
        advanceMax = 0,
        minLsb = 32767,
        minRsb = 32767,
        maxExtent = 0;
    for (Shape s : glyphs) {
      loca.u32(glyf.size());
      glyf.raw(glyph(s));
      hmtx.u16(s.advance);
      hmtx.u16(s.xMin);
      xMin = Math.min(xMin, s.xMin);
      yMin = Math.min(yMin, s.yMin);
      xMax = Math.max(xMax, s.xMax);
      yMax = Math.max(yMax, s.yMax);
      maxPoints = Math.max(maxPoints, s.points);
      maxContours = Math.max(maxContours, s.contours.size());
      advanceMax = Math.max(advanceMax, s.advance);
      minLsb = Math.min(minLsb, s.xMin);
      minRsb = Math.min(minRsb, s.advance - s.xMax);
      maxExtent = Math.max(maxExtent, s.xMax);
    }
    loca.u32(glyf.size());
    tables.put("glyf", glyf.toByteArray());
    tables.put("loca", loca.toByteArray());
    tables.put("hmtx", hmtx.toByteArray());
    Bytes head = new Bytes();
    head.u32(0x10000);
    head.u32(0x10000);
    head.u32(0);
    head.u32(0x5F0F3CF5);
    head.u16(3);
    head.u16(1000);
    long date = System.currentTimeMillis() / 1000 + 2082844800L;
    head.u64(date);
    head.u64(date);
    head.u16(xMin);
    head.u16(yMin);
    head.u16(xMax);
    head.u16(yMax);
    head.u16(0);
    head.u16(8);
    head.u16(2);
    head.u16(1);
    head.u16(0);
    tables.put("head", head.toByteArray());
    Bytes hhea = new Bytes();
    hhea.u32(0x10000);
    hhea.u16(800);
    hhea.u16(-200);
    hhea.u16(100);
    hhea.u16(advanceMax);
    hhea.u16(minLsb);
    hhea.u16(minRsb);
    hhea.u16(maxExtent);
    hhea.u16(1);
    hhea.u16(0);
    hhea.u16(0);
    hhea.zeros(8);
    hhea.u16(0);
    hhea.u16(glyphs.size());
    tables.put("hhea", hhea.toByteArray());
    Bytes maxp = new Bytes();
    maxp.u32(0x10000);
    maxp.u16(glyphs.size());
    maxp.u16(maxPoints);
    maxp.u16(maxContours);
    maxp.u16(0);
    maxp.u16(0);
    maxp.u16(1);
    maxp.zeros(16);
    tables.put("maxp", maxp.toByteArray());
    Bytes os = new Bytes();
    os.u16(4);
    os.u16(700);
    os.u16(400);
    os.u16(5);
    os.u16(0);
    int[] metrics = {650, 600, 0, 75, 650, 600, 0, 350, 50, 300, 0};
    for (int n : metrics) os.u16(n);
    os.zeros(10);
    os.u32(1);
    os.u32(1L << 24);
    os.u32(0);
    os.u32(0);
    os.raw("FFNT".getBytes(StandardCharsets.US_ASCII));
    os.u16(0xC0);
    os.u16(32);
    os.u16(glyphs.get(glyphs.size() - 1).code);
    os.u16(800);
    os.u16(-200);
    os.u16(100);
    os.u16(Math.max(800, yMax));
    os.u16(Math.max(200, -yMin));
    os.u32((1L << 19) | 1);
    os.u32(0);
    os.u16(500);
    os.u16(700);
    os.u16(0);
    os.u16(32);
    os.u16(0);
    tables.put("OS/2", os.toByteArray());
    tables.put("cmap", cmap(glyphs));
    tables.put("name", names(family));
    Bytes post = new Bytes();
    post.u32(0x30000);
    post.u32(0);
    post.u16(-80);
    post.u16(50);
    post.zeros(20);
    tables.put("post", post.toByteArray());
    Bytes gasp = new Bytes();
    gasp.u16(1);
    gasp.u16(1);
    gasp.u16(65535);
    gasp.u16(15);
    tables.put("gasp", gasp.toByteArray());
    Bytes out = new Bytes();
    int num = tables.size(), pow = Integer.highestOneBit(num);
    out.u32(0x10000);
    out.u16(num);
    out.u16(pow * 16);
    out.u16(Integer.numberOfTrailingZeros(pow));
    out.u16(num * 16 - pow * 16);
    int offset = 12 + num * 16, headOffset = 0;
    for (Map.Entry<String, byte[]> t : tables.entrySet()) {
      out.raw(t.getKey().getBytes(StandardCharsets.US_ASCII));
      out.u32(checksum(t.getValue()));
      out.u32(offset);
      out.u32(t.getValue().length);
      if (t.getKey().equals("head")) headOffset = offset;
      offset += (t.getValue().length + 3) & ~3;
    }
    for (byte[] data : tables.values()) {
      out.raw(data);
      out.pad();
    }
    byte[] font = out.toByteArray();
    long adjustment = (0xB1B0AFBAL - checksum(font)) & 0xFFFFFFFFL;
    for (int j = 0; j < 4; j++) font[headOffset + 8 + j] = (byte) (adjustment >>> (24 - 8 * j));
    return font;
  }

  static byte[] cmap(List<Shape> glyphs) {
    ArrayList<int[]> runs = new ArrayList<>();
    for (int id = 1; id < glyphs.size(); id++) {
      int cp = glyphs.get(id).code;
      int[] last = runs.isEmpty() ? null : runs.get(runs.size() - 1);
      if (last != null && cp == last[1] + 1) {
        last[1] = cp;
      } else runs.add(new int[] {cp, cp, id});
    }
    // Format 12 contains the complete map. Format 4 is a BMP compatibility map.
    int runCount = Math.min(runs.size(), 8188),
        seg = runCount + 1,
        pow = Integer.highestOneBit(seg);
    Bytes f4 = new Bytes();
    f4.u16(4);
    f4.u16(16 + 8 * seg);
    f4.u16(0);
    f4.u16(2 * seg);
    f4.u16(pow * 2);
    f4.u16(Integer.numberOfTrailingZeros(pow));
    f4.u16(seg * 2 - pow * 2);
    for (int i = 0; i < runCount; i++) f4.u16(runs.get(i)[1]);
    f4.u16(65535);
    f4.u16(0);
    for (int i = 0; i < runCount; i++) f4.u16(runs.get(i)[0]);
    f4.u16(65535);
    for (int i = 0; i < runCount; i++) {
      int[] r = runs.get(i);
      f4.u16(r[2] - r[0]);
    }
    f4.u16(1);
    f4.zeros(seg * 2);
    Bytes f12 = new Bytes();
    f12.u16(12);
    f12.u16(0);
    f12.u32(16 + 12 * runs.size());
    f12.u32(0);
    f12.u32(runs.size());
    for (int[] r : runs) {
      f12.u32(r[0]);
      f12.u32(r[1]);
      f12.u32(r[2]);
    }
    Bytes b = new Bytes();
    b.u16(0);
    b.u16(3);
    b.u16(0);
    b.u16(4);
    b.u32(28 + f4.size());
    b.u16(3);
    b.u16(1);
    b.u32(28);
    b.u16(3);
    b.u16(10);
    b.u32(28 + f4.size());
    b.raw(f4.toByteArray());
    b.raw(f12.toByteArray());
    return b.toByteArray();
  }

  static byte[] names(String family) {
    family = family == null || family.trim().isEmpty() ? "My Hand" : family.trim();
    if (family.length() > 80) family = family.substring(0, 80);
    String ps = family.replaceAll("[^A-Za-z0-9-]", "");
    if (ps.isEmpty()) ps = "ForFont";
    if (ps.length() > 48) ps = ps.substring(0, 48);
    String[] strings = {
      "Created with For Font", family, "Regular", ps + ";1.0", family, "Version 1.000", ps
    };
    Bytes records = new Bytes(), data = new Bytes();
    for (int i = 0; i < strings.length; i++) {
      byte[] s = strings[i].getBytes(StandardCharsets.UTF_16BE);
      records.u16(3);
      records.u16(1);
      records.u16(0x409);
      records.u16(i);
      records.u16(s.length);
      records.u16(data.size());
      data.raw(s);
    }
    Bytes b = new Bytes();
    b.u16(0);
    b.u16(strings.length);
    b.u16(6 + records.size());
    b.raw(records.toByteArray());
    b.raw(data.toByteArray());
    return b.toByteArray();
  }
}
