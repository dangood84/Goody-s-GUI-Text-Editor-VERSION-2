package goodysgui;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;

/**
 * Goody's GUI Text Editor — Swing version of console v2.
 *
 * WORKING (why Swing, and why this shape):
 * Console v2 already grouped commands into File / Edit / Help, but they were
 * nested text menus. A JMenuBar is the same grouping with real menus, keyboard
 * accelerators, and a JTextArea instead of "append / insert line 3".
 *
 * WORKING (what we no longer reimplement):
 * - Clipboard: JTextArea.copy/cut/paste talk to the OS clipboard. Console v1/v2
 *   kept an in-app clipboard because AWT is awkward in a headless terminal.
 *   A Swing app is already using AWT, so the system clipboard is the right call.
 * - Undo: javax.swing.undo.UndoManager listens to the text document and undoes
 *   typing in small steps. Console v2 snapshot-copied the whole file; that is
 *   clumsy once the user can type freely.
 * - Paths: JFileChooser returns an absolute File. No ~ / %USERPROFILE% typing.
 *
 * WORKING (Java 8): same floor as v2 — no text blocks, Path.of, or isBlank —
 * so this compiles at work on JDK 8. Lambdas and Swing are both Java 8.
 *
 * Run:
 *   javac -d out src/goodysgui/*.java
 *   java -cp out goodysgui.GoodyGuiEditor
 */
public final class GoodyGuiEditor extends JFrame {

    private static final String APP_NAME = "Goody's GUI Text Editor";

    private final JTextArea textArea = new JTextArea();
    private final JLabel status = new JLabel();
    private final JFileChooser chooser = new JFileChooser();
    private final UndoManager undo = new UndoManager();

    // WORKING: null path means untitled (File > New), same idea as console v2.
    private Path currentPath;

    private boolean dirty;

    // WORKING: setText fires remove+insert. We ignore those so Open / New do
    // not immediately look like unsaved edits.
    private boolean syncing;

