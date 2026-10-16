package com.scarabuff.example

import com.google.common.truth.Truth.assertThat
import com.google.flatbuffers.FlatBufferBuilder
import com.scarabuff.example.echo.EchoRequest
import com.scarabuff.example.echo.EchoResponse
import com.scarabuff.example.echo.EchoServiceGrpcKt
import com.scarabuff.example.echo.EchoServiceGrpcKt.EchoServiceCoroutineImplBase
import com.scarabuff.example.echo.EchoServiceGrpcKt.EchoServiceCoroutineStub
import com.scarabuff.example.echo.build
import com.scarabuff.example.echo.buildBytes
import io.grpc.KnownLength
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.NettyServerBuilder
import java.io.InputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * The generated marshaller sends whatever buffer a message sits in, and reads a message whatever
 * way the transport hands it over. flatbuffers-java-grpc's marshaller, used before 0.6.0, sent the
 * wrong bytes for a slice, failed on a direct buffer, and assumed one read returns everything.
 */
@Timeout(10)
class MarshallerTest {

    private lateinit var server: Server
    private lateinit var channel: ManagedChannel

    @BeforeEach
    fun start() {
        val service = object : EchoServiceCoroutineImplBase() {
            override suspend fun echo(request: EchoRequest): EchoResponse = EchoResponse.build(message = request.message)
        }
        server = NettyServerBuilder.forAddress(InetSocketAddress("127.0.0.1", 0)).addService(service).build().start()
        channel = NettyChannelBuilder.forAddress("127.0.0.1", server.port).usePlaintext().build()
    }

    @AfterEach
    fun stop() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    private fun echo(request: EchoRequest): String? = runBlocking { EchoServiceCoroutineStub(channel).echo(request).message }

    @Test
    fun `a message in a slice of a larger array is sent from its own offset`() {
        val bytes = EchoRequest.buildBytes(message = "sliced")
        val larger = ByteArray(bytes.size + 16) { 0x55 }
        bytes.copyInto(larger, destinationOffset = 7)
        val slice = ByteBuffer.wrap(larger, 7, bytes.size).slice().order(ByteOrder.LITTLE_ENDIAN)

        assertThat(echo(EchoRequest.getRootAsEchoRequest(slice))).isEqualTo("sliced")
    }

    @Test
    fun `a message built in a direct buffer is sent`() {
        val direct = object : FlatBufferBuilder.ByteBufferFactory() {
            override fun newByteBuffer(capacity: Int): ByteBuffer = ByteBuffer.allocateDirect(capacity).order(ByteOrder.LITTLE_ENDIAN)
        }
        val request = EchoRequest.build(message = "direct", builder = FlatBufferBuilder(64, direct))

        assertThat(request.byteBuffer.isDirect).isTrue()
        assertThat(echo(request)).isEqualTo("direct")
    }

    @Test
    fun `a message is read whole from a stream that returns one byte per read`() {
        val bytes = EchoRequest.buildBytes(message = "trickled")
        val trickle = object : InputStream(), KnownLength {
            var position = 0
            override fun available() = bytes.size - position
            override fun read(): Int = if (position < bytes.size) bytes[position++].toInt() and 0xff else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (position >= bytes.size) return -1
                b[off] = bytes[position++]
                return 1
            }
        }

        assertThat(EchoServiceGrpcKt.getEchoMethod.parseRequest(trickle).message).isEqualTo("trickled")
    }
}
