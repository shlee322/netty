/*
 * Copyright 2015 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.http;

import io.netty.handler.codec.Headers;
import io.netty.util.internal.StringUtil;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

import static io.netty.util.internal.StringUtil.COMMA;

/**
 * Will add multiple values for the same header as single header with a comma separated list of values.
 * <p>
 * Please refer to section <a href="https://tools.ietf.org/html/rfc7230#section-3.2.2">RFC 7230, 3.2.2</a>.
 */
public class CombinedHttpHeaders extends DefaultHttpHeaders {
    /**
     * An estimate of the size of a header value.
     */
    private static final int VALUE_LENGTH_ESTIMATE = 10;
    private CsvValueEscaper<Object> objectEscaper;
    private CsvValueEscaper<CharSequence> charSequenceEscaper;

    public CombinedHttpHeaders(boolean validate) {
        super(validate);
    }

    private CsvValueEscaper<Object> objectEscaper() {
        if (objectEscaper == null) {
            objectEscaper = new CsvValueEscaper<Object>() {
                @Override
                public CharSequence escape(Object value) {
                    return StringUtil.escapeCsv(valueConverter().convertObject(value));
                }
            };
        }
        return objectEscaper;
    }

    private CsvValueEscaper<CharSequence> charSequenceEscaper() {
        if (charSequenceEscaper == null) {
            charSequenceEscaper = new CsvValueEscaper<CharSequence>() {
                @Override
                public CharSequence escape(CharSequence value) {
                    return StringUtil.escapeCsv(value);
                }
            };
        }
        return charSequenceEscaper;
    }

    @Override
    public HttpHeaders add(Headers<? extends CharSequence, ? extends CharSequence, ?> headers) {
        // Override the fast-copy mechanism used by DefaultHeaders
        if (headers == this) {
            throw new IllegalArgumentException("can't add to itself.");
        }
        if (headers instanceof CombinedHttpHeaders) {
            if (isEmpty()) {
                // Can use the fast underlying copy
                addImpl(headers);
            } else {
                // Values are already escaped so don't escape again
                for (Map.Entry<? extends CharSequence, ? extends CharSequence> header : headers) {
                    addEscapedValue(header.getKey(), header.getValue());
                }
            }
        } else {
            for (Map.Entry<? extends CharSequence, ? extends CharSequence> header : headers) {
                add(header.getKey(), header.getValue());
            }
        }
        return this;
    }

    @Override
    public HttpHeaders set(Headers<? extends CharSequence, ? extends CharSequence, ?> headers) {
        if (headers == this) {
            return this;
        }
        clear();
        return add(headers);
    }

    @Override
    public HttpHeaders setAll(Headers<? extends CharSequence, ? extends CharSequence, ?> headers) {
        if (headers == this) {
            return this;
        }
        for (CharSequence key : headers.names()) {
            remove(key);
        }
        return add(headers);
    }

    @Override
    public CombinedHttpHeaders add(CharSequence name, CharSequence value) {
        return addEscapedValue(name, StringUtil.escapeCsv(value));
    }

    @Override
    public CombinedHttpHeaders add(CharSequence name, CharSequence... values) {
        return addEscapedValue(name, commaSeparate(charSequenceEscaper(), values));
    }

    @Override
    public CombinedHttpHeaders add(CharSequence name, Iterable<? extends CharSequence> values) {
        return addEscapedValue(name, commaSeparate(charSequenceEscaper(), values));
    }

    @Override
    public CombinedHttpHeaders addObject(CharSequence name, Iterable<?> values) {
        return addEscapedValue(name, commaSeparate(objectEscaper(), values));
    }

    @Override
    public CombinedHttpHeaders addObject(CharSequence name, Object... values) {
        return addEscapedValue(name, commaSeparate(objectEscaper(), values));
    }

    @Override
    public CombinedHttpHeaders set(CharSequence name, CharSequence... values) {
        super.set(name, commaSeparate(charSequenceEscaper(), values));
        return this;
    }

    @Override
    public CombinedHttpHeaders set(CharSequence name, Iterable<? extends CharSequence> values) {
        super.set(name, commaSeparate(charSequenceEscaper(), values));
        return this;
    }

    @Override
    public CombinedHttpHeaders setObject(CharSequence name, Object... values) {
        super.set(name, commaSeparate(objectEscaper(), values));
        return this;
    }

    @Override
    public CombinedHttpHeaders setObject(CharSequence name, Iterable<?> values) {
        super.set(name, commaSeparate(objectEscaper(), values));
        return this;
    }

    private CombinedHttpHeaders addEscapedValue(CharSequence name, CharSequence escapedValue) {
        CharSequence currentValue = super.get(name);
        if (currentValue == null) {
            super.add(name, escapedValue);
        } else {
            super.set(name, commaSeparateEscapedValues(currentValue, escapedValue));
        }
        return this;
    }

    private static <T> CharSequence commaSeparate(CsvValueEscaper<T> escaper, T... values) {
        StringBuilder sb = new StringBuilder(values.length * VALUE_LENGTH_ESTIMATE);
        if (values.length > 0) {
            int end = values.length - 1;
            for (int i = 0; i < end; i++) {
                sb.append(escaper.escape(values[i])).append(COMMA);
            }
            sb.append(escaper.escape(values[end]));
        }
        return sb;
    }

    private static <T> CharSequence commaSeparate(CsvValueEscaper<T> escaper, Iterable<? extends T> values) {
        @SuppressWarnings("rawtypes")
        final StringBuilder sb = values instanceof Collection
                ? new StringBuilder(((Collection) values).size() * VALUE_LENGTH_ESTIMATE) : new StringBuilder();
        Iterator<? extends T> iterator = values.iterator();
        if (iterator.hasNext()) {
            T next = iterator.next();
            while (iterator.hasNext()) {
                sb.append(escaper.escape(next)).append(COMMA);
                next = iterator.next();
            }
            sb.append(escaper.escape(next));
        }
        return sb;
    }

    private CharSequence commaSeparateEscapedValues(CharSequence currentValue, CharSequence value) {
        return new StringBuilder(currentValue.length() + 1 + value.length())
                .append(currentValue)
                .append(COMMA)
                .append(value);
    }

    /**
     * Escapes comma separated values (CSV).
     *
     * @param <T> The type that a concrete implementation handles
     */
    private interface CsvValueEscaper<T> {
        /**
         * Appends the value to the specified {@link StringBuilder}, escaping if necessary.
         *
         * @param value the value to be appended, escaped if necessary
         */
        CharSequence escape(T value);
    }
}