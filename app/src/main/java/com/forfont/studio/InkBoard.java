package com.forfont.studio;

import android.content.Context;
import android.graphics.*;
import android.os.Build;
import android.view.*;
import java.util.*;

final class InkBoard extends View {
  interface Listener {
    void changed();

    void referenceRequested();

    void penActivity();
  }

  final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  FontProject.Glyph glyph = new FontProject.Glyph(65), reference;
  FontProject.Stroke live;
  float width = 18;
  boolean eraser = false, ghost = false, inputEnabled = true, moveMode = false, guideEnabled = true;
  float guideOpacity = .18f, moveStartX, moveStartY, movedX, movedY;
  boolean moveGesture, stylusButtonPressed;
  static final int STYLUS_BUTTONS =
      MotionEvent.BUTTON_STYLUS_PRIMARY | MotionEvent.BUTTON_STYLUS_SECONDARY;
  final ArrayDeque<FontProject.Glyph> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
  final RectF writing = new RectF(), comparison = new RectF();
  Listener listener;
  int penId = -1;
  long penSeen = 0;
  FontProject.Glyph before;
  boolean eraseGesture = false;
  final int ink = Color.rgb(42, 42, 53), accent = Color.rgb(87, 80, 181);

  InkBoard(Context c) {
    super(c);
    setFocusable(true);
    setContentDescription("글자 필기 영역. S펜으로만 입력합니다. 왼쪽은 비교용 글자입니다.");
  }

  float dp(float n) {
    return n * getResources().getDisplayMetrics().density;
  }

  void setGlyph(FontProject.Glyph g) {
    cancel();
    glyph = g;
    moveMode = false;
    undo.clear();
    redo.clear();
    invalidate();
  }

  void cancel() {
    if (before != null) glyph = before;
    before = null;
    live = null;
    moveGesture = false;
    penId = -1;
    invalidate();
  }

  boolean penNearby() {
    return penId >= 0 || android.os.SystemClock.uptimeMillis() - penSeen < 350;
  }

  void checkpoint() {
    undo.push(glyph.copy());
    while (undo.size() > 40) undo.removeLast();
    redo.clear();
  }

  void undo() {
    if (penId >= 0) return;
    if (!undo.isEmpty()) {
      redo.push(glyph.copy());
      glyph = undo.pop();
      notifyChanged();
    }
  }

  void redo() {
    if (penId >= 0) return;
    if (!redo.isEmpty()) {
      undo.push(glyph.copy());
      glyph = redo.pop();
      notifyChanged();
    }
  }

  void clear() {
    if (glyph.strokes.isEmpty() || penId >= 0) return;
    checkpoint();
    glyph.strokes.clear();
    notifyChanged();
  }

  void notifyChanged() {
    invalidate();
    if (listener != null) listener.changed();
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    float margin = dp(14), side = dp(58), top = dp(68), bottom = dp(34);
    if (w >= dp(600)) {
      float size = Math.max(40, Math.min(w / 2f - side - 2 * margin, h - top - bottom));
      float y = top + (h - top - bottom - size) / 2;
      float left = side + (w / 2f - side - size) / 2;
      comparison.set(left, y, left + size, y + size);
      float right = w / 2f + (w / 2f - side - size) / 2;
      writing.set(right, y, right + size, y + size);
    } else {
      float ref = Math.min(dp(115), h * .22f);
      comparison.set(side + margin, dp(45), side + margin + ref, dp(45) + ref);
      float y = ref + dp(105);
      float size = Math.max(40, Math.min(w - side - 2 * margin, h - y - bottom));
      writing.set((w - side - size) / 2, y, (w - side + size) / 2, y + size);
    }
  }

  void text(Canvas c, String s, float x, float y, float size, int color) {
    paint.reset();
    paint.setAntiAlias(true);
    paint.setColor(color);
    paint.setTextSize(dp(size));
    paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    paint.setTextAlign(Paint.Align.LEFT);
    c.drawText(s, x, y, paint);
  }

