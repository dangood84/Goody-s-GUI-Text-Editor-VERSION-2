# How Goody's GUI Text Editor V2 works

This note is for an automation tester who wants to see how a small Java Swing application is structured: where it starts, who owns state, who paints the text, and how a keystroke becomes an unsaved `*` in the title.

You do not need to be a Swing expert. The same ideas show up in many GUI apps: an entry point, a UI thread, a model (here a buffer + dirty flag), a view (`JTextArea`), and an event loop that turns input into state then redraws.

There is **no animation timer**. Nothing moves until a key, menu, or mouse event arrives. That is the whole difference from the Java savers (Scrolling Text, Flying Through Space): those tick at ~60 FPS; this editor waits.

This is the Swing version of [console v2](../GoodysTextEditorV2). Same File / Edit / Help job, real menus, and the system clipboard. It is a **separate project** from [GUI v1](../GoodysGUITextEditor) (`GoodyGuiEditor` vs `GoodyGuiEditorV2`).

## Mental model

```
main()
  → apple.laf.useScreenMenuBar = true
  → queue work on the Swing Event Dispatch Thread (EDT)
      → installAppLookAndFeel()
      → new GoodyGuiEditorV2 (window, menus, JTextArea, chooser)
           → native run loop (AWT)
                → type / menu / file dialog
                → buffer, dirty flag, UndoManager, TextFileIO
                → title + status bar refresh
```

| Layer | Class | Tester-friendly analogy |
|-------|--------|-------------------------|
| Entry / routing | `GoodyGuiEditorV2.main` | Test runner that only boots the window |
| Window shell | `GoodyGuiEditorV2` | Host + menus + “abort on close” |
| View | `JTextArea` | The widget that actually holds and paints glyphs |
| State | `currentPath`, `dirty`, `syncing`, `UndoManager` | Fixture: which file, unsaved?, ignore programmatic setText |
| Disk | `TextFileIO` | UTF-8 load/save; platform newlines on write |
| Dialog | `JFileChooser` + Metal + folder-click fix | Open/Save As; the OS-bug workaround |

Swing is **event-driven**. Almost everything after `main` runs on one thread: the **Event Dispatch Thread (EDT)**. Clicks, keystrokes, `DocumentListener` callbacks, and dialogs all happen there. That is why there is no worker thread and no `javax.swing.Timer`.

---

## 1. Entry point and execution lifecycle

### Where `main` lives

The process entry point is `GoodyGuiEditorV2.main(String[] args)`. There is no second compatibility class (the savers have `FlyingThroughSpace` / `ScrollingTextScreensaver` aliases; this app does not).

`GoodyGuiEditorV2` **is** instantiated. It is a `JFrame` subclass: bootstrap in `main`, then the constructor builds the window.

### Lifecycle, step by step

1. **JVM starts** and calls `main`.
2. **`System.setProperty("apple.laf.useScreenMenuBar", "true")`** — must happen **before** any Swing window. On macOS, File / Edit / Help go in the **system menu bar** (same place as Finder). On Windows/Linux the property is ignored; the bar stays in the window.
3. **`SwingUtilities.invokeLater(...)`** posts a Runnable to the EDT.  
   `main` itself must not create Swing windows. They are not thread-safe.
4. On the EDT:
   - **`installAppLookAndFeel()`** — Linux: whole app is Metal + `FileChooser.readOnly`. macOS/Windows: system look-and-feel (Aqua / Windows).
   - **`new GoodyGuiEditorV2().setVisible(true)`** — constructor: close hook, Metal file chooser if needed, text area, menus, blank untitled buffer.
5. After the window is showing, the process stays alive because Swing keeps a **non-daemon AWT thread** running until `System.exit(0)` (File > Exit or the window close box, both call `exitApp()`).

`setDefaultCloseOperation(DO_NOTHING_ON_CLOSE)` is deliberate. The close box must go through the same Save / Don't Save / Cancel path as **Exit**.

### One user journey

**Type, save, quit:**

```
EDT creates the window (untitled, dirty=false)
  → keystroke in JTextArea → DocumentListener → dirty=true, title gets *
  → File > Save As... → Metal chooser (Mac/Linux) → TextFileIO.write
       → dirty=false, title loses *, status says "saved"
  → File > Exit (or close box) → confirmProceed is a no-op → dispose + System.exit(0)
```

**Open with unsaved work:**

```
dirty buffer
  → File > Open... → confirmProceed → Save / Don't Save / Cancel
       → Save: write current file (or Save As if untitled)
       → Don't Save: proceed
       → Cancel: stay in the current buffer
  → chooser → TextFileIO.read → loadIntoEditor (syncing=true so setText is not dirty)
```

### Why testers care

