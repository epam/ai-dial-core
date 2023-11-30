package com.epam.aidial.core.storage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.buffer.Buffer;
import org.jclouds.io.payloads.BasePayload;

import java.io.InputStream;

import static com.google.common.base.Preconditions.checkNotNull;

public class BufferPayload extends BasePayload<ByteBuf> {

    public BufferPayload(Buffer content) {
        this(content, null);
    }

    public BufferPayload(Buffer content, byte[] md5) {
        super(content.getByteBuf());
        getContentMetadata().setContentLength((long) checkNotNull(content, "content").length());
        getContentMetadata().setContentMD5(md5);
    }


    @Override
    public InputStream openStream() {
        return new ByteBufInputStream(content);
    }

    @Override
    public boolean isRepeatable() {
        return false;
    }
}
