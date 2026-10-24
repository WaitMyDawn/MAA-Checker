Packaging notice
================

The product is distributed in two Windows forms:

  MAA-Checker-<version>-desktop-win-x64.zip   self-contained portable package
  MAA-Checker-<version>-light-win-x64.exe     single-file launcher without a JRE

The desktop ZIP requires no installed Java: extract it and double-click
MAA-Checker.exe because jpackage includes the application runtime.

The light EXE is a single file, but requires Java 21 or newer available through
JAVA_HOME or PATH. It intentionally does not bundle a JRE.

See README-DESKTOP.txt and DATA-AND-REMOVAL.txt for data locations and removal rules.
