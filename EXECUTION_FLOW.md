# Execution flow: from `main` to a saved file

A step-by-step trace of what happens from `public static void main(String[] args)` through window initialisation, down to how typing, Open, and Save As are calculated and drawn.

Default launch (`java -cp out goodysgui.GoodyGuiEditorV2`) opens one untitled window. There is no `--config` / `--fullscreen`. The interesting OS-specific work is the **file dialog**, not a second window type.

Two threads matter after startup:

- **main** — sets a Mac property, posts work, then returns
- **EDT** (Event Dispatch Thread) — creates the frame, menus, text area, dialogs, dirty flag, paints

Assume a desktop session. Headless servers never get a window (`HeadlessException` from AWT).

---

## Phase A — Process start (main thread)

**1.** The JVM loads `GoodyGuiEditorV2` and calls `main`.

```java
// GoodyGuiEditorV2.java
public static void main(String[] args) {
    System.setProperty("apple.laf.useScreenMenuBar", "true");

    SwingUtilities.invokeLater(new Runnable() {
        public void run() {
            installAppLookAndFeel();
            new GoodyGuiEditorV2().setVisible(true);
        }
    });
}
```

**2.** `apple.laf.useScreenMenuBar` is set **before** any Swing object. On macOS, File / Edit / Help will attach to the **screen menu bar**. On other OSes the property is ignored.

**3.** `SwingUtilities.invokeLater` does **not** build the UI yet. It queues a `Runnable` on the EDT. `main` then returns. The JVM stays alive because AWT has started a non-daemon thread.

---

## Phase B — Window initialisation (EDT)

**4.** The EDT runs the queued `Runnable`.

**5.** `installAppLookAndFeel()`:

| OS | What happens |
|----|----------------|
| Linux | `FileChooser.readOnly = true`, `UIManager.setLookAndFeel(new MetalLookAndFeel())` |
| macOS / Windows | system look-and-feel (`Apple AquaLookAndFeel` / `WindowsLookAndFeel`) |
| any failure | stay on Metal; do not crash |

```java
private static void installAppLookAndFeel() {
    try {
        if (isLinux()) {
            UIManager.put("FileChooser.readOnly", Boolean.TRUE);
            UIManager.setLookAndFeel(new MetalLookAndFeel());
        } else {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        }
    } catch (Exception ignored) {
    }
}
```

**6.** `new GoodyGuiEditorV2()`:

- title `Goody's GUI Text Editor V2`
- `DO_NOTHING_ON_CLOSE` + `windowClosing` → `exitApp()` (same confirm as File > Exit)
- `chooser = newFileChooser()` then `buildChooser()` (All Files + `*.txt` filter, start in `user.home`, ancestor listener for the click fix)
- `buildTextArea()` — monospaced 14, word wrap on, tab size 4, undo listener, dirty listener
- `setJMenuBar(buildMenuBar())` — File / Edit / Help, accelerators from `getMenuShortcutKeyMask()` (Cmd on Mac, Ctrl elsewhere)
- status label south, `JScrollPane(textArea)` centre, size 840×620, centred on screen
- **`newFile(false)`** — no confirm, `loadIntoEditor(null, "")`

**7.** `newFileChooser()` on Mac/Linux: temporarily Metal, `new JFileChooser()`, `updateComponentTreeUI`, restore previous L&F so the **main window** stays Aqua on Mac. Windows: plain `new JFileChooser()`.

**8.** `loadIntoEditor(null, "")` with `syncing = true`: empty text, undo stack cleared, `currentPath = null`, `dirty = false`. Title becomes `… — untitled`. Status: `untitled   |   saved`.

**9.** `setVisible(true)` realizes the frame. Swing paints the empty text area. On macOS the menu bar is already in the system bar.

The process now waits. There is no timer.

---

## Phase C — Typing (dirty + undo)

**10.** The user types `h` in the text area.

**11.** The document fires `UndoableEditEvent` then `insertUpdate`.

**12.** Undo listener (not `syncing`) → `undo.addEdit(...)`.

**13.** `markDirty()`: `dirty` was false → `dirty = true` → `refreshChrome()`. Title ends with `untitled*`. Status: `untitled   |   modified`.

**14.** Swing paints the glyph. We never call `repaint()` ourselves.

Further keystrokes: `markDirty` returns immediately (`dirty` already true). Undo still records each edit.

**Edit > Undo** / `Cmd+Z`: `undo.undo()` if `canUndo()`. That mutates the document again (listeners fire; `dirty` stays true unless you undo back to empty-and-never-saved — the flag does **not** clear on undo). A tester who needs “clean after undo to original” will not find that behaviour; only Save / `loadIntoEditor` clear `dirty`.

---

## Phase D — File > Save As… (first save)

**15.** Menu item (or `Cmd+Shift+S`) → `saveFileAs()` → `showChooserDialog(false)`.

**16.** On Windows: `chooser.showSaveDialog(this)` and skip to step 19.

**17.** On Mac/Linux:

```java
LookAndFeel previous = UIManager.getLookAndFeel();
try {
    UIManager.setLookAndFeel(new MetalLookAndFeel());
    SwingUtilities.updateComponentTreeUI(chooser);
} catch (Exception ignored) {
}
installFolderDoubleClickFix(chooser);
SwingUtilities.invokeLater(new Runnable() {
    public void run() {
        installFolderDoubleClickFix(chooser);
    }
});
try {
    return chooser.showSaveDialog(this);   // modal; EDT nested pump
} finally {
    restoreLookAndFeel(previous);
}
```

