package com.epam.aidial.core.server.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import com.epam.aidial.core.server.log.layout.AutoEnrichedOtelJsonLayout;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.LoggerFactory;

/** Supplies the Core-private layout as an object: Shared Logback cannot load it by XML class name. */
public final class LoggingInitializer {

    private LoggingInitializer() {
    }

    public static void initialize() {
        if (!"dial-core".equals(LoggingInitializer.class.getClassLoader().getName())) {
            return;
        }
        if (System.getProperty("logback.configurationFile") != null) {
            throw new IllegalStateException("Custom logback.configurationFile is not supported by the split-loader POC");
        }
        String level = System.getProperty("AIDIAL_LOG_LEVEL", System.getenv("AIDIAL_LOG_LEVEL"));
        configure((LoggerContext) LoggerFactory.getILoggerFactory(), level);
    }

    static void configure(LoggerContext context, String level) {
        context.reset();
        AutoEnrichedOtelJsonLayout layout = new AutoEnrichedOtelJsonLayout();
        layout.setContext(context);
        layout.start();
        LayoutWrappingEncoder<ILoggingEvent> encoder = new LayoutWrappingEncoder<>();
        encoder.setContext(context);
        encoder.setLayout(layout);
        encoder.start();
        ConsoleAppender<ILoggingEvent> console = new ConsoleAppender<>();
        console.setName("CONSOLE_JSON");
        console.setContext(context);
        console.setEncoder(encoder);
        console.start();
        OpenTelemetryAppender telemetry = new OpenTelemetryAppender();
        telemetry.setName("OTEL");
        telemetry.setContext(context);
        telemetry.start();
        context.getLogger("io.vertx").setLevel(Level.WARN);
        context.getLogger("io.vertx.tracing").setLevel(Level.DEBUG);
        context.getLogger("io.opentelemetry").setLevel(Level.DEBUG);
        context.getLogger("org.redisson").setLevel(Level.WARN);
        context.getLogger("org.redisson.connection").setLevel(Level.INFO);
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.toLevel(level, Level.INFO));
        root.addAppender(console);
        root.addAppender(telemetry);
    }
}
