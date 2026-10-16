package com.scarabuff.example

import com.google.common.truth.Truth.assertThat
import com.google.flatbuffers.FlatBufferBuilder
import com.scarabuff.example.orders.ItemSpec
import com.scarabuff.example.orders.Order
import com.scarabuff.example.orders.OrderServiceGrpcKt.OrderServiceCoroutineImplBase
import com.scarabuff.example.orders.OrderServiceGrpcKt.OrderServiceCoroutineStub
import com.scarabuff.example.orders.Receipt
import com.scarabuff.example.orders.build
import com.scarabuff.example.orders.missingRequiredField
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A request missing a required field, as an older client or one in another language can send,
 * fails with INVALID_ARGUMENT naming the field, for every call shape, and never reaches the
 * implementation. flatc's builders refuse to write such a message, so these are built by hand.
 */
class RequiredFieldsTest {

    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private val calls = AtomicInteger()

    private val service = object : OrderServiceCoroutineImplBase() {
        override suspend fun place(request: Order): Receipt = receipt(request)

        override fun track(request: Order): Flow<Receipt> = flowOf(receipt(request))

        override suspend fun placeAll(requests: Flow<Order>): Receipt =
            Receipt.build(items = requests.map { receipt(it) }.count())

        override fun live(requests: Flow<Order>): Flow<Receipt> = requests.map { receipt(it) }

        // Reads every required field, which throws if one is missing.
        private fun receipt(order: Order): Receipt {
            calls.incrementAndGet()
            check(order.id.isNotEmpty())
            for (i in 0 until order.itemsLength) check(order.items(i)!!.sku.isNotEmpty())
            return Receipt.build(items = order.itemsLength)
        }
    }

    @BeforeEach
    fun start() {
        val name = InProcessServerBuilder.generateName()
        server = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start()
        channel = InProcessChannelBuilder.forName(name).directExecutor().build()
    }

    @AfterEach
    fun stop() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    private val valid = Order.build(id = "o-1", items = listOf(ItemSpec(sku = "a", quantity = 1)))

    /** An Order with no fields at all, so no `id`. */
    private fun withoutId(): Order {
        val builder = FlatBufferBuilder()
        builder.startTable(3)
        builder.finish(builder.endTable())
        return Order.getRootAsOrder(builder.dataBuffer())
    }

    /** An Order whose second item has no `sku`. */
    private fun withItemWithoutSku(): Order {
        val builder = FlatBufferBuilder()
        val good = ItemSpec(sku = "a").writeTo(builder)
        builder.startTable(2)
        builder.addInt(1, 5, 0)
        val bad = builder.endTable()
        val items = Order.createItemsVector(builder, intArrayOf(good, bad))
        val id = builder.createString("o-2")
        Order.startOrder(builder)
        Order.addId(builder, id)
        Order.addItems(builder, items)
        builder.finish(Order.endOrder(builder))
        return Order.getRootAsOrder(builder.dataBuffer())
    }

    private suspend fun statusOf(call: suspend () -> Unit): Status =
        try {
            call()
            Status.OK
        } catch (e: StatusException) {
            e.status
        }

    private fun assertRejected(status: Status, field: String) {
        assertThat(status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
        assertThat(status.description).isEqualTo("Order is missing required field $field")
        assertThat(calls.get()).isEqualTo(0)
    }

    @Test
    fun `missingRequiredField names the first missing field, nested ones by path`() {
        assertThat(valid.missingRequiredField()).isNull()
        assertThat(withoutId().missingRequiredField()).isEqualTo("id")
        assertThat(withItemWithoutSku().missingRequiredField()).isEqualTo("items[1].sku")
    }

    @Test
    fun `valid requests reach the implementation`() = runBlocking {
        val stub = OrderServiceCoroutineStub(channel)

        assertThat(stub.place(valid).items).isEqualTo(1)
        assertThat(stub.track(valid).toList()).hasSize(1)
        assertThat(stub.placeAll(flowOf(valid, valid)).items).isEqualTo(2)
        assertThat(stub.live(flowOf(valid)).toList()).hasSize(1)
        assertThat(calls.get()).isEqualTo(5)
    }

    @Test
    fun `unary and server-streaming calls reject a request missing a required field`() = runBlocking {
        val stub = OrderServiceCoroutineStub(channel)

        assertRejected(statusOf { stub.place(withoutId()) }, "id")
        assertRejected(statusOf { stub.track(withItemWithoutSku()).toList() }, "items[1].sku")
    }

    @Test
    fun `client and bidi streams reject the first request missing a required field`() = runBlocking {
        val stub = OrderServiceCoroutineStub(channel)

        assertRejected(statusOf { stub.placeAll(flowOf(withoutId(), valid)) }, "id")
        assertRejected(statusOf { stub.live(flowOf(withItemWithoutSku(), valid)).toList() }, "items[1].sku")
    }
}
