/*
 * Copyright 2012-2025 CodeLibs Project and the Others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.codelibs.fess.ds.notion;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * A minimal, in-memory Log4j2 appender for asserting on log output -- in particular, that a
 * secret or a signed URL never reaches a log line, message or thrown-exception chain included.
 * Ported from fess-ds-slack's test helper of the same name.
 *
 * <p>
 * Attach with {@link #attachTo(Class)}, exercise the code under test, inspect with {@link
 * #messagesAt(Level)} or {@link #containsAnywhere(String)}, then always {@link #detach()} --
 * ideally in a {@code finally} block -- so a failed assertion does not leave this appender
 * attached to a logger shared by every other test in the same JVM.
 * </p>
 */
public final class TestLogAppender extends AbstractAppender {

    private static final AtomicLong NEXT_ID = new AtomicLong();

    private final List<LogEvent> events = new CopyOnWriteArrayList<>();

    private Logger attachedLogger;

    private Level previousLevel;

    private TestLogAppender() {
        super("test-capture-" + NEXT_ID.getAndIncrement(), null, null, false, Property.EMPTY_ARRAY);
    }

    /**
     * Creates a new capturing appender and attaches it to the given class's logger, raising that
     * logger's level to {@link Level#TRACE} first so debug-level events are captured too.
     *
     * @param loggedClass the class whose {@code LogManager.getLogger(...)} logger to attach to
     * @return the attached appender; call {@link #detach()} when done with it
     */
    public static TestLogAppender attachTo(final Class<?> loggedClass) {
        final TestLogAppender appender = new TestLogAppender();
        appender.start();
        final Logger logger = (Logger) LogManager.getLogger(loggedClass);
        appender.attachedLogger = logger;
        appender.previousLevel = logger.getLevel();
        logger.addAppender(appender);
        logger.setLevel(Level.TRACE);
        return appender;
    }

    @Override
    public void append(final LogEvent event) {
        events.add(event.toImmutable());
    }

    /**
     * Detaches this appender from the logger it was attached to and restores that logger's
     * original level. Safe to call more than once.
     */
    public void detach() {
        if (attachedLogger != null) {
            attachedLogger.removeAppender(this);
            attachedLogger.setLevel(previousLevel);
            attachedLogger = null;
        }
        stop();
    }

    /**
     * Returns the formatted messages of every captured event at the given level, in capture
     * order. Does not include a thrown exception's own message; see {@link #containsAnywhere}
     * for a check that does.
     *
     * @param level the level to filter by
     * @return the formatted messages at that level
     */
    public List<String> messagesAt(final Level level) {
        return events.stream()
                .filter(e -> e.getLevel().equals(level))
                .map(e -> e.getMessage().getFormattedMessage())
                .collect(Collectors.toList());
    }

    /**
     * Returns whether {@code text} appears anywhere in any captured event: the formatted message,
     * or the message or class name of the thrown exception or any exception in its cause chain.
     * This is the check to use for "a secret must never reach the log", since a plain {@link
     * #messagesAt} check would miss a leak carried only by an exception's own message -- which
     * still ends up in the log output through Log4j2's default exception rendering.
     *
     * @param text the text that must not appear (e.g. a secret URL)
     * @return true if {@code text} was found anywhere in any captured event
     */
    public boolean containsAnywhere(final String text) {
        for (final LogEvent event : events) {
            if (event.getMessage().getFormattedMessage().contains(text)) {
                return true;
            }
            for (Throwable t = event.getThrown(); t != null; t = t.getCause()) {
                if (t.getMessage() != null && t.getMessage().contains(text)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns a snapshot of every captured event's formatted message, across all levels, for
     * diagnostics when an assertion fails.
     *
     * @return the formatted messages, in capture order
     */
    public List<String> allMessages() {
        return new ArrayList<>(events.stream().map(e -> e.getMessage().getFormattedMessage()).collect(Collectors.toList()));
    }

}
