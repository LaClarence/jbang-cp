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
import java.util.function.Supplier;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;
import java.io.PrintStream;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.io.UncheckedIOException;

@Command(name = "cp", mixinStandardHelpOptions = true, version = {
    "cp 1.0.2",
    "Java 26 implementation",
    "CopyLeft 2026"
}, description = "Copy files and directories")
public final class cp implements Callable<Integer> {

  private static final int COPY_PERFORMED = 0;
  private static final int COPY_FAILED = 2;

  private static record TimingContext(boolean showTime, Instant start) {
    static TimingContext create(boolean showTime) {
      return new TimingContext(showTime, showTime ? Instant.now() : null);
    }

    void after(Printer printer) {
      if (showTime && start != null) {
        var elapsed = Duration.between(start, Instant.now());
        printer.log(() -> new LogEvent(Level.INFO, "Elapsed time: " + formatDuration(elapsed)));
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

  private static final PrintStream Logger = System.out;

  private Printer printer = _ -> {
    // empty implementation for non-verbose mode
  };

  private static record LogEvent(Level level, String msg) {
  }

  @FunctionalInterface
  private static interface Printer {
    void log(Supplier<LogEvent> event);
  }

  @Override
  public Integer call() {
    var context = TimingContext.create(arguments.showTime);
    // initialize printer strategy based on verbose flag (use lambdas)
    if (arguments.verbose) {
      this.printer = evt -> {
        LogEvent e = evt.get();
        Logger.println(e.msg());
      };
    }
    try {
      performCopy();
      context.after(printer);
      return COPY_PERFORMED;
    } catch (RuntimeException uex) {
      printError(uex);
      return COPY_FAILED;
    }
  }

  private void printError(RuntimeException uex) {
    String header = switch (uex) {
      case UncheckedIOException _ -> "I/O error: ";
      default -> "Error: ";
    };
    Throwable c = uex.getCause();
    printer.log(() -> new LogEvent(Level.SEVERE, header + (c == null ? uex.getMessage() : c.getMessage())));
    if (arguments.verbose && c != null) {
      printer.log(() -> new LogEvent(Level.SEVERE, formatStackTrace(c)));
    }
  }

  private static String formatStackTrace(Throwable throwable) {
    var sw = new StringWriter();
    throwable.printStackTrace(new PrintWriter(sw));
    return sw.toString();
  }

  private void performCopy() {
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
  void copyFile(Path src, Path dst) {
    if (Files.exists(dst)) {
      if (Files.isRegularFile(src) && Files.isRegularFile(dst)) {
        try {
          long mismatch = Files.mismatch(src, dst);
          if (mismatch == -1L) {
            printer.log(() -> new LogEvent(Level.INFO, "skip (identical) " + dst));
            return;
          }
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
      if (arguments.noClobber) {
        printer.log(() -> new LogEvent(Level.INFO, "skip " + dst));
        return;
      }
      if (!arguments.force) {
        throw new UncheckedIOException(new FileAlreadyExistsException(dst.toString()));
      }
    }
    copyUnchecked(src, dst);
    printer.log(() -> new LogEvent(Level.INFO, src + " -> " + dst));
  }

  private static void copyUnchecked(Path src, Path dst) {
    try {
      Files.copy(src, dst,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.COPY_ATTRIBUTES);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Recursively copy a directory
   * 
   * @param src the source directory
   * @param dst the destination directory
   * @throws IOException
   */
  void copyDirectory(Path src, Path dst) {
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir,
              BasicFileAttributes attrs)
              throws IOException {
            Path targetDir = dst.resolve(src.relativize(dir));
            Files.createDirectories(targetDir);
            printer.log(() -> new LogEvent(Level.INFO, "dir  " + dir + " -> " + targetDir));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file,
              BasicFileAttributes attrs)
              throws IOException {
            Path destFile = dst.resolve(src.relativize(file));

            futures.add(CompletableFuture.runAsync(() -> copyUnchecked(file, destFile), executor));

            return FileVisitResult.CONTINUE;
          }
        });
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }

      CompletableFuture<?>[] cfs = futures.toArray(new CompletableFuture[0]);
      try {
        CompletableFuture.allOf(cfs).join();
      } catch (CompletionException e) {
        Throwable c = e.getCause();
        while (c instanceof CompletionException && c.getCause() != null) {
          c = c.getCause();
        }
        if (c instanceof UncheckedIOException uio) {
          throw uio;
        }
        if (c instanceof IOException ioEx) {
          throw new UncheckedIOException(ioEx);
        }
        throw new RuntimeException(c);
      }
    }
  }

}