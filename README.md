# jbang-cp

Description: a file copy unix-like command.

[Java 26](https://openjdk.org/projects/jdk/26/)
[Jbang](https://www.jbang.dev/)
[picocli](https://picocli.info/)

[jbang - Unleash the power of Java](https://youtu.be/cpKwBbz1sf0)

## Usage

```bash
jbang cp.java [options] <source> <target>
```

### Options

- `-r`: recursive copy
- `-f`: force overwrite
- `-n`: no overwrite
- `-v`: verbose
- `-V, --version`: display version information
- `-T, --time`: print elapsed time for the copy

### Examples

- `jbang cp.java -r src dest` : recursive copy
- `jbang cp.java -v file1 file2` : verbose copy
- `jbang cp.java -T -r dir1 dir2` : recursive copy with timing

## Version 1.0.1

- Regroupement des options dans une classe imbriquée `Arguments` remplie par Picocli via `@Mixin`, permettant une séparation claire entre parsing et logique métier.
- Picocli instancie `cp` (usage `new CommandLine(new cp())`) pour que le mixin soit correctement injecté.
- Chronométrage optionnel `-T|--time` encapsulé dans le `record` `TimingContext` et formaté avec l'API `java.time`.
- Copie récursive parallèle avec virtual threads via `Executors.newVirtualThreadPerTaskExecutor()` et collecte des `Future`s.
- Détection de fichiers identiques avec `Files.mismatch(src, dst)` pour éviter d'écraser les fichiers inchangés.
- Options gérées : `-r` (recursive), `-f` (force), `-n` (noClobber), `-v` (verbose), `-T` (time), `-V` (version).
- Gestion des erreurs en mode parallèle avec propagation propre des exceptions (`ExecutionException`, `InterruptedException`).
- Utilisation de l'API `java.time` pour un calcul de durée.
