/*
 * Copyright 2017 The gRPC Authors
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

package io.grpc;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.grpc.MethodDescriptor.Marshaller;
import io.opencensus.trace.Span;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.logging.Logger;
import javax.annotation.Nullable;

// TODO(zpencer): rename class to AbstractBinaryLog
@Internal
public abstract class BinaryLogProvider implements Closeable {
  public static final CallOptions.Key<CallId> CLIENT_CALL_ID_CALLOPTION_KEY
      = CallOptions.Key.create("binarylog-calloptions-key");
  @VisibleForTesting
  public static final Marshaller<byte[]> BYTEARRAY_MARSHALLER = new ByteArrayMarshaller();

  private static final Logger logger = Logger.getLogger(BinaryLogProvider.class.getName());

  private final ClientInterceptor binaryLogShim = new BinaryLogShim();

  /**
   * Wraps a channel to provide binary logging on {@link ClientCall}s as needed.
   */
  public final Channel wrapChannel(Channel channel) {
    return ClientInterceptors.intercept(channel, binaryLogShim);
  }

  private static MethodDescriptor<byte[], byte[]> toByteBufferMethod(
      MethodDescriptor<?, ?> method) {
    return method.toBuilder(BYTEARRAY_MARSHALLER, BYTEARRAY_MARSHALLER).build();
  }

  /**
   * Wraps a {@link ServerMethodDefinition} such that it performs binary logging if needed.
   */
  public final <ReqT, RespT> ServerMethodDefinition<?, ?> wrapMethodDefinition(
      ServerMethodDefinition<ReqT, RespT> oMethodDef) {
    ServerInterceptor binlogInterceptor =
        getServerInterceptor(oMethodDef.getMethodDescriptor().getFullMethodName());
    if (binlogInterceptor == null) {
      return oMethodDef;
    }
    MethodDescriptor<byte[], byte[]> binMethod =
        BinaryLogProvider.toByteBufferMethod(oMethodDef.getMethodDescriptor());
    ServerMethodDefinition<byte[], byte[]> binDef =
        ServerInterceptors.wrapMethod(oMethodDef, binMethod);
    ServerCallHandler<byte[], byte[]> binlogHandler =
        ServerInterceptors.InterceptCallHandler.create(
            binlogInterceptor, binDef.getServerCallHandler());
    return ServerMethodDefinition.create(binMethod, binlogHandler);
  }

  /**
   * Returns a {@link ServerInterceptor} for binary logging. gRPC is free to cache the interceptor,
   * so the interceptor must be reusable across calls. At runtime, the request and response
   * marshallers are always {@code Marshaller<InputStream>}.
   * Returns {@code null} if this method is not binary logged.
   */
  // TODO(zpencer): ensure the interceptor properly handles retries and hedging
  @Nullable
  protected abstract ServerInterceptor getServerInterceptor(String fullMethodName);

  /**
   * Returns a {@link ClientInterceptor} for binary logging. gRPC is free to cache the interceptor,
   * so the interceptor must be reusable across calls. At runtime, the request and response
   * marshallers are always {@code Marshaller<InputStream>}.
   * Returns {@code null} if this method is not binary logged.
   */
  // TODO(zpencer): ensure the interceptor properly handles retries and hedging
  @Nullable
  protected abstract ClientInterceptor getClientInterceptor(
      String fullMethodName, CallOptions callOptions);

  @Override
  public void close() throws IOException {
    // default impl: noop
    // TODO(zpencer): make BinaryLogProvider provide a BinaryLog, and this method belongs there
  }

  // Creating a named class makes debugging easier
  private static final class ByteArrayMarshaller implements Marshaller<byte[]> {
    @Override
    public InputStream stream(byte[] value) {
      return new ByteArrayInputStream(value);
    }

    @Override
    public byte[] parse(InputStream stream) {
      try {
        return parseHelper(stream);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    private byte[] parseHelper(InputStream stream) throws IOException {
      try {
        return IoUtils.toByteArray(stream);
      } finally {
        stream.close();
      }
    }
  }

  /**
   * The pipeline of interceptors is hard coded when the {@link ManagedChannel} is created.
   * This shim interceptor should always be installed as a placeholder. When a call starts,
   * this interceptor checks with the {@link BinaryLogProvider} to see if logging should happen
   * for this particular {@link ClientCall}'s method.
   */
  private final class BinaryLogShim implements ClientInterceptor {
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method,
        CallOptions callOptions,
        Channel next) {
      ClientInterceptor binlogInterceptor = getClientInterceptor(
          method.getFullMethodName(), callOptions);
      if (binlogInterceptor == null) {
        return next.newCall(method, callOptions);
      } else {
        return ClientInterceptors
            .wrapClientInterceptor(
                binlogInterceptor,
                BYTEARRAY_MARSHALLER,
                BYTEARRAY_MARSHALLER)
            .interceptCall(method, callOptions, next);
      }
    }
  }

  /**
   * A CallId is two byte[] arrays both of size 8 that uniquely identifies the RPC. Users are
   * free to use the byte arrays however they see fit.
   */
  public static final class CallId {
    public final long hi;
    public final long lo;

    /**
     * Creates an instance.
     */
    public CallId(long hi, long lo) {
      this.hi = hi;
      this.lo = lo;
    }

    public static CallId fromCensusSpan(Span span) {
      return new CallId(0, ByteBuffer.wrap(span.getContext().getSpanId().getBytes()).getLong());
    }
  }

  // Copied from internal
  private static final class IoUtils {
    /** maximum buffer to be read is 16 KB. */
    private static final int MAX_BUFFER_LENGTH = 16384;

    /** Returns the byte array. */
    public static byte[] toByteArray(InputStream in) throws IOException {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      copy(in, out);
      return out.toByteArray();
    }

    /** Copies the data from input stream to output stream. */
    public static long copy(InputStream from, OutputStream to) throws IOException {
      // Copied from guava com.google.common.io.ByteStreams because its API is unstable (beta)
      Preconditions.checkNotNull(from);
      Preconditions.checkNotNull(to);
      byte[] buf = new byte[MAX_BUFFER_LENGTH];
      long total = 0;
      while (true) {
        int r = from.read(buf);
        if (r == -1) {
          break;
        }
        to.write(buf, 0, r);
        total += r;
      }
      return total;
    }
  }
}
