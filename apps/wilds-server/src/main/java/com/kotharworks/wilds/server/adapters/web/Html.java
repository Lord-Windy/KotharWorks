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

import java.util.Objects;
import java.util.Set;

/**
 * Escape-by-default HTML builder for the server-rendered console: every
 * dynamic value routed through {@link #text(CharSequence)} or an
 * {@link Attr} value is HTML-escaped ({@code & < > " '}), so reflected and
 * stored markup — seat names are deliberately stored HTML-rich — cannot break
 * out of the document. The only bypass is wrapping a string in
 * {@link RawTrustedHtml} and passing it to {@link #raw(RawTrustedHtml)},
 * which this package uses exclusively for static developer-authored markup
 * (the doctype). Tag and attribute names are developer constants, still
 * validated against {@code [a-z][a-z0-9-]*} (and void vs non-void HTML5
 * element sets) so nothing dynamic can smuggle markup through a name or emit
 * an unclosed non-void element.
 *
 * <p>Zero template-engine reflection: the builder appends to one
 * {@link StringBuilder} and renders the accumulated document.
 */
public final class Html {

    private static final Set<String> VOID_TAGS = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img",
            "input", "link", "meta", "source", "track", "wbr");

    private final StringBuilder out = new StringBuilder();

    private Html() {
    }

    /** A string that is emitted verbatim; the single, explicit escape bypass. */
    public record RawTrustedHtml(String trusted) {

        public RawTrustedHtml {
            Objects.requireNonNull(trusted, "trusted");
        }
    }

    /** One attribute; the {@code value} is escaped at render time. */
    public record Attr(String name, String value) {

        public Attr {
            requireName(name);
            Objects.requireNonNull(value, "value");
        }
    }

    /** Starts a fresh document builder. */
    public static Html create() {
        return new Html();
    }

    /**
     * HTML-escapes a dynamic value in a single pass: {@code & < > " '}.
     * Returns {@code value} unchanged when no HTML-significant character is
     * present.
     */
    public static String escape(String value) {
        Objects.requireNonNull(value, "value");
        int first = firstIndexToEscape(value);
        if (first < 0) {
            return value;
        }
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        escaped.append(value, 0, first);
        for (int i = first; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /** First index holding an HTML-significant character, or {@code -1} (fast path). */
    private static int firstIndexToEscape(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '&' || c == '<' || c == '>' || c == '"' || c == '\'') {
                return i;
            }
        }
        return -1;
    }

    /** Appends pre-trusted markup (e.g. {@code RawTrustedHtml}). */
    public Html raw(RawTrustedHtml trusted) {
        out.append(trusted.trusted());
        return this;
    }

    /** Appends {@code <!DOCTYPE html>}. */
    public Html doctype() {
        return raw(new RawTrustedHtml("<!DOCTYPE html>\n"));
    }

    /** Appends the escaped dynamic {@code text}. */
    public Html text(CharSequence text) {
        out.append(escape(text.toString()));
        return this;
    }

    /** Appends an opening tag {@code <tag name="value" ...>}. */
    public Html open(String tag, Attr... attrs) {
        requireNonVoidTag(tag);
        out.append('<').append(tag);
        appendAttrs(attrs);
        out.append('>');
        return this;
    }

    /** Appends a closing tag {@code </tag>}. */
    public Html close(String tag) {
        requireNonVoidTag(tag);
        out.append("</").append(tag).append('>');
        return this;
    }

    /** Appends an HTML5 void element {@code <tag name="value" ...>}. */
    public Html voidTag(String tag, Attr... attrs) {
        requireName(tag);
        if (!VOID_TAGS.contains(tag)) {
            throw new IllegalArgumentException(
                    "not an HTML5 void element: \"" + tag + "\" (use open/close)");
        }
        out.append('<').append(tag);
        appendAttrs(attrs);
        out.append('>');
        return this;
    }

    /**
     * Appends {@code <tag attrs>escaped children...</tag>} — each child is a
     * dynamic text node and therefore escaped.
     */
    public Html el(String tag, Attr[] attrs, CharSequence... textChildren) {
        open(tag, attrs);
        for (CharSequence child : textChildren) {
            text(child);
        }
        return close(tag);
    }

    /** Renders the accumulated document. */
    public String render() {
        return out.toString();
    }

    private void appendAttrs(Attr[] attrs) {
        for (Attr attr : attrs) {
            out.append(' ').append(attr.name()).append("=\"")
                    .append(escape(attr.value())).append('"');
        }
    }

    private static void requireNonVoidTag(String tag) {
        requireName(tag);
        if (VOID_TAGS.contains(tag)) {
            throw new IllegalArgumentException(
                    "HTML5 void element \"" + tag + "\" has no closing tag (use voidTag)");
        }
    }

    /** Developer-constant names only: lowercase ASCII letter followed by [a-z0-9-]. */
    private static void requireName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("empty tag/attribute name");
        }
        char first = name.charAt(0);
        if (first < 'a' || first > 'z') {
            throw new IllegalArgumentException(
                    "tag/attribute names must match [a-z][a-z0-9-]*, got: \"" + name + "\"");
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-')) {
                throw new IllegalArgumentException(
                        "tag/attribute names must match [a-z][a-z0-9-]*, got: \"" + name + "\"");
            }
        }
    }
}