- **There is no CLI feature flag.** Automating “open the editor” is `java -cp out goodysgui.GoodyGuiEditorV2`. There is no `--config` / `/s`.
- **There is no preferences file.** Word wrap defaults on each launch. Font is hardcoded (monospaced 14). A test that wants wrap off should toggle **Edit > Word Wrap**, not edit a config.
- **Exit is process-level** (`System.exit(0)`), not “hide the window.”
- **Close ≠ Exit.** Close loads a blank untitled buffer and stays in the process (Notepad-style). Exit leaves.
- **The `*` in the title is the dirty oracle.** If Open / New immediately shows `*`, `syncing` failed to swallow `setText`.
- **macOS menus are not inside the window.** A screenshot of the frame will miss File / Edit / Help; they are in the screen menu bar. Accessibility / `Cmd+N` still work.

---

## 2. Main classes and responsibilities

This is a **separation of UI vs disk**, not a full MVC framework. There is no database and no service layer. Almost everything lives on one class so a tester can keep a single debugger focus.

### `GoodyGuiEditorV2` — window, menus, buffer chrome

Owns:

- `JTextArea textArea` — in-memory buffer (`'\n'` only)
- `JLabel status` — path + `modified` / `saved`
- `JFileChooser chooser` — one instance, reused
- `UndoManager undo` — per-edit undo (Swing, not a full-file snapshot)
- `Path currentPath` — `null` means untitled
- `boolean dirty` — unsaved changes
- `boolean syncing` — true while `loadIntoEditor` calls `setText`, so Open / New do not look dirty

Builds the `JMenuBar` (File / Edit / Help), mnemonics (`Alt+F` on Windows/Linux), and accelerators (`Ctrl` or `Cmd` via `Toolkit.getMenuShortcutKeyMask()`).

**Does not** parse paths with `~` / `%USERPROFILE%`. That was console v2. The chooser returns an absolute `File`.

### `TextFileIO` — UTF-8 load/save

- `read`: `Files.readAllLines` → `String.join("\n", lines)` so the text area never sees `'\r'`
- `write`: `text.split("\n", -1)` then `Files.write` so the **platform** newline is used (LF on Unix, CRLF on Windows)
- `limit -1` keeps a trailing empty line (`"hello\n"` stays two parts)

No Swing types. The editor never calls `Files` itself for the document.

### What is *not* a class

There is no `Document` model (console v2 had one), no in-app `Clipboard`, and no snapshot `UndoManager`. `JTextArea` **is** the buffer. Cut / copy / paste call `textArea.cut()` / `copy()` / `paste()` and talk to the **system clipboard**. Undo listens to `UndoableEditEvent`s from the text document.

Nested type `FolderDoubleClickFix` is a `MouseAdapter` on the chooser's file list / details table. It is not a public API.

---

## 3. How input becomes a redraw

This is **not** a game loop (`while (running) { update(); render(); }`).

It is a **GUI event loop** on the EDT:

```
native event (key / menu / mouse)
    → JTextArea or JMenuItem
        → DocumentListener / ActionListener
            → dirty / undo / TextFileIO
                → refreshChrome()   // title + status
                → Swing paints the text area
```

### When it starts and stops

| Hook | Meaning |
|------|--------|
| `setVisible(true)` | Window exists; first paint of untitled |
| `DocumentListener` | Any insert/remove (typing, paste, undo) |
| File / Edit / Help actions | One command |
| `exitApp()` | `dispose()` + `System.exit(0)` |

There is no timer. Caret blink is Swing's own. Hover does nothing.

### `repaint()` vs the text area

You almost never call `repaint()` in this app. `JTextArea` marks itself dirty when the document changes. `refreshChrome()` only updates the **title** and the **status label**.

### Update vs draw (important split)

| Method | Mutates | Draws |
|--------|---------|-------|
| `DocumentListener` / `markDirty` | `dirty` | no (then `refreshChrome`) |
| `UndoableEditListener` | `UndoManager` | no |
| `loadIntoEditor` | text, path, dirty, undo stack | via `setText` |
| `writeTo` / `TextFileIO.write` | disk + path + dirty | no |
| `refreshChrome` | title string, status text | the chrome, not the glyphs |
| `JTextArea` paint | nothing in our code | yes |

A tester debugging “Open left a `*`” should breakpoint `markDirty` and `loadIntoEditor` (`syncing`). A tester debugging “Save wrote the wrong newlines” should breakpoint `TextFileIO.write`. A tester debugging “double-click does not enter a folder” should breakpoint `FolderDoubleClickFix.mousePressed` (especially on a Pi). A tester debugging “menus are missing on Mac” should look at the **screen menu bar**, not the frame.

### Why not `Thread.sleep` in a loop?

A blocking loop on the EDT would freeze typing and menus. An editor has no frames to produce while idle. The AWT loop is the platform-native “wait for the next key.”

---

## 4. Buffer, dirty flag, undo, and disk

Two flags matter besides the text itself:

- **`dirty`** — the buffer does not match disk (or has never been saved)
- **`syncing`** — we are stuffing text in from Open / New / Close, so ignore document events

### `loadIntoEditor`

```text
syncing = true
  setText(text)          # fires remove + insert; listeners no-op
  caret = 0
  undo.discardAllEdits()
  currentPath = path     # null = untitled
  dirty = false
syncing = false
refreshChrome()
```

`setText` always fires document events. Without `syncing`, every Open would immediately look modified.

### Typing / paste / cut

