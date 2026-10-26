package com.forfont.studio;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class MainActivity extends Activity implements InkBoard.Listener {
  final ExecutorService worker = Executors.newSingleThreadExecutor();
  final Handler main = new Handler(Looper.getMainLooper());
  FontProject project;
  InkBoard board;
  TextView title, target, detail, progress, status, penLabel;
  Button undo, redo, erase, ghost, move;
  Spinner groups;
  LinearLayout root;
  boolean loading = true, busy = false, ignoreGroup = false, restoring = false;
  byte[] pendingExport;
  String pendingType;
  final int accent = Color.rgb(87, 80, 181),
      textColor = Color.rgb(42, 42, 53),
      muted = Color.rgb(117, 113, 133),
      paper = Color.rgb(246, 245, 241);
  final ArrayList<int[]> sets = new ArrayList<>();
  final String[] labels = {
    "영어 대문자 · 26",
    "영어 소문자 · 26",
    "숫자 · 10",
    "기호 · 32",
    "한글 시작 · 114",
    "한글 전체 · 11,172",
    "한글 자모 · 51"
  };

  int dp(float n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
    getWindow().setStatusBarColor(paper);
    getWindow().setNavigationBarColor(paper);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    makeSets();
    TextView start = label("For Font\n작업을 불러오는 중…", 22, textColor);
    start.setGravity(Gravity.CENTER);
    start.setBackgroundColor(paper);
    setContentView(start);
    worker.execute(
        () -> {
          try {
            FontProject.recover(this);
            FontProject p = new FontProject(this);
            main.post(
                () -> {
                  project = p;
                  loading = false;
                  buildUi();
                });
          } catch (Exception e) {
            main.post(
                () ->
                    new AlertDialog.Builder(this)
                        .setTitle("작업을 불러올 수 없습니다")
                        .setMessage("기존 데이터를 덮어쓰지 않았습니다.\n" + e.getMessage())
                        .setPositiveButton("닫기", (d, w) -> finish())
                        .setCancelable(false)
                        .show());
          }
        });
  }

  void makeSets() {
    sets.add(range('A', 'Z'));
    sets.add(range('a', 'z'));
    sets.add(range('0', '9'));
    ArrayList<Integer> punct = new ArrayList<>();
    for (int i = 33; i <= 126; i++) if (!Character.isLetterOrDigit(i)) punct.add(i);
    sets.add(punct.stream().mapToInt(i -> i).toArray());
    ArrayList<Integer> basic = new ArrayList<>();
    for (int initial = 0; initial < 19; initial++)
      for (int vowel : new int[] {0, 4, 8, 13, 18, 20})
        basic.add(0xAC00 + (initial * 21 + vowel) * 28);
    sets.add(basic.stream().mapToInt(i -> i).toArray());
    sets.add(range(0xAC00, 0xD7A3));
    sets.add(range(0x3131, 0x3163));
  }

  int[] range(int a, int b) {
    int[] out = new int[b - a + 1];
    for (int i = 0; i < out.length; i++) out[i] = a + i;
    return out;
  }

  TextView label(String s, int size, int color) {
    TextView v = new TextView(this);
    v.setText(s);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setGravity(Gravity.CENTER_VERTICAL);
    v.setFontFeatureSettings("tnum");
    return v;
  }

  GradientDrawable shape(int color, int stroke) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(12));
    if (stroke != 0) d.setStroke(dp(1), stroke);
    return d;
  }

  Button button(String s, boolean primary, Runnable action) {
    Button b = new Button(this);
    b.setText(s);
    b.setTextSize(13);
    b.setAllCaps(false);
    b.setTextColor(primary ? Color.WHITE : accent);
    b.setBackground(shape(primary ? accent : Color.WHITE, 0));
    b.setMinHeight(0);
    b.setMinimumHeight(0);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setPadding(dp(11), 0, dp(11), 0);
    b.setStateListAnimator(null);
    b.setContentDescription(s);
    b.setOnClickListener(
        v -> {
          if (!busy && !loading && board != null && board.penId < 0) action.run();
        });
    return b;
  }

  LinearLayout row() {
    LinearLayout r = new LinearLayout(this);
    r.setGravity(Gravity.CENTER_VERTICAL);
    r.setOrientation(LinearLayout.HORIZONTAL);
    return r;
  }

  void addButton(LinearLayout r, Button b, int weight) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(43), weight);
    p.setMargins(dp(3), 0, dp(3), 0);
    r.addView(b, p);
  }

  void buildUi() {
    root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(paper);
    root.setPadding(dp(12), dp(10), dp(12), dp(10));
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          int l, r, t, b;
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets s =
                insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            l = s.left;
            r = s.right;
            t = s.top;
            b = s.bottom;
          } else {
            l = insets.getSystemWindowInsetLeft();
            r = insets.getSystemWindowInsetRight();
            t = insets.getSystemWindowInsetTop();
            b = insets.getSystemWindowInsetBottom();
          }
          v.setPadding(l + dp(12), t + dp(8), r + dp(12), b + dp(8));
          return insets;
        });
    LinearLayout header = row();
    title = label("For Font", 24, textColor);
    title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    header.addView(title, new LinearLayout.LayoutParams(0, dp(49), 1));
    header.addView(
        button("문장 보기", false, this::preview), new LinearLayout.LayoutParams(dp(88), dp(40)));
    Button menu = button("내보내기", true, this::exportMenu);
    LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(93), dp(40));
    mp.leftMargin = dp(8);
    header.addView(menu, mp);
    root.addView(header);
    TextView projectName = label(project.name + "   ✎", 13, muted);
    projectName.setPadding(dp(2), 0, 0, dp(5));
    projectName.setOnClickListener(
        v -> {
          if (!busy) rename();
        });
    projectName.setContentDescription("글꼴 이름 변경: " + project.name);
    root.addView(projectName, new LinearLayout.LayoutParams(-1, dp(29)));
    LinearLayout groupRow = row();
    groups = new Spinner(this);
    ArrayAdapter<String> adapter =
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    groups.setAdapter(adapter);
    project.group = Math.max(0, Math.min(sets.size() - 1, project.group));
    project.index = Math.max(0, Math.min(sets.get(project.group).length - 1, project.index));
    groups.setSelection(project.group);
    groupRow.addView(groups, new LinearLayout.LayoutParams(0, dp(47), 1));
    groupRow.addView(
        button("글자 찾기", false, () -> pickGlyph(false)),
        new LinearLayout.LayoutParams(dp(95), dp(40)));
    root.addView(groupRow);
    LinearLayout prompt = row();
    target = label("", 46, accent);
    target.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    target.setGravity(Gravity.CENTER);
    prompt.addView(target, new LinearLayout.LayoutParams(dp(100), dp(77)));
    LinearLayout caption = new LinearLayout(this);
    caption.setOrientation(LinearLayout.VERTICAL);
    TextView instruction = label("위 글자를 아래에 써 주세요", 16, textColor);
    caption.addView(instruction);
    detail = label("", 12, muted);
    caption.addView(detail);
    prompt.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));
    prompt.addView(
        button("기본 글자", false, this::guideOptions), new LinearLayout.LayoutParams(dp(82), dp(39)));
    root.addView(prompt);
    board = new InkBoard(this);
    board.listener = this;
    board.width = project.penWidth;
    board.ghost = project.ghost;
    board.guideEnabled = project.guideEnabled;
    board.guideOpacity = project.guideOpacity;
    root.addView(board, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout tools = row();
    undo = button("↶ 취소", false, () -> board.undo());
    redo = button("↷ 복원", false, () -> board.redo());
    erase =
        button(
            "지우개",
            false,
            () -> {
              board.eraser = !board.eraser;
              board.moveMode = false;
              updateTools();
            });
    addButton(tools, undo, 1);
    addButton(tools, redo, 1);
    addButton(tools, erase, 1);
    move =
        button(
            "이동",
            false,
            () -> {
              if (board.glyph.strokes.isEmpty()) {
                toast("먼저 글자를 써 주세요.");
                return;
              }
              board.moveMode = !board.moveMode;
              board.eraser = false;
              updateTools();
              status.setText(board.moveMode ? "S펜으로 끌어서 글자 전체 이동" : "S펜으로 필기");
            });
    addButton(tools, move, 1);
    addButton(tools, button("비우기", false, this::clearGlyph), 1);
    root.addView(tools, new LinearLayout.LayoutParams(-1, dp(49)));
    LinearLayout options = row();
    penLabel = label("굵기 " + Math.round(project.penWidth), 12, muted);
    options.addView(penLabel, new LinearLayout.LayoutParams(dp(60), dp(40)));
    SeekBar width = new SeekBar(this);
    width.setMax(48);
    width.setProgress(Math.round(project.penWidth) - 6);
    width.setProgressTintList(ColorStateList.valueOf(accent));
    width.setThumbTintList(ColorStateList.valueOf(accent));
    options.addView(width, new LinearLayout.LayoutParams(0, dp(40), 1));
    width.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar s, int p, boolean user) {
            if (board.penId >= 0) return;
            board.width = project.penWidth = p + 6;
            penLabel.setText("굵기 " + (p + 6));
          }

          public void onStartTrackingTouch(SeekBar s) {}

          public void onStopTrackingTouch(SeekBar s) {
            saveSettings();
          }
        });
    ghost =
        button(
            "겹쳐 보기",
            false,
            () -> {
              project.ghost = board.ghost = !board.ghost;
              board.invalidate();
              updateTools();
              saveSettings();
            });
    options.addView(ghost, new LinearLayout.LayoutParams(dp(92), dp(37)));
    options.addView(
        button("비교", false, () -> pickGlyph(true)), new LinearLayout.LayoutParams(dp(55), dp(37)));
    root.addView(options);
    LinearLayout nav = row();
    addButton(nav, button("이전", false, () -> move(-1)), 1);
    addButton(nav, button("안 쓴 글자", false, this::nextEmpty), 2);
    addButton(nav, button("다음 →", true, () -> move(1)), 2);
    root.addView(nav, new LinearLayout.LayoutParams(-1, dp(51)));
    LinearLayout footer = row();
    progress = label("", 11, muted);
    status = label("", 11, muted);
    status.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
    footer.addView(progress, new LinearLayout.LayoutParams(0, dp(27), 1));
    footer.addView(status, new LinearLayout.LayoutParams(0, dp(27), 1));
    root.addView(footer);
    setContentView(root);
    root.requestApplyInsets();
    showGlyph();
    groups.setOnItemSelectedListener(
        new android.widget.AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(android.widget.AdapterView<?> p) {}

          public void onItemSelected(
              android.widget.AdapterView<?> p, View v, int position, long id) {
            if (ignoreGroup || position == project.group) return;
            if (busy || board.penId >= 0) {
              ignoreGroup = true;
              groups.setSelection(project.group);
              ignoreGroup = false;
              return;
            }
            project.group = position;
            project.index = 0;
            showGlyph();
            saveSettings();
          }
        });
  }

  int code() {
    return sets.get(project.group)[project.index];
  }

  void showGlyph() {
    board.setGlyph(project.get(code()));
    target.setText(new String(Character.toChars(code())));
    detail.setText(code() < 0x1100 ? "왼쪽 점선부터 쓰기 · S펜 전용" : "가운데에 맞춰 쓰기 · S펜 전용");
    updateReference();
    updateProgress();
    updateTools();
  }

  void updateProgress() {
    int count = 0;
    for (int cp : sets.get(project.group)) if (project.glyphs.containsKey(cp)) count++;
    progress.setText(
        (project.index + 1) + " / " + sets.get(project.group).length + "   ·   " + count + "자 작성");
  }

  void updateReference() {
    FontProject.Glyph ref = null;
    if (project.reference >= 0) ref = project.glyphs.get(project.reference);
    if (ref == null) {
      int[] set = sets.get(project.group);
      for (int i = project.index - 1; i >= 0; i--)
        if (project.glyphs.containsKey(set[i])) {
          ref = project.glyphs.get(set[i]);
          break;
        }
      if (ref == null)
        for (FontProject.Glyph g : project.glyphs.values())
          if (g.code != code()) {
            ref = g;
            break;
          }
    }
    board.reference = ref == null ? null : ref.copy();
    board.invalidate();
  }

  void updateTools() {
    undo.setEnabled(!board.undo.isEmpty());
    redo.setEnabled(!board.redo.isEmpty());
    move.setTextColor(board.moveMode ? Color.WHITE : accent);
    move.setBackground(shape(board.moveMode ? accent : Color.WHITE, 0));
    erase.setText(board.eraser ? "펜으로" : "지우개");
    erase.setTextColor(board.eraser ? Color.WHITE : accent);
    erase.setBackground(shape(board.eraser ? accent : Color.WHITE, 0));
    ghost.setTextColor(board.ghost ? Color.WHITE : accent);
    ghost.setBackground(shape(board.ghost ? accent : Color.WHITE, 0));
  }

  void saveSettings() {
    if (project == null) return;
    try {
      JSONObject snapshot = project.settings();
      worker.execute(
          () -> {
            try {
              project.saveSettings(snapshot);
            } catch (Exception e) {
              main.post(() -> saveError(e));
            }
          });
    } catch (Exception e) {
      saveError(e);
    }
  }

  @Override
  public void changed() {
    FontProject.Glyph snapshot = board.glyph.copy();
    project.accept(snapshot);
    status.setText("저장 중…");
    updateProgress();
    updateTools();
    worker.execute(
        () -> {
          try {
            project.saveGlyph(snapshot);
            main.post(
                () -> {
                  if (!isFinishing()) status.setText("자동 저장됨 · 전체 " + project.glyphs.size() + "자");
                });
          } catch (Exception e) {
            main.post(() -> saveError(e));
          }
        });
  }

  @Override
  public void referenceRequested() {
    if (!busy) pickGlyph(true);
  }

  @Override
  public void penActivity() {
    status.setText(board.moveGesture ? "글자 전체 이동 중…" : board.eraseGesture ? "획 지우기" : "필기 중…");
  }

  void saveError(Exception e) {
    if (status != null) {
      status.setText("저장 실패 · 원본 내보내기 필요");
      status.setTextColor(Color.RED);
    }
    toast("저장하지 못했습니다: " + e.getMessage());
  }

  void move(int step) {
    int n = project.index + step;
    if (n < 0 || n >= sets.get(project.group).length) {
      toast(step > 0 ? "이 묶음의 마지막 글자입니다." : "이 묶음의 첫 글자입니다.");
      return;
    }
    project.index = n;
    showGlyph();
    saveSettings();
  }

  void nextEmpty() {
    int[] set = sets.get(project.group);
    for (int step = 1; step <= set.length; step++) {
      int i = (project.index + step) % set.length;
      if (!project.glyphs.containsKey(set[i])) {
        project.index = i;
        showGlyph();
        saveSettings();
        return;
      }
    }
    toast("이 묶음은 모두 작성했습니다.");
  }

  void clearGlyph() {
    if (board.glyph.strokes.isEmpty()) return;
    new AlertDialog.Builder(this)
        .setTitle("이 글자를 비울까요?")
        .setMessage("비운 직후 ‘취소’로 되돌릴 수 있습니다.")
        .setNegativeButton("그대로 두기", null)
        .setPositiveButton("비우기", (d, w) -> board.clear())
        .show();
  }

  void rename() {
    EditText input = new EditText(this);
    input.setSingleLine(true);
    input.setText(project.name);
    input.setSelectAllOnFocus(true);
    input.setFilters(
        new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(80)});
    new AlertDialog.Builder(this)
        .setTitle("글꼴 이름")
        .setView(input)
        .setNegativeButton("취소", null)
        .setPositiveButton(
            "저장",
            (d, w) -> {
              String s = input.getText().toString().trim();
              if (!s.isEmpty()) {
                project.name = s;
                saveSettings();
                buildUi();
              }
            })
        .show();
  }

  void pickGlyph(boolean comparison) {
    LinearLayout wrap = new LinearLayout(this);
    wrap.setOrientation(LinearLayout.VERTICAL);
    wrap.setPadding(dp(22), dp(5), dp(22), 0);
    EditText input = new EditText(this);
    input.setSingleLine(true);
    input.setHint(comparison ? "비교할 글자 한 글자" : "이동할 글자 한 글자 (예: 똠, A)");
    input.setTextSize(24);
    wrap.addView(input);
    if (comparison) {
      TextView note = label("작성한 글자를 선택하면 고정됩니다.", 12, muted);
      wrap.addView(note);
    }
    ArrayList<Integer> saved = new ArrayList<>(project.glyphs.keySet());
    if (!saved.isEmpty()) {
      TextView recent = label("작성한 글자 · 누르면 선택", 12, muted);
      recent.setPadding(0, dp(12), 0, dp(5));
      wrap.addView(recent);
      GridView grid = new GridView(this);
      grid.setNumColumns(8);
      grid.setVerticalSpacing(dp(3));
      ArrayList<String> items = new ArrayList<>();
      for (int cp : saved) items.add(new String(Character.toChars(cp)));
      grid.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, items));
      grid.setOnItemClickListener((p, v, i, id) -> input.setText(items.get(i)));
      wrap.addView(grid, new LinearLayout.LayoutParams(-1, dp(220)));
    }
    AlertDialog.Builder builder =
        new AlertDialog.Builder(this)
            .setTitle(comparison ? "비교 글자 고정" : "글자 찾기")
            .setView(wrap)
            .setNegativeButton("취소", null)
            .setPositiveButton("선택", null);
    if (comparison)
      builder.setNeutralButton(
          "자동 비교",
          (d, w) -> {
            project.reference = -1;
            updateReference();
            saveSettings();
          });
    AlertDialog dialog = builder.create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      String s = input.getText().toString().trim();
                      if (s.codePointCount(0, s.length()) != 1) {
                        input.setError("한 글자를 입력해 주세요.");
                        return;
                      }
                      int cp = s.codePointAt(0);
                      if (comparison) {
                        if (!project.glyphs.containsKey(cp)) {
                          input.setError("아직 작성하지 않은 글자입니다.");
                          return;
                        }
                        project.reference = cp;
                        updateReference();
                        saveSettings();
                      } else if (!jump(cp)) {
                        input.setError("영문·숫자·기호·한글 음절과 자모를 입력해 주세요.");
                        return;
                      }
                      dialog.dismiss();
                    }));
    dialog.show();
  }

  boolean jump(int cp) {
    for (int g = 0; g < sets.size(); g++) {
      int[] a = sets.get(g);
      for (int i = 0; i < a.length; i++)
        if (a[i] == cp) {
          project.group = g;
          project.index = i;
          ignoreGroup = true;
          groups.setSelection(g);
          ignoreGroup = false;
          showGlyph();
          saveSettings();
          return true;
        }
    }
    return false;
  }

  void exportMenu() {
    new AlertDialog.Builder(this)
        .setTitle("내보내기 · " + project.glyphs.size() + "자")
        .setItems(
            new String[] {"TTF 글꼴 저장", "원본 획 데이터 저장 (.forfont)", "원본 가져오기"},
            (d, i) -> {
              if (i == 2) confirmImport();
              else prepareExport(i == 0);
            })
        .setNegativeButton("닫기", null)
        .show();
  }

  ArrayList<FontProject.Glyph> snapshot() {
    ArrayList<FontProject.Glyph> list = new ArrayList<>();
    for (FontProject.Glyph g : project.glyphs.values()) list.add(g.copy());
    return list;
  }

  void setBusy(boolean value, String message) {
    busy = value;
    if (board != null) board.inputEnabled = !value;
    if (status != null) status.setText(message);
  }

  byte[] makeFont(String name, ArrayList<FontProject.Glyph> glyphs) throws Exception {
    ArrayList<TtfWriter.Shape> shapes = new ArrayList<>();
    for (FontProject.Glyph g : glyphs) shapes.add(InkGeometry.shape(g));
    return TtfWriter.write(name, shapes);
  }

  void prepareExport(boolean font) {
    if (font && project.glyphs.isEmpty()) {
      toast("먼저 글자를 하나 이상 써 주세요.");
      return;
    }
    try {
      String name = project.name;
      ArrayList<FontProject.Glyph> glyphs = snapshot();
      JSONObject settings = project.settings();
      setBusy(true, font ? "TTF 만드는 중…" : "원본 준비 중…");
      worker.execute(
          () -> {
            try {
              byte[] data =
                  font ? makeFont(name, glyphs) : FontProject.sourceBytes(glyphs, settings);
              main.post(
                  () -> {
                    if (isFinishing()) return;
                    pendingExport = data;
                    pendingType = font ? "TTF" : "원본";
                    setBusy(false, "저장 위치를 선택해 주세요");
                    Intent intent =
                        new Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .setType(font ? "font/ttf" : "application/octet-stream")
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .putExtra(
                                Intent.EXTRA_TITLE, safeName(name) + (font ? ".ttf" : ".forfont"));
                    startActivityForResult(intent, 10);
                  });
            } catch (Exception e) {
              main.post(
                  () -> {
                    setBusy(false, "내보내기 실패");
                    toast(e.getMessage());
                  });
            }
          });
    } catch (Exception e) {
      toast(e.getMessage());
    }
  }

  String safeName(String name) {
    String n = name.replaceAll("[^\\p{L}\\p{N}_-]", "_");
    return n.isEmpty() ? "MyHand" : n;
  }

  void confirmImport() {
    new AlertDialog.Builder(this)
        .setTitle("원본 가져오기")
        .setMessage("가져온 원본으로 현재 작업을 교체합니다. 현재 작업이 필요하면 먼저 원본으로 저장해 주세요.")
        .setNegativeButton("취소", null)
        .setPositiveButton(
            "파일 선택",
            (d, w) ->
                startActivityForResult(
                    new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .setType("*/*")
                        .addCategory(Intent.CATEGORY_OPENABLE),
                    11))
        .show();
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (isFinishing() || isDestroyed()) return;
    if (loading) {
      main.postDelayed(() -> onActivityResult(request, result, data), 100);
      return;
    }
    if (request == 10) {
      if (result != RESULT_OK || data == null) {
        pendingExport = null;
        status.setText("저장을 취소했습니다");
        return;
      }
      if (pendingExport == null) {
        toast("내보내기를 다시 시도해 주세요.");
        return;
      }
      byte[] bytes = pendingExport;
      pendingExport = null;
      Uri uri = data.getData();
      String kind = pendingType;
      setBusy(true, "저장 중…");
      worker.execute(
          () -> {
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
              if (out == null) throw new IOException("파일을 열 수 없습니다.");
              out.write(bytes);
              main.post(
                  () -> {
                    setBusy(false, kind + " 저장 완료");
                    toast(kind + " 파일을 저장했습니다.");
                  });
            } catch (Exception e) {
              main.post(
                  () -> {
                    setBusy(false, "파일 저장 실패");
                    toast(e.getMessage());
                  });
            }
          });
    } else if (request == 11 && result == RESULT_OK && data != null) {
      Uri uri = data.getData();
      setBusy(true, "원본 가져오는 중…");
      worker.execute(
          () -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
              if (in == null) throw new IOException("파일을 열 수 없습니다.");
              FontProject.Source source = FontProject.readSource(in);
              project.replace(source.glyphs, source.settings);
              FontProject loaded = new FontProject(this);
              main.post(
                  () -> {
                    project = loaded;
                    busy = false;
                    buildUi();
                    toast("원본을 가져왔습니다.");
                  });
            } catch (Exception e) {
              main.post(
                  () -> {
                    setBusy(false, "가져오기 실패 · 기존 작업 유지");
                    toast(e.getMessage());
                  });
            }
          });
    }
  }

  static byte[] readLimited(InputStream in, int max) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) != -1) {
      if (out.size() + n > max) throw new IOException("파일 크기가 너무 큽니다.");
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  void preview() {
    if (project.glyphs.isEmpty()) {
      toast("글자를 쓴 뒤 미리 볼 수 있습니다.");
      return;
    }
    String name = project.name;
    ArrayList<FontProject.Glyph> glyphs = snapshot();
    setBusy(true, "미리보기 만드는 중…");
    worker.execute(
        () -> {
          try {
            File f = File.createTempFile("preview-", ".ttf", getCacheDir());
            try (FileOutputStream out = new FileOutputStream(f)) {
              out.write(makeFont(name, glyphs));
            }
            Typeface face = Typeface.createFromFile(f);
            f.delete();
            main.post(
                () -> {
                  setBusy(false, "자동 저장됨");
                  showPreview(face);
                });
          } catch (Exception e) {
            main.post(
                () -> {
                  setBusy(false, "미리보기 실패");
                  toast(e.getMessage());
                });
          }
        });
  }

  void guideOptions() {
    LinearLayout panel = new LinearLayout(this);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(dp(22), dp(8), dp(22), dp(8));
    Switch enabled = new Switch(this);
    enabled.setText("현재 글자의 기본 모양 표시");
    enabled.setChecked(project.guideEnabled);
    panel.addView(enabled, new LinearLayout.LayoutParams(-1, dp(48)));
    TextView opacity =
        label("투명도 " + Math.round((1 - project.guideOpacity) * 100) + "%", 14, muted);
    panel.addView(opacity);
    SeekBar slider = new SeekBar(this);
    slider.setMax(100);
    slider.setProgress(Math.round((1 - project.guideOpacity) * 100));
    slider.setEnabled(project.guideEnabled);
    panel.addView(slider, new LinearLayout.LayoutParams(-1, dp(45)));
    panel.addView(label("기기 기본 글꼴을 기준선에 맞춰 보여 줍니다.", 12, muted));
    enabled.setOnCheckedChangeListener(
        (v, on) -> {
          project.guideEnabled = board.guideEnabled = on;
          slider.setEnabled(on);
          board.invalidate();
        });
    slider.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar s, int p, boolean user) {
            project.guideOpacity = board.guideOpacity = 1 - p / 100f;
            opacity.setText("투명도 " + p + "%");
            board.invalidate();
          }

          public void onStartTrackingTouch(SeekBar s) {}

          public void onStopTrackingTouch(SeekBar s) {}
        });
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("기본 글자 안내")
            .setView(panel)
            .setPositiveButton("완료", null)
            .create();
    dialog.setOnDismissListener(d -> saveSettings());
    dialog.show();
  }

  void showPreview(Typeface face) {
    LinearLayout view = new LinearLayout(this);
    view.setOrientation(LinearLayout.VERTICAL);
    view.setPadding(dp(22), dp(10), dp(22), dp(10));
    view.addView(label("확인할 문장을 입력해 주세요", 14, textColor));
    EditText sample = new EditText(this);
    sample.setHint("예: 가나다 ABC");
    sample.setText(project.previewText);
    sample.setTextSize(18);
    sample.setMinLines(2);
    sample.setMaxLines(4);
    sample.setFilters(
        new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(2000)});
    view.addView(sample, new LinearLayout.LayoutParams(-1, -2));
    view.addView(label("내 글씨", 13, accent));
    TextView rendered = label(sample.getText().toString(), 38, textColor);
    rendered.setTypeface(face);
    rendered.setPadding(dp(10), dp(16), dp(10), dp(16));
    rendered.setBackground(shape(Color.WHITE, 0));
    rendered.setMinHeight(dp(110));
    view.addView(rendered, new LinearLayout.LayoutParams(-1, -2));
    TextView note = label("", 12, muted);
    note.setPadding(0, dp(10), 0, 0);
    view.addView(note);
    Runnable updateNote =
        () -> {
          LinkedHashSet<String> missing = new LinkedHashSet<>();
          String value = sample.getText().toString();
          value
              .codePoints()
              .filter(cp -> !Character.isWhitespace(cp) && !project.glyphs.containsKey(cp))
              .forEach(cp -> missing.add(new String(Character.toChars(cp))));
          note.setText(
              missing.isEmpty()
                  ? "모두 직접 쓴 글자입니다."
                  : "아직 안 쓴 글자는 기본 글꼴로 표시해요: " + String.join(" ", missing));
        };
    updateNote.run();
    sample.addTextChangedListener(
        new android.text.TextWatcher() {
          public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

          public void onTextChanged(CharSequence s, int st, int before, int count) {
            rendered.setText(s);
            project.previewText = s.toString();
            updateNote.run();
          }

          public void afterTextChanged(android.text.Editable e) {}
        });
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.addView(view);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("문장 미리보기 · " + project.name)
            .setView(scroll)
            .setPositiveButton("닫기", null)
            .create();
    dialog.setOnDismissListener(d -> saveSettings());
    dialog.show();
    dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent e) {
    if (board != null
        && board.penNearby()
        && e.getToolType(e.getActionIndex()) == MotionEvent.TOOL_TYPE_FINGER) {
      int[] location = new int[2];
      board.getLocationOnScreen(location);
      if (e.getRawY() < location[1] || e.getRawY() > location[1] + board.getHeight()) return true;
    }
    return super.dispatchTouchEvent(e);
  }

  void toast(String message) {
    Toast.makeText(this, message == null ? "작업을 완료하지 못했습니다." : message, Toast.LENGTH_LONG).show();
  }

  @Override
  protected void onPause() {
    if (board != null && board.penId >= 0) {
      board.cancel();
      status.setText("진행 중이던 획을 취소했습니다");
    }
    if (project != null && !busy) saveSettings();
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    worker.shutdown();
  }
}
