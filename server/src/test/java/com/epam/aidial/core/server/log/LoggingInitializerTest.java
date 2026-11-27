package com.epam.aidial.core.server.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import com.epam.aidial.core.server.log.layout.AutoEnrichedOtelJsonLayout;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggingInitializerTest {

    @Test
    void testOrdinaryClasspathKeepsExistingConfiguration() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var console = root.getAppender("CONSOLE_JSON");
        Level level = root.getLevel();
        LoggingInitializer.initialize();
        assertSame(console, root.getAppender("CONSOLE_JSON"));
        assertEquals(level, root.getLevel());
    }

    @Test
    void testDefaultConfigurationUsesCoreLayoutAndBothAppenders() {
        LoggerContext context = new LoggerContext();
        try {
            LoggingInitializer.configure(context, null);
            var root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            assertEquals(Level.INFO, root.getLevel());
            var console = assertInstanceOf(ConsoleAppender.class, root.getAppender("CONSOLE_JSON"));
            var encoder = assertInstanceOf(LayoutWrappingEncoder.class, console.getEncoder());
            assertInstanceOf(AutoEnrichedOtelJsonLayout.class, encoder.getLayout());
            assertTrue(console.isStarted());
            assertTrue(root.getAppender("OTEL").isStarted());
            assertInstanceOf(OpenTelemetryAppender.class, root.getAppender("OTEL"));
            assertEquals(Level.WARN, context.getLogger("io.vertx").getLevel());
            assertEquals(Level.DEBUG, context.getLogger("io.vertx.tracing").getLevel());
            assertEquals(Level.DEBUG, context.getLogger("io.opentelemetry").getLevel());
            assertEquals(Level.WARN, context.getLogger("org.redisson").getLevel());
            assertEquals(Level.INFO, context.getLogger("org.redisson.connection").getLevel());
        } finally {
            context.stop();
        }
    }

    @Test
    void testReconfigurationPreservesLevelAndDoesNotDuplicateAppenders() {
        LoggerContext context = new LoggerContext();
        try {
            LoggingInitializer.configure(context, "ERROR");
            LoggingInitializer.configure(context, "DEBUG");
            var root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            assertEquals(Level.DEBUG, root.getLevel());
            int count = 0;
            var appenders = root.iteratorForAppenders();
            while (appenders.hasNext()) {
                appenders.next();
                count++;
            }
            assertEquals(2, count);
        } finally {
            context.stop();
        }
    }
}