`insertUpdate` / `removeUpdate` / `changedUpdate` → `markDirty()`. First time only (`if (syncing || dirty) return`), then `refreshChrome()`:

```text
title  = "Goody's GUI Text Editor V2 — {filename or untitled}{* if dirty}"
status = "{absolute path or untitled}   |   {modified|saved}"
```

### Undo

Every `UndoableEdit` from the document (while not `syncing`) is pushed on `javax.swing.undo.UndoManager`. **Edit > Undo** / `Cmd+Z` calls `undo.undo()` if `canUndo()`. Empty stack is swallowed (`CannotUndoException`) — same idea as console v2's “Nothing to undo.”

This undoes **edits**, not whole-file snapshots. Console v2 copied the entire document; a free-typing GUI would make that clumsy.

There is **no Redo** menu.

### Confirm before losing work

`confirmProceed(action)` is shared by New, Open, Close, and Exit:

```text
if not dirty:                 proceed
else show Save / Don't Save / Cancel
  Save        → saveFile()    (Save As if untitled); fail/cancel aborts the action
  Don't Save  → proceed
  Cancel / X  → stay
```

That is Notepad's three-button dialog, not console v2's `y/N`.

### Save vs Save As

- **Save** with a path → `writeTo(currentPath)`
- **Save** while untitled → same as Save As
- **Save As** → chooser; if the file exists, Yes/No replace; then `writeTo`

`JFileChooser` does not always append `.txt`. The name is left as typed.

### Close vs New vs Exit

| Command | After confirm | Process |
|---------|----------------|---------|
| New | `loadIntoEditor(null, "")` | stays |
| Close | same blank untitled | stays |
| Exit / window X | `dispose()` + `System.exit(0)` | ends |

New and Close are the same buffer reset. Close is there so the File menu matches a desktop editor.

---

## 5. Look-and-feel and the file dialog

This is the part that looks like “too much code for Open.” It is two long-standing JDK bugs, not extra features.

### Why Metal at all

| OS | Main window | File dialog | Why |
|----|-------------|-------------|-----|
| **Windows** | native | native | No bug to work around |
| **macOS** | Aqua (native menus) | **Metal** | Aqua's filter dropdown often cannot select **All Files** (visible, clicks do nothing) |
| **Linux** (incl. Pi) | **Metal** | **Metal** | GTK `JFileChooser`: folder double-click does nothing; `clickCount` often stays `1`; the second click starts a **rename** |

On Linux, building **only** the chooser under Metal is not enough: GTK stays loaded and its mouse handling can still leave `clickCount` at 1. The whole Linux app uses Metal. `UIManager.put("FileChooser.readOnly", Boolean.TRUE)` stops FilePane starting that rename.

On Mac we must **not** call `updateUI()` on the chooser after restoring Aqua, or the dialog would pick up Aqua again.

### Creating the chooser (`newFileChooser`)

```text
if Windows:           new JFileChooser()
else:
  previous = current L&F
  set Metal
  created = new JFileChooser()
  updateComponentTreeUI(created)    # widgets that exist now
  restore previous                  # window stays Aqua on Mac
  do not updateUI the chooser
```

Some FilePane widgets (the file **list**) are created the **first time the dialog is shown**. `showChooserDialog` switches to Metal again, `updateComponentTreeUI(chooser)`, installs the click fix, then `showOpenDialog` / `showSaveDialog` (modal). `finally` restores the previous L&F.

### Folder double-click fix

Swing opens a folder only when `mouseClicked` has `clickCount == 2`. On Raspberry Pi OS / GTK3, AWT often reports every click as count 1.

`FolderDoubleClickFix` listens to **`mousePressed`** on the chooser's `JList` and `JTable`:

```text
if clickCount >= 2:     leave it to Metal (a real double-click)
else if same row twice within awt.multiClickInterval (else 500 ms):
    folder → setCurrentDirectory + rescanCurrentDirectory
    file   → setSelectedFile + approveSelection
```

A client property (`goodysgui.folderDoubleClickFix`) marks lists we already hooked so show-time `updateUI()` does not stack a second listener.

The listener is installed three times on purpose: before `show*Dialog`, `invokeLater` once the modal dialog is up (FilePane has built the list), and on `AncestorListener.ancestorAdded`. The client property makes the extras cheap.

Windows never takes this path (`fileChooserNeedsMetal()` is Mac/Linux only).

---

## Quick map of files

```
src/goodysgui/
  GoodyGuiEditorV2.java   # main, EDT, window, menus, dirty, undo, chooser workarounds
  TextFileIO.java         # UTF-8 read/write, '\n' in memory, platform newlines on disk
Manifest.txt              # Main-Class for jar cfm
GoodyGuiEditorV2.command  # macOS Finder double-click → java -jar
.java-version             # jenv: 1.8
```

If you are tracing in a debugger, put breakpoints on `GoodyGuiEditorV2.main`, `loadIntoEditor`, `markDirty`, `confirmProceed`, and `FolderDoubleClickFix.mousePressed`. You will see: **event → optional document mutation → dirty/undo or disk → title/status**.
