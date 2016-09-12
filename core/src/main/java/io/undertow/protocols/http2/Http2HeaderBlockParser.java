/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.undertow.protocols.http2;

import io.undertow.UndertowLogger;

import io.undertow.UndertowMessages;
import io.undertow.util.HeaderMap;
import io.undertow.util.HttpString;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Parser for HTTP2 headers
 *
 * @author Stuart Douglas
 */
abstract class Http2HeaderBlockParser extends Http2PushBackParser implements HpackDecoder.HeaderEmitter {

    private final HeaderMap headerMap = new HeaderMap();
    private boolean beforeHeadersHandled = false;

    private final HpackDecoder decoder;
    private int frameRemaining = -1;
    private boolean invalid = false;
    private boolean processingPseudoHeaders = true;

    Http2HeaderBlockParser(int frameLength, HpackDecoder decoder) {
        super(frameLength);
        this.decoder = decoder;
    }

    @Override
    protected void handleData(ByteBuffer resource, Http2FrameHeaderParser header) throws IOException {
        if (frameRemaining == -1) {
            frameRemaining = header.length;
        }
        final boolean moreDataThisFrame = resource.remaining() < frameRemaining;
        final int pos = resource.position();
        try {
            if (!beforeHeadersHandled) {
                if (!handleBeforeHeader(resource, header)) {
                    return;
                }
            }
            beforeHeadersHandled = true;
            decoder.setHeaderEmitter(this);
            try {
                decoder.decode(resource, moreDataThisFrame);
            } catch (HpackException e) {
                throw new ConnectionErrorException(Http2Channel.ERROR_COMPRESSION_ERROR, e);
            }
        } finally {
            int used = resource.position() - pos;
            frameRemaining -= used;
        }
    }

    protected abstract boolean handleBeforeHeader(ByteBuffer resource, Http2FrameHeaderParser header);


    HeaderMap getHeaderMap() {
        return headerMap;
    }

    @Override
    public void emitHeader(HttpString name, String value, boolean neverIndex) {
        headerMap.add(name, value);
        if(name.length() == 0) {
            throw UndertowMessages.MESSAGES.invalidHeader();
        }
        if(name.byteAt(0) == ':') {
            if(!processingPseudoHeaders) {
                throw UndertowMessages.MESSAGES.pseudoHeaderInWrongOrder(name);
            }
        } else {
            processingPseudoHeaders = false;
        }
        for(int i = 0; i < name.length(); ++i) {
            byte c = name.byteAt(i);
            if(c>= 'A' && c <= 'Z') {
                invalid = true;
                UndertowLogger.REQUEST_LOGGER.debugf("Malformed request, header %s contains uppercase characters", name);
            }
        }

    }

    @Override
    protected void moreData(int data) {
        super.moreData(data);
        frameRemaining += data;
    }

    public boolean isInvalid() {
        return invalid;
    }
}