  void guides(Canvas c, RectF r) {
    paint.reset();
    paint.setAntiAlias(true);
    paint.setColor(Color.WHITE);
    c.drawRoundRect(r, dp(12), dp(12), paint);
    c.save();
    c.translate(r.left, r.top);
    c.scale(r.width() / 1000, r.height() / 1000);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(1.4f * 1000 / r.width());
    float[] ys = {100, 300, 800, 950};
    for (float y : ys) {
      paint.setColor(y == 800 ? Color.rgb(139, 132, 197) : Color.rgb(224, 222, 234));
      paint.setPathEffect(y == 800 ? null : new DashPathEffect(new float[] {10, 10}, 0));
      c.drawLine(35, y, 965, y, paint);
    }
    paint.setPathEffect(new DashPathEffect(new float[] {8, 12}, 0));
    paint.setColor(Color.rgb(234, 232, 240));
    c.drawLine(100, 35, 100, 965, paint);
    c.drawLine(900, 35, 900, 965, paint);
    c.drawLine(500, 35, 500, 965, paint);
    paint.setPathEffect(null);
    c.restore();
  }

  void drawGlyph(Canvas c, RectF r, FontProject.Glyph g, int color) {
    if (g == null) return;
    c.save();
    c.clipRect(r);
    c.translate(r.left, r.top);
    c.scale(r.width() / 1000, r.height() / 1000);
    paint.reset();
    paint.setAntiAlias(true);
    paint.setColor(color);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
    for (FontProject.Stroke s : g.strokes) drawStroke(c, s);
    c.restore();
  }

  void drawStroke(Canvas c, FontProject.Stroke s) {
    if (s.points.isEmpty()) return;
    paint.setStrokeWidth(s.width);
    if (s.points.size() == 1) {
      FontProject.Point p = s.points.get(0);
      c.drawPoint(p.x, p.y, paint);
    } else c.drawPath(InkGeometry.center(s), paint);
  }

  @Override
  protected void onDraw(Canvas c) {
    super.onDraw(c);
    c.drawColor(Color.rgb(246, 245, 241));
    boolean wide = getWidth() >= dp(600);
    String compareTitle =
        "비교" + (reference == null ? "" : " · " + new String(Character.toChars(reference.code)));
    centeredText(
        c,
        compareTitle,
        comparison.centerX(),
        comparison.top - dp(18),
        18,
        Color.rgb(111, 108, 127));
    centeredText(
        c,
        new String(Character.toChars(glyph.code)),
        writing.centerX(),
        writing.top - dp(18),
        34,
        accent);
    guides(c, comparison);
    guides(c, writing);
    if (reference == null)
      text(
          c,
          "작성 후 비교",
          comparison.left + dp(12),
          comparison.centerY(),
          wide ? 17 : 10,
          Color.rgb(158, 155, 169));
    else drawGlyph(c, comparison, reference, ink);
    if (guideEnabled && guideOpacity > 0) drawStandardGlyph(c);
    if (ghost && reference != null) drawGlyph(c, writing, reference, Color.argb(42, 87, 80, 181));
    drawGlyph(c, writing, glyph, ink);
    if (live != null) {
      FontProject.Glyph g = new FontProject.Glyph(glyph.code);
      g.strokes.add(live);
      drawGlyph(c, writing, g, ink);
    }
    if (!wide)
      text(
          c,
          "눌러서 비교 글자 선택",
          comparison.right + dp(8),
          comparison.centerY(),
          10,
          Color.rgb(130, 127, 145));
    text(
        c,
        "보라색 기준선 · 점선은 높이와 폭 맞춤용",
        writing.left,
        writing.bottom + dp(23),
        10,
        Color.rgb(135, 131, 151));
  }

  void centeredText(Canvas c, String value, float x, float y, float size, int color) {
    paint.reset();
    paint.setAntiAlias(true);
    paint.setColor(color);
    paint.setTextSize(dp(size));
    paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    paint.setTextAlign(Paint.Align.CENTER);
    c.drawText(value, x, y, paint);
  }

  @Override
  public boolean onGenericMotionEvent(MotionEvent e) {
    if (e.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS
        && (e.getActionButton() & STYLUS_BUTTONS) != 0) {
      stylusButtonPressed = true;
      return true;
    }
    if (e.getActionMasked() == MotionEvent.ACTION_BUTTON_RELEASE
        && (e.getActionButton() & STYLUS_BUTTONS) != 0) {
      stylusButtonPressed = false;
      return true;
    }
    return super.onGenericMotionEvent(e);
  }

  boolean pen(MotionEvent e, int i) {
    int t = e.getToolType(i);
    return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER;
  }