The `invokeLater` and the chooser's `AncestorListener` install the same click fix after FilePane has actually built the `JList`. The client property `goodysgui.folderDoubleClickFix` prevents duplicate listeners.

**18.** User picks a name (e.g. `notes.txt` under home). If that file exists: Yes/No “Replace it?” No → `saveFileAs` returns false, buffer still dirty.

**19.** `writeTo(path)`:

```java
TextFileIO.write(path, textArea.getText());
currentPath = path;
dirty = false;
refreshChrome();
```

**20.** `TextFileIO.write`: create parent dirs if needed; `split("\n", -1)`; `Files.write(..., UTF_8)` with **platform** line endings. In-memory text stays `'\n'`-separated.

**21.** Title: `… — notes.txt` (no `*`). Status: `/Users/…/notes.txt   |   saved`.

---

## Phase E — Unsaved confirm, then Open

**22.** User types more. `dirty = true` again.

**23.** File > Open... → `openFile()` → `confirmProceed("Open")`.

```java
int choice = JOptionPane.showOptionDialog(this,
        "Unsaved changes in " + displayName() + ".",
        "Open",
        JOptionPane.YES_NO_CANCEL_OPTION,
        JOptionPane.WARNING_MESSAGE,
        null,
        new Object[] { "Save", "Don't Save", "Cancel" },
        "Save");
```

| Button | Result |
|--------|--------|
| Save | `saveFile()` (write current path; untitled would Save As). Failure/cancel **aborts Open** |
| Don't Save | proceed to chooser |
| Cancel / dialog X | stay in this buffer |

**24.** Open chooser (`showChooserDialog(true)`), same Metal/fix path as Save As.

**25.** Approve → `TextFileIO.read(path)`: `readAllLines` + `String.join("\n", lines)` (strips any `'\r'`).

**26.** `loadIntoEditor(path, text)` with `syncing = true` so `setText`'s remove+insert does **not** `markDirty`. Undo stack discarded. Caret at 0. `dirty = false`.

If Open immediately shows `*` in the title, `syncing` did not cover the document events — breakpoint `markDirty`.

---

## Phase F — Folder double-click in the chooser (Linux / Pi)

This phase only matters when AWT reports `clickCount == 1` for every click (GTK3 on Raspberry Pi OS is the usual case). On Windows, and on a Mac with a real `clickCount == 2`, Metal/Aqua already enter the folder.

**27.** User presses the mouse on a folder row.

**28.** `FolderDoubleClickFix.mousePressed`:

- left button, not popup
- `indexAt` / `fileAt` resolve the `File` under the pointer (`JList` or details `JTable`)
- if `e.getClickCount() >= 2`: reset timestamps and **return** (native double-click)
- else if same `index` as last press and `(when - lastWhen) <= multiClickInterval()` (desktop property, else 500 ms):  
  `activateChooserFile`:
  - traversable → `setCurrentDirectory` + `rescanCurrentDirectory`
  - else → `setSelectedFile` + `approveSelection` (opens/saves that file)
- else: remember `lastIndex` / `lastWhen`

**29.** `FileChooser.readOnly` (Linux, set at L&F install) stops FilePane treating that second click as **rename**.

Without this listener, the tester symptom is: first click selects, second click starts editing the name, folder never opens. **Enter** still works even without the fix.

---

## Phase G — Close vs Exit

**30.** File > Close (`Cmd+W`) → `confirmProceed("Close")` → `loadIntoEditor(null, "")`. Process **stays**. Untitled, saved, empty undo.

**31.** File > Exit (`Cmd+Q`) or the window close box → `confirmProceed("Exit")` → `dispose()` → `System.exit(0)`.

```java
private void exitApp() {
    if (!confirmProceed("Exit")) {
        return;
    }
    dispose();
    System.exit(0);
}
```

Cancel on that dialog leaves the window up. There is no “minimize to tray.”

---

## The repeating loop

There is no timer. The AWT loop waits:

```text
EDT
  → key / menu / mouse / dialog
        DocumentListener or ActionListener
        maybe TextFileIO
        refreshChrome
        Swing paints JTextArea
  → wait for the next event
```

Cut / copy / paste are `textArea.cut()` / `copy()` / `paste()` — **system clipboard**, not an in-app buffer. Select All is `textArea.selectAll()`. Word Wrap toggles `setLineWrap` only (wrap-style stays word).

Help > How to use / About are `JOptionPane`s. F1 has no modifier; it is not a menu-shortcut mask.

---

## macOS Finder launch (same app, extra wrapper)

Double-click **`GoodyGuiEditorV2.command`** (not the `.jar` — Apple's JavaLauncher often does nothing):

1. `cd` to the script's folder
2. require `GoodyGuiEditorV2.jar`
3. `java` from `/usr/libexec/java_home` if present
4. `java -jar GoodyGuiEditorV2.jar` → JVM calls the same `main` as phase A (`Manifest.txt` `Main-Class: goodysgui.GoodyGuiEditorV2`)

---

## One-line map

`main` → `invokeLater` → look-and-feel → `GoodyGuiEditorV2` + untitled `loadIntoEditor` → **event** → **document / menu** → **dirty or `TextFileIO`** → **`refreshChrome`**.

Debugger: `GoodyGuiEditorV2.main`, constructor, `loadIntoEditor`, `markDirty`, `showChooserDialog`, `FolderDoubleClickFix.mousePressed`, `confirmProceed`, `exitApp`. First paint is empty untitled; there is no “stamp time only” frame like the Java savers.

See also `WORKINGS.md` for class responsibilities, the `syncing` flag, and the Metal / GTK / Aqua file-dialog bugs in more detail.
