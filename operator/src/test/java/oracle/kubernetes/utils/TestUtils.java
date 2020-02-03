// Copyright (c) 2018, 2019, Oracle Corporation and/or its affiliates.  All rights reserved.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import ch.qos.logback.classic.LoggerContext;
import com.meterware.simplestub.Memento;
import oracle.kubernetes.operator.logging.LoggingFactory;
import org.slf4j.LoggerFactory;

import static com.meterware.simplestub.Stub.createStub;

public class TestUtils {
  /**
   * Removes the console handlers from the specified logger, in order to silence them during a test.
   *
   * @return a collection of the removed handlers
   */
  public static ConsoleHandlerMemento silenceOperatorLogger() {
    Logger logger = LoggingFactory.getLogger("Operator", "Operator").getUnderlyingLogger();
    List<Handler> savedHandlers = new ArrayList<>();
    for (Handler handler : logger.getHandlers()) {
      if (handler instanceof ConsoleHandler) {
        savedHandlers.add(handler);
      }
    }

    for (Handler handler : savedHandlers) {
      logger.removeHandler(handler);
    }

    TestLogHandler testHandler = createStub(TestLogHandler.class);
    logger.addHandler(testHandler);

    return new ConsoleHandlerMemento(logger, testHandler, savedHandlers);
  }

  /**
   * Removes the console handlers from the specified logger, in order to silence them during a test.
   *
   * @param logger a logger to silence
   * @return a collection of the removed handlers
   */
  public static List<Handler> removeConsoleHandlers(Logger logger) {
    List<Handler> savedHandlers = new ArrayList<>();
    for (Handler handler : logger.getHandlers()) {
      if (handler instanceof ConsoleHandler) {
        savedHandlers.add(handler);
      }
    }
    for (Handler handler : savedHandlers) {
      logger.removeHandler(handler);
    }
    return savedHandlers;
  }

  /**
   * Restores the silenced logger handlers.
   *
   * @param logger a logger to restore
   * @param savedHandlers the handlers to restore
   */
  public static void restoreConsoleHandlers(Logger logger, List<Handler> savedHandlers) {
    for (Handler handler : savedHandlers) {
      logger.addHandler(handler);
    }
  }

  public static Memento silenceJsonPathLogger() {
    return new JsonPathLoggerMemento();
  }

  abstract static class TestLogHandler extends Handler {
    private static final List<String> ALL_MESSAGES = new ArrayList<>();
    private Throwable throwable;
    private List<Throwable> ignoredExceptions = new ArrayList<>();
    private List<Class<? extends Throwable>> ignoredClasses = new ArrayList<>();
    private Collection<LogRecord> logRecords = new ArrayList<>();
    private List<String> messagesToTrack = new ArrayList<>();

    @Override
    public void publish(LogRecord record) {
      if (record.getThrown() != null && !shouldIgnore(record.getThrown())) {
        throwable = record.getThrown();
      }
      if (shouldTrack(record)) {
        logRecords.add(record);
      }
    }

    private boolean shouldTrack(LogRecord record) {
      return messagesToTrack == ALL_MESSAGES || messagesToTrack.contains(record.getMessage());
    }

    boolean shouldIgnore(Throwable thrown) {
      return ignoredExceptions.contains(thrown) || ignoredClasses.contains(thrown.getClass());
    }

    void throwLoggedThrowable() {
      if (throwable == null) {
        return;
      }

      throwable.printStackTrace();
      if (throwable instanceof Error) {
        throw (Error) throwable;
      }
      if (throwable instanceof RuntimeException) {
        throw (RuntimeException) throwable;
      }
      throw new RuntimeException(throwable);
    }

    void ignoreLoggedException(Throwable t) {
      ignoredExceptions.add(t);
    }

    void ignoreLoggedException(Class<? extends Throwable> t) {
      ignoredClasses.add(t);
    }

    void collectLogMessages(Collection<LogRecord> collection, String[] messages) {
      this.logRecords = collection;
      this.messagesToTrack = new ArrayList<>();
      this.messagesToTrack.addAll(Arrays.asList(messages));
    }

    void collectAllLogMessages(Collection<LogRecord> collection) {
      this.logRecords = collection;
      this.messagesToTrack = ALL_MESSAGES;
    }

    void throwUncheckedLogMessages() {
      if (logRecords.isEmpty()) {
        return;
      }

      SimpleFormatter formatter = new SimpleFormatter();
      List<String> messageKeys = new ArrayList<>();
      for (LogRecord record : logRecords) {
        messageKeys.add(formatter.format(record));
      }

      throw new AssertionError("Unexpected log messages " + messageKeys);
    }
  }

  public static class ConsoleHandlerMemento implements Memento {
    private Logger logger;
    private TestLogHandler testHandler;
    private List<Handler> savedHandlers;
    private Level savedLogLevel;
    // log level could be null, so need a boolean to indicate if we have saved it
    private boolean loggerLevelSaved;

    ConsoleHandlerMemento(Logger logger, TestLogHandler testHandler, List<Handler> savedHandlers) {
      this.logger = logger;
      this.testHandler = testHandler;
      this.savedHandlers = savedHandlers;
    }

    public ConsoleHandlerMemento ignoringLoggedExceptions(Throwable... throwables) {
      for (Throwable throwable : throwables) {
        testHandler.ignoreLoggedException(throwable);
      }
      return this;
    }

    @SafeVarargs
    public final ConsoleHandlerMemento ignoringLoggedExceptions(
        Class<? extends Throwable>... classes) {
      for (Class<? extends Throwable> klass : classes) {
        testHandler.ignoreLoggedException(klass);
      }
      return this;
    }

    public ConsoleHandlerMemento collectLogMessages(
        Collection<LogRecord> collection, String... messages) {
      testHandler.collectLogMessages(collection, messages);
      return this;
    }

    public ConsoleHandlerMemento collectAllLogMessages(Collection<LogRecord> collection) {
      testHandler.collectAllLogMessages(collection);
      return this;
    }

    public ConsoleHandlerMemento withLogLevel(Level logLevel) {
      if (!loggerLevelSaved) {
        savedLogLevel = logger.getLevel();
        loggerLevelSaved = true;
      }
      logger.setLevel(logLevel);
      return this;
    }

    public void ignoreMessage(String message) {
      testHandler.messagesToTrack.remove(message);
    }

    @Override
    public void revert() {
      logger.removeHandler(testHandler);
      restoreConsoleHandlers(logger, savedHandlers);
      if (loggerLevelSaved) {
        logger.setLevel(savedLogLevel);
      }

      testHandler.throwLoggedThrowable();
      testHandler.throwUncheckedLogMessages();
    }

    @Override
    public <T> T getOriginalValue() {
      throw new UnsupportedOperationException();
    }
  }

  static class JsonPathLoggerMemento implements Memento {

    private final ch.qos.logback.classic.Logger log;
    private final ch.qos.logback.classic.Level originalLogLevel;

    private JsonPathLoggerMemento() {
      LoggerContext logContext = (LoggerContext) LoggerFactory.getILoggerFactory();
      log = logContext.getLogger("com.jayway.jsonpath.internal.path.CompiledPath");
      originalLogLevel = log.getLevel();
      log.setLevel(ch.qos.logback.classic.Level.INFO);
    }

    static Memento silenceLogger() {
      return new JsonPathLoggerMemento();
    }

    @Override
    public void revert() {
      log.setLevel(originalLogLevel);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getOriginalValue() {
      return (T) originalLogLevel;
    }
  }
}
