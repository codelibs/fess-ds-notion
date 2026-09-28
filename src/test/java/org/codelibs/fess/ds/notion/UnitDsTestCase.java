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

import org.codelibs.fess.util.ComponentUtil;
import org.dbflute.utflute.lastadi.LastaDiTestCase;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.TestInfo;

public abstract class UnitDsTestCase extends LastaDiTestCase {
    @Override
    protected String prepareConfigFile() {
        return "test_app.xml";
    }

    @Override
    protected void tearDown(final TestInfo testInfo) throws Exception {
        ComponentUtil.setFessConfig(null);
        // NotionClient.setEndpoint/resetEndpoint mutate process-wide static state (see
        // NotionClient's javadoc). Reset it here unconditionally so a subclass that forgets to
        // stop its NotionApiMockServer (or fails before reaching it) cannot leave every later
        // test class in this JVM pointed at a closed port.
        NotionClient.resetEndpoint();
        super.tearDown(testInfo);
    }

    // ===== Assert methods for JUnit 4/5 compatibility =====

    protected void fail(final String message) {
        Assertions.fail(message);
    }

    protected void assertTrue(final String message, final boolean condition) {
        Assertions.assertTrue(condition, message);
    }

    protected void assertFalse(final String message, final boolean condition) {
        Assertions.assertFalse(condition, message);
    }

    protected void assertEquals(final String message, final Object expected, final Object actual) {
        Assertions.assertEquals(expected, actual, message);
    }

    protected void assertEquals(final String message, final long expected, final long actual) {
        Assertions.assertEquals(expected, actual, message);
    }

    protected void assertNotNull(final String message, final Object object) {
        Assertions.assertNotNull(object, message);
    }

    protected void assertNull(final String message, final Object object) {
        Assertions.assertNull(object, message);
    }
}
