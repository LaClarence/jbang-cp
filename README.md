# jbang-cp

A cp unix like command using Java 25 & Jbang

[jbang - Unleash the power of Java](https://youtu.be/cpKwBbz1sf0)

## Java 25

La copie récursive utilise des virtual threads via:

* **ExecutorsnewVirtualThreadPerTaskExecutor()** et
* **CompletableFuture** pour paralléliser les copies de fichiers.

Détection **Files.mismatch** pour éviter d'écraser les fichiers identiques.
