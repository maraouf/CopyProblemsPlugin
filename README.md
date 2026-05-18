<img src="src/main/resources/META-INF/pluginIcon.svg" alt="Copy All Problems icon" width="96" align="left" />

# Copy All Problems — IntelliJ Platform Plugin

<br clear="left" />

A tiny plugin that adds an action to copy every diagnostic for the currently
active file to the clipboard, with file path, line, column, severity, and
description — one entry per line.

Works in any IntelliJ-platform IDE: IntelliJ IDEA, PyCharm (Community and
Professional), WebStorm, GoLand, RubyMine, CLion, etc.

## Output format

```
beszel.py:42:5 [WARNING] Unresolved reference 'foo'
beszel.py:87:12 [ERROR] Expected type 'int', got 'str' instead
beszel.py:103:1 [WEAK_WARNING] Function 'bar' may be 'static'
```

## How to use

After installing the plugin (see below):

1. Open the file you want to inspect.
2. Wait a beat for the analyzer to finish (watch the bottom status bar — when
   "Analyzing…" disappears, you're good).
3. Either:
   - Right-click anywhere in the editor → **Copy All Problems with Line Numbers**
   - Or press **Ctrl+Shift+Alt+P** (Windows/Linux) / **⌘+Shift+Alt+P** (Mac)
   - Or **Tools → Copy All Problems with Line Numbers**
4. A balloon notification confirms how many problems were copied.
5. Paste anywhere.

## Build from source

You need a JDK 17+ on your PATH.

```bash
# From the project root:
./gradlew buildPlugin           # macOS / Linux
gradlew.bat buildPlugin         # Windows
```

The plugin zip will appear at:

```
build/distributions/copy-problems-1.0.0.zip
```

## Install in your IDE

1. Open your IDE.
2. **Settings / Preferences → Plugins**.
3. Click the gear icon (⚙) at the top → **Install Plugin from Disk…**
4. Select the zip from `build/distributions/`.
5. Click **OK**, then **Restart IDE** when prompted.

## Uninstall

**Settings → Plugins → Installed**, find "Copy All Problems", click the gear
icon next to it → **Uninstall** → restart.

## How it works

The plugin uses `DaemonCodeAnalyzerImpl.getHighlights(document, null, project)`
— the same engine that powers the Problems tool window — to read every
highlight currently computed for the document. It filters out internal markers
(things without a description), sorts by offset, and writes:

```
<filename>:<line>:<col> [<severity>] <description>
```

to the system clipboard via `CopyPasteManager`.

`DaemonCodeAnalyzerImpl` is an internal IntelliJ Platform class. It's stable
across 2022.3 through 2024.x. If a future release breaks it, the action will
log a `NoSuchMethodError` and the keyboard shortcut will be a no-op until the
plugin is updated.

## Files

```
copy-problems-plugin/
├── build.gradle.kts                                    # Gradle build script
├── settings.gradle.kts                                 # Gradle settings
├── gradle.properties                                   # Gradle properties
├── gradlew, gradlew.bat                                # Gradle wrapper scripts
├── gradle/wrapper/gradle-wrapper.properties            # Wrapper config
├── src/main/
│   ├── kotlin/com/moraouf/copyproblems/
│   │   └── CopyProblemsAction.kt                       # The action
│   └── resources/META-INF/
│       └── plugin.xml                                  # Plugin descriptor
└── README.md                                           # This file
```

## License

[MIT](LICENSE.md) © Mohamed Abdelraouf
