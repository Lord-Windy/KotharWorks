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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
        assertEquals("a&amp;&lt;&gt;&quot;&#39;z", Html.escape("a&<>\"'z"));
        assertEquals("House &lt;script&gt;alert(1)&lt;/script&gt;", Html.escape(PAYLOAD));
        String plain = "plain";
        assertSame(plain, Html.escape(plain), "fast path returns the identical String");
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
        assertThrows(IllegalArgumentException.class, () -> Html.create().open("1div"));
        assertThrows(IllegalArgumentException.class, () -> Html.create().open("p",
                new Html.Attr("-bad", "1")));
    }

    @Test
    void voidAndNonVoidElementsCannotBeConfused() {
        assertThrows(IllegalArgumentException.class,
                () -> Html.create().voidTag("script", new Html.Attr("src", "/assets/wilds-ui.js")));
        assertThrows(IllegalArgumentException.class, () -> Html.create().voidTag("div"));
        assertThrows(IllegalArgumentException.class, () -> Html.create().open("meta"));
        assertThrows(IllegalArgumentException.class, () -> Html.create().close("input"));
    }

    @Test
    void consoleJavascriptAssetStaysUnderTwoHundredLinesAndAvoidsUnsafeDomSinks()
            throws IOException {
        try (var stream = AssetRoutes.class.getResourceAsStream("/assets/wilds-ui.js")) {
            assertTrue(stream != null, "/assets/wilds-ui.js must be packaged on classpath");
            String js = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            long lines = js.lines().count();
            assertTrue(lines < 200, "wilds-ui.js must stay < 200 LOC, got: " + lines);
            assertFalse(js.contains("innerHTML"), "DOM XSS guard: zero innerHTML");
            assertFalse(js.contains("outerHTML"), "DOM XSS guard: zero outerHTML");
            assertFalse(js.contains("insertAdjacentHTML"), "DOM XSS guard: zero insertAdjacentHTML");
        }
    }
}
