# Goody's GUI Text Editor

A Swing desktop editor with **File**, **Edit**, and **Help** menus. Same job as [console v2](../GoodysTextEditorV2): create, open, save, and close files; copy, cut, and paste; undo.

This is a separate project. The console apps stay as they are.

## Requirements

- A **JDK 8 or newer** on your `PATH` (`java` and `javac`).
- A desktop session (Swing needs a display). Headless servers will not show a window.
- On macOS/Linux with [jenv](https://www.jenv.be/), `.java-version` selects **1.8** in this folder.

## Build and run

```bash
javac -d out src/goodysgui/*.java
java -cp out goodysgui.GoodyGuiEditor
```

Those two lines work in bash, Git Bash, PowerShell, and Command Prompt on a machine with a GUI.

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

Close starts a blank untitled buffer (Notepad-style). Exit leaves the app. Unsaved work offers **Save / Don't Save / Cancel**.

## How this differs from console v2

- The File / Edit / Help grouping is a real `JMenuBar`, not nested console lists.
- You type in a `JTextArea` instead of Append / Insert line / Edit line.
- Cut, copy, and paste use the **system clipboard**.
- Undo uses Swing's `UndoManager` (per edit, not a full-file snapshot).
- Open and Save As use `JFileChooser` instead of typing `~` paths.
- On **Linux** (including Raspberry Pi OS) the whole app uses Swing's Metal look, not GTK. On **macOS** the window stays native and only the file dialog is Metal. That is a workaround for two JDK bugs: macOS often cannot select **All Files**, and GTK file choosers often ignore double-clicks on folders (Enter still works) — even if only the dialog is switched to Metal. Windows keeps the native look.

## Layout

| Class | Role |
|---|---|
| `GoodyGuiEditor` | Window, menus, text area, dirty flag, undo |
| `TextFileIO` | UTF-8 load/save with platform line endings |

Comments in the source walk through the design choices.