  @Override
  public boolean onHoverEvent(MotionEvent e) {
    if (pen(e, 0)) {
      penSeen = android.os.SystemClock.uptimeMillis();
      stylusButtonPressed = (e.getButtonState() & STYLUS_BUTTONS) != 0;
    }
    return super.onHoverEvent(e);
  }

  @Override
  public boolean onTouchEvent(MotionEvent e) {
    if (!inputEnabled) return true;
    int action = e.getActionMasked(), ai = e.getActionIndex();
    if (action == MotionEvent.ACTION_CANCEL) {
      cancel();
      return true;
    }
    if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
      if (penId < 0 && pen(e, ai) && writing.contains(e.getX(ai), e.getY(ai))) {
        penId = e.getPointerId(ai);
        penSeen = android.os.SystemClock.uptimeMillis();
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        before = glyph.copy();
        boolean penEraser =
            stylusButtonPressed
                || (e.getButtonState() & STYLUS_BUTTONS) != 0
                || e.getToolType(ai) == MotionEvent.TOOL_TYPE_ERASER;
        moveGesture = moveMode && !penEraser;
        moveStartX = (e.getX(ai) - writing.left) * 1000 / writing.width();
        moveStartY = (e.getY(ai) - writing.top) * 1000 / writing.height();
        movedX = movedY = 0;
        eraseGesture = !moveGesture && (eraser || penEraser);
        if (!eraseGesture && !moveGesture) live = new FontProject.Stroke(width);
        add(e, ai, -1);
        if (listener != null) listener.penActivity();
      }
      return true;
    }
    int pi = penId < 0 ? -1 : e.findPointerIndex(penId);
    if (pi >= 0 && action == MotionEvent.ACTION_MOVE) {
      for (int h = 0; h < e.getHistorySize(); h++) add(e, pi, h);
      add(e, pi, -1);
      invalidate();
      return true;
    }
    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
      if (penId >= 0 && e.getPointerId(ai) == penId) {
        if (Build.VERSION.SDK_INT >= 33 && (e.getFlags() & MotionEvent.FLAG_CANCELED) != 0) {
          cancel();
          return true;
        }
        add(e, ai, -1);
        if (live != null && !live.points.isEmpty()) glyph.strokes.add(live);
        boolean changed = !moveGesture || movedX != 0 || movedY != 0;
        if (changed) {
          undo.push(before);
          while (undo.size() > 40) undo.removeLast();
          redo.clear();
        }
        before = null;
        live = null;
        penId = -1;
        moveGesture = false;
        stylusButtonPressed = (e.getButtonState() & STYLUS_BUTTONS) != 0;
        penSeen = android.os.SystemClock.uptimeMillis();
        notifyChanged();
      } else if (action == MotionEvent.ACTION_UP
          && !penNearby()
          && comparison.contains(e.getX(ai), e.getY(ai))
          && listener != null) listener.referenceRequested();
      return true;
    }
    return true; // Fingers and palms never create ink, even before the pen arrives.
  }

  void add(MotionEvent e, int i, int history) {
    float ex = history < 0 ? e.getX(i) : e.getHistoricalX(i, history),
        ey = history < 0 ? e.getY(i) : e.getHistoricalY(i, history);
    if (moveGesture) {
      translateFromStart(
          (ex - writing.left) * 1000 / writing.width() - moveStartX,
          (ey - writing.top) * 1000 / writing.height() - moveStartY);
      return;
    }
    float x = Math.max(35, Math.min(965, (ex - writing.left) * 1000 / writing.width())),
        y = Math.max(35, Math.min(965, (ey - writing.top) * 1000 / writing.height()));
    float p = history < 0 ? e.getPressure(i) : e.getHistoricalPressure(i, history);
    if (eraseGesture) {
      glyph.strokes.removeIf(s -> hit(s, x, y));
      return;
    }
    if (live == null) return;
    if (!live.points.isEmpty()) {
      FontProject.Point prev = live.points.get(live.points.size() - 1);
      if (Math.hypot(x - prev.x, y - prev.y) < 1) return;
    }
    live.points.add(new FontProject.Point(x, y, p));
    invalidate();
  }

  void drawStandardGlyph(Canvas c) {
    c.save();
    c.clipRect(writing);
    c.translate(writing.left, writing.top);
    c.scale(writing.width() / 1000, writing.height() / 1000);
    Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
    guide.setColor(Color.argb(Math.round(255 * guideOpacity), 95, 101, 119));
    guide.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    guide.setTextSize(1000);
    boolean korean = glyph.code >= 0x1100;
    Rect metrics = new Rect();
    String anchor = korean ? "가" : "H";
    guide.getTextBounds(anchor, 0, anchor.length(), metrics);
    float scale = 700f / Math.max(1, metrics.height());
    guide.setTextSize(1000 * scale);
    String value = new String(Character.toChars(glyph.code));
    float advance = guide.measureText(value);
    if (advance > 850) {
      float shrink = 850 / advance;
      guide.setTextSize(guide.getTextSize() * shrink);
      scale *= shrink;
    }
    float x = korean ? (1000 - guide.measureText(value)) / 2 : 100;
    float baseline = korean ? 800 - metrics.bottom * scale : 800;
    c.drawText(value, x, baseline, guide);
    c.restore();
  }

  void translateFromStart(float dx, float dy) {
    if (before == null || before.strokes.isEmpty()) return;
    float left = 1000, top = 1000, right = 0, bottom = 0;
    for (FontProject.Stroke stroke : before.strokes)
      for (FontProject.Point p : stroke.points) {
        float radius = stroke.width / 2;
        left = Math.min(left, p.x - radius);
        top = Math.min(top, p.y - radius);
        right = Math.max(right, p.x + radius);
        bottom = Math.max(bottom, p.y + radius);
      }
    dx = Math.max(Math.min(0, -left), Math.min(Math.max(0, 1000 - right), dx));
    dy = Math.max(Math.min(0, -top), Math.min(Math.max(0, 1000 - bottom), dy));
    movedX = dx;
    movedY = dy;
    glyph = before.copy();
    for (FontProject.Stroke stroke : glyph.strokes)
      for (int i = 0; i < stroke.points.size(); i++) {
        FontProject.Point p = stroke.points.get(i);
        stroke.points.set(i, new FontProject.Point(p.x + dx, p.y + dy, p.pressure));
      }
    invalidate();
  }

  float previewScale(FontProject.Glyph original, float requested) {
    RectF bounds = new RectF();
    boolean first = true;
    float maxWidth = 1, minWidth = 100;
    for (FontProject.Stroke stroke : original.strokes) {
      maxWidth = Math.max(maxWidth, stroke.width);
      minWidth = Math.min(minWidth, stroke.width);
      for (FontProject.Point p : stroke.points) {
        float r = stroke.width / 2;
        if (first) {
          bounds.set(p.x - r, p.y - r, p.x + r, p.y + r);
          first = false;
        } else {
          bounds.left = Math.min(bounds.left, p.x - r);
          bounds.right = Math.max(bounds.right, p.x + r);
          bounds.top = Math.min(bounds.top, p.y - r);
          bounds.bottom = Math.max(bounds.bottom, p.y + r);
        }
      }
    }
    if (first) return 1;
    float cx = bounds.centerX(), cy = bounds.centerY();
    float max = 100 / maxWidth;
    if (bounds.width() > 0) max = Math.min(max, 2 * Math.min(cx, 1000 - cx) / bounds.width());
    if (bounds.height() > 0) max = Math.min(max, 2 * Math.min(cy, 1000 - cy) / bounds.height());
    float scale = Math.max(1 / minWidth, Math.min(Math.max(.01f, max), requested));
    glyph = original.copy();
    for (FontProject.Stroke stroke : glyph.strokes) {
      stroke.width *= scale;
      for (int i = 0; i < stroke.points.size(); i++) {
        FontProject.Point p = stroke.points.get(i);
        stroke.points.set(
            i, new FontProject.Point(cx + (p.x - cx) * scale, cy + (p.y - cy) * scale, p.pressure));
      }
    }
    invalidate();
    return scale;
  }

  static boolean hit(FontProject.Stroke s, float x, float y) {
    float radius = 22 + s.width / 2;
    for (int n = 0; n < s.points.size(); n++) {
      FontProject.Point a = s.points.get(n), b = s.points.get(Math.max(0, n - 1));
      float dx = a.x - b.x,
          dy = a.y - b.y,
          t =
              (dx * dx + dy * dy) == 0
                  ? 0
                  : Math.max(
                      0, Math.min(1, ((x - b.x) * dx + (y - b.y) * dy) / (dx * dx + dy * dy)));
      if (Math.hypot(x - (b.x + t * dx), y - (b.y + t * dy)) <= radius) return true;
    }
    return false;
  }
}
