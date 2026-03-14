
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//PREVIEW
//DEPS info.picocli:picocli:4.7.7

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

@Command(name = "cp", mixinStandardHelpOptions = true, version = {
    "cp 1.0",
    "Java 25 implementation",
    "CopyLeft 2026"
}, description = "Copy files and directories")
public class cp implements Callable<Integer> {

  @Option(names = "-r", description = "recursive copy")
  boolean recursive;

  @Option(names = "-f", description = "force overwrite")
  boolean force;

  @Option(names = "-n", description = "no overwrite")
  boolean noClobber;

  @Option(names = "-v", description = "verbose")
  boolean verbose;

  @Option(names = { "-V", "--version" }, versionHelp = true, description = "display version information")
  boolean versionInfoRequested;

  @Parameters(index = "0")
  Path source;

  @Parameters(index = "1")
  Path target;

  @Override
  public Integer call() throws Exception {

    if (Files.isDirectory(source)) {
      if (!recursive) {
        throw new IllegalArgumentException("Source is a directory (use -r)");
      }
      copyDirectory(source, target);
    } else {
      copyFile(source, target);
    }

    return 0;
  }

  /**
   * Copy a single file, respecting the force and noClobber options
   * 
   * @param src the source file
   * @param dst the destination file
   * @throws IOException
   */
  void copyFile(Path src, Path dst) throws IOException {

    if (Files.exists(dst)) {

      // If destination exists and contents are identical, skip copying.
      if (Files.isRegularFile(src) && Files.isRegularFile(dst)) {
        long mismatch = Files.mismatch(src, dst);
        if (mismatch == -1L) {
          if (verbose) {
            System.out.println("skip (identical) " + dst);
          }
          return;
        }
      }

      if (noClobber) {
        if (verbose) {
          System.out.println("skip " + dst);
        }
        return;
      }

      if (!force) {
        throw new FileAlreadyExistsException(dst.toString());
      }
    }

    Files.copy(src, dst,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.COPY_ATTRIBUTES);

    if (verbose) {
      System.out.println(src + " -> " + dst);
    }
  }

  /**
   * Recursively copy a directory
   * 
   * @param src the source directory
   * @param dst the destination directory
   * @throws IOException
   */
  void copyDirectory(Path src, Path dst) throws IOException {
    List<CompletableFuture<Void>> futures = new ArrayList<>();

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

      Files.walkFileTree(src, new SimpleFileVisitor<>() {

        @Override
        public FileVisitResult preVisitDirectory(Path dir,
            BasicFileAttributes attrs)
            throws IOException {

          Path targetDir = dst.resolve(src.relativize(dir));
          Files.createDirectories(targetDir);

          if (verbose) {
            System.out.println("dir  " + dir + " -> " + targetDir);
          }

          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file,
            BasicFileAttributes attrs)
            throws IOException {

          Path destFile = dst.resolve(src.relativize(file));

          futures.add(CompletableFuture.runAsync(() -> {
            try {
              copyFile(file, destFile);
            } catch (IOException e) {
              throw new CompletionException(e);
            }
          }, executor));

          return FileVisitResult.CONTINUE;
        }
      });

      try {
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
      } catch (CompletionException e) {
        Throwable c = e.getCause();
        if (c instanceof IOException) {
          throw (IOException) c;
        }
        throw e;
      }
    }
  }

  public static void main(String[] args) {
    int exitCode = new CommandLine(new cp()).execute(args);
    System.exit(exitCode);
  }
}