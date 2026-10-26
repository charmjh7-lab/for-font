package com.forfont.studio;

import java.nio.file.*;
import java.util.*;

/** Host smoke fixture for table/checksum/cmap validation with FontTools/FreeType. */
public final class FontSmoke {
  public static void main(String[] args) throws Exception {
    List<int[][]> ring =
        Arrays.asList(
            new int[][] {{100, 0}, {100, 700}, {700, 700}, {700, 0}},
            new int[][] {{200, 100}, {600, 100}, {600, 600}, {200, 600}});
    List<TtfWriter.Shape> s = new ArrayList<>();
    s.add(new TtfWriter.Shape('A', 800, ring));
    s.add(new TtfWriter.Shape('a', 800, ring));
    for (int c = 0xAC00; c <= 0xD7A3; c++) s.add(new TtfWriter.Shape(c, 1000, ring));
    Files.write(Path.of(args[0]), TtfWriter.write("나의 글씨 My Hand", s));
  }
}
