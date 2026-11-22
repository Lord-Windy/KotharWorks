/*
 * Copyright 2026 Samuel "Lord-Windy" Brown
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.kotharworks.wilds.server.adapters.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Ticket 09: the escape-by-default HTML builder must neutralize the XSS
 * payload class at every dynamic entry point (text nodes, attribute values)
 * while only {@link Html.RawTrustedHtml} emits markup verbatim.
 */
class HtmlTest {

    private static final String PAYLOAD = "House <script>alert(1)</script>";

    @Test
    void escapeCoversEveryHtmlSignificantCharacter() {
        assertEquals("a&amp;b", Html.escape("a&b"));
        assertEquals("&lt;x&gt;", Html.escape("<x>"));
        assertEquals("&quot;q&quot;", Html.escape("\"q\""));
        assertEquals("&#39;p&#39;", Html.escape("'p'"));
        assertEquals("House &lt;script&gt;alert(1)&lt;/script&gt;", Html.escape(PAYLOAD));
        assertEquals("plain", Html.escape("plain"));
    }

    @Test
    void dynamicTextNodesAreAlwaysEscaped() {
        String html = Html.create()
                .open("p")
                .text(PAYLOAD)
                .close("p")
                .render();
        assertTrue(html.contains("House &lt;script&gt;alert(1)&lt;/script&gt;"), html);
        assertFalse(html.contains(PAYLOAD), "raw payload must never survive a text node");
    }

    @Test
    void elChildrenAreEscaped() {
        String html = Html.create()
                .el("li", new Html.Attr[] { new Html.Attr("class", "event") }, PAYLOAD, " tail")
                .render();
        assertEquals("<li class=\"event\">House &lt;script&gt;alert(1)&lt;/script&gt;"
                + " tail</li>", html);
    }

    @Test
    void attributeValuesCannotBreakOutOfQuotes() {
        String html = Html.create()
                .open("button", new Html.Attr("data-name", "a\"b'onClick=x"),
                        new Html.Attr("title", "<img src=x onerror=alert(1)>"))
                .text("go")
                .close("button")
                .render();
        assertTrue(html.contains("data-name=\"a&quot;b&#39;onClick=x\""), html);
        assertTrue(html.contains("title=\"&lt;img src=x onerror=alert(1)&gt;\""), html);
        assertFalse(html.contains("onClick=x>"), html);
    }

    @Test
    void rawTrustedHtmlIsTheOnlyVerbatimPath() {
        String html = Html.create()
                .doctype()
                .el("p", new Html.Attr[0], "safe")
                .render();
        assertEquals("<!DOCTYPE html>\n<p>safe</p>", html);

        String trusted = Html.create()
                .raw(new Html.RawTrustedHtml("<b>bold</b>"))
                .render();
        assertEquals("<b>bold</b>", trusted);
    }

    @Test
    void voidTagAndDocumentShape() {
        String html = Html.create()
                .doctype()
                .open("html", new Html.Attr("lang", "en"))
                .voidTag("meta", new Html.Attr("charset", "utf-8"))
                .close("html")
                .render();
        assertEquals("<!DOCTYPE html>\n<html lang=\"en\"><meta charset=\"utf-8\"></html>", html);
    }

    @Test
    void dynamicTagAndAttributeNamesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Html.create().open("<script>"));
        assertThrows(IllegalArgumentException.class, () -> Html.create().open("p",
                new Html.Attr("x onclick", "1")));
        assertThrows(IllegalArgumentException.class, () -> Html.create().close("P"));
        assertThrows(IllegalArgumentException.class, () -> Html.create().voidTag(""));
    }
}