    private GoodyGuiEditor() {
        super(APP_NAME);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                exitApp();
            }
        });

        buildChooser();
        buildTextArea();
        setJMenuBar(buildMenuBar());

        status.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(new JScrollPane(textArea), BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        setSize(840, 620);
        setLocationRelativeTo(null);
        newFile(false);
    }

    public static void main(String[] args) {
        // WORKING: all Swing construction must happen on the Event Dispatch
        // Thread. invokeLater queues us there after the OS look-and-feel is set.
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignored) {
                    // WORKING: if the native L&F fails we keep Metal rather than crash.
                }
                new GoodyGuiEditor().setVisible(true);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Widget setup
    // -------------------------------------------------------------------------

    private void buildChooser() {
        FileNameExtensionFilter txt = new FileNameExtensionFilter("Text files (*.txt)", "txt");
        chooser.addChoosableFileFilter(txt);
        chooser.setFileFilter(txt);
        chooser.setCurrentDirectory(new java.io.File(System.getProperty("user.home")));
    }

    private void buildTextArea() {
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setTabSize(4);

        // WORKING: every keystroke (and paste) arrives as an UndoableEdit.
        // We cap nothing here — Swing's UndoManager already has a limit.
        textArea.getDocument().addUndoableEditListener(new javax.swing.event.UndoableEditListener() {
            public void undoableEditHappened(javax.swing.event.UndoableEditEvent e) {
                if (!syncing) {
                    undo.addEdit(e.getEdit());
                }
            }
        });

        textArea.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                markDirty();
            }

            public void removeUpdate(DocumentEvent e) {
                markDirty();
            }

            public void changedUpdate(DocumentEvent e) {
                markDirty();
            }
        });
    }

    /**
     * WORKING: this is console v2's File / Edit / Help bar, as real JMenus.
     * Mnemonics (Alt+F) and accelerators (Ctrl/Cmd+S) are the GUI extras.
     * getMenuShortcutKeyMask is Command on macOS and Control on Windows/Linux.
     */
    private JMenuBar buildMenuBar() {
        int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();

        JMenu file = new JMenu("File");
        file.setMnemonic(KeyEvent.VK_F);
        file.add(item("New", KeyEvent.VK_N, shortcut, KeyEvent.VK_N, new Runnable() {
            public void run() {
                newFile(true);
            }
        }));
        file.add(item("Open...", KeyEvent.VK_O, shortcut, KeyEvent.VK_O, new Runnable() {
            public void run() {
                openFile();
            }
        }));
        file.addSeparator();
        file.add(item("Save", KeyEvent.VK_S, shortcut, KeyEvent.VK_S, new Runnable() {
            public void run() {
                saveFile();
            }
        }));
        file.add(item("Save As...", KeyEvent.VK_A, shortcut | InputEvent.SHIFT_MASK, KeyEvent.VK_S,
                new Runnable() {
                    public void run() {
                        saveFileAs();
                    }
                }));
        file.addSeparator();
        file.add(item("Close", KeyEvent.VK_C, shortcut, KeyEvent.VK_W, new Runnable() {
            public void run() {
                closeFile();
            }
        }));
        file.add(item("Exit", KeyEvent.VK_X, shortcut, KeyEvent.VK_Q, new Runnable() {
            public void run() {
                exitApp();
            }
        }));

        JMenu edit = new JMenu("Edit");
        edit.setMnemonic(KeyEvent.VK_E);
        edit.add(item("Undo", KeyEvent.VK_U, shortcut, KeyEvent.VK_Z, new Runnable() {
            public void run() {
                undoLast();
            }
        }));
        edit.addSeparator();
        // WORKING: these call JTextComponent methods, which use the system clipboard.
        edit.add(item("Cut", KeyEvent.VK_T, shortcut, KeyEvent.VK_X, new Runnable() {
            public void run() {
                textArea.cut();
            }
        }));
        edit.add(item("Copy", KeyEvent.VK_C, shortcut, KeyEvent.VK_C, new Runnable() {
            public void run() {
                textArea.copy();
            }
        }));
        edit.add(item("Paste", KeyEvent.VK_P, shortcut, KeyEvent.VK_V, new Runnable() {
            public void run() {
                textArea.paste();
            }
        }));
        edit.addSeparator();
        edit.add(item("Select All", KeyEvent.VK_L, shortcut, KeyEvent.VK_A, new Runnable() {
            public void run() {
                textArea.selectAll();
            }
        }));
        edit.addSeparator();
        final JCheckBoxMenuItem wrap = new JCheckBoxMenuItem("Word Wrap");
        wrap.setSelected(true);
        wrap.setMnemonic(KeyEvent.VK_W);
        wrap.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(ActionEvent e) {
                textArea.setLineWrap(wrap.isSelected());
            }
        });
        edit.add(wrap);

        JMenu help = new JMenu("Help");
        help.setMnemonic(KeyEvent.VK_H);
        help.add(item("How to use", KeyEvent.VK_H, 0, KeyEvent.VK_F1, new Runnable() {
            public void run() {
                showHowToUse();
            }
        }));
        help.add(item("About", KeyEvent.VK_A, 0, 0, new Runnable() {
            public void run() {
                showAbout();
            }
        }));

        JMenuBar bar = new JMenuBar();
        bar.add(file);
        bar.add(edit);
        bar.add(help);
        return bar;
    }

    private JMenuItem item(String name, int mnemonic, int acceleratorMask, int acceleratorKey,
            final Runnable action) {
        AbstractAction swingAction = new AbstractAction(name) {
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        };
        swingAction.putValue(Action.MNEMONIC_KEY, Integer.valueOf(mnemonic));
        if (acceleratorKey != 0) {
            // F1 has no modifier; menu shortcuts use Control/Command.
            int mask = (acceleratorKey == KeyEvent.VK_F1) ? 0 : acceleratorMask;
            swingAction.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(acceleratorKey, mask));
        }
        return new JMenuItem(swingAction);
    }

    // -------------------------------------------------------------------------
    // File menu
    // -------------------------------------------------------------------------

    private void newFile(boolean confirm) {
        if (confirm && !confirmProceed("New")) {
            return;
        }
        loadIntoEditor(null, "");
    }

    private void openFile() {
        if (!confirmProceed("Open")) {
            return;
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path path = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        try {
            loadIntoEditor(path, TextFileIO.read(path));
        } catch (IOException ex) {
            showError("Could not open file:\n" + ex.getMessage());
        }
    }

    /**
     * @return false if the user cancelled Save As or the write failed
     */
    private boolean saveFile() {
        if (currentPath == null) {
            return saveFileAs();
        }
        return writeTo(currentPath);
    }

    private boolean saveFileAs() {
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return false;
        }
        Path path = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        // WORKING: JFileChooser does not always add .txt; we leave the name as typed.
        if (Files.exists(path)) {
            int replace = JOptionPane.showConfirmDialog(this,
                    path.getFileName() + " already exists.\nReplace it?",
                    "Save As",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (replace != JOptionPane.YES_OPTION) {
                return false;
            }
        }
        return writeTo(path);
    }

    /**
     * WORKING: GUI Close is Notepad-style — discard this file and start untitled.
     * Console v2 could sit on [no file]; a windowed editor always has a buffer.
     */
    private void closeFile() {
        if (!confirmProceed("Close")) {
            return;
        }
        loadIntoEditor(null, "");
    }

    private void exitApp() {
        if (!confirmProceed("Exit")) {
            return;
        }
        dispose();
        System.exit(0);
    }

    // -------------------------------------------------------------------------
    // Edit / Help
    // -------------------------------------------------------------------------

    private void undoLast() {
        try {
            if (undo.canUndo()) {
                undo.undo();
            }
        } catch (CannotUndoException ignored) {
            // WORKING: empty stack — same as console v2's "Nothing to undo."
        }
    }

    private void showAbout() {
        JOptionPane.showMessageDialog(this,
                String.join("\n",
                        APP_NAME,
                        "A Swing editor with File, Edit, and Help menus.",
                        "Based on Goody's Console Text Editor v2.",
                        "Java 8 or newer."),
                "About",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void showHowToUse() {
        JOptionPane.showMessageDialog(this,
                String.join("\n",
                        "File" + " " + \u2014 + " " + "New, Open, Save, Save As, Close, Exit",
                        "Edit" + " " + \u2014 + " " + "Undo, Cut, Copy, Paste, Select All, Word Wrap",
                        "Help" + " " + \u2014 + " " + "this window, and About",
                        "",
                        "Shortcuts use Ctrl on Windows/Linux and Cmd on Mac.",
                        "A * in the title means unsaved changes.",
                        "Close starts a blank untitled file; Exit leaves the app.",
                        "Unsaved work offers Save / Don't Save / Cancel."),
                "How to use",
                JOptionPane.INFORMATION_MESSAGE);
    }

    // -------------------------------------------------------------------------
    // Buffer + status
    // -------------------------------------------------------------------------

    private void loadIntoEditor(Path path, String text) {
        syncing = true;
        try {
            textArea.setText(text);
            textArea.setCaretPosition(0);
            undo.discardAllEdits();
            currentPath = path;
            dirty = false;
        } finally {
            syncing = false;
        }
        refreshChrome();
        textArea.requestFocusInWindow();
    }

    private boolean writeTo(Path path) {
        try {
            TextFileIO.write(path, textArea.getText());
            currentPath = path;
            dirty = false;
            refreshChrome();
            return true;
        } catch (IOException ex) {
            showError("Could not save file:\n" + ex.getMessage());
            return false;
        }
    }

    private void markDirty() {
        if (syncing || dirty) {
            return;
        }
        dirty = true;
        refreshChrome();
    }

    /**
     * WORKING: v2 asked y/N. A GUI can offer Save / Don't Save / Cancel,
     * which is what Notepad and most editors do.
     */
    private boolean confirmProceed(String action) {
        if (!dirty) {
            return true;
        }
        int choice = JOptionPane.showOptionDialog(this,
                "Unsaved changes in " + displayName() + ".",
                action,
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                new Object[] { "Save", "Don't Save", "Cancel" },
                "Save");
        if (choice == JOptionPane.YES_OPTION) {
            return saveFile();
        }
        if (choice == JOptionPane.NO_OPTION) {
            return true;
        }
        return false;
    }

    private void refreshChrome() {
        String mark = dirty ? "*" : "";
        setTitle(APP_NAME + " " + \u2014 + " " + displayName() + mark);
        String where = currentPath == null ? "untitled" : currentPath.toString();
        String state = dirty ? "modified" : "saved";
        status.setText(where + "   |   " + state);
    }

    private String displayName() {
        return currentPath == null ? "untitled" : currentPath.getFileName().toString();
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, APP_NAME, JOptionPane.ERROR_MESSAGE);
    }
}
