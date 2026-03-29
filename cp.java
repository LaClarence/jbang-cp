///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 26
//DEPS info.picocli:picocli:4.7.7

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Mixin;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.time.Duration;
import java.time.Instant;

@Command(name = "cp", mixinStandardHelpOptions = true, version = {
    "cp 1.0.1",
    "Java 26 implementation",
    "CopyLeft 2026"
}, description = "Copy files and directories")
public final class cp implements Callable<Integer> {

  private static record TimingContext(boolean showTime, Instant start) {
    static TimingContext create(boolean showTime) {
      return new TimingContext(showTime, showTime ? Instant.now() : null);
    }

    void after() {
      if (showTime && start != null) {
        var elapsed = Duration.between(start, Instant.now());
        System.out.printf("Elapsed time: %s%n", formatDuration(elapsed));
      }
    }
  }

  private static String formatDuration(Duration duration) {
    long hours = duration.toHours();
    int minutes = duration.toMinutesPart();
    int seconds = duration.toSecondsPart();
    int millis = duration.toMillisPart();

    return (hours > 0 ? hours + "h " : "") +
        (hours > 0 || minutes > 0 ? minutes + "m " : "") +
        seconds + '.' + String.format("%03d", millis) + "s";
  }

  private static final class Arguments {
    @Option(names = "-r", description = "recursive copy")
    boolean recursive;

    @Option(names = "-f", description = "force overwrite")
    boolean force;

    @Option(names = "-n", description = "no overwrite")
    boolean noClobber;

    @Option(names = "-v", description = "verbose")
    boolean verbose;

    @Option(names = { "-T", "--time" }, description = "print elapsed time")
    boolean showTime;

    @Parameters(index = "0")
    Path source;

    @Parameters(index = "1")
    Path target;
  }

  public static void main(String[] args) {
    int exitCode = new CommandLine(new cp()).execute(args);
    System.exit(exitCode);
  }

  @Mixin
  private Arguments arguments;

  @Override
  public Integer call() throws Exception {
    var context = TimingContext.create(arguments.showTime);
    performCopy();
    context.after();
    return 0;
  }

  private void performCopy() throws IOException {
    if (Files.isDirectory(arguments.source)) {
      if (!arguments.recursive) {
        throw new IllegalArgumentException("Source is a directory (use -r)");
      }
      copyDirectory(arguments.source, arguments.target);
    } else {
      copyFile(arguments.source, arguments.target);
    }
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
      if (Files.isRegularFile(src) && Files.isRegularFile(dst)) {
        long mismatch = Files.mismatch(src, dst);
        if (mismatch == -1L) {
          if (arguments.verbose) {
            System.out.println("skip (identical) " + dst);
          }
          return;
        }
      }
      if (arguments.noClobber) {
        if (arguments.verbose) {
          System.out.println("skip " + dst);
        }
        return;
      }
      if (!arguments.force) {
        throw new FileAlreadyExistsException(dst.toString());
      }
    }
    Files.copy(src, dst,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.COPY_ATTRIBUTES);
    if (arguments.verbose) {
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
    List<Future<?>> futures = new ArrayList<>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Files.walkFileTree(src, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult preVisitDirectory(Path dir,
            BasicFileAttributes attrs)
            throws IOException {
          Path targetDir = dst.resolve(src.relativize(dir));
          Files.createDirectories(targetDir);
          if (arguments.verbose) {
            System.out.println("dir  " + dir + " -> " + targetDir);
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file,
            BasicFileAttributes attrs)
            throws IOException {
          Path destFile = dst.resolve(src.relativize(file));
          futures.add(executor.submit(() -> {
            copyFile(file, destFile);
            return null;
          }));
          return FileVisitResult.CONTINUE;
        }
      });

      for (Future<?> future : futures) {
        try {
          future.get();
        } catch (ExecutionException e) {
          if (e.getCause() instanceof IOException ioEx) {
            throw ioEx;
          }
          throw new IOException(e);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException(e);
        }
      }
    }
  }

}