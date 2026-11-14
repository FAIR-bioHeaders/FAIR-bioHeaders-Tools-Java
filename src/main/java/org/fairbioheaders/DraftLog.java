/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The loud channel for rule V7. A record read through the draft lane carries a
 * {@code V7} warning in its {@link VerifyResult} - which is the checkable part - and the same
 * statement is logged here, so an interactive reader cannot mistake a draft reading for a
 * released-contract reading.
 *
 * <p>This is {@code java.util.logging}, the JDK's own logging API: the library adds no logging
 * dependency and an application routes or silences these records with the {@link Logger} named
 * {@value #LOGGER_NAME} like any other JDK logger. The reference toolkit's equivalent is its
 * {@code ExperimentalDraftWarning}.
 */
final class DraftLog {

    static final String LOGGER_NAME = "org.fairbioheaders.draft";

    private static final Logger LOG = Logger.getLogger(LOGGER_NAME);

    private DraftLog() {
    }

    static void draft(String message) {
        LOG.log(Level.WARNING, message);
    }
}
