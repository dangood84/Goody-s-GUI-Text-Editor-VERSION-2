# Goody's GUI Text Editor V2

A Swing desktop editor with **File**, **Edit**, and **Help** menus. Same job as [console v2](../GoodysTextEditorV2): create, open, save, and close files; copy, cut, and paste; undo.

This is a separate project. The console apps stay as they are. GUI v1 lives in [GoodysGUITextEditor](../GoodysGUITextEditor) (`GoodyGuiEditor`).

How the pieces fit together (same style as the Java savers): `WORKINGS.md` for responsibilities, dirty-flag / undo / file-dialog workarounds, `EXECUTION_FLOW.md` for a click-by-click trace.

## Requirements

- A **JDK 8 or newer** on your `PATH` (`java` and `javac`).
- A desktop session (Swing needs a display). Headless servers will not show a window.
- On macOS/Linux with [jenv](https://www.jenv.be/), `.java-version` selects **1.8** in this folder.

## Build and run

```bash
javac -d out src/goodysgui/*.java
java -cp out goodysgui.GoodyGuiEditorV2
```

Those two lines work in bash, Git Bash, PowerShell, and Command Prompt on a machine with a GUI.

To pack a runnable jar (JDK 8's `jar` uses short flags, not `--create`):

```bash
jar cfm GoodyGuiEditorV2.jar Manifest.txt -C out .
java -jar GoodyGuiEditorV2.jar
```

On **macOS**, Finder will not launch the `.jar` (Apple's JavaLauncher often does nothing). Double-click **`GoodyGuiEditorV2.command`** instead. That opens Terminal and runs `java -jar`. The first time, macOS may ask you to allow it: right-click the `.command` file → Open.

## Menus

| File | Edit | Help |
|---|---|---|
| New | Undo | How to use |
| Open... | Cut | About |
| Save | Copy | |
| Save As... | Paste | |
| Close | Select All | |
| Exit | Word Wrap | |

Shortcuts use **Ctrl** on Windows/Linux and **Cmd** on Mac (`N` `O` `S` `Shift+S` `W` `Q` / `Z` `X` `C` `V` `A`). A `*` in the window title means unsaved changes.

On **macOS**, those menus live in the **Mac menu bar** at the top of the screen (same place as Finder, Safari, and other Mac apps), not in a bar inside the window. Windows and Linux keep the menu bar in the window.

Close starts a blank untitled buffer (Notepad-style). Exit leaves the app. Unsaved work offers **Save / Don't Save / Cancel**.

## How this differs from console v2

- The File / Edit / Help grouping is a real `JMenuBar`, not nested console lists. On macOS that bar is installed in the system menu bar (`apple.laf.useScreenMenuBar`).
- You type in a `JTextArea` instead of Append / Insert line / Edit line.
- Cut, copy, and paste use the **system clipboard**.
- Undo uses Swing's `UndoManager` (per edit, not a full-file snapshot).
- Open and Save As use `JFileChooser` instead of typing `~` paths.
- On **Linux** (including Raspberry Pi OS) the whole app uses Swing's Metal look, not GTK. On **macOS** the window stays native and only the file dialog is Metal. That is a workaround for two JDK bugs: macOS often cannot select **All Files**, and GTK/AWT often never reports a real double-click (`clickCount` stays 1), so the dialog starts a **rename** instead of entering the folder. We treat two clicks on the same row as "open folder" ourselves. Windows keeps the native look.

## Project layout

```
src/goodysgui/
  GoodyGuiEditorV2.java   # window, menus, text area, dirty flag, undo, chooser
  TextFileIO.java         # UTF-8 load/save with platform line endings
Manifest.txt              # Main-Class for jar cfm
GoodyGuiEditorV2.command  # macOS Finder → java -jar
WORKINGS.md               # who owns state; Metal / GTK / Aqua workarounds
EXECUTION_FLOW.md         # main → EDT → type / Open / Save / Exit
```

Comments in the source (`WORKING:`) walk through the same design choices.
